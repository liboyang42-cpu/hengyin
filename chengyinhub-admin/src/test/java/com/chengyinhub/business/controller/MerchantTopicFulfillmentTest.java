package com.chengyinhub.business.controller;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsRegistrationScan;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.ViewRegistrationMerchant;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsRegistrationScanService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMerchantFulfillmentReadService;
import com.chengyinhub.business.service.IMerchantRegistrationDetailReadService;
import com.chengyinhub.business.service.IViewRegistrationMerchantService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.impl.MerchantFulfillmentReadServiceImpl;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.service.impl.MerchantRegistrationDetailReadServiceImpl;
import com.chengyinhub.web.controller.api.ApiMerchantController;
import com.chengyinhub.web.controller.api.ApiRegistrationMerchantController;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 商家主题履约详情的两处口径:
 * ① 「已核销」必须只数本商家的核销台账,不能把同主题其他商家的战绩算进来;
 * ② 「预计到店」只在事实齐全时给区间,缺一个就不给 —— 不编精确时刻。
 */
class MerchantTopicFulfillmentTest {

    private static CmsRegistration paidRegistration(long id) {
        CmsRegistration r = new CmsRegistration();
        r.setId(id);
        return r;
    }

    private static CmsRegistrationScan scan(long registrationId, long memberId, Integer type) {
        CmsRegistrationScan s = new CmsRegistrationScan();
        s.setRegistrationId(registrationId);
        s.setMemberId(memberId);
        s.setRegistrationType(type);
        return s;
    }

    private static IMerchantFulfillmentReadService fulfillmentReadService(
            ICmsRegistrationMerchantService merchantService, ICmsTopicService topicService,
            IClubService clubService, ICmsTopicNodeService nodeService,
            IOmsTicketService ticketService, ICmsRegistrationService registrationService) {
        return fulfillmentReadService(merchantService, topicService, clubService, nodeService, ticketService,
                registrationService, mock(com.chengyinhub.business.service.ICmsActivityService.class));
    }

    private static IMerchantFulfillmentReadService fulfillmentReadService(
            ICmsRegistrationMerchantService merchantService, ICmsTopicService topicService,
            IClubService clubService, ICmsTopicNodeService nodeService,
            IOmsTicketService ticketService, ICmsRegistrationService registrationService,
            com.chengyinhub.business.service.ICmsActivityService activityService) {
        return new MerchantFulfillmentReadServiceImpl(merchantService, topicService, clubService,
                nodeService, ticketService, registrationService, mock(CmsRegistrationMapper.class), activityService);
    }

    /** 2026-09-23 裁决:承接卡要写「哪个俱乐部、什么时候来」—— 俱乐部开的场次(cms_activity)必须进即将到店。 */
    @Test
    void upcomingRunsIncludeClubSessionsWithClubNameAndSkipCancelledOrPast() {
        long merchantId = 7L;
        long topicId = 701L;
        CmsRegistrationMerchant awarded = new CmsRegistrationMerchant();
        awarded.setTopicId(topicId);
        awarded.setNodeId(702L);
        ICmsRegistrationMerchantService merchantService = mock(ICmsRegistrationMerchantService.class);
        when(merchantService.selectCmsRegistrationMerchantList(any())).thenReturn(Collections.singletonList(awarded));
        CmsTopic topic = new CmsTopic();
        topic.setId(topicId);
        topic.setName("探店日一期");
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        when(topicService.selectCmsTopicById(topicId)).thenReturn(topic);
        CmsTopicNode node = new CmsTopicNode();
        node.setId(702L);
        ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
        IOmsTicketService ticketService = mock(IOmsTicketService.class);
        when(ticketService.selectOmsTicketList(any())).thenReturn(Collections.<OmsTicket>emptyList());
        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        when(registrationService.selectCmsRegistrationList(org.mockito.ArgumentMatchers.argThat(
                q -> q != null && Integer.valueOf(2).equals(q.getRegistrationStatus()) && Long.valueOf(2L).equals(q.getOwnerType()))))
                .thenReturn(java.util.Arrays.asList(paidRegistration(1L), paidRegistration(2L)));
        IClubService clubService = mock(IClubService.class);
        com.chengyinhub.business.domain.Club club = new com.chengyinhub.business.domain.Club();
        club.setName("城瘾跑团");
        when(clubService.selectClubById(9L)).thenReturn(club);

        long now = System.currentTimeMillis();
        com.chengyinhub.business.domain.CmsActivity upcoming = session(801L, 9L, now + 24 * 3600_000L, now + 26 * 3600_000L);
        com.chengyinhub.business.domain.CmsActivity shell = session(804L, 0L, now + 24 * 3600_000L, now + 26 * 3600_000L);
        shell.setClubId(null);   // 主题自己的活动壳:票款已在主题票里,不得再算一遍
        com.chengyinhub.business.domain.CmsActivity past = session(803L, 9L, now - 50 * 3600_000L, now - 48 * 3600_000L);
        com.chengyinhub.business.service.ICmsActivityService activityService =
                mock(com.chengyinhub.business.service.ICmsActivityService.class);
        // 列表查询不回 cancel_time,取消过滤必须下沉 SQL:只有带 excludeCancelled=1 的探针才返回数据
        when(activityService.selectCmsActivityList(org.mockito.ArgumentMatchers.argThat(
                q -> q != null && Integer.valueOf(1).equals(q.getExcludeCancelled()) && Long.valueOf(topicId).equals(q.getTopicId()))))
                .thenReturn(java.util.Arrays.asList(upcoming, shell, past));

        List<Map<String, Object>> runs = fulfillmentReadService(merchantService, topicService, clubService,
                nodeService, ticketService, registrationService, activityService).listUpcomingRuns(merchantId, null);

        assertEquals(1, runs.size(), "已结束的场次与主题活动壳不算俱乐部到店");
        assertEquals(801L, runs.get(0).get("activityId"));
        assertEquals("城瘾跑团", runs.get(0).get("clubName"));
        assertEquals(2, runs.get(0).get("paidCount"));
        assertEquals(upcoming.getStartDate(), runs.get(0).get("startTime"));
    }

