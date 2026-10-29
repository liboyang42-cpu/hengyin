package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MemberCashWithdraw;
import com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent;
import com.chengyinhub.business.mapper.MemberCashWithdrawMapper;
import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.business.service.impl.CashWithdrawServiceImpl;
import com.chengyinhub.business.service.impl.MerchantReferralBountyService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P块冻结在「人工重查」路径上的真实 SQL 回归。
 *
 * ★为什么必须用 H2 跑真 MyBatis XML,不用 mock mapper:
 *   本缺陷的闸就在 updateStatusIfProcessing 的 where 子句里。mapper 一旦被 mock,
 *   删掉 SQL 守卫做负控时测试照样绿 —— 那是假绿,证明不了任何事。
 *
 * 覆盖的缺陷:requery -> syncOne -> 微信 FAIL -> CAS 0→3 -> umsMemberService.addBalance() 真加钱。
 *   selectPendingForQuery(定时任务入口)本就带 (frozen is null or frozen = 0),
 *   但 requery 走的是 selectById -> syncOne,不经那道过滤;
 *   updateStatusIfProcessing 原先只有 `status = 0`,冻结单照样被推进并回补余额。
 */
class CashWithdrawFrozenRequeryH2Test {

    private static final long MEMBER_ID = 77L;
    private static final BigDecimal AMOUNT = new BigDecimal("50.00");

    private static final String DDL =
            "create table member_cash_withdraw ("
                    + "id bigint primary key auto_increment,"
                    + "member_id bigint not null,"
                    + "openid varchar(64),"
                    + "amount decimal(15,2) not null,"
                    + "out_batch_no varchar(64),"
                    + "out_detail_no varchar(64),"
                    + "transfer_batch_id varchar(64),"
                    + "wechat_detail_id varchar(64),"
                    + "status tinyint not null default 0,"
                    + "fail_reason varchar(255),"
                    + "channel_error_code varchar(64),"
                    + "create_time datetime,"
                    + "finish_time datetime,"
                    + "del_flag tinyint not null default 0,"
                    + "wechat_batch_status varchar(32),"
                    + "submit_time datetime,"
                    + "last_query_time datetime,"
                    + "balance_deduct_id bigint,"
                    + "balance_refund_id bigint,"
                    + "manual_operator varchar(64),"
                    + "manual_verifier varchar(64),"
                    + "manual_note varchar(255),"
                    + "trace_id varchar(64),"
                    + "audit_by bigint,"
                    + "audit_time datetime,"
                    + "frozen tinyint default 0,"
                    + "frozen_reason varchar(255),"
                    + "frozen_by varchar(64),"
                    + "frozen_at datetime)";

    private JdbcTemplate jdbc;
    private MemberCashWithdrawMapper mapper;
    private ICashWithdrawService service;
    private IUmsMemberService umsMemberService;
    private IWechatPayService wechatPayService;
    private CashWithdrawIntentTestSupport evidence;

