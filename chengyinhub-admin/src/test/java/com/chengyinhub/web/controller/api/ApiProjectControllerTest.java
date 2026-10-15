package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MerchantChapterApplication;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IMerchantChapterApplicationService;
import com.chengyinhub.business.service.IProjectHomeReadService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * ApiProjectController#my 三角色 ownerType 分层验证。
 *
 * 构造 memberId=42 的聚合数据:主题(个人/俱乐部)+活动(俱乐部/中标商家/个人自办)。
 * 用 spy 桩住 getAppUserId,mock 三个 service,断言三种 ownerType 各返回正确子集且不破坏"全部"。
 *
 * 关键口径:merchant = activity.topicId 命中「我 auditStatus=1 中标」的 topicId 集合(中标承接活动)。
 */
class ApiProjectControllerTest {

    private static final long ME = 42L;
    private static final long WON_TOPIC_ID = 100L;   // 我中标承接的主题
    private static final long SELF_TOPIC_ID = 200L;  // 我自办的主题(未中标关系)

    private ApiProjectController controller;
    private ICmsTopicService cmsTopicService;
    private ICmsActivityService cmsActivityService;
    private ICmsRegistrationMerchantService cmsRegistrationMerchantService;
    private IMerchantAccessService merchantAccessService;
    private IMmsMerchantService mmsMerchantService;
    private IProjectHomeReadService projectHomeReadService;
    private IMerchantChapterApplicationService merchantChapterApplicationService;

