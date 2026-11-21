package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MemberDataCopyRequest;
import com.chengyinhub.business.domain.vo.DataCopyRequestVO;
import com.chengyinhub.business.mapper.MemberDataCopyRequestMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ONB-10 (PIPL Art. 45): idempotent data-copy request, working-day deadline, admin status machine. */
class MemberDataCopyServiceImplTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    /** Friday 2026-11-20 10:15:30 in Shanghai. */
    private static final Clock FRIDAY = Clock.fixed(Instant.parse("2026-11-20T02:15:30Z"), SHANGHAI);

    // ---- dueBy working-day math ----

    @Test
    void fridayPlusFifteenWorkingDaysIsTheFridayThreeWeeksLater() {
        assertEquals(LocalDate.of(2026, 12, 11), MemberDataCopyServiceImpl.dueBy(LocalDate.of(2026, 11, 20), 15));
    }

    @Test
    void weekendRequestsCountFromMondayAndWeekendsAreSkipped() {
        // Saturday and Sunday: day 1 is Monday 11-23, day 15 is Friday 12-11.
        assertEquals(LocalDate.of(2026, 12, 11), MemberDataCopyServiceImpl.dueBy(LocalDate.of(2026, 11, 21), 15));
        assertEquals(LocalDate.of(2026, 12, 11), MemberDataCopyServiceImpl.dueBy(LocalDate.of(2026, 11, 22), 15));
        // Monday + 15 working days = Monday three weeks later.
        assertEquals(LocalDate.of(2026, 12, 14), MemberDataCopyServiceImpl.dueBy(LocalDate.of(2026, 11, 23), 15));
        // Friday + 1 working day = Monday.
        assertEquals(LocalDate.of(2026, 11, 23), MemberDataCopyServiceImpl.dueBy(LocalDate.of(2026, 11, 20), 1));
        // Wednesday + 3 = Monday.
        assertEquals(LocalDate.of(2026, 11, 23), MemberDataCopyServiceImpl.dueBy(LocalDate.of(2026, 11, 18), 3));
    }

    @Test
    void theDeadlineFollowsTheConfiguredWorkingDays() {
        MemberDataCopyRequestMapper mapper = mock(MemberDataCopyRequestMapper.class);
        DataCopyRequestVO vo = new MemberDataCopyServiceImpl(mapper, 5, FRIDAY).request(7L);
        assertEquals("2026-11-27", vo.getDueBy());
        assertThrows(IllegalArgumentException.class, () -> new MemberDataCopyServiceImpl(mapper, 0, FRIDAY));
    }

    // ---- idempotent create ----

    @Test
    void createsAPendingRequestWithTheContractShape() {
        MemberDataCopyRequestMapper mapper = mock(MemberDataCopyRequestMapper.class);
        when(mapper.insert(any())).thenAnswer(inv -> {
            ((MemberDataCopyRequest) inv.getArgument(0)).setId(41L);
            return 1;
        });
        DataCopyRequestVO vo = new MemberDataCopyServiceImpl(mapper, 15, FRIDAY).request(7L);
        assertEquals(41L, vo.getRequestId());
        assertEquals("PENDING", vo.getStatus());
        assertEquals("2026-11-20 10:15:30", vo.getCreatedAt());
        assertEquals("2026-12-11", vo.getDueBy());
        verify(mapper).lockMember(7L);
    }

    @Test
    void anOpenRequestIsReturnedInsteadOfCreatingAnother() {
        MemberDataCopyRequestMapper mapper = mock(MemberDataCopyRequestMapper.class);
        MemberDataCopyRequest open = row(5L, 7L, "PROCESSING", Date.from(Instant.parse("2026-11-16T01:00:00Z")));
        when(mapper.selectOpenByMember(7L)).thenReturn(open);
        DataCopyRequestVO vo = new MemberDataCopyServiceImpl(mapper, 15, FRIDAY).request(7L);
        assertEquals(5L, vo.getRequestId());
        assertEquals("PROCESSING", vo.getStatus());
        assertEquals("2026-11-16 09:00:00", vo.getCreatedAt());
        assertEquals("2026-12-07", vo.getDueBy());
        verify(mapper, never()).insert(any());
    }

    @Test
    void requestingAgainOnTheDatabaseKeepsOneOpenTicketAndAClosedOneAllowsANewRequest() throws Exception {
        try (H2 h2 = new H2()) {
            MemberDataCopyServiceImpl service = new MemberDataCopyServiceImpl(h2.mapper, 15, FRIDAY);
            DataCopyRequestVO first = h2.tx.execute(s -> service.request(7L));
            DataCopyRequestVO again = h2.tx.execute(s -> service.request(7L));
            assertEquals(first.getRequestId(), again.getRequestId());
            assertEquals(1, h2.count(7L));
            assertEquals(first.getCreatedAt(), service.latest(7L).getCreatedAt());
            assertNull(service.latest(8L), "no request yet");

            h2.tx.execute(s -> service.changeStatus(first.getRequestId(), "PROCESSING", null, 1L));
            assertEquals(first.getRequestId(), h2.tx.execute(s -> service.request(7L)).getRequestId(),
                    "PROCESSING is still open");
            MemberDataCopyRequest delivered = h2.tx.execute(s -> service.changeStatus(first.getRequestId(), "DELIVERED", "已发送至绑定邮箱", 1L));
            assertEquals("DELIVERED", delivered.getStatus());
            assertEquals(1L, delivered.getHandledBy());
            assertNotNull(delivered.getHandledAt());
            assertEquals("2026-12-11", delivered.getDueBy());

            DataCopyRequestVO second = h2.tx.execute(s -> service.request(7L));
            assertEquals("PENDING", second.getStatus());
            assertEquals(2, h2.count(7L));
            assertEquals(second.getRequestId(), service.latest(7L).getRequestId());

            List<MemberDataCopyRequest> pending = service.list("pending");
            assertEquals(1, pending.size());
            assertEquals("2026-12-11", pending.get(0).getDueBy());
            assertEquals(2, service.list(null).size());
        }
    }

    // ---- admin status machine ----

    @Test
    void allowedTransitionsAreApplied() {
        for (String[] t : new String[][]{{"PENDING", "PROCESSING"}, {"PROCESSING", "DELIVERED"},
                {"PENDING", "REJECTED"}, {"PROCESSING", "REJECTED"}}) {
            MemberDataCopyRequestMapper mapper = mock(MemberDataCopyRequestMapper.class);
            when(mapper.selectById(3L)).thenReturn(row(3L, 7L, t[0], new Date()));
            when(mapper.updateStatus(any(), anyString(), anyString(), any(), any(), any())).thenReturn(1);
            new MemberDataCopyServiceImpl(mapper, 15, FRIDAY).changeStatus(3L, t[1].toLowerCase(), "原因", 1L);
            verify(mapper).updateStatus(3L, t[0], t[1], "原因", 1L, Date.from(FRIDAY.instant()));
        }
    }

    @Test
    void illegalTransitionsAreRejected() {
        String[][] illegal = {{"PENDING", "DELIVERED"}, {"PENDING", "PENDING"}, {"PROCESSING", "PENDING"},
                {"DELIVERED", "PROCESSING"}, {"DELIVERED", "REJECTED"}, {"REJECTED", "PROCESSING"},
                {"REJECTED", "DELIVERED"}, {"PENDING", "DONE"}};
        for (String[] t : illegal) {
            MemberDataCopyRequestMapper mapper = mock(MemberDataCopyRequestMapper.class);
            when(mapper.selectById(3L)).thenReturn(row(3L, 7L, t[0], new Date()));
            MemberDataCopyServiceImpl service = new MemberDataCopyServiceImpl(mapper, 15, FRIDAY);
            assertThrows(ServiceException.class, () -> service.changeStatus(3L, t[1], "原因", 1L), t[0] + "→" + t[1]);
            verify(mapper, never()).updateStatus(any(), any(), any(), any(), any(), any());
        }
    }

    @Test
    void rejectionNeedsAReasonAndMissingOrRacedRowsFail() {
        MemberDataCopyRequestMapper mapper = mock(MemberDataCopyRequestMapper.class);
        when(mapper.selectById(3L)).thenReturn(row(3L, 7L, "PENDING", new Date()));
        MemberDataCopyServiceImpl service = new MemberDataCopyServiceImpl(mapper, 15, FRIDAY);
        assertThrows(ServiceException.class, () -> service.changeStatus(3L, "REJECTED", "  ", 1L));
        assertThrows(ServiceException.class, () -> service.changeStatus(3L, "PROCESSING", "x".repeat(501), 1L));
        assertThrows(ServiceException.class, () -> service.changeStatus(9L, "PROCESSING", null, 1L), "unknown id");
        assertThrows(ServiceException.class, () -> service.changeStatus(3L, null, null, 1L));
        // Another admin moved it first: the compare-and-set update touches no row.
        when(mapper.updateStatus(anyLong(), anyString(), anyString(), any(), any(), any())).thenReturn(0);
        assertThrows(ServiceException.class, () -> service.changeStatus(3L, "PROCESSING", null, 1L));
    }

    @Test
    void anonymousCallsCreateNothing() {
        MemberDataCopyRequestMapper mapper = mock(MemberDataCopyRequestMapper.class);
        MemberDataCopyServiceImpl service = new MemberDataCopyServiceImpl(mapper, 15, FRIDAY);
        assertThrows(ServiceException.class, () -> service.request(null));
        assertThrows(ServiceException.class, () -> service.request(0L));
        assertNull(service.latest(null));
        verify(mapper, never()).insert(any());
    }

    private static MemberDataCopyRequest row(Long id, Long memberId, String status, Date createdAt) {
        MemberDataCopyRequest r = new MemberDataCopyRequest();
        r.setId(id);
        r.setMemberId(memberId);
        r.setStatus(status);
        r.setCreatedAt(createdAt);
        return r;
    }

    /** H2 (MySQL mode) with the production mapper XML and a Spring-managed transaction. */
    static final class H2 implements AutoCloseable {
        final JdbcTemplate db;
        final TransactionTemplate tx;
        final MemberDataCopyRequestMapper mapper;

        H2() throws Exception {
            DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:data_copy_"
                    + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
            db = new JdbcTemplate(source);
            db.execute("create table ums_member(id bigint primary key)");
            db.execute("insert into ums_member values (7), (8)");
            db.execute("create table member_data_copy_request(id bigint auto_increment primary key, member_id bigint not null,"
                    + " status varchar(16) not null default 'PENDING', note varchar(500), created_at datetime not null,"
                    + " updated_at datetime not null, handled_by bigint, handled_at datetime)");
            Configuration config = new Configuration(new Environment("data-copy", new SpringManagedTransactionFactory(), source));
            String xml = "mapper/business/MemberDataCopyRequestMapper.xml";
            try (InputStream input = Resources.getResourceAsStream(xml)) {
                new XMLMapperBuilder(input, config, xml, config.getSqlFragments()).parse();
            }
            mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(MemberDataCopyRequestMapper.class);
            tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        }

        int count(long member) {
            return db.queryForObject("select count(*) from member_data_copy_request where member_id=?", Integer.class, member);
        }

        @Override public void close() {
            db.execute("drop all objects");
        }
    }
}