    @BeforeEach
    void setUp() throws Exception {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:cash_withdraw_frozen_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        DataSource dataSource = ds;

        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(DDL);
        // Legacy H2 regression fixture only; MySQL fund guarantees are tested separately.
        jdbc.execute("create table member_cash_withdraw_fund_fact (id bigint primary key auto_increment,"
                + "withdrawal_id bigint, member_id bigint, amount decimal(15,2), out_batch_no varchar(64), out_detail_no varchar(64),"
                + "request_mchid varchar(64),request_appid varchar(64),request_amount_fen bigint,request_openid varchar(128),request_remark varchar(32),request_version int,request_hash varchar(64),channel_batch_id varchar(64),"
                + "kind varchar(20), submission_intent_id bigint, balance_before decimal(15,2), balance_after decimal(15,2), create_time datetime,"
                + "channel_batch_status varchar(32), channel_detail_status varchar(32), channel_detail_id varchar(64), channel_error_code varchar(64),"
                + "unique(withdrawal_id, kind))");

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/MemberCashWithdrawMapper.xml"));
        SqlSessionFactory sqlSessionFactory = factory.getObject();
        mapper = new SqlSessionTemplate(sqlSessionFactory).getMapper(MemberCashWithdrawMapper.class);

        umsMemberService = mock(IUmsMemberService.class);
        when(umsMemberService.addBalance(any(), any())).thenReturn(true);
        wechatPayService = mock(IWechatPayService.class);

        CashWithdrawServiceImpl target = new CashWithdrawServiceImpl();
        evidence = new CashWithdrawIntentTestSupport();
        ReflectionTestUtils.setField(target, "transferIntents", evidence.mapper);
        ReflectionTestUtils.setField(target, "transactionManager",
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        ReflectionTestUtils.setField(target, "mapper", mapper);
        ReflectionTestUtils.setField(target, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(target, "wechatPayService", wechatPayService);
        ReflectionTestUtils.setField(target, "alarmService", mock(AlarmService.class));
        com.chengyinhub.business.service.IPlayerWithdrawalCooldownService cooldownStub = mock(com.chengyinhub.business.service.IPlayerWithdrawalCooldownService.class);
        when(cooldownStub.frozenAmount(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(java.math.BigDecimal.ZERO);
        ReflectionTestUtils.setField(target, "frozenAmountResolver",
                new com.chengyinhub.business.service.support.WithdrawalFrozenAmountResolver(mock(MerchantReferralBountyService.class), cooldownStub));
        service = target;
    }

    /** (a) 冻结中的处理中单:人工重查必须被入口守卫显式拒绝,不得回补余额、不得推进状态。 */
    @Test
    void frozenProcessingWithdrawIsRejectedByManualRequery() {
        long id = insertProcessing(1);
        wechatReturnsFail(id);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.requery(id));

        // 断言 1:★指认是哪道闸 —— 文案点名冻结,不是被别的前置判定顺手接住
        assertTrue(ex.getMessage().contains("已被冻结"),
                "异常文案必须点明是冻结拦的,实际=" + ex.getMessage());

        // 断言 2:钱没动
        verify(umsMemberService, never()).addBalance(any(), any());

        // 断言 3:syncOne 那组写入一个都没落库(FAIL 分支只有 updateStatusIfProcessing 这一条写语句)
        Map<String, Object> row = row(id);
        assertEquals(0, ((Number) row.get("status")).intValue(), "状态不得推进");
        assertNull(row.get("fail_reason"), "fail_reason 是 updateStatusIfProcessing 的 SET 项");
        assertNull(row.get("channel_error_code"), "channel_error_code 同上");
        assertNull(row.get("last_query_time"), "last_query_time 同上");
        assertEquals(1, ((Number) row.get("frozen")).intValue(), "本单确实处于冻结态");
    }

    /**
     * (a3) ★并发窗口:进入时未冻结(过了入口守卫),查微信期间运营点了冻结 —— 此时只剩
     * updateStatusIfProcessing 的 CAS 守卫能拦。这条专门覆盖 mapper 那一层,
     * 保证「入口拦人 + CAS 拦并发」两层各自都有红-capable 的测试,不会因为加了入口守卫
     * 就让 mapper 守卫变成没人测的死条件。
     */
    @Test
    void withdrawFrozenDuringChannelQueryIsStillStoppedByTheCasGuard() {
        long id = insertProcessing(0);
        Map<String, String> q = failResponse(id);
        // 模拟并发:requery 已通过入口守卫、正在查微信时,另一个会话把这单冻结了
        when(wechatPayService.queryTransferResult(org.mockito.ArgumentMatchers.argThat(i -> i != null && ("CW" + id).equals(i.outBatchNo()) && ("CD" + id).equals(i.outDetailNo())))).thenAnswer(inv -> {
            jdbc.update("update member_cash_withdraw set frozen = 1, frozen_reason = ? where id = ?",
                    "风控核查中", id);
            return q;
        });

        service.requery(id);   // 入口守卫读到的是未冻结的快照,不抛异常

        verify(wechatPayService, times(1)).queryTransferResult(org.mockito.ArgumentMatchers.argThat(i -> i != null && ("CW" + id).equals(i.outBatchNo()) && ("CD" + id).equals(i.outDetailNo())));
        //  ↑ 确实走进了 syncOne 的 FAIL 分支,是在 CAS 才被拦 —— 排除「没进 syncOne 所以没加钱」的假通过
        verify(umsMemberService, never()).addBalance(any(), any());

        Map<String, Object> row = row(id);
        assertEquals(0, ((Number) row.get("status")).intValue(),
                "CAS 0→3 必须被 mapper 的 frozen 守卫拦下");
        assertNull(row.get("fail_reason"),
                "fail_reason 非空即说明该 CAS 通过了 = mapper 守卫失效");
    }

    /**
     * (a2) ★冻结单 + 微信侧其实已转账成功:入口必须显式报错,不许静默 no-op。
     *
     * 这是 mapper 守卫单独存在时的盲区:syncOne 的 SUCCESS 分支是
     *   `int n = mapper.updateStatusIfProcessing(upd); if (n > 0) logger.info(...); return;`
     * CAS 被 frozen 守卫拦下时 n=0,直接静默 return,接口照样回成功 ——
     * 操作者看到「重查成功」,而钱已经出去、库里仍写着 status=0,零日志零提示。
     * 所以入口那道闸不是冗余,它才是让人看得见的那一层。
     */
    @Test
    void frozenWithdrawRejectsRequeryLoudlyEvenWhenChannelSucceeded() {
        long id = insertProcessing(1);
        wechatReturnsSuccess(id);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.requery(id));

        // (a) ★文案必须指认是冻结这道闸,而不是别的闸顺手接住了
        assertTrue(ex.getMessage().contains("已被冻结"),
                "异常文案必须点明是冻结拦的,实际=" + ex.getMessage());
        assertTrue(ex.getMessage().contains("风控核查中"),
                "必须带上冻结原因,操作者才知道找谁解冻,实际=" + ex.getMessage());

        // (b) status 仍为 0、finish_time 仍为空
        Map<String, Object> row = row(id);
        assertEquals(0, ((Number) row.get("status")).intValue(), "冻结单不得被推进到成功态");
        assertNull(row.get("finish_time"), "finish_time 是 SUCCESS 分支 CAS 的 SET 项,必须仍为空");
        assertNull(row.get("wechat_detail_id"), "wechat_detail_id 同上");

        // 入口就拦下了 ⇒ 连微信都不该查(比 CAS 那层更早,省一次外部调用)
        verify(wechatPayService, never()).queryTransferResult(org.mockito.ArgumentMatchers.any(CashWithdrawTransferIntent.class));
    }

    /** (b) 未冻结的同型单:正常路径不许被改坏,微信 FAIL 仍要自动回补。 */
    @Test
    void unfrozenProcessingWithdrawStillRefundsOnChannelFailure() {
        long id = insertProcessing(0);
        wechatReturnsFail(id);

        service.requery(id);

        verify(umsMemberService, times(1)).addBalance(eq(MEMBER_ID), eq(AMOUNT));

        Map<String, Object> row = row(id);
        assertEquals(MemberCashWithdraw.STATUS_FAILED_REFUNDED, ((Number) row.get("status")).intValue(),
                "未冻结单应走完 0→3→2,最终为『失败已退款』");
        assertEquals("微信转账失败", row.get("fail_reason"));
        assertNotNull(row.get("last_query_time"));
    }

    @Test
    void automaticRefundRollsBackBalanceAndTerminalStatusWhenBalanceWriteFails() {
        long id = insertProcessing(0);
        wechatReturnsFail(id);
        jdbc.execute("create table refund_balance (member_id bigint primary key, balance decimal(10,2))");
        jdbc.update("insert into refund_balance values (?, 100.00)", MEMBER_ID);
        org.mockito.Mockito.doAnswer(invocation -> {
            jdbc.update("update refund_balance set balance=balance+? where member_id=?", AMOUNT, MEMBER_ID);
            throw new IllegalStateException("balance write interrupted");
        }).when(umsMemberService).addBalance(MEMBER_ID, AMOUNT);

        service.requery(id);

        assertEquals(0, new BigDecimal("100.00").compareTo(jdbc.queryForObject(
                "select balance from refund_balance where member_id=?", BigDecimal.class, MEMBER_ID)));
        assertEquals(MemberCashWithdraw.STATUS_FAILED_MANUAL, ((Number) row(id).get("status")).intValue());
        org.mockito.Mockito.reset(umsMemberService);
        service.requery(id);
        verify(umsMemberService, never()).addBalance(any(), any());
    }

    /** (b2) 未冻结 + 微信 SUCCESS:确认到账这条正常路径同样不许被两道守卫改坏。 */
    @Test
    void unfrozenProcessingWithdrawStillConfirmsSuccess() {
        long id = insertProcessing(0);
        wechatReturnsSuccess(id);

        service.requery(id);

        verify(umsMemberService, never()).addBalance(any(), any());   // 到账不退款
        Map<String, Object> row = row(id);
        assertEquals(MemberCashWithdraw.STATUS_SUCCESS, ((Number) row.get("status")).intValue(),
                "未冻结单查到 SUCCESS 应正常 CAS 0→1");
        assertNotNull(row.get("finish_time"), "到账确认必须写 finish_time");
        assertEquals("D123456", row.get("wechat_detail_id"));
    }

    /**
     * (c) 归因对照:同一行、同一份微信 FAIL 响应,只翻转 frozen 这一个字段,结果就翻转。
     * 这条是「断言能指认哪道闸」的硬证据 —— 排除了状态守卫、del_flag、金额等其它变量。
     */
    @Test
    void onlyFrozenFlagDecidesWhetherRequeryRefunds() {
        long id = insertProcessing(1);
        wechatReturnsFail(id);

        assertThrows(IllegalStateException.class, () -> service.requery(id));
        verify(umsMemberService, never()).addBalance(any(), any());
        assertEquals(0, ((Number) row(id).get("status")).intValue());

        // 只解冻,其它一律不动
        jdbc.update("update member_cash_withdraw set frozen = 0 where id = ?", id);

        service.requery(id);
        verify(umsMemberService, times(1)).addBalance(eq(MEMBER_ID), eq(AMOUNT));
        assertEquals(MemberCashWithdraw.STATUS_FAILED_REFUNDED, ((Number) row(id).get("status")).intValue(),
                "解冻后同样的输入应当放行 ⇒ 前一次被拦的原因只能是 frozen");
    }

    /**
     * (d) 二阶后果护栏:定时任务入口本就取不到冻结单,补守卫不会让它少处理任何本该处理的单。
     * selectPendingForQuery 是 syncPendingWithdrawals 的唯一取数口。
     */
    @Test
    void scheduledScanNeverPicksUpFrozenRowsAnyway() {
        long frozen = insertProcessing(1);
        long normal = insertProcessing(0);

        List<MemberCashWithdraw> pending = mapper.selectPendingForQuery(50);

        assertEquals(1, pending.size(), "定时任务取数口只应看到未冻结的处理中单");
        assertEquals(normal, pending.get(0).getId().longValue());
        assertTrue(pending.stream().noneMatch(w -> w.getId() == frozen),
                "冻结单从来就进不了定时任务的处理列表 ⇒ 新守卫对定时任务是 no-op");
    }

    // ---------- helpers ----------

    private long insertProcessing(int frozen) {
        MemberCashWithdraw w = new MemberCashWithdraw();
        w.setMemberId(MEMBER_ID);
        w.setOpenid("o_test");
        w.setAmount(AMOUNT);
        w.setStatus(MemberCashWithdraw.STATUS_PROCESSING);
        w.setDelFlag(0);
        w.setCreateTime(new Date());
        w.setSubmitTime(new Date());
        mapper.insertMemberCashWithdraw(w);
        Long id = w.getId();
        MemberCashWithdraw idc = new MemberCashWithdraw();
        idc.setId(id);
        idc.setOutBatchNo("CW" + id);
        idc.setOutDetailNo("CD" + id);
        mapper.updateMemberCashWithdraw(idc);
        // Explicit fixture evidence for an already debited/submitted original; no real debit is claimed by H2.
        w.setOutBatchNo(idc.getOutBatchNo()); w.setOutDetailNo(idc.getOutDetailNo());
        com.chengyinhub.business.domain.CashWithdrawFundFact debit =
                com.chengyinhub.business.domain.CashWithdrawFundFact.of(w, "DEBIT");
        debit.setBalanceBefore(new BigDecimal("100.00")); debit.setBalanceAfter(new BigDecimal("50.00"));
        mapper.insertFundFact(debit);
        jdbc.update("update member_cash_withdraw set balance_deduct_id=? where id=?", debit.getId(), id);
        com.chengyinhub.business.domain.CashWithdrawFundFact submitted =
                com.chengyinhub.business.domain.CashWithdrawFundFact.of(w, "SUBMIT_INTENT");
        submitted.bindTransferIntent(CashWithdrawTransferIntent.fromRequest(id, "unit-merchant", "wx-mp-appid",
                w.getOutBatchNo(), w.getOutDetailNo(), AMOUNT, "o_test", "城瘾分润提现"));
        mapper.insertFundFact(submitted);
        jdbc.update("update member_cash_withdraw set frozen = ?, frozen_reason = ?, frozen_by = ? where id = ?",
                frozen, frozen == 1 ? "风控核查中" : null, frozen == 1 ? "finance01" : null, id);
        return id;
    }

    private Map<String, String> originalFacts(long id) {
        Map<String, String> facts = new HashMap<>();
        facts.put("mchid", "unit-merchant"); facts.put("appid", "wx-mp-appid"); facts.put("openid", "o_test");
        facts.put("outBatchNo", "CW" + id); facts.put("outDetailNo", "CD" + id);
        facts.put("transferAmount", "5000"); facts.put("batchId", "provider-batch"); facts.put("detailId", "provider-detail");
        return facts;
    }

    private Map<String, String> failResponse(long id) {
        Map<String, String> q = new HashMap<>();
        q.put("result", "FAIL");
        q.put("detailStatus", "FAIL");
        q.put("batchStatus", "FINISHED");
        q.putAll(originalFacts(id));
        q.put("failReasonCode", "ACCOUNT_FROZEN");
        return q;
    }

    private void wechatReturnsFail(long id) {
        when(wechatPayService.queryTransferResult(org.mockito.ArgumentMatchers.argThat(i -> i != null && ("CW" + id).equals(i.outBatchNo()) && ("CD" + id).equals(i.outDetailNo())))).thenReturn(failResponse(id));
    }

    private void wechatReturnsSuccess(long id) {
        Map<String, String> q = new HashMap<>();
        q.put("result", "SUCCESS"); q.put("detailStatus", "SUCCESS"); q.putAll(originalFacts(id));
        q.put("batchStatus", "FINISHED");
        q.put("detailId", "D123456");
        when(wechatPayService.queryTransferResult(org.mockito.ArgumentMatchers.argThat(i -> i != null && ("CW" + id).equals(i.outBatchNo()) && ("CD" + id).equals(i.outDetailNo())))).thenReturn(q);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("select * from member_cash_withdraw where id = ?", id);
    }
}
