package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.ActivityTicketRequest;
import com.chengyinhub.business.domain.dto.CmsActivityPublishRequest;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.impl.CmsActivityServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-1(审查D §1):已售锁不能从票务同步绕过。
 *
 * <p>已售出(有用户下单:待支付 0/支付中 1/已支付 2)的票,时间、集合点、坐标、价格、
 * 退款规则全锁,且不许从活动里删掉;只允许改票名等文字。未售出的票照常可改可删。
 *
 * <p>这里真串 controller → CmsActivityServiceImpl → mapper,只桩最外面的 mapper,
 * 所以「闸挂在 service 里、controller 也能拦住」是实测出来的。
 *
 * <p>⚠️ 负控:拆掉 {@code assertSoldTicketEditsAllowed} 调用 ⇒ 前 10 条全红;
 * 把「已售集合」换成恒空 ⇒ 锁用例全红;把未售票一起锁 ⇒ 放行用例红。
 */
class ActivitySoldTicketEditLockTest {

    private static final long ME = 9L;
    private static final long ACTIVITY = 300L;
    private static final long SOLD_TICKET = 77L;
    private static final long UNSOLD_TICKET = 78L;
    private static final Date TICKET_START = new Date(1_800_000_000_000L);
    private static final Date TICKET_END = new Date(1_800_003_600_000L);
    private static final Date ACTIVITY_START = new Date(1_800_000_000_000L);
    private static final Date ACTIVITY_END = new Date(1_800_003_600_000L);

    private CmsActivityMapper activityMapper;
    private CmsRegistrationMapper registrationMapper;
    private OmsTicketMapper ticketMapper;
    private ApiActivityController controller;

    @BeforeEach
    void setUp() {
        activityMapper = org.mockito.Mockito.mock(CmsActivityMapper.class);
        registrationMapper = org.mockito.Mockito.mock(CmsRegistrationMapper.class);
        ticketMapper = org.mockito.Mockito.mock(OmsTicketMapper.class);

        CmsActivityServiceImpl service = new CmsActivityServiceImpl();
        ReflectionTestUtils.setField(service, "memberIdentityService", org.mockito.Mockito.mock(com.chengyinhub.business.service.impl.MemberIdentityService.class));
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        // 收费独立活动只允许平台账号发布(F-W2-01)；本用例验证的是收费票的改单锁，发布者按平台账号构造。
        ReflectionTestUtils.setField(service, "platformMemberId", ME);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);

        controller = org.mockito.Mockito.spy(new ApiActivityController());
        org.mockito.Mockito.doReturn(ME).when(controller).getAppUserId();
        org.mockito.Mockito.doReturn("me").when(controller).getAppUserName();
        ReflectionTestUtils.setField(controller, "cmsActivityService", service);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", org.mockito.Mockito.mock(com.chengyinhub.business.service.IWxContentSecurityService.class));
        // /update 走统一门禁 + 分级(AUDIT-G1-2):本类只测改单锁,门禁放行、配置为空 ⇒ 先发后审。
        com.chengyinhub.business.service.IContentSafetyGate gate =
                org.mockito.Mockito.mock(com.chengyinhub.business.service.IContentSafetyGate.class);
        when(gate.decide(ArgumentMatchers.any())).thenReturn(com.chengyinhub.business.domain.dto.SafetyDecision.pass());
        ReflectionTestUtils.setField(controller, "contentSafetyGate", gate);
        com.chengyinhub.business.service.support.ActivityReviewPolicy policy =
                new com.chengyinhub.business.service.support.ActivityReviewPolicy();
        ReflectionTestUtils.setField(policy, "configService",
                org.mockito.Mockito.mock(com.chengyinhub.system.service.ISysConfigService.class));
        ReflectionTestUtils.setField(controller, "activityReviewPolicy", policy);

