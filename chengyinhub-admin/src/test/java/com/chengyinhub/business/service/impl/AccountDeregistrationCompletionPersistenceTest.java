package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AccountDeregistration;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real Spring transactions and production state/member/address SQL; unrelated erasure domains are isolated. */
class AccountDeregistrationCompletionPersistenceTest {
    @ParameterizedTest
    @ValueSource(strings = {"normal", "missing", "already-anonymous"})
    void zeroRowCompletionRollsBackAndCannotBeCountedAsFinished(String mode) throws Exception {
        try (Fixture f = new Fixture(mode, true)) {
            assertEquals(0, f.service.executeDue(new Date()));
            assertEquals("FAILED", f.db.queryForObject("select status from account_deregistration where id=9", String.class));
            assertNull(f.db.queryForObject("select complete_time from account_deregistration where id=9", Date.class));
            assertEquals("执行异常:IllegalStateException", f.db.queryForObject("select failure_reason from account_deregistration where id=9", String.class));
            verify(f.alarm).alarm(eq("注销执行异常"), anyString());
            if ("normal".equals(mode)) {
                assertEquals("fixture-phone", f.db.queryForObject("select phone from ums_member where id=1001", String.class));
                assertEquals(0, f.db.queryForObject("select del_flag from ums_member where id=1001", Integer.class));
                assertEquals("NORMAL", f.db.queryForObject("select deregister_status from ums_member where id=1001", String.class));
                assertEquals(1, f.db.queryForObject("select count(*) from ums_member_address where member_id=1001", Integer.class));
            } else if ("missing".equals(mode)) {
                assertEquals(0, f.db.queryForObject("select count(*) from ums_member", Integer.class));
            } else {
                assertEquals(2, f.db.queryForObject("select del_flag from ums_member where id=1001", Integer.class));
            }
        }
    }

    @Test
    void oneRowCompletionCommitsActualAnonymizationAndAddressDeletion() throws Exception {
        try (Fixture f = new Fixture("normal", false)) {
            assertEquals(1, f.service.executeDue(new Date()));
            assertEquals("COMPLETED", f.db.queryForObject("select status from account_deregistration where id=9", String.class));
            assertNotNull(f.db.queryForObject("select complete_time from account_deregistration where id=9", Date.class));
            assertNull(f.db.queryForObject("select phone from ums_member where id=1001", String.class));
            assertEquals(2, f.db.queryForObject("select del_flag from ums_member where id=1001", Integer.class));
            assertEquals(0, f.db.queryForObject("select count(*) from ums_member_address where member_id=1001", Integer.class));
            assertEquals(1, f.db.queryForObject("select count(*) from ums_member_address where member_id=1002", Integer.class));
            verifyNoInteractions(f.alarm);
        }
    }

    private static class Fixture implements AutoCloseable {
        final JdbcTemplate db;
        final AccountDeregistrationServiceImpl service = new AccountDeregistrationServiceImpl();
        final AlarmService alarm = mock(AlarmService.class);

