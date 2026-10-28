package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsRegistrationScan;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsRegistrationScanService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IOmsOrderService;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.business.mapper.MerchantAftercareMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 商家统计的主题口径必须只认调配状态机的中标态。 */
class ApiMerchantAwardedTopicStatsTest {

    private static final long MERCHANT_ID = 71L;
    private static final long MERCHANT_ROW_ID = 11L;
    private static final long TOPIC_ID = 72L;

    private static final long DAY = 24L * 60L * 60L * 1000L;

    /** 竞猜待答名单桩:默认空,用例可先赋值再建 MockMvc。 */
    private List<java.util.Map<String, Object>> predictInbox = Collections.emptyList();
    private java.util.Map<Long, long[]> topicCounts = Collections.emptyMap();
    private ICmsRegistrationService lastRegistrationService;

    private MockMvc mvc(List<CmsRegistration> registrations) {
        return mvc(registrations, null, Collections.<CmsRegistrationScan>emptyList());
    }

    private MockMvc mvc(List<CmsRegistration> registrations, Date topicEndDate) {
        return mvc(registrations, topicEndDate, Collections.<CmsRegistrationScan>emptyList());
    }

    /** topicEndDate=null 表示主题没有结束时间(永远算未结束)。 */
    private MockMvc mvc(List<CmsRegistration> registrations, Date topicEndDate, List<CmsRegistrationScan> pendingScans) {
        CmsRegistrationMerchant awarded = new CmsRegistrationMerchant();
        awarded.setTopicId(TOPIC_ID);
        ICmsRegistrationMerchantService merchantService = mock(ICmsRegistrationMerchantService.class);
        when(merchantService.selectCmsRegistrationMerchantList(any())).thenAnswer(invocation -> {
            CmsRegistrationMerchant probe = invocation.getArgument(0);
            if (Long.valueOf(MERCHANT_ID).equals(probe.getMemberId())
                    && Long.valueOf(1L).equals(probe.getAuditStatus())
                    && Integer.valueOf(0).equals(probe.getDelFlag())) {
                return Collections.singletonList(awarded);
            }
            return Collections.emptyList();
        });

        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        when(registrationService.selectCmsRegistrationList(any())).thenReturn(registrations);
        when(registrationService.countPaidVerificationByOwners(org.mockito.ArgumentMatchers.eq(1L), any()))
                .thenAnswer(invocation -> topicCounts);
        lastRegistrationService = registrationService;
        // scan 的项目归属靠它解析:一律指回本商家中标的那个主题
        com.chengyinhub.business.domain.vo.MerchantTodoRegistrationVO scanOwner = new com.chengyinhub.business.domain.vo.MerchantTodoRegistrationVO();
        scanOwner.setId(72L);
        scanOwner.setOwnerType(1L);
        scanOwner.setOwnerId(TOPIC_ID);
        scanOwner.setEndDate(topicEndDate);
        when(registrationService.selectTodoRegistrationOwners(any()))
                .thenReturn(Collections.singletonMap(72L, scanOwner));
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        when(activityService.selectCmsActivityList(any())).thenReturn(Collections.emptyList());

        ApiMerchantController controller = spy(new ApiMerchantController());
        doReturn(MERCHANT_ID).when(controller).getAppUserId();
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        MerchantAccessContext owner = new MerchantAccessContext(
                MERCHANT_ID, 11L, MERCHANT_ID, MerchantRoleCode.MERCHANT_OWNER);
        when(access.require(MERCHANT_ID, MerchantPermission.BASIC_READ)).thenReturn(owner);
        when(access.require(MERCHANT_ID, MerchantPermission.MARKETING_READ)).thenReturn(owner);
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantService", merchantService);
        ReflectionTestUtils.setField(controller, "cmsRegistrationService", registrationService);
        ReflectionTestUtils.setField(controller, "cmsActivityService", activityService);

        ICmsTopicService topicService = mock(ICmsTopicService.class);
        when(topicService.selectTodoTopicEndDates(any())).thenReturn(Collections.singletonMap(TOPIC_ID, topicEndDate));
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);

        ICmsRegistrationScanService scanService = mock(ICmsRegistrationScanService.class);
        when(scanService.selectCmsRegistrationScanList(any())).thenReturn(pendingScans);
        ReflectionTestUtils.setField(controller, "cmsRegistrationScanService", scanService);