        IUmsMemberService umsMemberService = org.mockito.Mockito.mock(IUmsMemberService.class);
        IRoleService roleService = org.mockito.Mockito.mock(IRoleService.class);
        UmsMember me = new UmsMember();
        me.setId(ME);
        when(umsMemberService.selectUmsMemberById(ME)).thenReturn(me);
        when(roleService.resolveRole(ArgumentMatchers.any())).thenReturn(IRoleService.ROLE_CLUB);
        ReflectionTestUtils.setField(controller, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(controller, "roleService", roleService);

        CmsActivity a = new CmsActivity();
        a.setId(ACTIVITY);
        a.setMemberId(ME);
        a.setDelFlag(0);
        a.setStartDate(ACTIVITY_START);
        a.setEndDate(ACTIVITY_END);
        a.setAddressName("外滩");
        a.setAddress("中山东一路");
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(a);
        when(activityMapper.updateCmsActivityByOwner(ArgumentMatchers.any())).thenReturn(1);

        when(ticketMapper.selectOmsTicketList(ArgumentMatchers.any()))
                .thenReturn(Arrays.asList(soldTicket(), unsoldTicket()));
        when(registrationMapper.selectOpenSaleTicketIdsByActivity(ACTIVITY))
                .thenReturn(Collections.singletonList(SOLD_TICKET));
    }

    private OmsTicket soldTicket() {
        OmsTicket t = new OmsTicket();
        t.setId(SOLD_TICKET);
        t.setOwnerType(2L);
        t.setOwnerId(ACTIVITY);
        t.setDelFlag(0);
        t.setName("早鸟票");
        t.setPrice(new BigDecimal("199.00"));
        t.setStartTime(TICKET_START);
        t.setEndTime(TICKET_END);
        t.setMeetingPoint("人民广场");
        t.setGatherLng(new BigDecimal("121.470000"));
        t.setGatherLat(new BigDecimal("31.230000"));
        t.setRefundRule("提前24小时可全额退");
        t.setTotalInventory(10L);
        t.setRemainingInventory(8L);
        return t;
    }

    private OmsTicket unsoldTicket() {
        OmsTicket t = soldTicket();
        t.setId(UNSOLD_TICKET);
        t.setName("现场票");
        t.setPrice(new BigDecimal("99.00"));
        return t;
    }

    private ActivityTicketRequest soldRequest() {
        ActivityTicketRequest r = new ActivityTicketRequest();
        r.setId(SOLD_TICKET);
        r.setName("早鸟票");
        r.setPrice(new BigDecimal("199.00"));
        r.setStartTime(TICKET_START);
        r.setEndTime(TICKET_END);
        r.setTotalStock(10);
        r.setMeetingPoint("人民广场");
        r.setGatherLng(new BigDecimal("121.470000"));
        r.setGatherLat(new BigDecimal("31.230000"));
        r.setRefundRule("提前24小时可全额退");
        return r;
    }

    private ActivityTicketRequest unsoldRequest() {
        ActivityTicketRequest r = soldRequest();
        r.setId(UNSOLD_TICKET);
        r.setName("现场票");
        r.setPrice(new BigDecimal("99.00"));
        return r;
    }

    private CmsActivityPublishRequest request(ActivityTicketRequest... tickets) {
        CmsActivityPublishRequest r = new CmsActivityPublishRequest();
        r.setId(ACTIVITY);
        r.setName("改过的活动名");
        r.setDescription("改过的简介");
        r.setStartDate(ACTIVITY_START);
        r.setEndDate(ACTIVITY_END);
        r.setAddressName("外滩");
        r.setAddress("中山东一路");
        r.setTickets(Arrays.asList(tickets));
        return r;
    }

    private void assertLocked(AjaxResult res, String field) {
        assertEquals(500, ((Integer) res.get("code")).intValue(), "实际:" + res.get("msg"));
        assertTrue(String.valueOf(res.get("msg")).contains("已售出的票"), "实际:" + res.get("msg"));
        assertTrue(String.valueOf(res.get("msg")).contains(field), "实际:" + res.get("msg"));
        verify(ticketMapper, never()).updateOmsTicket(any(OmsTicket.class));
        verify(ticketMapper, never()).insertOmsTicket(any(OmsTicket.class));
        verify(activityMapper, never()).updateCmsActivityByOwner(any());
    }

