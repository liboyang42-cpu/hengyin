package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AccountDeregistration;
import com.chengyinhub.business.domain.OmsOrder;
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

/**
 * 小程序验收 SC-DEREG-10 / SC-DEREG-11 的服务层部分:真 Spring 事务 + 生产 XML 的状态/会员/地址 SQL(H2 MySQL 模式),
 * 其余擦除域隔离成 mock。冷静期内出现资金/订单 → 到期检查中止、解冻、FAILED+原因+告警、不匿名化;
 * 同批多个账号各自独立事务,中途失败或会话吊销失败的那个整笔回滚,不出现半匿名账号,另一个照常完成。
 */
class AccountDeregistrationDueBatchAcceptanceTest {

    // ---------- SC-DEREG-10:冷静期内出现阻断项,到期检查中止注销 ----------

    @ParameterizedTest
    @ValueSource(strings = {"balance", "cash-withdraw", "bank-withdrawal", "active-order", "aftersale"})
    void dueCheckAbortsWhenBlockerAppearsDuringCoolingOff(String blocker) throws Exception {
        try (Fixture f = new Fixture()) {
            f.pending(9, 1001);
            switch (blocker) {
                case "balance" -> f.db.update("update ums_member set balance=50 where id=1001");
                case "cash-withdraw" -> when(f.cashWithdraw.hasPendingWithdraw(1001L)).thenReturn(true);
                case "bank-withdrawal" -> when(f.bankWithdrawal.hasPendingWithdrawal(1001L)).thenReturn(true);
                case "active-order" -> f.orderFor(1001L, 1L, null);
                default -> f.orderFor(1001L, 4L, 2L);
            }

            assertEquals(0, f.service.executeDue(new Date()));

            assertEquals("FAILED", f.status(9));
            String reason = f.reason(9);
            assertTrue(reason.startsWith("执行期阻断:"), reason);
            assertTrue(reason.contains(expectedReason(blocker)), reason);
            // 解冻:账号回到 NORMAL,冷静期字段清空,PII 与资金原样
            assertEquals("NORMAL", f.memberString(1001, "deregister_status"));
            assertNull(f.db.queryForObject("select deregister_execute_after from ums_member where id=1001", Date.class));
            assertEquals(0, f.memberInt(1001, "del_flag"));
            assertEquals("phone-1001", f.memberString(1001, "phone"));
            assertEquals("open-1001", f.memberString(1001, "wx_open_id"));
            assertEquals(0, new BigDecimal("balance".equals(blocker) ? "50" : "0").compareTo(
                    f.db.queryForObject("select balance from ums_member where id=1001", BigDecimal.class)));
            assertEquals(1, f.addressCount(1001));
            verify(f.alarm).alarm(eq("注销执行阻断"), contains(expectedReason(blocker)));
            verify(f.alarm, never()).alarm(eq("注销执行异常"), anyString());
            // 阻断路径不碰会话、不碰任何资金/PII 擦除域
            verify(f.sessions, never()).beginMemberMutation(anyLong());
            verify(f.sessions, never()).revokeAll(anyLong());
            verify(f.real, never()).anonymizeMemberWithdrawal(anyLong());
            verify(f.real, never()).anonymizeCashWithdraw(anyLong());
            verifyNoInteractions(f.posts, f.hangouts);
            // 已 FAILED 的申请不会被下一批再拾起
            assertEquals(0, f.service.executeDue(new Date()));
            assertEquals("FAILED", f.status(9));
        }
    }

    private static String expectedReason(String blocker) {
        return switch (blocker) {
            case "balance" -> "账户余额未处理";
            case "cash-withdraw" -> "存在处理中的提现";
            case "bank-withdrawal" -> "存在待审核的银行提现";
            default -> "存在进行中订单或退款";
        };
    }

    // ---------- SC-DEREG-11:同批账号相互独立,失败的那个整笔回滚 ----------

