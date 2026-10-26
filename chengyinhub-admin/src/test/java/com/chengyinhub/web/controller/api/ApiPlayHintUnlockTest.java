package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsPointsSetting;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsPointsSettingService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IUmsMemberPointsDetailService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 积分解锁游玩提示:POST /api/play/hint/unlock 的扣分/幂等/余额/免费模式闸。
 * 口径:规则行 (cms_points_setting type=1, event_type=14) 的分值>0 才收费;
 * 解锁记录 = 消费流水本身 (memberId, eventType=14, eventId=nodeId),不建新表。
 */
class ApiPlayHintUnlockTest {

    private static final Long MEMBER = 88L;
    private static final Long NODE = 401L;
    private static final Long TPL = 7L;
    private static final int COST = 10;

    private static final Long ACTIVITY = 500L;
    private static final Long TOPIC = 300L;

    private ICmsTopicNodeService nodeSvc;
    private ICmsMemberTemplateService tplSvc;
    private ICmsPointsSettingService settingSvc;
    private IUmsMemberService memberSvc;
    private IUmsMemberPointsDetailService detailSvc;
    private com.chengyinhub.business.service.ICmsActivityService activitySvc;
    private com.chengyinhub.business.service.ICmsRegistrationService registrationSvc;
    private com.chengyinhub.business.service.IXpBudgetService xpBudgetService;
    private ApiPlayProgressController controller;