    private static com.chengyinhub.business.domain.CmsActivity session(long id, long clubId, long start, long end) {
        com.chengyinhub.business.domain.CmsActivity a = new com.chengyinhub.business.domain.CmsActivity();
        a.setId(id);
        a.setClubId(clubId);
        a.setName("周六场");
        a.setStartDate(new Date(start));
        a.setEndDate(new Date(end));
        return a;
    }

    private static IMerchantRegistrationDetailReadService registrationDetailReadService(
            IViewRegistrationMerchantService viewService, ICmsRegistrationMerchantService merchantService,
            ICmsTopicNodeService nodeService, ICmsMemberTemplateService templateService,
            ICmsTopicChapterService chapterService, ICmsRegistrationService registrationService,
            ICmsRegistrationScanService scanService) {
        return new MerchantRegistrationDetailReadServiceImpl(viewService, merchantService, nodeService,
                templateService, chapterService, registrationService, scanService);
    }

    /**
     * 负控在同一张断言里:探针只按 memberId+type=1 取台账,所以 mock 返回的就是「本商家的核销行」。
     * 真正要挡住的是 registrationId 落在本主题之外的那些行 —— 商家在别的主题核销过,不算这条路线的战绩。
     */
    @Test
    void verifiedCountOnlyCountsThisMerchantScansInsideThisTopic() {
        long merchantId = 7L;
        long registrationId = 901L;
        ViewRegistrationMerchant view = new ViewRegistrationMerchant();
        view.setId(registrationId);
        view.setMemberId(merchantId);
        view.setTopicId(902L);
        view.setNodeId(0L);
        IViewRegistrationMerchantService viewService = mock(IViewRegistrationMerchantService.class);
        when(viewService.selectViewRegistrationMerchantById(registrationId)).thenReturn(view);
        ICmsRegistrationMerchantService merchantService = mock(ICmsRegistrationMerchantService.class);
        ICmsRegistrationScanService scanService = mock(ICmsRegistrationScanService.class);
        // 本商家核销过 3 张票,其中 101/102 属于本主题,999 属于别的主题
        when(scanService.selectCmsRegistrationScanList(any())).thenReturn(Arrays.asList(
                scan(101L, merchantId, 1),
                scan(102L, merchantId, 1),
                scan(999L, merchantId, 1)));
        List<CmsRegistration> paid = Arrays.asList(paidRegistration(101L), paidRegistration(102L),
                paidRegistration(103L));
        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        when(registrationService.selectCmsRegistrationList(any())).thenReturn(paid);
        IMerchantRegistrationDetailReadService detailReadService = registrationDetailReadService(viewService,
                merchantService, mock(ICmsTopicNodeService.class), mock(ICmsMemberTemplateService.class),
                mock(ICmsTopicChapterService.class), registrationService, scanService);

        ViewRegistrationMerchant detail = detailReadService.getRegistrationDetail(merchantId, registrationId);

        assertEquals(2, detail.getVerifiedNum(), "只应数落在本主题已支付报名里的核销行");
    }

