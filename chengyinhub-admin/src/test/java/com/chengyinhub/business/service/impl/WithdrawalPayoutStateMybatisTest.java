package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.domain.UmsMemberWithdrawal;
import com.chengyinhub.business.domain.dto.WithdrawalPayoutDTO;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberWithdrawalMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 提现审核通过后的真实 MyBatis 打款 CAS 与凭据回读契约。 */
class WithdrawalPayoutStateMybatisTest {

    private static final Long WITHDRAWAL_ID = 100L;
    private static final Long MEMBER_ID = 5L;
    private static final Long FIRST_OPERATOR = 7L;
    private static final Long SECOND_OPERATOR = 8L;

    private static final String DDL =
            "create table ums_member_withdrawal ("
                    + "id bigint primary key auto_increment,"
                    + "member_id bigint,"
                    + "request_id varchar(64),"
                    + "request_fingerprint char(64),"
                    + "realname varchar(64),"
                    + "bank_name varchar(64),"
                    + "bank_account varchar(128),"
                    + "mobilephone varchar(32),"
                    + "realname_enc varchar(512),"
                    + "bank_name_enc varchar(512),"
                    + "bank_account_enc varchar(512),"
                    + "mobilephone_enc varchar(512),"
                    + "realname_mask varchar(64),"
                    + "bank_name_mask varchar(64),"
                    + "bank_account_mask varchar(128),"
                    + "mobilephone_mask varchar(32),"
                    + "pii_cipher_version int,"
                    + "pii_migrated_at datetime,"
                    + "withdrawal_amount decimal(15,2),"
                    + "service_fee decimal(15,2),"
                    + "received_amount decimal(15,2),"
                    + "status bigint not null,"
                    + "payout_reference varchar(128),"
                    + "consent_version varchar(32),"
                    + "del_flag bigint not null,"
                    + "remark varchar(500),"
                    + "create_by varchar(64),"
                    + "create_time datetime,"
                    + "update_by varchar(64),"
                    + "update_time datetime,"
                    + "audit_time datetime,"
                    + "audit_by bigint,"
                    + "handle_by bigint,"
                    + "handle_time datetime)";

    private JdbcTemplate jdbc;
    private UmsMemberWithdrawalServiceImpl service;
    private UmsMemberWithdrawalMapper withdrawalMapper;
    private UmsMemberMapper memberMapper;
    private UmsMemberBalanceDetailMapper detailMapper;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() throws Exception {
        org.h2.jdbcx.JdbcDataSource dataSource = new org.h2.jdbcx.JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:withdrawal_payout_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        DataSource testDataSource = dataSource;

        jdbc = new JdbcTemplate(testDataSource);
        jdbc.execute(DDL);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(testDataSource));

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(testDataSource);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/UmsMemberWithdrawalMapper.xml"));
        SqlSessionFactory sqlSessionFactory = factory.getObject();
        withdrawalMapper = new SqlSessionTemplate(sqlSessionFactory).getMapper(UmsMemberWithdrawalMapper.class);

        memberMapper = mock(UmsMemberMapper.class);
        detailMapper = mock(UmsMemberBalanceDetailMapper.class);
        service = new UmsMemberWithdrawalServiceImpl();
        ReflectionTestUtils.setField(service, "umsMemberWithdrawalMapper", withdrawalMapper);
        ReflectionTestUtils.setField(service, "umsMemberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "umsMemberBalanceDetailMapper", detailMapper);
    }