    @Test
    void soldTicket_locksStartTimeChange() {
        ActivityTicketRequest sold = soldRequest();
        sold.setStartTime(new Date(TICKET_START.getTime() + 3_600_000L));

        assertLocked(controller.update(request(sold, unsoldRequest())), "集合时间");
    }

    @Test
    void soldTicket_locksEndTimeChange() {
        ActivityTicketRequest sold = soldRequest();
        sold.setEndTime(new Date(TICKET_END.getTime() + 3_600_000L));

        assertLocked(controller.update(request(sold, unsoldRequest())), "结束时间");
    }

    @Test
    void soldTicket_locksMeetingPointChange() {
        ActivityTicketRequest sold = soldRequest();
        sold.setMeetingPoint("新集合点");

        assertLocked(controller.update(request(sold, unsoldRequest())), "集合地点");
    }

    @Test
    void soldTicket_locksGatherLngChange() {
        ActivityTicketRequest sold = soldRequest();
        sold.setGatherLng(new BigDecimal("121.480000"));

        assertLocked(controller.update(request(sold, unsoldRequest())), "集合点坐标");
    }

    @Test
    void soldTicket_locksGatherLatChange() {
        ActivityTicketRequest sold = soldRequest();
        sold.setGatherLat(new BigDecimal("31.240000"));

        assertLocked(controller.update(request(sold, unsoldRequest())), "集合点坐标");
    }

    @Test
    void soldTicket_locksPriceChange() {
        ActivityTicketRequest sold = soldRequest();
        sold.setPrice(new BigDecimal("149.00"));

        assertLocked(controller.update(request(sold, unsoldRequest())), "价格");
    }

    @Test
    void soldTicket_locksRefundRuleChange() {
        ActivityTicketRequest sold = soldRequest();
        sold.setRefundRule("不可退");

        assertLocked(controller.update(request(sold, unsoldRequest())), "退款规则");
    }

    /** 请求里不带已售票 = 删除意图,同样拒。 */
    @Test
    void soldTicket_deletionRejected() {
        assertLocked(controller.update(request(unsoldRequest())), "不能删除");
    }

    /** 只改票名(文字)放行:锁的是票据要素,不是编辑本身。 */
    @Test
    void soldTicket_allowsNameOnlyChange() {
        ActivityTicketRequest sold = soldRequest();
        sold.setName("早鸟票(含保险)");

        AjaxResult res = controller.update(request(sold, unsoldRequest()));

        assertEquals(200, ((Integer) res.get("code")).intValue(), "实际:" + res.get("msg"));
        ArgumentCaptor<OmsTicket> cap = ArgumentCaptor.forClass(OmsTicket.class);
        verify(ticketMapper, times(2)).updateOmsTicket(cap.capture());
        OmsTicket soldPatch = cap.getAllValues().stream()
                .filter(t -> t.getId() != null && t.getId() == SOLD_TICKET)
                .findFirst().orElseThrow(() -> new AssertionError("已售票必须被更新"));
        assertEquals("早鸟票(含保险)", soldPatch.getName());
        assertEquals(new BigDecimal("199.00"), soldPatch.getPrice(), "价格原样带过,不是被改掉");
        assertEquals(TICKET_START, soldPatch.getStartTime(), "时间原样带过,不是被改掉");
        assertEquals("人民广场", soldPatch.getMeetingPoint(), "集合点原样带过,不是被改掉");
    }

    /**
     * 审查 §12.3 的票务面:编辑页回传的坐标/价格常与库里只差格式(121.47 vs 121.470000、
     * 199.0 vs 199.00)。按 equals 比会把「只改了票名」误判成改地点/改价直接拒掉,
     * 主办就再也改不动错别字了。坐标按数值比(容差 1e-6),金额按 compareTo 比。
     */
    @Test
    void soldTicket_sameCoordinateAndPriceInDifferentScale_isNotAChange() {
        ActivityTicketRequest sold = soldRequest();
        sold.setName("早鸟票(含保险)");
        sold.setGatherLng(new BigDecimal("121.47"));
        sold.setGatherLat(new BigDecimal("31.23"));
        sold.setPrice(new BigDecimal("199.0"));

        AjaxResult res = controller.update(request(sold, unsoldRequest()));

        assertEquals(200, ((Integer) res.get("code")).intValue(),
                "同一个点/同一个价只是格式不同,不能当成改动拒掉。实际:" + res.get("msg"));
    }

