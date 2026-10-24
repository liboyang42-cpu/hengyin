package com.chengyinhub.business.it;

import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.AccountDeregistrationMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.mapper.OmsOrderMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper;
import com.chengyinhub.business.service.IAppSessionRegistryService;
import com.chengyinhub.business.service.ICashWithdrawService;
import com.chengyinhub.business.service.ICmsPointsSettingService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IRoamHangoutService;
import com.chengyinhub.business.service.IUmsMemberWithdrawalService;
import com.chengyinhub.business.service.impl.AccountDeregistrationServiceImpl;
import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.business.service.impl.UmsMemberPointsDetailServiceImpl;
import com.chengyinhub.business.service.impl.UmsMemberServiceImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AC1 · P0 注销卡的后端半边:冷静期到期执行 {@link AccountDeregistrationServiceImpl#executeDue} 后,
 * 在真 MySQL 上回读三件事 ——
 * <ol>
 *   <li>清理只作用于注销用户:同库另一用户 B 的会员/订单/报名/退款/提现/积分/地址行逐列不变;</li>
 *   <li>财务记录保留:A 的订单、报名、退款、两条提现、积分流水行数与金额不变,只抹 PII;</li>
 *   <li>重新注册隔离:同一微信 openid 再登录拿到新会员 id,按新 id 查不到旧订单,旧行仍挂在旧 id。</li>
 * </ol>
 * 生产 mapper XML + base schema 原样 DDL + 原 migration;社区擦除、Redis 会话、站内信、漫游局在此 mock
 * (社区擦除另有 CommunityPostMapperMyBatisTest,会话撤销由 Mockito 用例验证)。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class P0DeregistrationMySqlIT {
    private static final String SUITE = "p0_deregistration";
    private static final long A = 4301L;
    private static final long B = 4302L;
    private static final String OPENID_A = "ac1-openid-a";
    private static final String OPENID_B = "ac1-openid-b";
    /** 每张表只看"钱/流水"列:注销后必须原样保留。 */
    private static final Map<String, String> FINANCE = new LinkedHashMap<>();

    static {
        FINANCE.put("cms_registration", "id,member_id,payable_amount,total_amount,payment_status,registration_status,"
                + "verification_status,transaction_id");
        FINANCE.put("oms_order", "id,member_id,order_sn,pay_amount,order_total_amount,status,aftersale_status");
        FINANCE.put("refund_application", "id,member_id,source_type,source_id,paid_amount,refund_amount,status,payout_status");
        FINANCE.put("ums_member_withdrawal", "id,member_id,withdrawal_amount,received_amount,status,payout_reference");
        FINANCE.put("member_cash_withdraw", "id,member_id,amount,status");
        FINANCE.put("ums_member_points_detail", "id,member_id,change_points,after_points,event_type,event_id");
    }

    private DriverManagerDataSource dataSource;
    private JdbcTemplate db;
    private AccountDeregistrationServiceImpl deregistration;
    private UmsMemberServiceImpl members;
    private CmsRegistrationMapper registrations;
    private IAppSessionRegistryService sessions;
    private AlarmService alarms;

    @BeforeAll
    void setUp() throws Exception {
        dataSource = P0AcceptanceSchema.dataSource(SUITE);
        db = new JdbcTemplate(dataSource);
        P0AcceptanceSchema.baseTables(db, "account_deregistration", "ums_member", "cms_registration", "oms_order",
                "refund_application", "ums_member_withdrawal", "member_cash_withdraw", "merchant_lead",
                "ums_member_address", "ums_member_points_detail", "sys_config", "club");
        P0AcceptanceSchema.migrations(dataSource, "migration_app_open_id.sql",
                "migration_wechat_identity_unique_20261013.sql", "migration_ums_member_identity_20260919.sql",
                "migration_20260930_registration_first_successful_scan.sql",
                "migration_20260821_commerce_order_idempotency.sql",
                // The canonical shared proof migration also evolves club_membership_invoice.
                // Load its real schema/FK dependencies; do not synthesize proof columns or facts.
                "migration_business_audit_event_20260823.sql",
                "migration_club_role_membership_20260823.sql",
                "migration_wx_payment_callback_inbox_outbox_20260725.sql",
                "migration_20261024_wx_verified_payment_fact.sql",
                "migration_20261024_clm_commerce_payment_proof.sql");

        SqlSessionTemplate sql = P0AcceptanceSchema.sql(dataSource, "AccountDeregistrationMapper", "UmsMemberMapper",
                "UmsMemberPointsDetailMapper", "CmsRegistrationMapper", "OmsOrderMapper");
        registrations = sql.getMapper(CmsRegistrationMapper.class);
        DataSourceTransactionManager tx = new DataSourceTransactionManager(dataSource);
        sessions = mock(IAppSessionRegistryService.class);
        when(sessions.beginMemberMutation(anyLong())).thenReturn("ac1-mutation");

        deregistration = new AccountDeregistrationServiceImpl();
        ReflectionTestUtils.setField(deregistration, "deregistrationMapper", sql.getMapper(AccountDeregistrationMapper.class));
        ReflectionTestUtils.setField(deregistration, "memberMapper", sql.getMapper(UmsMemberMapper.class));
        ReflectionTestUtils.setField(deregistration, "orderMapper", sql.getMapper(OmsOrderMapper.class));
        ReflectionTestUtils.setField(deregistration, "registrationMapper", registrations);
        ReflectionTestUtils.setField(deregistration, "transactionManager", tx);
        ClubMapper clubs = mock(ClubMapper.class);
        when(clubs.selectClubsByMemberId(anyLong())).thenReturn(Collections.emptyList());
        MmsMerchantMapper merchants = mock(MmsMerchantMapper.class);
        when(merchants.selectMmsMerchantList(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(deregistration, "clubMapper", clubs);
        ReflectionTestUtils.setField(deregistration, "merchantMapper", merchants);
        ReflectionTestUtils.setField(deregistration, "perkLedgerService", mock(IPerkLedgerService.class));
        ReflectionTestUtils.setField(deregistration, "cashWithdrawService", mock(ICashWithdrawService.class));
        ReflectionTestUtils.setField(deregistration, "memberWithdrawalService", mock(IUmsMemberWithdrawalService.class));
        alarms = mock(AlarmService.class);
        ReflectionTestUtils.setField(deregistration, "alarmService", alarms);
        ReflectionTestUtils.setField(deregistration, "appSessionRegistryService", sessions);
        ReflectionTestUtils.setField(deregistration, "communityPostMapper", mock(CommunityPostMapper.class));
        ReflectionTestUtils.setField(deregistration, "roamHangoutService", mock(IRoamHangoutService.class));
        ReflectionTestUtils.setField(deregistration, "imService", mock(IImService.class));

        UmsMemberPointsDetailServiceImpl gifts = new UmsMemberPointsDetailServiceImpl();
        ReflectionTestUtils.setField(gifts, "umsMemberPointsDetailMapper", sql.getMapper(UmsMemberPointsDetailMapper.class));
        members = new UmsMemberServiceImpl();
        ReflectionTestUtils.setField(members, "umsMemberMapper", sql.getMapper(UmsMemberMapper.class));
        ReflectionTestUtils.setField(members, "pointsDetailService", gifts);
        ReflectionTestUtils.setField(members, "cmsPointsSettingService", mock(ICmsPointsSettingService.class));
        ReflectionTestUtils.setField(members, "transactionManager", tx);
        ReflectionTestUtils.setField(members, "appSessionRegistryService", mock(IAppSessionRegistryService.class));
        ReflectionTestUtils.setField(members, "defaultAvatarUrl", "");
    }

    @AfterAll
    void dropSchema() {
        if (db != null) {
            P0AcceptanceSchema.drop(db, SUITE);
        }
    }

    @Test
    void dueDeregistrationScopesCleanupToTheUserKeepsFinanceAndIsolatesReRegistration() {
        seedMember(A, OPENID_A, 0);
        seedMember(B, OPENID_B, 1000);
        db.update("insert into account_deregistration(id,member_id,status,apply_time,execute_after,create_time)"
                + " values (77,?,'PENDING',now()-interval 8 day,now()-interval 1 minute,now()-interval 8 day)", A);
        db.update("update ums_member set deregister_status='PENDING' where id=?", A);
        Map<String, String> financeA = finance(A);
        Map<String, String> everythingB = everything(B);

        int completed = deregistration.executeDue(new Date());
        assertEquals(1, completed, () -> "到期且无阻断的注销必须完成:" + db.queryForList(
                "select status, failure_reason from account_deregistration where id=77") + mockingDetails(alarms).getInvocations());

        // ① 账号本身:匿名化 + 不可登录,地址与实名登记整行删除。
        assertEquals(Map.of("del_flag", 2, "deregister_status", "COMPLETED", "nickname", "已注销用户"),
                db.queryForMap("select del_flag, deregister_status, nickname from ums_member where id=?"
                        + " and phone is null and wx_open_id is null and name is null", A));
        assertEquals("COMPLETED", db.queryForObject("select status from account_deregistration where id=77", String.class));
        assertEquals(0, count("ums_member_address", A));
        assertEquals(0, count("ums_member_identity", A));
        verify(sessions).revokeAll(A);
        verify(sessions, never()).revokeAll(B);

        // ② 财务记录保留:行数与金额/状态列逐列不变,只有 PII 被抹。
        assertEquals(financeA, finance(A), "注销不得删除或改动 A 的订单/报名/退款/提现/积分流水");
        assertEquals(1, db.queryForObject("select count(*) from cms_registration where member_id=? and phone is null"
                + " and real_name='已注销用户'", Integer.class, A));
        assertEquals(1, db.queryForObject("select count(*) from oms_order where member_id=? and receiver_phone is null"
                + " and receiver_name='已注销用户'", Integer.class, A));
        assertEquals(1, db.queryForObject("select count(*) from ums_member_withdrawal where member_id=?"
                + " and realname is null and bank_account is null and mobilephone is null", Integer.class, A));
        assertEquals(1, db.queryForObject("select count(*) from member_cash_withdraw where member_id=? and openid is null",
                Integer.class, A));

        // ③ 另一用户 B 的全部行逐列不变。
        assertEquals(everythingB, everything(B), "注销 A 不得触碰 B 的任何行");

        // ④ 同一 openid 重新注册:新会员、新 id,看不到旧单,旧单仍挂在旧 id。
        UmsMember again = members.getOrCreateWechatMember(OPENID_A);
        assertNotEquals(A, again.getId());
        assertEquals(Integer.valueOf(0), again.getDelFlag());
        assertEquals(List.of(again.getId()), db.queryForList(
                "select id from ums_member where wx_open_id=?", Long.class, OPENID_A), "旧账号不得被 openid 再次命中");
        CmsRegistration mine = new CmsRegistration();
        mine.setMemberId(again.getId());
        assertTrue(registrations.selectCmsRegistrationList(mine).isEmpty(), "新账号不得看到注销账号的报名");
        assertEquals(0, count("oms_order", again.getId()));
        assertEquals(0, count("refund_application", again.getId()));
        assertEquals(financeA, finance(A), "重新注册不得把旧财务记录挂到新账号");
        assertEquals(1, db.queryForObject("select count(*) from ums_member_points_detail where member_id=?",
                Integer.class, again.getId()), "新账号只有自己的注册赠送积分流水");
        assertEquals(everythingB, everything(B));
        assertEquals(0, deregistration.executeDue(new Date()), "已完成的申请不得被再次执行");
    }

    private void seedMember(long id, String openid, int balanceFen) {
        db.update("insert into ums_member(id,user_type,role,phone,name,nickname,wx_open_id,status,balance,point,del_flag,"
                        + "deregister_status,create_time) values (?,1,'player',?,?,?,?,1,?,10,0,'NORMAL',now())",
                id, "1380000" + id, "fixture-name-" + id, "fixture-nick-" + id, openid, balanceFen / 100.0);
        db.update("insert into cms_registration(registration_no,owner_type,owner_id,ticket_id,ticket_price,member_id,"
                        + "real_name,phone,original_amount,total_amount,payable_amount,payment_status,registration_status,"
                        + "pay_expire_time,verification_status,purchase_kind,transaction_id,create_time)"
                        + " values (?,1,501,1,'68.00',?,'fixture-real-name','13800000000','68.00','68.00','68.00',2,2,"
                        + "now(),1,1,?,now())",
                "AC1D-" + id, id, "wx-tx-" + id);
        db.update("insert into oms_order(order_sn,member_id,pay_amount,order_total_amount,status,aftersale_status,"
                + "receiver_name,receiver_phone,receiver_detail_address) values (?,?,'30.00','30.00',4,1,'收件人','13800000000','fixture-address')",
                "SN-AC1-" + id, id);
        db.update("insert into refund_application(source_type,source_id,member_id,paid_amount,refund_amount,status,"
                + "payout_status) values ('registration',1,?,'68.00','20.00',1,1)", id);
        db.update("insert into ums_member_withdrawal(member_id,realname,bank_name,bank_account,mobilephone,"
                + "withdrawal_amount,received_amount,status,payout_reference) values (?,'户名','银行','6222000000','13800000000',"
                + "'50.00','50.000',3,?)", id, "bank-ref-" + id);
        db.update("insert into member_cash_withdraw(member_id,openid,amount,status) values (?,?,'12.00',1)", id, openid);
        db.update("insert into ums_member_points_detail(member_id,current_points,after_points,change_points,event_type,"
                + "event_id,change_type,del_flag,create_time) values (?,0,10,10,1,?,1,0,now())", id, id);
        db.update("insert into ums_member_address(member_id,mobile_phone) values (?,'13800000000')", id);
        db.update("insert into ums_member_identity(member_id,real_name,id_card,id_card_hash,source,consent_time,"
                + "create_time,update_time) values (?,'enc','enc',?,'topic_publish',now(),now(),now())", id, String.format("%064d", id));
        db.update("insert into merchant_lead(member_id,name,phone) values (?,'lead','13800000000')", id);
    }

    private Map<String, String> finance(long member) {
        Map<String, String> rows = new LinkedHashMap<>();
        FINANCE.forEach((table, columns) -> rows.put(table, db.queryForList(
                "select " + columns + " from " + table + " where member_id=? order by id", member).toString()));
        return rows;
    }

    private Map<String, String> everything(long member) {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("ums_member", db.queryForList("select * from ums_member where id=?", member).toString());
        for (String table : new String[] {"cms_registration", "oms_order", "refund_application", "ums_member_withdrawal",
                "member_cash_withdraw", "ums_member_points_detail", "ums_member_address", "ums_member_identity",
                "merchant_lead", "account_deregistration"}) {
            rows.put(table, db.queryForList("select * from " + table + " where member_id=? order by id", member).toString());
        }
        return rows;
    }

    private int count(String table, long member) {
        return db.queryForObject("select count(*) from " + table + " where member_id=?", Integer.class, member);
    }
}
