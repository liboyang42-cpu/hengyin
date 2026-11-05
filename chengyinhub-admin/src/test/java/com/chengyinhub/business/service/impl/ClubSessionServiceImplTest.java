package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.dto.ClubSessionOpenRequest;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.CoopGroupVerifyMapper;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.IQrcodeService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IOmsTicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 俱乐部主动开团：主理人 + 生效 club 合作单两道闸后，topic_id/club_id 必须同一笔写入。 */
class ClubSessionServiceImplTest {

    private static final Long CLUB_ID = 21L;
    private static final Long TOPIC_ID = 31L;
    private static final Long OWNER_ID = 41L;

    private IClubService clubService;
    private ICmsTopicService topicService;
    private ICmsActivityService activityService;
    private IOmsTicketService ticketService;
    private CoopOrderMapper orderMapper;
    private com.chengyinhub.business.mapper.UmsMemberSettlementDebtMapper debtMapper;
    private ClubSessionServiceImpl service;
    private com.chengyinhub.business.service.IContentSafetyGate gate;
    private com.chengyinhub.system.service.ISysConfigService config;
    private com.chengyinhub.business.service.IAuditTaskService auditTaskService;
    private CmsTopic topic;

    @BeforeEach
    void setUp() {
        clubService = mock(IClubService.class);
        topicService = mock(ICmsTopicService.class);
        activityService = mock(ICmsActivityService.class);
        ticketService = mock(IOmsTicketService.class);
        orderMapper = mock(CoopOrderMapper.class);
        debtMapper = mock(com.chengyinhub.business.mapper.UmsMemberSettlementDebtMapper.class);
        gate = mock(com.chengyinhub.business.service.IContentSafetyGate.class);
        when(gate.decide(any())).thenReturn(com.chengyinhub.business.domain.dto.SafetyDecision.pass());
        config = mock(com.chengyinhub.system.service.ISysConfigService.class);
        com.chengyinhub.business.service.support.ActivityReviewPolicy policy =
                new com.chengyinhub.business.service.support.ActivityReviewPolicy();
        org.springframework.test.util.ReflectionTestUtils.setField(policy, "configService", config);
        auditTaskService = mock(com.chengyinhub.business.service.IAuditTaskService.class);
        service = new ClubSessionServiceImpl(clubService, topicService, activityService, ticketService, orderMapper,
                new com.chengyinhub.business.service.support.SettlementDebtLedger(debtMapper,
                        mock(com.chengyinhub.business.mapper.UmsMemberMapper.class),
                        mock(com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper.class)),
                gate, policy, auditTaskService, mock(com.chengyinhub.business.service.IUmsMemberService.class));

        Club club = new Club();
        club.setId(CLUB_ID);
        club.setMemberId(OWNER_ID);
        when(clubService.selectClubById(CLUB_ID)).thenReturn(club);
        when(clubService.selectClubByIdForUpdate(CLUB_ID)).thenReturn(club);

        topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setName("夜行主题");
        topic.setDescription("沿河夜行");
        topic.setImgUrl("cover.jpg");
        topic.setStatus(1);
        topic.setUserStatus(1);
        topic.setDelFlag(0);
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        when(orderMapper.selectEffectiveOrdersByTopicId(TOPIC_ID))
                .thenReturn(Collections.singletonList(clubOrder(CLUB_ID)));
        when(activityService.insertCmsActivity(any())).thenAnswer(invocation -> {
            CmsActivity activity = invocation.getArgument(0);
            activity.setId(51L);
            return 1;
        });
    }