    @ParameterizedTest
    @ValueSource(strings = {"midway-sql", "session-revocation"})
    void failedAccountRollsBackAloneAndSameBatchAccountStillCompletes(String failure) throws Exception {
        try (Fixture f = new Fixture()) {
            f.pending(9, 1001);
            f.pending(10, 1002);
            if ("midway-sql".equals(failure)) {
                // anonymizeMember 已真实写库之后再失败 → 必须整笔回滚
                f.failAddressDeleteFor = 1001L;
            } else {
                doThrow(new IllegalStateException("session store unavailable")).when(f.sessions).revokeAll(1001L);
            }

            assertEquals(1, f.service.executeDue(new Date()));

            // 失败账号:FAILED + 原因 + 解冻 + 告警,匿名化与地址删除全部回滚,无半匿名
            assertEquals("FAILED", f.status(9));
            assertEquals("执行异常:IllegalStateException", f.reason(9));
            assertNull(f.db.queryForObject("select complete_time from account_deregistration where id=9", Date.class));
            assertEquals(0, f.memberInt(1001, "del_flag"));
            assertEquals("NORMAL", f.memberString(1001, "deregister_status"));
            assertEquals("phone-1001", f.memberString(1001, "phone"));
            assertEquals("open-1001", f.memberString(1001, "wx_open_id"));
            assertEquals(1, f.addressCount(1001));
            verify(f.sessions).finishMemberMutation(1001L, "mutation-1001");
            verify(f.alarm).alarm(eq("注销执行异常"), contains("member=1001"));
            verify(f.alarm, never()).alarm(eq("注销异常解冻失败"), anyString());
            // 同批另一账号照常完成
            assertEquals("COMPLETED", f.status(10));
            assertNotNull(f.db.queryForObject("select complete_time from account_deregistration where id=10", Date.class));
            assertEquals(2, f.memberInt(1002, "del_flag"));
            assertNull(f.memberString(1002, "phone"));
            assertEquals(0, f.addressCount(1002));
            verify(f.sessions).finishMemberMutationAfterTransaction(1002L, "mutation-1002");

            // 重跑:同一批次再执行,两条终态申请都不再被处理(不重复匿名化、不覆盖 FAILED)
            clearInvocations(f.real);
            assertEquals(0, f.service.executeDue(new Date()));
            verify(f.real, never()).anonymizeMember(anyLong());
            assertEquals("FAILED", f.status(9));

            // 失败账号重新申请后(新 PENDING 行),故障消除,下一批单独完成
            f.failAddressDeleteFor = null;
            doNothing().when(f.sessions).revokeAll(1001L);
            f.db.update("update ums_member set deregister_status='PENDING' where id=1001");
            f.pending(11, 1001);
            assertEquals(1, f.service.executeDue(new Date()));
            assertEquals("COMPLETED", f.status(11));
            assertEquals(2, f.memberInt(1001, "del_flag"));
            assertNull(f.memberString(1001, "phone"));
            assertEquals(0, f.addressCount(1001));
            assertEquals("FAILED", f.status(9));
        }
    }

    @Test
    void blockedAndEligibleAccountsInSameBatchAreHandledIndependently() throws Exception {
        try (Fixture f = new Fixture()) {
            f.pending(9, 1001);
            f.pending(10, 1002);
            when(f.bankWithdrawal.hasPendingWithdrawal(1001L)).thenReturn(true);

            assertEquals(1, f.service.executeDue(new Date()));

            assertEquals("FAILED", f.status(9));
            assertEquals("NORMAL", f.memberString(1001, "deregister_status"));
            assertEquals("phone-1001", f.memberString(1001, "phone"));
            assertEquals("COMPLETED", f.status(10));
            assertEquals(2, f.memberInt(1002, "del_flag"));
            verify(f.real, never()).anonymizeMember(1001L);
        }
    }

    private static class Fixture implements AutoCloseable {
        final JdbcTemplate db;
        final AccountDeregistrationServiceImpl service = new AccountDeregistrationServiceImpl();
        final AlarmService alarm = mock(AlarmService.class);
        final ICashWithdrawService cashWithdraw = mock(ICashWithdrawService.class);
        final IUmsMemberWithdrawalService bankWithdrawal = mock(IUmsMemberWithdrawalService.class);
        final IAppSessionRegistryService sessions = mock(IAppSessionRegistryService.class);
        final CommunityPostMapper posts = mock(CommunityPostMapper.class);
        final IRoamHangoutService hangouts = mock(IRoamHangoutService.class);
        final OmsOrderMapper orders = mock(OmsOrderMapper.class);
        final AccountDeregistrationMapper real;
        volatile Long failAddressDeleteFor;