    /** 未售出的票照常可改:时间/地点/价格都不锁。 */
    @Test
    void unsoldTicket_canChangeTimePlaceAndPrice() {
        ActivityTicketRequest unsold = unsoldRequest();
        // CU-C-48:票种窗口必须落在活动起止内(本夹具活动与票同为 1 小时),改时间就在窗口里挪
        unsold.setStartTime(new Date(TICKET_START.getTime() + 1_800_000L));
        unsold.setEndTime(TICKET_END);
        unsold.setPrice(new BigDecimal("89.00"));
        unsold.setMeetingPoint("新集合点");
        unsold.setGatherLng(new BigDecimal("121.500000"));

        AjaxResult res = controller.update(request(soldRequest(), unsold));

        assertEquals(200, ((Integer) res.get("code")).intValue(), "实际:" + res.get("msg"));
        ArgumentCaptor<OmsTicket> cap = ArgumentCaptor.forClass(OmsTicket.class);
        verify(ticketMapper, times(2)).updateOmsTicket(cap.capture());
        OmsTicket unsoldPatch = cap.getAllValues().stream()
                .filter(t -> t.getId() != null && t.getId() == UNSOLD_TICKET)
                .findFirst().orElseThrow(() -> new AssertionError("未售票必须被更新"));
        assertEquals(new BigDecimal("89.00"), unsoldPatch.getPrice());
        assertEquals("新集合点", unsoldPatch.getMeetingPoint());
        assertEquals(new Date(TICKET_START.getTime() + 1_800_000L), unsoldPatch.getStartTime());
    }

    /** 未售出的票照常可删(缺席 = 软删),已售那张还留着。 */
    @Test
    void unsoldTicket_canBeDeleted() {
        AjaxResult res = controller.update(request(soldRequest()));

        assertEquals(200, ((Integer) res.get("code")).intValue(), "实际:" + res.get("msg"));
        ArgumentCaptor<OmsTicket> cap = ArgumentCaptor.forClass(OmsTicket.class);
        verify(ticketMapper, times(2)).updateOmsTicket(cap.capture());
        OmsTicket deleted = cap.getAllValues().stream()
                .filter(t -> t.getId() != null && t.getId() == UNSOLD_TICKET)
                .findFirst().orElseThrow(() -> new AssertionError("未售票必须被软删"));
        assertEquals(2, deleted.getDelFlag().intValue());
        assertTrue(cap.getAllValues().stream().noneMatch(t ->
                        t.getId() != null && t.getId() == SOLD_TICKET && t.getDelFlag() != null && t.getDelFlag() == 2),
                "已售票不能被软删");
    }

    /**
     * 判据 SQL 必须把待支付(0)/支付中(1)也算「有用户下单」。
     * ⚠️ 负控:把 in (0, 1, 2) 改回 = 2 ⇒ 本用例红(P1-2 的方向)。
     */
    @Test
    void openSaleTicketSetQuery_countsPendingAndPayingOrders() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get(System.getProperty("user.dir"),
                "../chengyinhub-system/src/main/resources/mapper/business/CmsRegistrationMapper.xml")),
                StandardCharsets.UTF_8);
        int start = xml.indexOf("<select id=\"selectOpenSaleTicketIdsByActivity\"");
        assertTrue(start >= 0, "必须有 selectOpenSaleTicketIdsByActivity");
        int end = xml.indexOf("</select>", start);
        String sql = xml.substring(start, end).replaceAll("\\s+", " ").toLowerCase();
        assertTrue(sql.contains("owner_type = 2") && sql.contains("owner_id = #{activityid}"),
                "必须精确到本活动,不能跨活动取票。实际:" + sql);
        assertTrue(sql.contains("payment_status in (0, 1, 2)"),
                "待支付/支付中/已支付都算有人下单。实际:" + sql);
        assertTrue(sql.contains("ticket_id is not null"), "无票 id 的报名不能归到任何一张票。实际:" + sql);
    }
}