    @BeforeEach
    void setUp() {
        cmsTopicService = mock(ICmsTopicService.class);
        cmsActivityService = mock(ICmsActivityService.class);
        cmsRegistrationMerchantService = mock(ICmsRegistrationMerchantService.class);
        merchantAccessService = mock(IMerchantAccessService.class);
        mmsMerchantService = mock(IMmsMerchantService.class);
        projectHomeReadService = mock(IProjectHomeReadService.class);
        merchantChapterApplicationService = mock(IMerchantChapterApplicationService.class);

        controller = spy(new ApiProjectController());
        doReturn(ME).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "cmsTopicService", cmsTopicService);
        ReflectionTestUtils.setField(controller, "cmsActivityService", cmsActivityService);
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantService", cmsRegistrationMerchantService);
        ReflectionTestUtils.setField(controller, "merchantAccessService", merchantAccessService);
        ReflectionTestUtils.setField(controller, "mmsMerchantService", mmsMerchantService);
        ReflectionTestUtils.setField(controller, "projectHomeReadService", projectHomeReadService);
        ReflectionTestUtils.setField(controller, "merchantChapterApplicationService", merchantChapterApplicationService);
        // P0-4 承接三态派生依赖(本测试不关心承接态,空返回即可)
        com.chengyinhub.business.service.ICoopInviteService coopInviteService =
                mock(com.chengyinhub.business.service.ICoopInviteService.class);
        when(coopInviteService.selectCoopInviteList(any(com.chengyinhub.business.domain.CoopInvite.class)))
                .thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "coopInviteService", coopInviteService);

        // 主题:个人(clubId=null) + 俱乐部(clubId=7)
        CmsTopic personalTopic = topic(1L, null, "simple");
        CmsTopic clubTopic = topic(2L, 7L, "pro");
        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class)))
                .thenReturn(new ArrayList<>(Arrays.asList(personalTopic, clubTopic)));
        when(cmsTopicService.selectDraftTopicIds(anyLong())).thenReturn(Collections.emptyList());

        // 活动:俱乐部(clubId=7) + 中标商家(topicId=100) + 个人自办(topicId=200)
        CmsActivity clubAct = activity(101L, 7L, null);
        CmsActivity merchantAct = activity(102L, null, WON_TOPIC_ID);
        CmsActivity selfAct = activity(103L, null, SELF_TOPIC_ID);
        when(cmsActivityService.selectCmsActivityList(any(CmsActivity.class)))
                .thenReturn(new ArrayList<>(Arrays.asList(clubAct, merchantAct, selfAct)));

        // 中标记录:我(memberId=42)以商家身份中标 topicId=100
        CmsRegistrationMerchant won = new CmsRegistrationMerchant();
        won.setMemberId(ME);
        won.setTopicId(WON_TOPIC_ID);
        won.setAuditStatus(1L);
        won.setDelFlag(0);
        when(cmsRegistrationMerchantService.selectCmsRegistrationMerchantList(any(CmsRegistrationMerchant.class)))
                .thenReturn(new ArrayList<>(Collections.singletonList(won)));
    }

    @Test
    void clubScopeKeepsDualIdentityPublisherAsDirectHost() {
        MmsMerchant merchant = new MmsMerchant();
        merchant.setMemberId(ME);
        when(mmsMerchantService.selectMmsMerchantByMemberId(ME)).thenReturn(merchant);
        Map<String, Object> expected = Collections.<String, Object>singletonMap("role", "host");
        when(projectHomeReadService.getProjectHome(ME, SELF_TOPIC_ID)).thenReturn(expected);
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("topicId", SELF_TOPIC_ID);
        body.put("scope", "CLUB");

        AjaxResult response = controller.home(body);

        assertEquals(200, response.get("code"));
        assertEquals(expected, response.get("data"));
        verify(projectHomeReadService).getProjectHome(ME, SELF_TOPIC_ID);
        verify(merchantAccessService, never()).require(anyLong(), anyString());
    }

    @Test
    void member_tab_returns_personal_topic_and_self_activity_only() {
        List<Map<String, Object>> rows = rows(call("member"));
        assertEquals(2, rows.size(), "个人 tab 应为:个人主题 + 个人自办活动");
        for (Map<String, Object> r : rows) {
            assertEquals("member", r.get("ownerType"));
        }
        assertTrue(ids(rows).containsAll(Arrays.asList(1L, 103L)));
    }

    @Test
    void club_tab_returns_club_topic_and_club_activity_only() {
        List<Map<String, Object>> rows = rows(call("club"));
        assertEquals(2, rows.size(), "俱乐部 tab 应为:俱乐部主题 + 俱乐部活动");
        for (Map<String, Object> r : rows) {
            assertEquals("club", r.get("ownerType"));
        }
        assertTrue(ids(rows).containsAll(Arrays.asList(2L, 101L)));
    }

    @Test
    void merchant_tab_returns_only_won_undertaken_activity() {
        List<Map<String, Object>> rows = rows(call("merchant"));
        assertEquals(1, rows.size(), "商家 tab 仅含中标承接生成的活动");
        Map<String, Object> only = rows.get(0);
        assertEquals("merchant", only.get("ownerType"));
        assertEquals(102L, ((Number) only.get("id")).longValue());
    }

    @Test
    void no_ownerType_returns_all_five_backward_compatible() {
        List<Map<String, Object>> rows = rows(call(null));
        assertEquals(5, rows.size(), "不传 ownerType 保持旧行为:返回全部");
    }

    /** 工作台「全部 N」用未结束总数:档期未知不算结束(与小程序 isEndedProject 同口径)。 */    @Test
    void activeTotalKeepsProjectsWithUnknownSchedule() {
        assertEquals(5, activeTotal(call(null)),
                "五条都没写档期 ⇒ 都不算结束,不能只数本页 rows");
    }

    /** 反面:结束日已经过去的不进未结束总数;仍在档期/未开始/档期未知的照留。 */
    @Test
    void activeTotalDropsOnlyProjectsWithPassedEndDay() {
        CmsTopic ended = topic(301L, null, "simple");
        ended.setStartDate(java.sql.Date.valueOf("2020-01-01"));
        ended.setEndDate(java.sql.Date.valueOf("2020-01-02"));
        CmsTopic running = topic(302L, null, "simple");
        running.setStartDate(java.sql.Date.valueOf("2026-01-01"));
        running.setEndDate(java.sql.Date.valueOf("2099-01-01"));
        CmsTopic unknown = topic(303L, null, "simple");
        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class)))
                .thenReturn(new ArrayList<>(Arrays.asList(ended, running, unknown)));
        when(cmsActivityService.selectCmsActivityList(any(CmsActivity.class)))
                .thenReturn(new ArrayList<>());

        assertEquals(2, activeTotal(call(null)), "只有结束日已过的那条出局");
    }

    @Test
    void projectQueriesHideRowsOwnedByDissolvedClubs() {        call(null);
        ArgumentCaptor<CmsTopic> topicCaptor = ArgumentCaptor.forClass(CmsTopic.class);
        ArgumentCaptor<CmsActivity> activityCaptor = ArgumentCaptor.forClass(CmsActivity.class);
        verify(cmsTopicService).selectCmsTopicList(topicCaptor.capture());
        verify(cmsActivityService).selectCmsActivityList(activityCaptor.capture());
        assertTrue(Boolean.TRUE.equals(topicCaptor.getValue().getActiveClubOnly()));
        assertTrue(Boolean.TRUE.equals(activityCaptor.getValue().getActiveClubOnly()));
    }

    @Test
    void merchantManagerProjectAggregationUsesResolvedOwnerScope() {
        long owner = 900L;
        MerchantAccessContext context = new MerchantAccessContext(ME, 11L, owner,
                MerchantRoleCode.MERCHANT_MANAGER);
        when(merchantAccessService.resolve(ME)).thenReturn(context);
        when(merchantAccessService.require(ME, MerchantPermission.PROJECT_MANAGE)).thenReturn(context);

        call(null, "MERCHANT");

        ArgumentCaptor<CmsRegistrationMerchant> wonCaptor = ArgumentCaptor.forClass(CmsRegistrationMerchant.class);
        ArgumentCaptor<CmsTopic> topicCaptor = ArgumentCaptor.forClass(CmsTopic.class);
        ArgumentCaptor<CmsActivity> activityCaptor = ArgumentCaptor.forClass(CmsActivity.class);
        verify(cmsRegistrationMerchantService).selectCmsRegistrationMerchantList(wonCaptor.capture());
        verify(cmsTopicService).selectCmsTopicList(topicCaptor.capture());
        verify(cmsActivityService).selectCmsActivityList(activityCaptor.capture());
        assertEquals(owner, wonCaptor.getValue().getMemberId());
        assertEquals(owner, topicCaptor.getValue().getMemberId());
        assertEquals(owner, activityCaptor.getValue().getMemberId());
        verify(merchantAccessService).require(ME, MerchantPermission.PROJECT_MANAGE);
    }

    @Test
    void merchantManagerWithoutExplicitScopeStillSeesPersonalProjects() {
        MerchantAccessContext context = new MerchantAccessContext(ME, 11L, 900L,
                MerchantRoleCode.MERCHANT_MANAGER);
        when(merchantAccessService.resolve(ME)).thenReturn(context);

        call(null, null);

        ArgumentCaptor<CmsTopic> topicCaptor = ArgumentCaptor.forClass(CmsTopic.class);
        verify(cmsTopicService).selectCmsTopicList(topicCaptor.capture());
        assertEquals(ME, topicCaptor.getValue().getMemberId());
        org.mockito.Mockito.verify(merchantAccessService, org.mockito.Mockito.never())
                .require(anyLong(), any());
    }

    /**
     * CU-C-47(2026-09-24 走查):我的项目里同名活动卡看不出是哪一场 —— 卡面完全没有档期。
     *
     * <p>{@code /api/project/my} 对活动行下发的 startTime/endTime 恒为 null:取的是
     * {@code CmsActivity.startTime}(@TableField(exist=false)、注释写明「搜索用」、全仓无写入点),
     * 而活动真正的档期落在 cms_activity.start_date/end_date。mapTopic 一直是后者,活动这一边
     * 也必须同口径 —— 顺带把卡片状态(进行中/已完成)从「恒为进行中」拉回真实值。
     */
    @Test
    void activityRowsCarryPersistedScheduleAndDerivedState() {
        CmsActivity endedAct = activity(401L, null, null);
        endedAct.setStartDate(java.sql.Timestamp.valueOf("2020-01-01 09:00:00"));
        endedAct.setEndDate(java.sql.Timestamp.valueOf("2020-01-02 18:00:00"));
        CmsActivity runningAct = activity(402L, null, null);
        runningAct.setStartDate(java.sql.Timestamp.valueOf("2026-01-01 09:00:00"));
        runningAct.setEndDate(java.sql.Timestamp.valueOf("2099-01-01 18:00:00"));
        when(cmsActivityService.selectCmsActivityList(any(CmsActivity.class)))
                .thenReturn(new ArrayList<>(Arrays.asList(endedAct, runningAct)));
        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class))).thenReturn(new ArrayList<>());

        Map<String, Object> ended = rowById(rows(call(null)), 401L);

        assertNotNull(ended.get("startTime"), "活动卡的档期不能是 null —— 小程序卡面只认 startTime");
        assertEquals(endedAct.getStartDate(), ended.get("startTime"));
        assertEquals(endedAct.getEndDate(), ended.get("endTime"));
        assertEquals("completed", ended.get("state"), "卡片状态与卡面档期同源,不能再从 null 推出「进行中」");
        assertEquals("running", rowById(rows(call(null)), 402L).get("state"));
    }

    /**
     * CU-M-175(2026-09-25 走查):同一场活动「我的项目」卡写「1 报名」,点进去详情写「已报名 0 人」。
     *
     * <p>卡片原先取 {@code countOpenSaleRegistrations}(payment_status ∈ 0/1/2,含拉起支付没付完的
     * 弃单),详情取 registration_status=2 —— 两套判据并存。这里钉死卡面只认
     * {@code countSignedUpRegistrations},与详情页同一个数;开卖口径留给「已售不可改」那类锁判据。
     *
     * <p>⚠️ 负控:把 ApiProjectController 改回 countOpenSaleRegistrations ⇒ 本用例两处一起红
     * (数字变 1、never() 被抓)。
     */
    @Test
    void activityCardSignupCountUsesTheSameJudgementAsTheDetailPage() {
        when(cmsActivityService.countOpenSaleRegistrations(101L)).thenReturn(1);
        when(cmsActivityService.countSignedUpRegistrations(101L)).thenReturn(0);

        Map<String, Object> card = rowById(rows(call("club")), 101L);

        assertEquals(0, ((Number) card.get("signupCount")).intValue(),
                "详情写「已报名 0 人」时,同一活动的卡片不能写 1 报名");
        verify(cmsActivityService, never()).countOpenSaleRegistrations(anyLong());
    }

    @Test
    void projectMetricsQueryBudgetDoesNotGrowWithProjectCount() {
        List<CmsTopic> topics = new ArrayList<>();
        List<CmsActivity> activities = new ArrayList<>();
        for (long id = 1; id <= 100; id++) {
            topics.add(topic(id, null, "pro"));
            activities.add(activity(1000 + id, null, SELF_TOPIC_ID));
        }
        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class))).thenReturn(topics);
        when(cmsActivityService.selectCmsActivityList(any(CmsActivity.class))).thenReturn(activities);
        Map<String, Object> data = (Map<String, Object>) controller.my(null, null, null, 1, 200, null).get("data");
        assertEquals(200, ((List<?>) data.get("rows")).size());
        Object invites = ReflectionTestUtils.getField(controller, "coopInviteService");
        long metricReads = org.mockito.Mockito.mockingDetails(invites).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("selectCoopInviteList")).count()
                + org.mockito.Mockito.mockingDetails(cmsActivityService).getInvocations().stream()
                .filter(i -> i.getMethod().getName().startsWith("countSignedUpRegistrations")
                        || i.getMethod().getName().startsWith("countPendingVerification")).count();
        assertTrue(metricReads <= 2, "200项目的承接/报名统计不能产生逐项查询: " + metricReads);
    }

    @Test
    void batchedSignupCountsStayOnTheirOwnActivityCards() {
        when(cmsActivityService.countSignedUpRegistrationsByActivityIds(anyList()))
                .thenReturn(Collections.singletonMap(101L, 7));
        List<Map<String, Object>> cards = rows(call(null));
        assertEquals(7, rowById(cards, 101L).get("signupCount"));
        assertEquals(0, rowById(cards, 102L).get("signupCount"));
        assertEquals(0, rowById(cards, 103L).get("signupCount"));
        verify(cmsActivityService).countSignedUpRegistrationsByActivityIds(Arrays.asList(101L, 102L, 103L));
        verify(cmsActivityService, never()).countSignedUpRegistrations(anyLong());
    }

    @Test
    void pendingVerificationCountsOnlyBelongToUndertakenMerchantActivities() {
        when(cmsActivityService.countPendingVerificationByActivityIds(anyList()))
                .thenReturn(Collections.singletonMap(102L, 4));
        List<Map<String, Object>> cards = rows(call(null));
        assertEquals(4, rowById(cards, 102L).get("pendingVerifyCount"));
        assertTrue(!rowById(cards, 101L).containsKey("pendingVerifyCount"));
        assertTrue(!rowById(cards, 103L).containsKey("pendingVerifyCount"));
        verify(cmsActivityService).countPendingVerificationByActivityIds(Collections.singletonList(102L));
    }

    @Test
    void manyUndertakenActivitiesReadEachStatisticOnlyOnce() {
        List<CmsActivity> activities = new ArrayList<>();
        for (long id = 1; id <= 100; id++) activities.add(activity(1000 + id, null, WON_TOPIC_ID));
        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class))).thenReturn(Collections.emptyList());
        when(cmsActivityService.selectCmsActivityList(any(CmsActivity.class))).thenReturn(activities);
        assertEquals(100, rows(controller.my(null, null, null, 1, 200, null)).size());
        verify(cmsActivityService, org.mockito.Mockito.times(1))
                .countSignedUpRegistrationsByActivityIds(org.mockito.ArgumentMatchers.argThat(ids -> ids.size() == 100));
        verify(cmsActivityService, org.mockito.Mockito.times(1))
                .countPendingVerificationByActivityIds(org.mockito.ArgumentMatchers.argThat(ids -> ids.size() == 100));
        verify(cmsActivityService, never()).countSignedUpRegistrations(anyLong());
    }

    @Test
    void freeClubProjectAcceptanceRequiresApprovedActiveChapterSupply() {
        CmsTopic free = topic(301L, 7L, "simple");
        free.setProductType(2);
        free.setMerchantStatus(1);
        CmsTopic otherFree = topic(302L, 7L, "simple");
        otherFree.setProductType(2);
        otherFree.setMerchantStatus(1);
        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class)))
                .thenReturn(Arrays.asList(free, otherFree));
        for (int applicationStatus : new int[] {0, 1, 2}) {
            for (boolean offerActive : new boolean[] {true, false}) {
                MerchantChapterApplication application = new MerchantChapterApplication();
                application.setTopicId(free.getId());
                application.setStatus(applicationStatus);
                application.setOfferActive(offerActive);
                when(merchantChapterApplicationService.selectForPublisher(ME))
                        .thenReturn(Collections.singletonList(application));
                List<Map<String, Object>> cards = rows(call(null));
                Map<String, Object> card = rowById(cards, free.getId());
                assertEquals(applicationStatus == 1 && offerActive
                        ? "merchantAccepted" : "merchantOpen", card.get("acceptStatus"));
                assertEquals("merchantOpen", rowById(cards, otherFree.getId()).get("acceptStatus"),
                        "生效供给不能串到另一主题");
            }
        }
        verify(merchantChapterApplicationService, org.mockito.Mockito.times(6)).selectForPublisher(ME);
        free.setProductType(1);
        otherFree.setProductType(1);
        assertEquals("merchantOpen", rowById(rows(call(null)), free.getId()).get("acceptStatus"),
                "城市定向继续使用原邀约承接口径");
        verify(merchantChapterApplicationService, org.mockito.Mockito.times(6)).selectForPublisher(ME);
    }

    // ---- helpers ----

    private AjaxResult call(String ownerType) {        return call(ownerType, null);
    }

    private AjaxResult call(String ownerType, String scope) {
        return controller.my(null, null, ownerType, 1, 50, scope);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(AjaxResult r) {
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        return (List<Map<String, Object>>) data.get("rows");
    }

    private int activeTotal(AjaxResult r) {
        Map<?, ?> data = (Map<?, ?>) r.get("data");
        return ((Number) data.get("activeTotal")).intValue();
    }

    private List<Long> ids(List<Map<String, Object>> rows) {
        List<Long> out = new ArrayList<>();
        for (Map<String, Object> r : rows) out.add(((Number) r.get("id")).longValue());
        return out;
    }

    private Map<String, Object> rowById(List<Map<String, Object>> rows, Long id) {
        for (Map<String, Object> r : rows) {
            if (id.equals(((Number) r.get("id")).longValue())) return r;
        }
        throw new AssertionError("结果里没有 id=" + id + " 的行:" + ids(rows));
    }

    private CmsTopic topic(Long id, Long clubId, String publishMode) {
        CmsTopic t = new CmsTopic();
        t.setId(id);
        t.setMemberId(ME);
        t.setClubId(clubId);
        t.setPublishMode(publishMode);
        t.setStatus(1);
        t.setUserStatus(1);
        return t;
    }

    private CmsActivity activity(Long id, Long clubId, Long topicId) {
        CmsActivity a = new CmsActivity();
        a.setId(id);
        a.setMemberId(ME);
        a.setClubId(clubId);
        a.setTopicId(topicId);
        a.setStatus(1L);
        a.setPublishStatus(1L);
        return a;
    }
}