        Fixture() throws Exception {
            DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:deregister_batch_"
                    + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
            db = new JdbcTemplate(source);
            db.execute("create table account_deregistration(id bigint primary key,member_id bigint,status varchar(20),apply_time datetime,execute_after datetime,cancel_time datetime,complete_time datetime,failure_reason varchar(255),create_time datetime,update_time datetime)");
            db.execute("create table ums_member(id bigint primary key,del_flag int,balance decimal(10,2),phone varchar(30),password varchar(255),name varchar(255),wechat varchar(255),nickname varchar(255),avatar varchar(255),wx_open_id varchar(255),union_id varchar(255),apple_id varchar(255),mark varchar(255),case_pics varchar(255),case_introducton varchar(255),website varchar(255),work_address varchar(255),introduction varchar(255),deregister_status varchar(20),deregister_apply_time datetime,deregister_execute_after datetime,update_time datetime)");
            db.execute("create table ums_member_address(id bigint primary key,member_id bigint)");
            db.execute("create table refund_application(member_id bigint,del_flag int,status int,payout_status int)");
            for (long id : new long[]{1001, 1002}) {
                db.update("insert into ums_member(id,del_flag,balance,phone,wx_open_id,deregister_status,deregister_execute_after) values(?,0,0,?,?,'PENDING',?)",
                        id, "phone-" + id, "open-" + id, new Date(0));
                db.update("insert into ums_member_address(id,member_id) values(?,?)", id, id);
            }
            Configuration config = new Configuration(new Environment("deregister-batch", new SpringManagedTransactionFactory(), source));
            config.getTypeAliasRegistry().registerAlias("AccountDeregistration", AccountDeregistration.class);
            String xml = "mapper/business/AccountDeregistrationMapper.xml";
            try (InputStream input = Resources.getResourceAsStream(xml)) {
                new XMLMapperBuilder(input, config, xml, config.getSqlFragments()).parse();
            }
            AccountDeregistrationMapper sql = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(AccountDeregistrationMapper.class);
            // 生产 SQL 走真库;其余擦除语句(订单/报名/提现等表本夹具未建)只记调用
            real = mock(AccountDeregistrationMapper.class);
            when(real.selectDue(any())).thenAnswer(call -> sql.selectDue(call.getArgument(0)));
            when(real.selectByIdForUpdate(anyLong())).thenAnswer(call -> sql.selectByIdForUpdate(call.getArgument(0)));
            when(real.countUnsettledRefunds(anyLong())).thenAnswer(call -> sql.countUnsettledRefunds(call.getArgument(0)));
            when(real.anonymizeMember(anyLong())).thenAnswer(call -> sql.anonymizeMember(call.getArgument(0)));
            when(real.deleteMemberAddresses(anyLong())).thenAnswer(call -> {
                Long memberId = call.getArgument(0);
                if (memberId.equals(failAddressDeleteFor)) throw new IllegalStateException("synthetic midway failure");
                return sql.deleteMemberAddresses(memberId);
            });
            when(real.markMemberNormal(anyLong())).thenAnswer(call -> sql.markMemberNormal(call.getArgument(0)));
            when(real.markFailed(anyLong(), anyString(), any())).thenAnswer(call -> sql.markFailed(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
            when(real.complete(anyLong(), any())).thenAnswer(call -> sql.complete(call.getArgument(0), call.getArgument(1)));
            UmsMemberMapper members = mock(UmsMemberMapper.class);
            when(members.selectUmsMemberById(anyLong())).thenAnswer(call -> {
                Long id = call.getArgument(0);
                UmsMember member = new UmsMember(); member.setId(id); member.setRole("player");
                member.setBalance(db.queryForObject("select balance from ums_member where id=?", BigDecimal.class, id));
                member.setDelFlag(db.queryForObject("select del_flag from ums_member where id=?", Integer.class, id));
                return member;
            });
            when(orders.selectOmsOrderList(any())).thenReturn(Collections.emptyList());
            MmsMerchantMapper merchants = mock(MmsMerchantMapper.class);
            when(merchants.selectMmsMerchantList(any())).thenReturn(Collections.emptyList());
            ClubMapper clubs = mock(ClubMapper.class); when(clubs.selectClubsByMemberId(anyLong())).thenReturn(Collections.emptyList());
            CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class); when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());
            when(sessions.beginMemberMutation(anyLong())).thenAnswer(call -> "mutation-" + call.getArgument(0));
            ReflectionTestUtils.setField(service, "deregistrationMapper", real);
            ReflectionTestUtils.setField(service, "memberMapper", members);
            ReflectionTestUtils.setField(service, "merchantMapper", merchants);
            ReflectionTestUtils.setField(service, "clubMapper", clubs);
            ReflectionTestUtils.setField(service, "orderMapper", orders);
            ReflectionTestUtils.setField(service, "registrationMapper", registrations);
            ReflectionTestUtils.setField(service, "cashWithdrawService", cashWithdraw);
            ReflectionTestUtils.setField(service, "memberWithdrawalService", bankWithdrawal);
            ReflectionTestUtils.setField(service, "appSessionRegistryService", sessions);
            ReflectionTestUtils.setField(service, "communityPostMapper", posts);
            ReflectionTestUtils.setField(service, "roamHangoutService", hangouts);
            ReflectionTestUtils.setField(service, "transactionManager", new DataSourceTransactionManager(source));
            ReflectionTestUtils.setField(service, "alarmService", alarm);
        }

        void pending(long id, long memberId) {
            db.update("insert into account_deregistration(id,member_id,status,execute_after) values(?,?,'PENDING',?)", id, memberId, new Date(0));
        }

        void orderFor(Long memberId, Long status, Long aftersale) {
            OmsOrder order = new OmsOrder(); order.setMemberId(memberId); order.setStatus(status); order.setAftersaleStatus(aftersale);
            when(orders.selectOmsOrderList(argThat(q -> q != null && memberId.equals(q.getMemberId()))))
                    .thenReturn(Collections.singletonList(order));
        }

        String status(long id) { return db.queryForObject("select status from account_deregistration where id=?", String.class, id); }
        String reason(long id) { return db.queryForObject("select failure_reason from account_deregistration where id=?", String.class, id); }
        String memberString(long id, String column) { return db.queryForObject("select " + column + " from ums_member where id=?", String.class, id); }
        int memberInt(long id, String column) { return db.queryForObject("select " + column + " from ums_member where id=?", Integer.class, id); }
        int addressCount(long memberId) { return db.queryForObject("select count(*) from ums_member_address where member_id=?", Integer.class, memberId); }

        public void close() { db.execute("drop all objects"); db.execute("shutdown"); }
    }
}