    /** 本主题一张票都没卖出去时,不管台账里有什么都必须是 0(旧口径这里会返回全主题已核销数)。 */
    @Test
    void verifiedCountIsZeroWhenTopicHasNoPaidRegistration() {
        long merchantId = 7L;
        long registrationId = 901L;
        ViewRegistrationMerchant view = new ViewRegistrationMerchant();
        view.setId(registrationId);
        view.setMemberId(merchantId);
        view.setTopicId(902L);
        view.setNodeId(0L);
        IViewRegistrationMerchantService viewService = mock(IViewRegistrationMerchantService.class);
        when(viewService.selectViewRegistrationMerchantById(registrationId)).thenReturn(view);
        ICmsRegistrationMerchantService merchantService = mock(ICmsRegistrationMerchantService.class);
        ICmsRegistrationScanService scanService = mock(ICmsRegistrationScanService.class);
        when(scanService.selectCmsRegistrationScanList(any()))
                .thenReturn(Collections.singletonList(scan(101L, merchantId, 1)));
        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        when(registrationService.selectCmsRegistrationList(any()))
                .thenReturn(Collections.<CmsRegistration>emptyList());
        IMerchantRegistrationDetailReadService detailReadService = registrationDetailReadService(viewService,
                merchantService, mock(ICmsTopicNodeService.class), mock(ICmsMemberTemplateService.class),
                mock(ICmsTopicChapterService.class), registrationService, scanService);

        ViewRegistrationMerchant detail = detailReadService.getRegistrationDetail(merchantId, registrationId);

        assertEquals(0, detail.getVerifiedNum());
    }