    @BeforeEach
    void setUp() {
        nodeSvc = mock(ICmsTopicNodeService.class);
        tplSvc = mock(ICmsMemberTemplateService.class);
        settingSvc = mock(ICmsPointsSettingService.class);
        memberSvc = mock(IUmsMemberService.class);
        detailSvc = mock(IUmsMemberPointsDetailService.class);

        activitySvc = mock(com.chengyinhub.business.service.ICmsActivityService.class);

        controller = spy(new ApiPlayProgressController());
        doReturn(MEMBER).when(controller).getAppUserId();
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeSvc);
        // 承接方现场层:没有中标商家时返回空对象,取值全部回落主办方模板 ——
        // 用真实现而不是 mock,免得「回落」这条路径在测试里被 mock 假装成功。
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "nodeMerchantOverrideService",
                new com.chengyinhub.business.service.impl.NodeMerchantOverrideServiceImpl(
                        org.mockito.Mockito.mock(com.chengyinhub.business.service.ICmsRegistrationMerchantService.class)));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsMemberTemplateService", tplSvc);


        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "umsMemberPointsDetailService", detailSvc);
        // nodes() 出口扣发用:活动会话解析 + 报名探针/进度/章节/模式解析(默认空返回即可)
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activitySvc);
        registrationSvc = mock(com.chengyinhub.business.service.ICmsRegistrationService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrationSvc);
        // C-19 准入闸:自玩通行证探针(ownerType=1)返一份有效通行证,让「只发 nodeId」的存量计费用例
        // 照旧可跑;活动报名探针(ownerType=2)保持空 —— nodes() 的 registered 语义不变。
        CmsRegistration validPass = new CmsRegistration();
        validPass.setMemberId(MEMBER);
        validPass.setOwnerType(1L);
        validPass.setOwnerId(TOPIC);
        validPass.setRegistrationStatus(2);
        validPass.setExpiresAt(new java.util.Date(System.currentTimeMillis() + 86400000L));
        when(registrationSvc.selectCmsRegistrationList(any())).thenAnswer(invocation -> {
            CmsRegistration probe = invocation.getArgument(0);
            return probe != null && Long.valueOf(1L).equals(probe.getOwnerType())
                    ? Collections.singletonList(validPass) : Collections.emptyList();
        });
        // nodes() 下发节点真实探索值:桩只证明「读的是它」,值本身由夹具给
        xpBudgetService = mock(com.chengyinhub.business.service.IXpBudgetService.class);
        when(xpBudgetService.resolveNodeXp(any(CmsTopicNode.class), any())).thenReturn(25);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "xpBudgetService", xpBudgetService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService",
                mock(com.chengyinhub.business.service.IPlayerNodeProgressService.class));
        com.chengyinhub.business.service.ICmsTopicService topicSvc =
                mock(com.chengyinhub.business.service.ICmsTopicService.class);
        // O-08:未报名者看节点要求主题对其公开可见;本夹具的主题是公开在架主题。
        when(topicSvc.selectCmsTopicDetailById(eq(TOPIC), any())).thenReturn(new com.chengyinhub.business.domain.CmsTopic());
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topicSvc);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "omsTicketService",
                mock(com.chengyinhub.business.service.IOmsTicketService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicChapterService",
                mock(com.chengyinhub.business.service.ICmsTopicChapterService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "puzzlePlayService",
                mock(com.chengyinhub.business.service.IPuzzlePlayService.class));

        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE);
        node.setTemplateId(TPL);
        node.setTopicId(TOPIC);   // C-19:只发 nodeId 的老调用方按「本主题自玩通行证」兜底
        node.setSortId(1L);
        when(nodeSvc.selectCmsTopicNodeById(NODE)).thenReturn(node);

        CmsMemberTemplate tpl = new CmsMemberTemplate();
        tpl.setId(TPL);
        tpl.setHint1("留意店内的特色陈设");
        tpl.setHint2("靠近收银台或入口处");
        when(tplSvc.selectCmsMemberTemplateById(TPL)).thenReturn(tpl);

        CmsPointsSetting rule = new CmsPointsSetting();
        rule.setPointsNum(COST);
        when(settingSvc.selectCmsPointsSettingList(any())).thenReturn(Collections.singletonList(rule));

        UmsMember member = new UmsMember();
        member.setId(MEMBER);
        member.setPoint(100L);
        when(memberSvc.selectUmsMemberById(MEMBER)).thenReturn(member);
        when(memberSvc.deductPoints(MEMBER, COST)).thenReturn(true);
        when(detailSvc.insertUmsMemberPointsDetail(any())).thenReturn(1);
        // 默认:该会员尚无 event_type=14 消费流水 = 未解锁过
        when(detailSvc.selectUmsMemberPointsDetailList(any())).thenReturn(Collections.emptyList());
        CurrentPlayControllerFixtures.install(controller);
        NodeHintUnlockTestFixtures.wire(controller, settingSvc, memberSvc, detailSvc, null);
    }

    @SuppressWarnings("unchecked")
    @Test
    void unlock_charges_points_writes_ledger_and_returns_hints() {
        AjaxResult res = controller.unlockHint(NODE);

        assertEquals(200, res.get("code"));
        Map<String, Object> data = (Map<String, Object>) res.get("data");
        assertEquals("留意店内的特色陈设", data.get("hint1"));
        assertEquals("靠近收银台或入口处", data.get("hint2"));

        // 规则探针必须钉在 (type=1, event_type=14) 上 —— 防「stub any() 恒中」假绿
        ArgumentCaptor<CmsPointsSetting> probe = ArgumentCaptor.forClass(CmsPointsSetting.class);
        verify(settingSvc).selectCmsPointsSettingList(probe.capture());
        assertEquals(Long.valueOf(1L), probe.getValue().getType());
        assertEquals(Long.valueOf(14L), probe.getValue().getEventType());

        verify(memberSvc).deductPoints(MEMBER, COST);
        ArgumentCaptor<UmsMemberPointsDetail> ledger = ArgumentCaptor.forClass(UmsMemberPointsDetail.class);
        verify(detailSvc).insertUmsMemberPointsDetail(ledger.capture());
        assertEquals(MEMBER, ledger.getValue().getMemberId());
        assertEquals(Long.valueOf(14L), ledger.getValue().getEventType());
        assertEquals(NODE, ledger.getValue().getEventId());
        assertEquals(Long.valueOf(2L), ledger.getValue().getChangeType()); // 2=支出
        assertEquals(Long.valueOf(COST), ledger.getValue().getChangePoints());
    }

    @SuppressWarnings("unchecked")
    @Test
    void unlock_is_idempotent_when_unique_ledger_insert_conflicts() {
        when(detailSvc.insertUmsMemberPointsDetail(any()))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_member_event_once"));

        AjaxResult res = controller.unlockHint(NODE);

        assertEquals(200, res.get("code"));
        Map<String, Object> data = (Map<String, Object>) res.get("data");
        assertEquals("留意店内的特色陈设", data.get("hint1"));

        verify(memberSvc, never()).deductPoints(anyLong(), anyInt());
    }

    @SuppressWarnings("unchecked")
    @Test
    void unlock_concurrent_calls_write_one_ledger_charge_once_and_return_already_unlocked() throws Exception {
        AtomicInteger insertAttempts = new AtomicInteger();
        AtomicInteger successfulLedgerWrites = new AtomicInteger();
        CountDownLatch firstInsertEntered = new CountDownLatch(1);
        CountDownLatch secondInsertAttempted = new CountDownLatch(1);
        when(detailSvc.insertUmsMemberPointsDetail(any())).thenAnswer(invocation -> {
            if (insertAttempts.incrementAndGet() == 1) {
                firstInsertEntered.countDown();
                assertTrue(secondInsertAttempted.await(2, TimeUnit.SECONDS), "第二个请求未到唯一键闸门");
                successfulLedgerWrites.incrementAndGet();
                return 1;
            }
            secondInsertAttempted.countDown();
            throw new org.springframework.dao.DuplicateKeyException("uk_member_event_once");
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AjaxResult> first = executor.submit(() -> controller.unlockHint(NODE));
            assertTrue(firstInsertEntered.await(2, TimeUnit.SECONDS), "第一个请求未到写入流水处");
            Future<AjaxResult> second = executor.submit(() -> controller.unlockHint(NODE));

            AjaxResult firstResult = first.get(2, TimeUnit.SECONDS);
            AjaxResult secondResult = second.get(2, TimeUnit.SECONDS);

            assertEquals(200, firstResult.get("code"));
            assertEquals(200, secondResult.get("code"));
            assertEquals(COST, ((Map<String, Object>) firstResult.get("data")).get("cost"));
            assertEquals(0, ((Map<String, Object>) secondResult.get("data")).get("cost"));
            assertEquals(1, successfulLedgerWrites.get(), "并发请求只能成功写入一条解锁流水");
            verify(memberSvc, times(1)).deductPoints(MEMBER, COST);
        } finally {
            executor.shutdownNow();
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void unlock_free_when_no_rule_row_no_charge_no_ledger() {
        when(settingSvc.selectCmsPointsSettingList(any())).thenReturn(Collections.emptyList());

        AjaxResult res = controller.unlockHint(NODE);

        assertEquals(200, res.get("code"));
        Map<String, Object> data = (Map<String, Object>) res.get("data");
        assertEquals("留意店内的特色陈设", data.get("hint1"));
        verify(memberSvc, never()).deductPoints(anyLong(), anyInt());
        verify(detailSvc, never()).insertUmsMemberPointsDetail(any());
    }

    @Test
    void unlock_rejected_when_template_has_no_hints_no_charge() {
        CmsMemberTemplate bare = new CmsMemberTemplate();
        bare.setId(TPL);
        bare.setHint1("");
        bare.setHint2(null);
        when(tplSvc.selectCmsMemberTemplateById(TPL)).thenReturn(bare);

        AjaxResult res = controller.unlockHint(NODE);

        assertNotEquals(200, res.get("code"));
        verify(memberSvc, never()).deductPoints(anyLong(), anyInt());
        verify(detailSvc, never()).insertUmsMemberPointsDetail(any());
    }

    @Test
    void unlock_rejected_by_precheck_when_balance_short_deduct_never_called() {
        UmsMember poor = new UmsMember();
        poor.setId(MEMBER);
        poor.setPoint(5L); // < COST
        when(memberSvc.selectUmsMemberById(MEMBER)).thenReturn(poor);

        AjaxResult res = controller.unlockHint(NODE);

        assertNotEquals(200, res.get("code"));
        verify(memberSvc, never()).deductPoints(anyLong(), anyInt());
        verify(detailSvc, never()).insertUmsMemberPointsDetail(any());
    }

    @Test
    void unlock_rejected_when_atomic_deduct_throws() {
        // 真实实现缺分时不是返 false 而是抛 RuntimeException(UmsMemberServiceImpl:257);
        // 预检通过后并发扣穿走的就是这条路,必须转业务文案而非 500
        when(memberSvc.deductPoints(MEMBER, COST))
                .thenThrow(new RuntimeException("用户积分不足，当前积分：5"));

        AjaxResult res = controller.unlockHint(NODE);

        assertNotEquals(200, res.get("code"));
    }

    @Test
    void unlock_free_when_rule_row_disabled_no_charge() {
        CmsPointsSetting disabled = new CmsPointsSetting();
        disabled.setPointsNum(COST);
        disabled.setStatus(0L); // 后台停用=关闸,与免费同义
        when(settingSvc.selectCmsPointsSettingList(any())).thenReturn(Collections.singletonList(disabled));

        AjaxResult res = controller.unlockHint(NODE);

        assertEquals(200, res.get("code"));
        verify(memberSvc, never()).deductPoints(anyLong(), anyInt());
        verify(detailSvc, never()).insertUmsMemberPointsDetail(any());
    }

    // ---------- /api/play/nodes 出口扣发 ----------

    private Map<String, Object> firstNodeOfNodesPayload() {
        com.chengyinhub.business.domain.CmsActivity act = new com.chengyinhub.business.domain.CmsActivity();
        act.setId(ACTIVITY);
        act.setTopicId(TOPIC);
        when(activitySvc.selectCmsActivityById(ACTIVITY)).thenReturn(act);
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE);
        node.setTemplateId(TPL);
        node.setTopicId(TOPIC);
        node.setXpValue(25);
        node.setCardHookLong("店门口的招牌会换");
        node.setFragmentText("你拿到了一枚碎片");
        when(nodeSvc.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));

        AjaxResult res = controller.nodes(ACTIVITY, null);
        assertEquals(200, res.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) res.get("data");
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> nodes = (java.util.List<Map<String, Object>>) data.get("nodes");
        return nodes.get(0);
    }

    @Test
    void nodes_withholds_hints_when_cost_active_and_not_unlocked() {
        Map<String, Object> m = firstNodeOfNodesPayload();

        org.junit.jupiter.api.Assertions.assertFalse(m.containsKey("hint1"), "hint1 不得明文下发");
        org.junit.jupiter.api.Assertions.assertFalse(m.containsKey("hint2"), "hint2 不得明文下发");
        assertEquals(Boolean.TRUE, m.get("hintLocked"));
        assertEquals(COST, m.get("hintCost"));
        assertEquals(2, m.get("hintCount"));
    }

    @Test
    void nodes_sends_hints_when_already_unlocked() {
        UmsMemberPointsDetail unlocked = new UmsMemberPointsDetail();
        unlocked.setEventId(NODE);
        when(detailSvc.selectUmsMemberPointsDetailList(any()))
                .thenReturn(Collections.singletonList(unlocked));

        Map<String, Object> m = firstNodeOfNodesPayload();

        assertEquals("留意店内的特色陈设", m.get("hint1"));
        assertEquals("靠近收银台或入口处", m.get("hint2"));
        org.junit.jupiter.api.Assertions.assertFalse(m.containsKey("hintLocked"));
    }

    @Test
    void nodes_sends_hints_free_when_no_rule_row_legacy_behavior() {
        when(settingSvc.selectCmsPointsSettingList(any())).thenReturn(Collections.emptyList());

        Map<String, Object> m = firstNodeOfNodesPayload();

        assertEquals("留意店内的特色陈设", m.get("hint1"));
        assertEquals("靠近收银台或入口处", m.get("hint2"));
        org.junit.jupiter.api.Assertions.assertFalse(m.containsKey("hintLocked"));
    }

    // ---------- C-19 提示解锁准入门禁(2026-09-16) ----------

    /** 负控:没有任何报名/通行证的登录用户,拿 nodeId 也读不到提示,更不会走计费。 */
    @Test
    void unlock_rejected_without_entitlement_and_no_hint_text_no_charge() {
        when(registrationSvc.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());

        AjaxResult res = controller.unlockHint(NODE);

        assertNotEquals(200, res.get("code"));
        org.junit.jupiter.api.Assertions.assertFalse(res.containsKey("data"), "拒绝时不得携带提示正文");
        verify(memberSvc, never()).deductPoints(anyLong(), anyInt());
        verify(detailSvc, never()).insertUmsMemberPointsDetail(any());
    }

    /** 带活动上下文但没报名:与 /answer 同口径「请先报名并完成支付」,不是把提示白送。 */
    @Test
    void unlock_rejected_when_activity_context_but_not_registered() {
        CmsActivity act = new CmsActivity();
        act.setId(ACTIVITY);
        act.setTopicId(TOPIC);
        when(activitySvc.selectCmsActivityById(ACTIVITY)).thenReturn(act);
        when(registrationSvc.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());

        AjaxResult res = controller.unlockHint(NODE, String.valueOf(ACTIVITY), null);

        assertNotEquals(200, res.get("code"));
        assertEquals("请先报名并完成支付", res.get("msg"));
        verify(detailSvc, never()).insertUmsMemberPointsDetail(any());
    }

    /** 正控:真报名的人照旧解锁。门禁只挡「没有资格的人」,不挡付费玩家。 */
    @Test
    void unlock_allowed_when_activity_registration_exists() {
        CmsActivity act = new CmsActivity();
        act.setId(ACTIVITY);
        act.setTopicId(TOPIC);
        when(activitySvc.selectCmsActivityById(ACTIVITY)).thenReturn(act);
        CmsRegistration registered = new CmsRegistration();
        registered.setMemberId(MEMBER);
        registered.setOwnerType(2L);   // 2=活动
        registered.setOwnerId(ACTIVITY);
        registered.setRegistrationStatus(2);
        when(registrationSvc.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(registered));

        AjaxResult res = controller.unlockHint(NODE, String.valueOf(ACTIVITY), null);

        assertEquals(200, res.get("code"));
        verify(memberSvc).deductPoints(MEMBER, COST);
    }

    // ---------- C-20 / C-27 节点内容与探索值下发 ----------

    @Test
    void nodes_sends_card_hook_fragment_and_real_xp() {
        Map<String, Object> m = firstNodeOfNodesPayload();

        assertEquals("店门口的招牌会换", m.get("cardHookLong"));
        assertEquals("你拿到了一枚碎片", m.get("fragmentText"));
        assertEquals(Integer.valueOf(25), m.get("xp"), "节点展示的探索值必须取自后端真源,不是写死的 12");
    }
}
