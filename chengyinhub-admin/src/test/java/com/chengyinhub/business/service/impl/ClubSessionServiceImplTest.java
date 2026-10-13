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

    @BeforeEach
    void setUp() {
        clubService = mock(IClubService.class);
        topicService = mock(ICmsTopicService.class);
        activityService = mock(ICmsActivityService.class);
        ticketService = mock(IOmsTicketService.class);
        orderMapper = mock(CoopOrderMapper.class);
        debtMapper = mock(com.chengyinhub.business.mapper.UmsMemberSettlementDebtMapper.class);
        service = new ClubSessionServiceImpl(clubService, topicService, activityService, ticketService, orderMapper,
                new com.chengyinhub.business.service.support.SettlementDebtLedger(debtMapper,
                        mock(com.chengyinhub.business.mapper.UmsMemberMapper.class),
                        mock(com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper.class)));

        Club club = new Club();
        club.setId(CLUB_ID);
        club.setMemberId(OWNER_ID);
        when(clubService.selectClubById(CLUB_ID)).thenReturn(club);
        when(clubService.selectClubByIdForUpdate(CLUB_ID)).thenReturn(club);

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setName("夜行主题");
        topic.setDescription("沿河夜行");
        topic.setImgUrl("cover.jpg");
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