    /** 报名详情入口不能把该商家在其他主题的核销行混进当前主题的已核销数。 */
    @Test
    void registrationInfoCountsOnlyThisMerchantsScansForThisTopic() throws Exception {
        long merchantId = 7L;
        long registrationId = 901L;
        ViewRegistrationMerchant view = new ViewRegistrationMerchant();
        view.setId(registrationId);
        view.setMemberId(merchantId);
        view.setTopicId(902L);
        view.setNodeId(0L); // 哨兵报名,不需要节点/模板富化
        IViewRegistrationMerchantService viewService = mock(IViewRegistrationMerchantService.class);
        when(viewService.selectViewRegistrationMerchantById(registrationId)).thenReturn(view);

        CmsRegistrationMerchant raw = new CmsRegistrationMerchant();
        raw.setAuditStatus(1L);
        ICmsRegistrationMerchantService merchantService = mock(ICmsRegistrationMerchantService.class);
        when(merchantService.selectCmsRegistrationMerchantById(registrationId)).thenReturn(raw);

        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        when(registrationService.selectCmsRegistrationList(any())).thenReturn(Arrays.asList(
                paidRegistration(101L), paidRegistration(102L)));
        ICmsRegistrationScanService scanService = mock(ICmsRegistrationScanService.class);
        when(scanService.selectCmsRegistrationScanList(any())).thenReturn(Arrays.asList(
                scan(101L, merchantId, 1),
                scan(999L, merchantId, 1)));
        ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
        ICmsMemberTemplateService templateService = mock(ICmsMemberTemplateService.class);
        ICmsTopicChapterService chapterService = mock(ICmsTopicChapterService.class);
        IMerchantRegistrationDetailReadService detailReadService = registrationDetailReadService(viewService,
                merchantService, nodeService, templateService, chapterService, registrationService, scanService);

        ApiRegistrationMerchantController controller = spy(new ApiRegistrationMerchantController());
        doReturn(merchantId).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "merchantRegistrationDetailReadService", detailReadService);
        injectOwnerProjectAccess(controller, merchantId);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/registration/merchant/info").param("id", String.valueOf(registrationId)))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalOrderNum").value(2))
                .andExpect(jsonPath("$.data.verifiedNum").value(1))
                .andExpect(jsonPath("$.data.auditStatus").value(1));
    }

    /** 报名详情 Controller 只处理当前用户和参数绑定，富化统计由 system service 完成。 */
    @Test
    void registrationInfoDelegatesRegistrationDetailReadToService() throws Exception {
        long merchantId = 7L;
        long registrationId = 901L;
        ViewRegistrationMerchant registration = new ViewRegistrationMerchant();
        registration.setId(registrationId);
        IMerchantRegistrationDetailReadService detailReadService = mock(IMerchantRegistrationDetailReadService.class);
        when(detailReadService.getRegistrationDetail(merchantId, registrationId)).thenReturn(registration);

        ApiRegistrationMerchantController controller = spy(new ApiRegistrationMerchantController());
        doReturn(merchantId).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "merchantRegistrationDetailReadService", detailReadService);
        injectOwnerProjectAccess(controller, merchantId);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/registration/merchant/info").param("id", String.valueOf(registrationId)))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value((int) registrationId));

        verify(detailReadService).getRegistrationDetail(merchantId, registrationId);
    }

    /**
     * 商家即将到店的场次只能来自调配状态机判定为中标的主题。status=1 曾在 CAS 时顺带写入，
     * 但它不是授权语义；两列一旦漂移，不能让非中标商家读取该主题的场次和人数。
     */
    @Test
    void upcomingRunsOnlyReturnsTopicsAwardedByAuditStatus() throws Exception {
        long merchantId = 7L;
        long topicId = 701L;
        long nodeId = 702L;

        CmsRegistrationMerchant awarded = new CmsRegistrationMerchant();
        awarded.setTopicId(topicId);
        awarded.setNodeId(nodeId);
        ICmsRegistrationMerchantService merchantService = mock(ICmsRegistrationMerchantService.class);
        when(merchantService.selectCmsRegistrationMerchantList(any())).thenAnswer(invocation -> {
            CmsRegistrationMerchant probe = invocation.getArgument(0);
            if (Long.valueOf(merchantId).equals(probe.getMemberId())
                    && Long.valueOf(1L).equals(probe.getAuditStatus())
                    && Integer.valueOf(0).equals(probe.getDelFlag())) {
                return Collections.singletonList(awarded);
            }
            return Collections.emptyList();
        });

        CmsTopic topic = new CmsTopic();
        topic.setId(topicId);
        topic.setName("审计中标主题");
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        when(topicService.selectCmsTopicById(topicId)).thenReturn(topic);

        CmsTopicNode node = new CmsTopicNode();
        node.setId(nodeId);
        ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));

        OmsTicket ticket = new OmsTicket();
        ticket.setId(703L);
        ticket.setName("周末场");
        ticket.setStartTime(new Date(System.currentTimeMillis() + 24 * 3600_000L));
        ticket.setEndTime(new Date(System.currentTimeMillis() + 26 * 3600_000L));
        IOmsTicketService ticketService = mock(IOmsTicketService.class);
        when(ticketService.selectOmsTicketList(any())).thenReturn(Collections.singletonList(ticket));

        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        when(registrationService.selectCmsRegistrationList(any())).thenReturn(Collections.<CmsRegistration>emptyList());
        IClubService clubService = mock(IClubService.class);
        IMerchantFulfillmentReadService fulfillmentReadService = fulfillmentReadService(merchantService, topicService,
                clubService, nodeService, ticketService, registrationService);

        ApiMerchantController controller = spy(new ApiMerchantController());
        doReturn(merchantId).when(controller).getAppUserId();
        injectOwnerAccess(controller, merchantId);
        ReflectionTestUtils.setField(controller, "merchantFulfillmentReadService", fulfillmentReadService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/merchant/upcoming-runs")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].topicId").value((int) topicId))
                .andExpect(jsonPath("$.data[0].ticketId").value(703));
    }

    /** Controller 只负责请求上下文，履约读取必须经 system service 完成。 */
    @Test
    void upcomingRunsDelegatesFulfillmentReadToService() throws Exception {
        long merchantId = 7L;
        long topicId = 701L;
        Map<String, Object> run = new java.util.HashMap<>();
        run.put("ticketId", 703L);
        IMerchantFulfillmentReadService fulfillmentReadService = mock(IMerchantFulfillmentReadService.class);
        when(fulfillmentReadService.listUpcomingRuns(merchantId, topicId))
                .thenReturn(Collections.singletonList(run));

        ApiMerchantController controller = spy(new ApiMerchantController());
        doReturn(merchantId).when(controller).getAppUserId();
        injectOwnerAccess(controller, merchantId);
        ReflectionTestUtils.setField(controller, "merchantFulfillmentReadService", fulfillmentReadService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/merchant/upcoming-runs")
                        .contentType("application/json")
                        .content("{\"topicId\":701}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].ticketId").value(703));

        verify(fulfillmentReadService).listUpcomingRuns(merchantId, topicId);
    }

    /**
     * 商家一个主题有多个场次时，报名人数应一次批量读取后按 ticket 分组；不能每个场次再查一次。
     */
    @Test
    void upcomingRunsLoadsPaidCountsOncePerTopic() throws Exception {
        long merchantId = 7L;
        long topicId = 701L;
        long nodeId = 702L;

        CmsRegistrationMerchant awarded = new CmsRegistrationMerchant();
        awarded.setTopicId(topicId);
        awarded.setNodeId(nodeId);
        ICmsRegistrationMerchantService merchantService = mock(ICmsRegistrationMerchantService.class);
        when(merchantService.selectCmsRegistrationMerchantList(any())).thenReturn(Collections.singletonList(awarded));

        CmsTopic topic = new CmsTopic();
        topic.setId(topicId);
        topic.setName("多场次主题");
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        when(topicService.selectCmsTopicById(topicId)).thenReturn(topic);

        CmsTopicNode node = new CmsTopicNode();
        node.setId(nodeId);
        ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));

        OmsTicket first = new OmsTicket();
        first.setId(703L);
        first.setName("周六场");
        first.setStartTime(new Date(System.currentTimeMillis() + 24 * 3600_000L));
        first.setEndTime(new Date(System.currentTimeMillis() + 26 * 3600_000L));
        OmsTicket second = new OmsTicket();
        second.setId(704L);
        second.setName("周日场");
        second.setStartTime(new Date(System.currentTimeMillis() + 48 * 3600_000L));
        second.setEndTime(new Date(System.currentTimeMillis() + 50 * 3600_000L));
        IOmsTicketService ticketService = mock(IOmsTicketService.class);
        when(ticketService.selectOmsTicketList(any())).thenReturn(Arrays.asList(first, second));

        CmsRegistration firstRegistration = paidRegistration(801L);
        firstRegistration.setTicketId(first.getId());
        CmsRegistration secondRegistration = paidRegistration(802L);
        secondRegistration.setTicketId(second.getId());
        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        when(registrationService.selectCmsRegistrationList(any())).thenAnswer(invocation -> {
            CmsRegistration probe = invocation.getArgument(0);
            if (first.getId().equals(probe.getTicketId())) {
                return Collections.singletonList(firstRegistration);
            }
            if (second.getId().equals(probe.getTicketId())) {
                return Collections.singletonList(secondRegistration);
            }
            if (Long.valueOf(topicId).equals(probe.getOwnerId())) {
                return Arrays.asList(firstRegistration, secondRegistration);
            }
            return Collections.emptyList();
        });
        IClubService clubService = mock(IClubService.class);
        IMerchantFulfillmentReadService fulfillmentReadService = fulfillmentReadService(merchantService, topicService,
                clubService, nodeService, ticketService, registrationService);

        ApiMerchantController controller = spy(new ApiMerchantController());
        doReturn(merchantId).when(controller).getAppUserId();
        injectOwnerAccess(controller, merchantId);
        ReflectionTestUtils.setField(controller, "merchantFulfillmentReadService", fulfillmentReadService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/merchant/upcoming-runs")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].paidCount").value(1))
                .andExpect(jsonPath("$.data[1].paidCount").value(1));

        verify(registrationService, times(1)).selectCmsRegistrationList(any());
    }

    @Test
    void upcomingRunsCarriesClubAndSkipsFinishedSessions() {
        long merchantId = 7L;
        long topicId = 55L;
        long nodeId = 900L;

        CmsRegistrationMerchant won = new CmsRegistrationMerchant();
        won.setTopicId(topicId);
        won.setNodeId(nodeId);
        ICmsRegistrationMerchantService merchantService = mock(ICmsRegistrationMerchantService.class);
        when(merchantService.selectCmsRegistrationMerchantList(any())).thenReturn(Collections.singletonList(won));

        CmsTopic topic = new CmsTopic();
        topic.setId(topicId);
        topic.setName("城市迷雾·外滩篇");
        topic.setClubId(31L);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        when(topicService.selectCmsTopicById(topicId)).thenReturn(topic);

        Club club = new Club();
        club.setId(31L);
        club.setName("野路子徒步社");
        IClubService clubService = mock(IClubService.class);
        when(clubService.selectClubById(31L)).thenReturn(club);

        CmsTopicNode first = new CmsTopicNode();
        first.setId(899L);
        CmsTopicNode mine = new CmsTopicNode();
        mine.setId(nodeId);
        ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Arrays.asList(first, mine));

        long hour = 3600_000L;
        Date now = new Date();
        OmsTicket future = new OmsTicket();
        future.setId(1L);
        future.setName("周六场");
        future.setStartTime(new Date(now.getTime() + 24 * hour));
        future.setEndTime(new Date(now.getTime() + 28 * hour));
        future.setTeamStatus(1);
        OmsTicket past = new OmsTicket();
        past.setId(2L);
        past.setName("上周场");
        past.setStartTime(new Date(now.getTime() - 48 * hour));
        past.setEndTime(new Date(now.getTime() - 44 * hour));
        IOmsTicketService ticketService = mock(IOmsTicketService.class);
        when(ticketService.selectOmsTicketList(any())).thenReturn(Arrays.asList(future, past));

        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        List<CmsRegistration> paid = new ArrayList<>();
        CmsRegistration firstPaid = paidRegistration(1L);
        firstPaid.setTicketId(future.getId());
        CmsRegistration secondPaid = paidRegistration(2L);
        secondPaid.setTicketId(future.getId());
        paid.add(firstPaid);
        paid.add(secondPaid);
        when(registrationService.selectCmsRegistrationList(any())).thenReturn(paid);

        IMerchantFulfillmentReadService fulfillmentReadService = fulfillmentReadService(merchantService, topicService,
                clubService, nodeService, ticketService, registrationService);
        List<Map<String, Object>> runs = fulfillmentReadService.listUpcomingRuns(merchantId, null);

        assertNotNull(runs);
        assertEquals(1, runs.size(), "已经结束的场次不算即将到店");
        Map<String, Object> run = runs.get(0);
        assertEquals("野路子徒步社", run.get("clubName"));
        assertEquals(2, run.get("paidCount"));
        assertEquals(2, run.get("nodeOrder"), "本商家是第 2 站");
        assertEquals(2, run.get("nodeTotal"));
        assertTrue(run.get("arrivalStart") instanceof Date);
        assertEquals(((Date) run.get("startTime")).getTime() + 2 * hour,
                ((Date) run.get("arrivalStart")).getTime(), "4 小时 2 个节点 ⇒ 第 2 站从第 2 小时起");
    }

    /** 没有俱乐部的主题不能凭空造一个俱乐部名。 */
    @Test
    void upcomingRunsLeavesClubNameNullForNonClubTopic() {
        long merchantId = 7L;
        long topicId = 56L;
        CmsRegistrationMerchant won = new CmsRegistrationMerchant();
        won.setTopicId(topicId);
        ICmsRegistrationMerchantService merchantService = mock(ICmsRegistrationMerchantService.class);
        when(merchantService.selectCmsRegistrationMerchantList(any())).thenReturn(Collections.singletonList(won));
        CmsTopic topic = new CmsTopic();
        topic.setId(topicId);
        topic.setName("自由探索路线");
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        when(topicService.selectCmsTopicById(topicId)).thenReturn(topic);
        IClubService clubService = mock(IClubService.class);
        ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.<CmsTopicNode>emptyList());

        OmsTicket ticket = new OmsTicket();
        ticket.setId(3L);
        ticket.setStartTime(new Date(System.currentTimeMillis() + 3600_000L));
        IOmsTicketService ticketService = mock(IOmsTicketService.class);
        when(ticketService.selectOmsTicketList(any())).thenReturn(Collections.singletonList(ticket));
        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        when(registrationService.selectCmsRegistrationList(any()))
                .thenReturn(Collections.<CmsRegistration>emptyList());

        IMerchantFulfillmentReadService fulfillmentReadService = fulfillmentReadService(merchantService, topicService,
                clubService, nodeService, ticketService, registrationService);
        List<Map<String, Object>> runs = fulfillmentReadService.listUpcomingRuns(merchantId, null);

        assertEquals(1, runs.size());
        assertNull(runs.get(0).get("clubName"));
        assertNull(runs.get(0).get("arrivalStart"), "没有 endTime 与节点序位就不给区间");
    }
    private static void injectOwnerAccess(ApiMerchantController controller, long merchantId) {
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(merchantId, MerchantPermission.VERIFY_RECORD_READ))
                .thenReturn(new MerchantAccessContext(merchantId, 11L, merchantId,
                        MerchantRoleCode.MERCHANT_OWNER));
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
    }

    private static void injectOwnerProjectAccess(ApiRegistrationMerchantController controller, long merchantId) {
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(merchantId, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(new MerchantAccessContext(merchantId, 11L, merchantId,
                        MerchantRoleCode.MERCHANT_OWNER));
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
    }
}