    @Test
    void ownerWithEffectiveClubOrderOpensSessionWithBothForeignKeys() {
        Long activityId = service.open(request(), OWNER_ID, "主理人");

        assertEquals(Long.valueOf(51L), activityId);
        ArgumentCaptor<CmsActivity> captor = ArgumentCaptor.forClass(CmsActivity.class);
        verify(activityService).insertCmsActivity(captor.capture());
        CmsActivity activity = captor.getValue();
        assertEquals(TOPIC_ID, activity.getTopicId(), "主题关系必须与场次同笔写入");
        assertEquals(CLUB_ID, activity.getClubId(), "俱乐部关系必须与场次同笔写入");
        assertEquals(OWNER_ID, activity.getMemberId(), "活动责任人必须是开团主理人");
        assertEquals(Long.valueOf(1L), activity.getStatus());
        assertEquals(Long.valueOf(1L), activity.getPublishStatus());
        verify(ticketService).copyTopicTicketsToActivity(TOPIC_ID, 51L, OWNER_ID);
        verify(clubService).selectClubByIdForUpdate(CLUB_ID);
    }

    /** C-17:开一场列表带出票价(最低)与集合点,主理人开场前就看得到玩家结账页的两项。 */
    @Test
    void openableTopicCarriesTicketPriceAndMeetingPoint() {
        com.chengyinhub.business.domain.OmsTicket a = new com.chengyinhub.business.domain.OmsTicket();
        a.setPrice(new java.math.BigDecimal("69.00"));
        a.setMeetingPoint("武康大楼");
        a.setMeetingPointAddress("淮海中路1842号");
        com.chengyinhub.business.domain.OmsTicket b = new com.chengyinhub.business.domain.OmsTicket();
        b.setPrice(new java.math.BigDecimal("39.00"));
        when(ticketService.selectOmsTicketList(any())).thenReturn(Arrays.asList(a, b));
        CmsTopic view = new CmsTopic();
        view.setId(TOPIC_ID);
        service.applySessionTicketFacts(view);
        assertEquals(new java.math.BigDecimal("39.00"), view.getSessionPrice());
        assertEquals("武康大楼 · 淮海中路1842号", view.getSessionMeetingPoint());
    }

    /** C-03:开场把票种集合点抄到场次经纬度,否则集合签到恒为「集合点未配置」。 */
    @Test
    void openSessionCopiesTicketGatherPointToActivity() {
        com.chengyinhub.business.domain.OmsTicket noPoint = new com.chengyinhub.business.domain.OmsTicket();
        com.chengyinhub.business.domain.OmsTicket ticket = new com.chengyinhub.business.domain.OmsTicket();
        ticket.setGatherLat(new java.math.BigDecimal("31.2056000"));
        ticket.setGatherLng(new java.math.BigDecimal("121.4380000"));
        when(ticketService.selectOmsTicketList(any())).thenReturn(Arrays.asList(noPoint, ticket));

        service.open(request(), OWNER_ID, "主理人");

        ArgumentCaptor<CmsActivity> captor = ArgumentCaptor.forClass(CmsActivity.class);
        verify(activityService).insertCmsActivity(captor.capture());
        assertEquals("31.2056", captor.getValue().getLatitude());
        assertEquals("121.438", captor.getValue().getLongitude());
        // 抄过来的点必须能被签到策略直接使用:500 米内放行,外面拒绝。
        CmsActivity activity = captor.getValue();
        activity.setDelFlag(0);
        Date start = new Date(System.currentTimeMillis() + 10L * 60L * 1000L);
        activity.setStartDate(start);
        activity.setEndDate(new Date(start.getTime() + 3600_000L));
        com.chengyinhub.business.service.support.ClubGatherArrivalPolicy policy =
                new com.chengyinhub.business.service.support.ClubGatherArrivalPolicy();
        assertEquals(null, policy.validate(activity, 31.2070, 121.4380, new Date()));
        assertEquals("请到集合点500米内签到", policy.validate(activity, 31.2156, 121.4380, new Date()));
    }