        Fixture(String mode, boolean failCompletion) throws Exception {
            DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:deregister_completion_"
                    + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
            db = new JdbcTemplate(source);
            db.execute("create table account_deregistration(id bigint primary key,member_id bigint,status varchar(20),apply_time datetime,execute_after datetime,cancel_time datetime,complete_time datetime,failure_reason varchar(255),create_time datetime,update_time datetime)");
            db.execute("create table ums_member(id bigint primary key,del_flag int,phone varchar(30),password varchar(255),name varchar(255),wechat varchar(255),nickname varchar(255),avatar varchar(255),wx_open_id varchar(255),union_id varchar(255),apple_id varchar(255),mark varchar(255),case_pics varchar(255),case_introducton varchar(255),website varchar(255),work_address varchar(255),introduction varchar(255),deregister_status varchar(20),deregister_apply_time datetime,deregister_execute_after datetime,update_time datetime)");
            db.execute("create table ums_member_address(id bigint primary key,member_id bigint)");
            db.execute("create table refund_application(member_id bigint,del_flag int,status int,payout_status int)");
            db.update("insert into account_deregistration(id,member_id,status,execute_after) values(9,1001,'PENDING',?)", new Date(0));
            if (!"missing".equals(mode)) db.update("insert into ums_member(id,del_flag,phone,deregister_status) values(1001,?,?,?)",
                    "already-anonymous".equals(mode) ? 2 : 0, "already-anonymous".equals(mode) ? null : "fixture-phone",
                    "already-anonymous".equals(mode) ? "COMPLETED" : "PENDING");
            db.update("insert into ums_member_address(id,member_id) values(1,1001),(2,1002)");
            Configuration config = new Configuration(new Environment("completion-persistence", new SpringManagedTransactionFactory(), source));
            config.getTypeAliasRegistry().registerAlias("AccountDeregistration", AccountDeregistration.class);
            String xml = "mapper/business/AccountDeregistrationMapper.xml";
            try (InputStream input = Resources.getResourceAsStream(xml)) {
                new XMLMapperBuilder(input, config, xml, config.getSqlFragments()).parse();
            }
            AccountDeregistrationMapper real = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(AccountDeregistrationMapper.class);
            AccountDeregistrationMapper adapter = mock(AccountDeregistrationMapper.class);
            when(adapter.selectDue(any())).thenAnswer(call -> real.selectDue(call.getArgument(0)));
            when(adapter.selectByIdForUpdate(anyLong())).thenAnswer(call -> real.selectByIdForUpdate(call.getArgument(0)));
            when(adapter.countUnsettledRefunds(anyLong())).thenAnswer(call -> real.countUnsettledRefunds(call.getArgument(0)));
            when(adapter.anonymizeMember(anyLong())).thenAnswer(call -> real.anonymizeMember(call.getArgument(0)));
            when(adapter.deleteMemberAddresses(anyLong())).thenAnswer(call -> real.deleteMemberAddresses(call.getArgument(0)));
            when(adapter.markMemberNormal(anyLong())).thenAnswer(call -> real.markMemberNormal(call.getArgument(0)));
            when(adapter.markFailed(anyLong(), anyString(), any())).thenAnswer(call -> real.markFailed(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
            when(adapter.complete(anyLong(), any())).thenAnswer(call -> {
                if (failCompletion) db.update("update account_deregistration set status='CANCELLED' where id=9");
                return real.complete(call.getArgument(0), call.getArgument(1));
            });
            UmsMemberMapper members = mock(UmsMemberMapper.class);
            when(members.selectUmsMemberById(1001L)).thenAnswer(call -> {
                if ("missing".equals(mode)) return null;
                UmsMember member = new UmsMember(); member.setId(1001L); member.setRole("player"); member.setBalance(BigDecimal.ZERO);
                member.setDelFlag(db.queryForObject("select del_flag from ums_member where id=1001", Integer.class));
                return member;
            });
            MmsMerchantMapper merchants = mock(MmsMerchantMapper.class);
            when(merchants.selectMmsMerchantList(any())).thenReturn(Collections.emptyList());
            ClubMapper clubs = mock(ClubMapper.class); when(clubs.selectClubsByMemberId(anyLong())).thenReturn(Collections.emptyList());
            OmsOrderMapper orders = mock(OmsOrderMapper.class); when(orders.selectOmsOrderList(any())).thenReturn(Collections.emptyList());
            CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class); when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());
            ReflectionTestUtils.setField(service, "deregistrationMapper", adapter);
            ReflectionTestUtils.setField(service, "memberMapper", members);
            ReflectionTestUtils.setField(service, "merchantMapper", merchants);
            ReflectionTestUtils.setField(service, "clubMapper", clubs);
            ReflectionTestUtils.setField(service, "orderMapper", orders);
            ReflectionTestUtils.setField(service, "registrationMapper", registrations);
            ReflectionTestUtils.setField(service, "cashWithdrawService", mock(ICashWithdrawService.class));
            ReflectionTestUtils.setField(service, "memberWithdrawalService", mock(IUmsMemberWithdrawalService.class));
            IAppSessionRegistryService sessions = mock(IAppSessionRegistryService.class);
            when(sessions.beginMemberMutation(1001L)).thenReturn("fixture-member-mutation");
            ReflectionTestUtils.setField(service, "appSessionRegistryService", sessions);
            ReflectionTestUtils.setField(service, "communityPostMapper", mock(CommunityPostMapper.class));
            ReflectionTestUtils.setField(service, "roamHangoutService", mock(IRoamHangoutService.class));
            ReflectionTestUtils.setField(service, "transactionManager", new DataSourceTransactionManager(source));
            ReflectionTestUtils.setField(service, "alarmService", alarm);
        }
        public void close() { db.execute("drop all objects"); db.execute("shutdown"); }
    }
}