        IOmsOrderService orderService = mock(IOmsOrderService.class);
        when(orderService.selectOmsOrderList(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "omsOrderService", orderService);

        IUmsMemberBalanceDetailService balanceDetailService = mock(IUmsMemberBalanceDetailService.class);
        when(balanceDetailService.selectUmsMemberBalanceDetailList(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "umsMemberBalanceDetailService", balanceDetailService);
        MerchantAftercareMapper aftercareMapper = mock(MerchantAftercareMapper.class);
        when(aftercareMapper.countOwnedRefundList(MERCHANT_ROW_ID, MERCHANT_ID, "PENDING")).thenReturn(0L);
        ReflectionTestUtils.setField(controller, "merchantAftercareMapper", aftercareMapper);

        com.chengyinhub.business.service.IPlayPredictService predictService =
                mock(com.chengyinhub.business.service.IPlayPredictService.class);
        when(predictService.pendingInbox(anyLong(), anyInt())).thenAnswer(invocation -> predictInbox);
        ReflectionTestUtils.setField(controller, "playPredictService", predictService);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void todoSummaryCountsOnlyTopicsAwardedByAuditStatus() throws Exception {
        mvc(Collections.<CmsRegistration>emptyList())
                .perform(post("/api/merchant/todo-summary"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.biddingTopics").value(1));
    }

    @Test
    void todoSummaryKeepsProjectCardWhileTopicIsStillWithinAWeekOfEnding() throws Exception {
        mvc(Collections.<CmsRegistration>emptyList(), new Date(System.currentTimeMillis() - 6 * DAY))
                .perform(post("/api/merchant/todo-summary"))
                .andExpect(jsonPath("$.data.byProject.length()").value(1));
    }

    /**
     * 2026-08-20 实测的病:2026-02 的一条扫码记录半年后仍挂在工作台上,点进去却是「已结束」。
     * 主题结束满 7 天后就不该再出待办卡 —— 口径同玩家侧 filterEndedOverWeek。
     */
    @Test
    void todoSummaryDropsProjectCardOnceTopicEndedOverAWeekAgo() throws Exception {
        mvc(Collections.<CmsRegistration>emptyList(), new Date(System.currentTimeMillis() - 8 * DAY))
                .perform(post("/api/merchant/todo-summary"))
                .andExpect(jsonPath("$.data.byProject.length()").value(0));
    }

    /** P1-020:待核销/已核销按项目一次分组计数,不再逐项目拉全行 .size()。 */
    @Test
    void todoSummaryCountsVerificationWithOneGroupedQueryPerOwnerType() throws Exception {
        topicCounts = Collections.singletonMap(TOPIC_ID, new long[] {3L, 5L});
        mvc(Collections.<CmsRegistration>emptyList(), new Date(System.currentTimeMillis() + DAY))
                .perform(post("/api/merchant/todo-summary"))
                .andExpect(jsonPath("$.data.pendingVerify").value(3))
                .andExpect(jsonPath("$.data.verifiedCount").value(5))
                .andExpect(jsonPath("$.data.byProject[0].pendingVerify").value(3));
        org.mockito.Mockito.verify(lastRegistrationService, org.mockito.Mockito.never()).selectCmsRegistrationList(any());
        org.mockito.Mockito.verify(lastRegistrationService, org.mockito.Mockito.times(1))
                .countPaidVerificationByOwners(org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq(new java.util.LinkedHashSet<>(Collections.singletonList(TOPIC_ID))));
        // 没有自建活动 ⇒ 活动侧不发查询
        org.mockito.Mockito.verify(lastRegistrationService, org.mockito.Mockito.never())
                .countPaidVerificationByOwners(org.mockito.ArgumentMatchers.eq(2L), any());
    }

    /**
     * D-14:商家不看私信就发现不了竞猜「给答案」的 48 小时待办。
     * 待办汇总必须带上待答轮次数,口径就是收件箱同一份名单。
     */
    @Test
    void todoSummaryReportsPendingPredictRoundsFromTheSameInbox() throws Exception {
        predictInbox = java.util.Arrays.asList(
                Collections.<String, Object>singletonMap("nodeId", 1L),
                Collections.<String, Object>singletonMap("nodeId", 2L));
        mvc(Collections.<CmsRegistration>emptyList())
                .perform(post("/api/merchant/todo-summary"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.pendingPredict").value(2));
    }

    /** 反面:收件箱为空(都答完/都作废)时不得留下一根永远挂着的待办。 */
    @Test
    void todoSummaryDropsPendingPredictOnceInboxIsEmpty() throws Exception {
        predictInbox = Collections.emptyList();
        mvc(Collections.<CmsRegistration>emptyList())
                .perform(post("/api/merchant/todo-summary"))
                .andExpect(jsonPath("$.data.pendingPredict").value(0));
    }

    @Test
    void endedOverWeekBoundary() {
        Date now = new Date();
        assertTrue(ApiMerchantController.endedOverWeek(new Date(now.getTime() - 8 * DAY), now));
        assertFalse(ApiMerchantController.endedOverWeek(new Date(now.getTime() - 6 * DAY), now));
        assertFalse(ApiMerchantController.endedOverWeek(null, now), "没有结束时间不能当成已结束");
    }

    private static List<CmsRegistrationScan> onePendingScan() {
        CmsRegistrationScan scan = new CmsRegistrationScan();
        scan.setRegistrationId(72L);
        scan.setStatus(0L);
        return Collections.singletonList(scan);
    }

    /**
     * 报障原形(2026-08-20):一条 2026-02 的待确认扫码 + 主题 2026-03-28 就结束了,
     * 工作台却一直挂着「待扫码」,点进去是「已结束」。
     * 全局数与项目卡都必须归零 —— scan.status 全仓没有写回路径,不在这里挡住就永远清不掉。
     */
    @Test
    void todoSummaryDropsPendingScanOnceItsProjectEndedOverAWeekAgo() throws Exception {
        mvc(Collections.<CmsRegistration>emptyList(), new Date(System.currentTimeMillis() - 8 * DAY), onePendingScan())
                .perform(post("/api/merchant/todo-summary"))
                .andExpect(jsonPath("$.data.pendingScanConfirm").value(0))
                .andExpect(jsonPath("$.data.byProject.length()").value(0));
    }

    /** 反面:项目还没结束满一周,待扫码必须照常上报,并挂到对应项目卡上。 */
    @Test
    void todoSummaryKeepsPendingScanWhileProjectIsStillLive() throws Exception {
        mvc(Collections.<CmsRegistration>emptyList(), new Date(System.currentTimeMillis() - 6 * DAY), onePendingScan())
                .perform(post("/api/merchant/todo-summary"))
                .andExpect(jsonPath("$.data.pendingScanConfirm").value(1))
                .andExpect(jsonPath("$.data.byProject[0].pendingScanConfirm").value(1));
    }
}