    /** C-18:同俱乐部同主题同集合时刻已有未取消的一场,再开直接拒;取消掉的那一场不挡。 */
    @Test
    void sameMomentSessionIsRefusedButCancelledOneDoesNotBlock() {
        service.open(request(), OWNER_ID, "主理人");
        ArgumentCaptor<CmsActivity> captor = ArgumentCaptor.forClass(CmsActivity.class);
        verify(activityService).insertCmsActivity(captor.capture());
        CmsActivity first = captor.getValue();
        ArgumentCaptor<CmsActivity> probe = ArgumentCaptor.forClass(CmsActivity.class);
        when(activityService.selectCmsActivityList(probe.capture())).thenReturn(Collections.singletonList(first));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.open(request(), OWNER_ID, "主理人"));
        assertEquals("这个时间已有一场，请换个时间或直接修改那一场", error.getMessage());
        assertEquals(TOPIC_ID, probe.getValue().getTopicId());
        assertEquals(CLUB_ID, probe.getValue().getClubId());
        assertEquals(Integer.valueOf(1), probe.getValue().getExcludeCancelled());

        first.setCancelTime(new Date());
        assertEquals(Long.valueOf(51L), service.open(request(), OWNER_ID, "主理人"));
    }

    /** C-21:场次日期落在主题日期之外只提醒(不拒),窗口内与没写日期的主题不提醒。 */
    @Test
    void themeWindowWarningNamesTheWindowOnlyWhenOutside() {
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        topic.setStartDate(Date.from(java.time.LocalDate.of(2026, 11, 12).atStartOfDay(zone).toInstant()));
        topic.setEndDate(Date.from(java.time.LocalDate.of(2026, 12, 12).atStartOfDay(zone).toInstant()));
        assertEquals("这一场不在主题日期（11月12日—12月12日）内，请确认",
                service.themeWindowWarning(TOPIC_ID, Arrays.asList(java.time.LocalDate.of(2026, 11, 2))));
        assertEquals("有 1 场不在主题日期（11月12日—12月12日）内，请确认",
                service.themeWindowWarning(TOPIC_ID, Arrays.asList(java.time.LocalDate.of(2026, 11, 12),
                        java.time.LocalDate.of(2026, 12, 13))));
        assertEquals(null, service.themeWindowWarning(TOPIC_ID, Arrays.asList(java.time.LocalDate.of(2026, 12, 12))));
        topic.setStartDate(null);
        topic.setEndDate(null);
        assertEquals(null, service.themeWindowWarning(TOPIC_ID, Arrays.asList(java.time.LocalDate.of(2026, 11, 2))));
    }

    /**
     * C-02 提示位 → F-04(j2):可开团列表带 onlinePaymentAvailable,与 assertCollectible 同一判据。
     * 收款闸自 2026-11-02 起不再按「是否平台自营」拒,俱乐部主题(非自营)的付费场次也照常收款;
     * 旧断言(非自营 ⇒ FALSE)编码的是改前的决定,已更新。
     */
    @Test
    void openableTopicsCarryOnlinePaymentAvailability() {
        when(orderMapper.selectEffectiveTopicIdsByClubId(CLUB_ID)).thenReturn(Collections.singletonList(TOPIC_ID));
        assertEquals(Boolean.TRUE, service.selectOpenableTopics(CLUB_ID, OWNER_ID).get(0).getOnlinePaymentAvailable());
        assertEquals(Boolean.FALSE, ClubSessionServiceImpl.onlinePaymentAvailable(null));
    }

    /** AUDIT-G1-3:父主题被驳回 / 作者下架 / 待审时不能开场次。 */
    @Test
    void parentTopicNotLive_refusesOpening() {
        for (int[] state : new int[][]{{2, 1}, {1, 0}, {0, 1}}) {
            topic.setStatus(state[0]);
            topic.setUserStatus(state[1]);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> service.open(request(), OWNER_ID, "主理人"));
            assertEquals("主题未上架或已下架，不能开团", error.getMessage());
        }
        verify(activityService, never()).insertCmsActivity(any());
    }

    /** 第十一轮 h4 L5:主题还没开售(立项待审 7 / 招募 1 / 定价与开售审核 2)或已结束(5 / 6)时不能开一场。 */
    @Test
    void topicNotOnSale_refusesOpeningBeforeAnyWrite() {
        for (int lc : new int[]{7, 1, 2}) {
            topic.setLifecycle(lc);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> service.open(request(), OWNER_ID, "主理人"));
            assertEquals("主题还没开售，开售后才能开一场", error.getMessage());
        }
        for (int lc : new int[]{5, 6}) {
            topic.setLifecycle(lc);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> service.open(request(), OWNER_ID, "主理人"));
            assertEquals("主题已结束售卖，不能再开一场", error.getMessage());
        }
        verify(activityService, never()).insertCmsActivity(any());
        for (Integer lc : new Integer[]{null, 0, 3, 4}) {
            topic.setLifecycle(lc);
            ClubSessionServiceImpl.requireTopicOpenForSessions(lc);
        }
    }

    /** 第十一轮 i1:自由探索在开售审核(2)时、本俱乐部有生效合作单,可先开一场给开售审核投影场次票。 */
    @Test
    void crossClubFreeExploreEvenWithCoopOrderRefusesNewSession() {
        topic.setProductType(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE);
        topic.setLifecycle(2);

        assertThrows(IllegalStateException.class, () -> service.open(request(), OWNER_ID, "主理人"));
        verify(activityService, never()).insertCmsActivity(any());
    }

    @Test
    void clubOwnedFreeExploreInSaleReviewOpensWithoutInvitingItself() {
        topic.setProductType(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE);
        topic.setLifecycle(2);
        topic.setClubId(CLUB_ID);
        when(orderMapper.selectEffectiveOrdersByTopicId(TOPIC_ID)).thenReturn(java.util.Collections.emptyList());

        assertEquals(Long.valueOf(51L), service.open(request(), OWNER_ID, "主理人"));
        verify(activityService).insertCmsActivity(any());
    }

    /** 第十一轮 i1:放行只给「自由探索 + 开售审核 + 本俱乐部生效合作单」,其余仍按 h4 规则拒。 */
    @Test
    void saleReviewException_isNarrow_otherProductsStagesAndOrderlessStillRefused() {
        topic.setLifecycle(2);
        for (Integer productType : new Integer[]{null, com.chengyinhub.common.constant.ProductType.ORIENTEERING}) {
            topic.setProductType(productType);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> service.open(request(), OWNER_ID, "主理人"));
            assertEquals("主题还没开售，开售后才能开一场", error.getMessage());
        }
        topic.setProductType(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE);
        for (int lc : new int[]{7, 1}) {
            topic.setLifecycle(lc);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> service.open(request(), OWNER_ID, "主理人"));
            assertEquals("主题还没开售，开售后才能开一场", error.getMessage());
        }
        for (int lc : new int[]{5, 6}) {
            topic.setLifecycle(lc);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> service.open(request(), OWNER_ID, "主理人"));
            assertEquals("主题已结束售卖，不能再开一场", error.getMessage());
        }
        topic.setLifecycle(2);
        when(orderMapper.selectEffectiveOrdersByTopicId(TOPIC_ID)).thenReturn(java.util.Collections.emptyList());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.open(request(), OWNER_ID, "主理人"));
        assertEquals("主题还没开售，开售后才能开一场", error.getMessage());
        verify(activityService, never()).insertCmsActivity(any());
    }

    /** AUDIT-G1-3:场次文本走统一门禁,明确违规拒绝。 */
    @Test
    void blockedTopicText_refusesOpening() {
        when(gate.decide(any())).thenReturn(com.chengyinhub.business.domain.dto.SafetyDecision.block("RISKY"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.open(request(), OWNER_ID, "主理人"));
        assertEquals("主题内容包含违规信息，不能开团", error.getMessage());
        verify(activityService, never()).insertCmsActivity(any());
        ArgumentCaptor<com.chengyinhub.business.domain.dto.PublishSafetyRequest> safety =
                ArgumentCaptor.forClass(com.chengyinhub.business.domain.dto.PublishSafetyRequest.class);
        verify(gate).decide(safety.capture());
        assertEquals("夜行主题\n沿河夜行", safety.getValue().getText());
        assertEquals("POST_REVIEW", safety.getValue().getReviewMode());
    }

    /** AUDIT-G1-3:先审(总闸)⇒ 场次待审 + 建审核单,不直接上架。 */
    @Test
    void preReview_opensPendingSessionWithAuditTask() {
        when(config.selectConfigByKey(
                com.chengyinhub.business.service.support.ActivityReviewPolicy.CONFIG_PRE_REVIEW_ALL)).thenReturn("true");
        when(gate.decide(any())).thenReturn(com.chengyinhub.business.domain.dto.SafetyDecision.hold("STRUCTURED_REVIEW", null));

        service.open(request(), OWNER_ID, "主理人");

        ArgumentCaptor<CmsActivity> captor = ArgumentCaptor.forClass(CmsActivity.class);
        verify(activityService).insertCmsActivity(captor.capture());
        assertEquals(Long.valueOf(0L), captor.getValue().getStatus());
        assertEquals(Long.valueOf(0L), captor.getValue().getPublishStatus());
        verify(auditTaskService).submitOrRefreshActivity(org.mockito.ArgumentMatchers.eq(51L),
                org.mockito.ArgumentMatchers.eq(OWNER_ID), org.mockito.ArgumentMatchers.eq("user_publish"),
                org.mockito.ArgumentMatchers.contains("沿河夜行"));
    }

    /** 9-16 拍板第 2 条:欠款逾期未补足 ⇒ 限制交易,主理人不能开新场次。 */
    @Test
    void overdueSettlementDebtBlocksOpeningANewSession() {
        when(debtMapper.countOverdueByMember(OWNER_ID)).thenReturn(1);

        com.chengyinhub.common.exception.ServiceException error = assertThrows(
                com.chengyinhub.common.exception.ServiceException.class,
                () -> service.open(request(), OWNER_ID, "主理人"));

        assertTrue(error.getMessage().startsWith("SETTLEMENT_DEBT_OVERDUE"), error.getMessage());
        verify(activityService, never()).insertCmsActivity(any());
    }

    @Test
    void dissolvedClubCannotRaceASecondSessionIntoExistence() {
        when(clubService.selectClubByIdForUpdate(CLUB_ID)).thenReturn(null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.open(request(), OWNER_ID, "主理人"));

        assertTrue(error.getMessage().contains("在营俱乐部"),
                "该红必须指认到解散互斥闸，实际:" + error.getMessage());
        verify(activityService, never()).insertCmsActivity(any());
    }

    @Test
    void openedSessionCanImmediatelyIssueGroupCode() {
        service.open(request(), OWNER_ID, "主理人");
        ArgumentCaptor<CmsActivity> activityCaptor = ArgumentCaptor.forClass(CmsActivity.class);
        verify(activityService).insertCmsActivity(activityCaptor.capture());
        when(activityService.selectCmsActivityById(51L)).thenReturn(activityCaptor.getValue());

        IClubAccessService clubAccessService = mock(IClubAccessService.class);
        when(clubAccessService.require(OWNER_ID, CLUB_ID, 51L, ClubPermission.EVENT_CHECKIN))
                .thenReturn(new ClubAccessContext(OWNER_ID, CLUB_ID, OWNER_ID, 51L,
                        Collections.singleton(ClubRoleCode.CLUB_OWNER)));
        GroupCodeServiceImpl groupCodeService = new GroupCodeServiceImpl(activityService, clubAccessService,
                orderMapper, mock(CoopGroupVerifyMapper.class), inlineQrcode(), "test-secret");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) groupCodeService.issue(51L, OWNER_ID).get("data");

        assertEquals(TOPIC_ID, data.get("topicId"));
        assertEquals(CLUB_ID, data.get("clubId"));
        assertEquals(51L, data.get("activityId"));
    }

    @Test
    void openableTopicsComeFromThisClubsEffectiveOrders() {
        CmsTopic another = new CmsTopic();
        another.setId(32L);
        another.setName("第二主题");
        when(orderMapper.selectEffectiveTopicIdsByClubId(CLUB_ID)).thenReturn(Arrays.asList(TOPIC_ID, 32L));
        when(topicService.selectCmsTopicById(32L)).thenReturn(another);

        assertEquals(Arrays.asList(TOPIC_ID, 32L), service.selectOpenableTopics(CLUB_ID, OWNER_ID)
                .stream().map(CmsTopic::getId).collect(java.util.stream.Collectors.toList()));
        verify(orderMapper).selectEffectiveTopicIdsByClubId(CLUB_ID);
    }

    @Test
    void nonOwnerCannotListOpenableTopics() {
        assertThrows(IllegalArgumentException.class, () -> service.selectOpenableTopics(CLUB_ID, 99L));
        verify(orderMapper, never()).selectEffectiveTopicIdsByClubId(CLUB_ID);
    }

    @Test
    void nonOwnerCannotOpenSession() {
        assertThrows(IllegalArgumentException.class, () -> service.open(request(), 99L, "越权人"));
        verify(activityService, never()).insertCmsActivity(any());
    }

    @Test
    void ownerWithoutEffectiveClubOrderCannotOpenSession() {
        when(orderMapper.selectEffectiveOrdersByTopicId(TOPIC_ID)).thenReturn(Collections.emptyList());

        assertThrows(IllegalArgumentException.class, () -> service.open(request(), OWNER_ID, "主理人"));
        verify(activityService, never()).insertCmsActivity(any());
    }

    @Test
    void clubOwnedPublicTopicsAreOpenableEvenWithoutCoopOrder() {
        CmsTopic owned = new CmsTopic();
        owned.setId(88L);
        owned.setName("俱乐部自有主题");
        owned.setClubId(CLUB_ID);
        when(orderMapper.selectEffectiveTopicIdsByClubId(CLUB_ID)).thenReturn(Collections.emptyList());
        when(topicService.selectPublicCmsTopicList(any())).thenReturn(Collections.singletonList(owned));
        when(topicService.selectCmsTopicById(88L)).thenReturn(owned);

        assertEquals(Collections.singletonList(88L), service.selectOpenableTopics(CLUB_ID, OWNER_ID)
                .stream().map(CmsTopic::getId).collect(java.util.stream.Collectors.toList()));
    }

    @Test
    void ownerCanOpenClubOwnedTopicWithoutCoopOrder() {
        CmsTopic topic = topicService.selectCmsTopicById(TOPIC_ID);
        topic.setClubId(CLUB_ID);
        when(orderMapper.selectEffectiveOrdersByTopicId(TOPIC_ID)).thenReturn(Collections.emptyList());

        assertEquals(Long.valueOf(51L), service.open(request(), OWNER_ID, "主理人"));
        verify(activityService).insertCmsActivity(any());
    }

    @Test
    void anotherClubsOrderDoesNotAuthorizeThisClub() {
        when(orderMapper.selectEffectiveOrdersByTopicId(TOPIC_ID))
                .thenReturn(Collections.singletonList(clubOrder(999L)));

        assertThrows(IllegalArgumentException.class, () -> service.open(request(), OWNER_ID, "主理人"));
        verify(activityService, never()).insertCmsActivity(any());
    }

    /**
     * 5.9 场次集合时间真源 = cms_activity.start_date,锚点由「日期 + 集合时刻」两个入参拼出。
     * 退款钟(集合前 24h)读的就是这个值,把 14:30 压成 00:00 等于把窗口凭空挪走 14.5 小时。
     */
    @Test
    void openedSessionCombinesDateAndTimeIntoAssemblyMoment() {
        java.util.Calendar cal = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        cal.clear();
        cal.set(2027, java.util.Calendar.MARCH, 8, 14, 30, 0);
        java.util.Calendar date = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        date.clear();
        date.set(2027, java.util.Calendar.MARCH, 8, 0, 0, 0);
        ClubSessionOpenRequest request = request();
        request.setSessionDate(date.getTime());
        request.setSessionTime("14:30");

        service.open(request, OWNER_ID, "主理人");

        ArgumentCaptor<CmsActivity> captor = ArgumentCaptor.forClass(CmsActivity.class);
        verify(activityService).insertCmsActivity(captor.capture());
        assertEquals(cal.getTime(), captor.getValue().getStartDate(),
                "集合时刻必须是「日期 + HH:mm」拼出的 14:30,不得被压成当天 00:00");
        java.util.Calendar end = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        end.setTime(captor.getValue().getEndDate());
        assertEquals(23, end.get(java.util.Calendar.HOUR_OF_DAY), "结束仍是场次当天日终");
        assertEquals(59, end.get(java.util.Calendar.MINUTE));
    }

    /** 缺集合时刻不再静默落 00:00 —— 直接拒绝开场,收费与退款窗口都基于 00:00 是假事实。 */
    @Test
    void openWithoutSessionTimeIsRejected() {
        ClubSessionOpenRequest request = request();
        request.setSessionTime(null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.open(request, OWNER_ID, "主理人"));

        assertTrue(error.getMessage().contains("集合时刻"),
                "拒绝原因必须点名缺失的是集合时刻,实际:" + error.getMessage());
        verify(activityService, never()).insertCmsActivity(any());
    }

    /** 时刻格式非法同样拒绝,不能静默回退当天 00:00。 */
    @Test
    void openWithMalformedSessionTimeIsRejected() {
        ClubSessionOpenRequest request = request();
        request.setSessionTime("下午两点");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.open(request, OWNER_ID, "主理人"));

        assertTrue(error.getMessage().contains("HH:mm"),
                "拒绝原因必须给出可用的格式,实际:" + error.getMessage());
        verify(activityService, never()).insertCmsActivity(any());
    }

    /** 旧PR#29 E01-12 交接：直接开团入口也不得把 24:00 静默归一成当天 00:00(退款钟锚点会提前一整天)。 */
    @Test
    void openWithHourTwentyFourIsRejectedInsteadOfMidnight() {
        ClubSessionOpenRequest request = request();
        request.setSessionTime("24:00");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.open(request, OWNER_ID, "主理人"));

        assertTrue(error.getMessage().contains("HH:mm"),
                "拒绝原因必须给出可用的格式,实际:" + error.getMessage());
        verify(activityService, never()).insertCmsActivity(any());
    }

    /** 存量口径显式传入 00:00 时仍落当天 00:00 —— 改造只强制入参带时刻,不改这个值本身的含义。 */
    @Test
    void openedSessionWithExplicitMidnightTimeStaysMidnight() {
        java.util.Calendar cal = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        cal.clear();
        cal.set(2027, java.util.Calendar.MARCH, 8, 0, 0, 0);
        ClubSessionOpenRequest request = request();
        request.setSessionDate(cal.getTime());
        request.setSessionTime("00:00");

        service.open(request, OWNER_ID, "主理人");

        ArgumentCaptor<CmsActivity> captor = ArgumentCaptor.forClass(CmsActivity.class);
        verify(activityService).insertCmsActivity(captor.capture());
        assertEquals(cal.getTime(), captor.getValue().getStartDate());
    }

    @Test
    void pastAssemblyMomentIsRejectedBeforeAnyWrite() {
        ClubSessionOpenRequest request = request();
        request.setSessionDate(new Date(0L));
        assertThrows(IllegalArgumentException.class, () -> service.open(request, OWNER_ID, "主理人"));
        verify(activityService, org.mockito.Mockito.never()).insertCmsActivity(org.mockito.ArgumentMatchers.any());
    }

    private ClubSessionOpenRequest request() {
        ClubSessionOpenRequest request = new ClubSessionOpenRequest();
        request.setClubId(CLUB_ID);
        request.setTopicId(TOPIC_ID);
        request.setSessionDate(new Date(1_800_000_000_000L));
        request.setSessionTime("09:00");
        return request;
    }

    private CoopOrder clubOrder(Long clubId) {
        CoopOrder order = new CoopOrder();
        order.setToType("club");
        order.setToId(clubId);
        return order;
    }

    /** 团码使用真实内联PNG渲染，不依赖对象存储。 */
    private static IQrcodeService inlineQrcode() {
        return new QrcodeServiceImpl();
    }
}