    @Test
    void concurrentMemberWithdrawalDeleteHasExactlyOnePendingStateWinner() throws Exception {
        BigDecimal amount = new BigDecimal("20.00");
        jdbc.update("insert into ums_member_withdrawal "
                        + "(id, member_id, withdrawal_amount, status, del_flag) values (?, ?, ?, ?, ?)",
                WITHDRAWAL_ID, MEMBER_ID, amount, 0L, 0L);
        UmsMember member = new UmsMember();
        member.setId(MEMBER_ID);
        member.setBalance(new BigDecimal("80.00"));
        when(memberMapper.selectUmsMemberById(MEMBER_ID)).thenReturn(member);
        when(memberMapper.addBalance(MEMBER_ID, amount)).thenReturn(1);
        when(detailMapper.insertUmsMemberBalanceDetail(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> withdrawPending = () -> {
                startTogether.await(5, TimeUnit.SECONDS);
                return transactionTemplate.execute(status ->
                        service.withdrawPendingByMember(WITHDRAWAL_ID, MEMBER_ID));
            };
            Future<Integer> first = pool.submit(withdrawPending);
            Future<Integer> second = pool.submit(withdrawPending);
            List<Integer> affectedRows = Arrays.asList(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertEquals(1, affectedRows.stream().mapToInt(Integer::intValue).sum(),
                    "同一待审提现并发撤回必须只有一个 affectedRows=1 的 CAS winner，实际=" + affectedRows);
            assertEquals(2L, jdbc.queryForObject(
                    "select del_flag from ums_member_withdrawal where id = ?", Long.class, WITHDRAWAL_ID));
            verify(memberMapper, times(1)).addBalance(MEMBER_ID, amount);
            org.mockito.ArgumentCaptor<UmsMemberBalanceDetail> detailCaptor =
                    org.mockito.ArgumentCaptor.forClass(UmsMemberBalanceDetail.class);
            verify(detailMapper, times(1)).insertUmsMemberBalanceDetail(detailCaptor.capture());
            UmsMemberBalanceDetail refundDetail = detailCaptor.getValue();
            assertAll(
                    () -> assertEquals(MEMBER_ID, refundDetail.getMemberId()),
                    () -> assertEquals(0, amount.compareTo(refundDetail.getChangeBalance())),
                    () -> assertEquals(Long.valueOf(4L), refundDetail.getEventType()),
                    () -> assertEquals(Long.valueOf(1L), refundDetail.getChangeType()),
                    () -> assertEquals(WITHDRAWAL_ID, refundDetail.getEventId()),
                    () -> assertEquals("提现撤回退款", refundDetail.getChangeReason()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void withdrawalRefundDetailFailureRollsBackSoftDeleteClaim() {
        BigDecimal amount = new BigDecimal("20.00");
        jdbc.update("insert into ums_member_withdrawal "
                        + "(id, member_id, withdrawal_amount, status, del_flag) values (?, ?, ?, ?, ?)",
                WITHDRAWAL_ID, MEMBER_ID, amount, 0L, 0L);
        UmsMember member = new UmsMember();
        member.setId(MEMBER_ID);
        member.setBalance(new BigDecimal("80.00"));
        when(memberMapper.selectUmsMemberById(MEMBER_ID)).thenReturn(member);
        when(memberMapper.addBalance(MEMBER_ID, amount)).thenReturn(1);
        when(detailMapper.insertUmsMemberBalanceDetail(org.mockito.ArgumentMatchers.any())).thenReturn(0);

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                transactionTemplate.execute(status ->
                        service.withdrawPendingByMember(WITHDRAWAL_ID, MEMBER_ID)));

        assertEquals("插入用户余额变动明细失败", failure.getMessage(),
                "必须指认到撤回余额流水闸，而不是被相邻状态闸接住");
        assertEquals(0L, delFlag(WITHDRAWAL_ID),
                "余额流水失败必须回滚 status=0/del_flag=0 的撤回 claim");
    }

    @Test
    void approvedPaidAndOtherMembersPendingWithdrawalCannotBeSoftDeleted() {
        long approvedId = WITHDRAWAL_ID;
        long paidId = WITHDRAWAL_ID + 1;
        long otherMembersPendingId = WITHDRAWAL_ID + 2;
        jdbc.update("insert into ums_member_withdrawal (id, member_id, status, del_flag) values (?, ?, ?, ?)",
                approvedId, MEMBER_ID, 1L, 0L);
        jdbc.update("insert into ums_member_withdrawal (id, member_id, status, del_flag) values (?, ?, ?, ?)",
                paidId, MEMBER_ID, 3L, 0L);
        jdbc.update("insert into ums_member_withdrawal (id, member_id, status, del_flag) values (?, ?, ?, ?)",
                otherMembersPendingId, MEMBER_ID + 1, 0L, 0L);

        int approvedRows = service.withdrawPendingByMember(approvedId, MEMBER_ID);
        int paidRows = service.withdrawPendingByMember(paidId, MEMBER_ID);
        int wrongOwnerRows = service.withdrawPendingByMember(otherMembersPendingId, MEMBER_ID);

        assertAll(
                () -> assertEquals(0, approvedRows, "status=1 已通过申请必须被 expected-status CAS 拒绝"),
                () -> assertEquals(0, paidRows, "status=3 已打款申请必须被 expected-status CAS 拒绝"),
                () -> assertEquals(0, wrongOwnerRows, "member_id 不匹配必须被归属条件拒绝"),
                () -> assertEquals(0L, delFlag(approvedId)),
                () -> assertEquals(0L, delFlag(paidId)),
                () -> assertEquals(0L, delFlag(otherMembersPendingId)));
    }

    @Test
    void missingPayoutReferenceCannotReachPaidState() {
        insertApprovedWithdrawal(0L);

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.confirmPayout(payoutRequest("   "), FIRST_OPERATOR)),
                () -> assertEquals(Long.valueOf(1L),
                        service.selectUmsMemberWithdrawalById(WITHDRAWAL_ID).getStatus()),
                () -> verifyNoInteractions(memberMapper, detailMapper));
    }

    @Test
    void repeatedPayoutConfirmationCannotOverwriteProofOrTouchBalance() {
        insertApprovedWithdrawal(0L);
        String firstReference = UUID.randomUUID().toString();
        String secondReference = UUID.randomUUID().toString();

        assertTrue(service.hasPendingWithdrawal(MEMBER_ID));
        int firstRows = service.confirmPayout(payoutRequest(firstReference), FIRST_OPERATOR);
        int secondRows = service.confirmPayout(payoutRequest(secondReference), SECOND_OPERATOR);

        UmsMemberWithdrawal paid = service.selectUmsMemberWithdrawalById(WITHDRAWAL_ID);

        UmsMemberWithdrawal filter = new UmsMemberWithdrawal();
        filter.setPayoutReference(firstReference);
        List<UmsMemberWithdrawal> rows = service.selectUmsMemberWithdrawalList(filter);
        String queriedReference = rows.isEmpty() ? null : rows.get(0).getPayoutReference();
        assertAll(
                () -> assertEquals(1, firstRows),
                () -> assertEquals(0, secondRows),
                () -> assertFalse(service.hasPendingWithdrawal(MEMBER_ID)),
                () -> assertEquals(Long.valueOf(3L), paid.getStatus()),
                () -> assertEquals(firstReference, paid.getPayoutReference()),
                () -> assertEquals(FIRST_OPERATOR, paid.getHandleBy()),
                () -> assertNotNull(paid.getHandleTime()),
                () -> assertEquals(1, rows.size()),
                () -> assertEquals(firstReference, queriedReference),
                () -> verifyNoInteractions(memberMapper, detailMapper));
    }

    @Test
    void deletedApprovedWithdrawalCannotBeMarkedPaid() {
        insertApprovedWithdrawal(2L);

        assertEquals(0, service.confirmPayout(payoutRequest(UUID.randomUUID().toString()), FIRST_OPERATOR));
        assertEquals(Long.valueOf(1L), service.selectUmsMemberWithdrawalById(WITHDRAWAL_ID).getStatus());
        verifyNoInteractions(memberMapper, detailMapper);
    }

    private void insertApprovedWithdrawal(Long delFlag) {
        jdbc.update("insert into ums_member_withdrawal (id, member_id, status, del_flag) values (?, ?, ?, ?)",
                WITHDRAWAL_ID, MEMBER_ID, 1L, delFlag);
    }

    private Long delFlag(Long id) {
        return jdbc.queryForObject(
                "select del_flag from ums_member_withdrawal where id = ?", Long.class, id);
    }

    private WithdrawalPayoutDTO payoutRequest(String payoutReference) {
        WithdrawalPayoutDTO request = new WithdrawalPayoutDTO();
        request.setId(WITHDRAWAL_ID);
        request.setPayoutReference(payoutReference);
        return request;
    }
}
