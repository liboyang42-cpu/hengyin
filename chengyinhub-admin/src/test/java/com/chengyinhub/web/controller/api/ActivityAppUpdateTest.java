package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
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
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP5 · 小程序 {@code POST /api/activity/update}。
 *
 * <p>闸放在 controller 入口才算数:只测 service 返回值,拆掉入口 if 照样全绿。
 * 这里真串 controller → CmsActivityServiceImpl → mapper,只桩最外面的 mapper。
 *
 * <p>CR-63(2026-09-15 裁决 15)口径:已售出后时间与地点不可改,
 * 文字/图片/音频仍可改;未售(无任何活单)时时间可改。
 * P1-2(审查D):「已售出」= payment_status 0 待支付/1 支付中/2 已支付;3 失败/4 退款不算。
 *
 * <p>⚠️ 负控:拆掉 {@code OwnershipUtils.notOwner} ⇒ {@code stranger_cannotUpdate} 红;
 * 拆掉已售时间/地点比较 ⇒ {@code paidRegistration_locksTimeChange/LocksPlaceChange} 红;
 * 把判据缩回只认已支付 ⇒ {@code pendingPayment_locksTimeChange} 红;
 * 把 3/4 也数进来 ⇒ {@code closedOrRefunded_doesNotLockTimeChange} 红;
 * 经纬度改回字符串 equals ⇒ {@code paidRegistration_allowsCoordinateScaleDifference} 红;
 * mapper {@code where} 改回只认 id ⇒ {@code mapperUpdateMustScopeByOwner} 红。
 */
class ActivityAppUpdateTest {

    private static final long ME = 9L;
    private static final long STRANGER = 999L;
    private static final long ACTIVITY = 300L;

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
        // 与 request() 的档期/地点一致:已售改图文要能过时间/地点比较,才测得准「只锁时间地点」。
        a.setStartDate(new Date(1_800_000_000_000L));
        a.setEndDate(new Date(1_800_003_600_000L));
        a.setAddressName("外滩");
        a.setAddress("中山东一路");
        // 库里是补零后的坐标串;客户端回传只有有效位 —— 数值比较才不该误拒(审查 §12.3)。
        a.setLongitude("121.470000");
        a.setLatitude("31.230000");
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(a);
        when(activityMapper.updateCmsActivityByOwner(ArgumentMatchers.any())).thenReturn(1);
    }

    private void givenRegistrationRows(Integer status, java.util.List<CmsRegistration> rows) {
        when(registrationMapper.countCmsRegistrationList(ArgumentMatchers.argThat(
                q -> q != null && status.equals(q.getPaymentStatus())
                        && Long.valueOf(2L).equals(q.getOwnerType())
                        && Long.valueOf(ACTIVITY).equals(q.getOwnerId()))))
                .thenReturn(rows.size());
    }

    private CmsActivityPublishRequest request() {
        CmsActivityPublishRequest r = new CmsActivityPublishRequest();
        r.setId(ACTIVITY);
        r.setName("改过的活动名");
        r.setDescription("改过的简介");
        r.setImgUrl("cover.jpg");
        r.setImgArr("cover.jpg");
        r.setCategoryIds("1");
        r.setAddressName("外滩");
        r.setAddress("中山东一路");
        r.setLongitude("121.47");
        r.setLatitude("31.23");
        r.setStartDate(new Date(1_800_000_000_000L));
        r.setEndDate(new Date(1_800_003_600_000L));
        r.setTemplateId(5L);
        return r;
    }

    /** 他人活动 ⇒ 拒。IDOR 校验不能被我改丢。 */
    @Test
    void stranger_cannotUpdate() {
        CmsActivity other = new CmsActivity();
        other.setId(ACTIVITY);
        other.setMemberId(STRANGER);
        other.setDelFlag(0);
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(other);

        AjaxResult r = controller.update(request());

        assertEquals(500, ((Integer) r.get("code")).intValue());
        assertTrue(String.valueOf(r.get("msg")).contains("无权"), "实际:" + r.get("msg"));
        verify(activityMapper, never()).updateCmsActivityByOwner(any());
        verify(activityMapper, never()).updateCmsActivity(any());
    }

    /**
     * CR-63:已售出(有已支付报名)+ 改时间(开始/结束)⇒ 拒。
     * 已售的判据只看 payment_status=2;待支付/支付中的单不在这道闸里(见 unpaidOnly 两条)。
     */
    @Test
    void paidRegistration_locksTimeChange() {
        givenPaidRegistration();
        CmsActivityPublishRequest r = request();
        r.setStartDate(new Date(1_900_000_000_000L));

        AjaxResult res = controller.update(r);

        assertEquals(500, ((Integer) res.get("code")).intValue(), "实际:" + res.get("msg"));
        assertTrue(String.valueOf(res.get("msg")).contains("时间"), "实际:" + res.get("msg"));
        verify(activityMapper, never()).updateCmsActivityByOwner(any());
        verify(activityMapper, never()).updateCmsActivity(any());
    }

    /** CR-63:已售出 + 改地点(名称/坐标/详细地址)⇒ 拒。 */
    @Test
    void paidRegistration_locksPlaceChange() {
        givenPaidRegistration();
        CmsActivityPublishRequest r = request();
        r.setAddressName("新集合点");

        AjaxResult res = controller.update(r);

        assertEquals(500, ((Integer) res.get("code")).intValue(), "实际:" + res.get("msg"));
        assertTrue(String.valueOf(res.get("msg")).contains("地点"), "实际:" + res.get("msg"));
        verify(activityMapper, never()).updateCmsActivityByOwner(any());
    }

    /** CR-63:已售出 + 只改文字/图片/音频(时间地点与库内一致)⇒ 放行。 */
    @Test
    void paidRegistration_allowsTextImageAudioChange() {
        givenPaidRegistration();

        AjaxResult res = controller.update(request());

        assertEquals(200, ((Integer) res.get("code")).intValue(), "实际:" + res.get("msg"));
        ArgumentCaptor<CmsActivity> cap = ArgumentCaptor.forClass(CmsActivity.class);
        verify(activityMapper).updateCmsActivityByOwner(cap.capture());
        CmsActivity patch = cap.getValue();
        assertEquals("改过的活动名", patch.getName());
        assertEquals("改过的简介", patch.getDescription());
        assertEquals("cover.jpg", patch.getImgUrl());
        assertEquals(new Date(1_800_000_000_000L), patch.getStartDate(), "时间原样带过,不是被改掉");
        assertEquals("外滩", patch.getAddressName(), "地点原样带过,不是被改掉");
    }

    /** 未售(无任何报名)+ 改时间 ⇒ 放行。裁决锁的是「已售出」,不是「开卖过就永久冻结」。 */
    @Test
    void unsold_canChangeTime() {
        CmsActivityPublishRequest r = request();
        r.setStartDate(new Date(1_900_000_000_000L));

        AjaxResult res = controller.update(r);

        assertEquals(200, ((Integer) res.get("code")).intValue(), "实际:" + res.get("msg"));
        verify(activityMapper).updateCmsActivityByOwner(any());
    }

    /**
     * P1-2(审查D):待支付(0)/支付中(1)的订单也算「已售出」——
     * 玩家下单时看到的是旧时间,等支付回调的窗口不能成为改期后门。
     */
    @Test
    void pendingPayment_locksTimeChange() {
        for (int status : new int[] { 0, 1 }) {
            CmsRegistration pending = new CmsRegistration();
            pending.setPaymentStatus(status);
            givenRegistrationRows(status, Collections.singletonList(pending));

            CmsActivityPublishRequest r = request();
            r.setStartDate(new Date(1_900_000_000_000L));
            AjaxResult res = controller.update(r);

            assertEquals(500, ((Integer) res.get("code")).intValue(),
                    "payment_status=" + status + " 是待支付/支付中,必须锁时间;实际:" + res.get("msg"));
            assertTrue(String.valueOf(res.get("msg")).contains("时间"), "实际:" + res.get("msg"));
            verify(activityMapper, never()).updateCmsActivityByOwner(any());
        }
    }

    /** 弃单(3)/已退款(4)不是活单,不该把未成交易的编辑冻死。 */
    @Test
    void closedOrRefunded_doesNotLockTimeChange() {
        for (int status : new int[] { 3, 4 }) {
            CmsRegistration closed = new CmsRegistration();
            closed.setPaymentStatus(status);
            givenRegistrationRows(status, Collections.singletonList(closed));

            CmsActivityPublishRequest r = request();
            r.setStartDate(new Date(1_900_000_000_000L));
            AjaxResult res = controller.update(r);

            assertEquals(200, ((Integer) res.get("code")).intValue(),
                    "payment_status=" + status + " 不是活单,不该锁时间;实际:" + res.get("msg"));
        }
    }

    /**
     * §12.3:经纬度按数值比较 —— 库里的 121.470000 与回传的 121.47 是同一个点,
     * 已售活动只改文字时不能被误拒。
     */
    @Test
    void paidRegistration_allowsCoordinateScaleDifference() {
        givenPaidRegistration();

        AjaxResult res = controller.update(request());

        assertEquals(200, ((Integer) res.get("code")).intValue(), "实际:" + res.get("msg"));
        verify(activityMapper).updateCmsActivityByOwner(any());
    }

    /** 容差边界:差 1e-6(小数点后 6 位)算同一个点;差 2e-6 是真改点,仍拒。 */
    @Test
    void paidRegistration_coordinateToleranceIsSixDecimals() {
        givenPaidRegistration();
        CmsActivityPublishRequest withinTolerance = request();
        withinTolerance.setLatitude("31.230001");
        AjaxResult ok = controller.update(withinTolerance);
        assertEquals(200, ((Integer) ok.get("code")).intValue(), "1e-6 是同一坐标;实际:" + ok.get("msg"));

        CmsActivityPublishRequest beyondTolerance = request();
        beyondTolerance.setLatitude("31.230002");
        AjaxResult res = controller.update(beyondTolerance);
        assertEquals(500, ((Integer) res.get("code")).intValue(), "实际:" + res.get("msg"));
        assertTrue(String.valueOf(res.get("msg")).contains("地点"), "实际:" + res.get("msg"));
    }

    /** 真改了经度(超出容差)仍拒,数值比较不是放行。 */
    @Test
    void paidRegistration_locksRealCoordinateChange() {
        givenPaidRegistration();
        CmsActivityPublishRequest r = request();
        r.setLongitude("121.48");

        AjaxResult res = controller.update(r);

        assertEquals(500, ((Integer) res.get("code")).intValue(), "实际:" + res.get("msg"));
        assertTrue(String.valueOf(res.get("msg")).contains("地点"), "实际:" + res.get("msg"));
        verify(activityMapper, never()).updateCmsActivityByOwner(any());
    }

    private void givenPaidRegistration() {
        CmsRegistration paid = new CmsRegistration();
        paid.setPaymentStatus(2);
        givenRegistrationRows(2, Collections.singletonList(paid));
    }

    /** 无有效报名 ⇒ 放行内容字段,且 mapper 带 owner。 */
    @Test
    void unsold_canUpdateContent() {
        AjaxResult r = controller.update(request());

        assertEquals(200, ((Integer) r.get("code")).intValue(), "实际:" + r.get("msg"));
        ArgumentCaptor<CmsActivity> cap = ArgumentCaptor.forClass(CmsActivity.class);
        verify(activityMapper).updateCmsActivityByOwner(cap.capture());
        verify(activityMapper, never()).updateCmsActivity(any());
        CmsActivity patch = cap.getValue();
        assertEquals(Long.valueOf(ACTIVITY), patch.getId());
        assertEquals(Long.valueOf(ME), patch.getMemberId());
        assertEquals("改过的活动名", patch.getName());
        assertEquals("改过的简介", patch.getDescription());
        assertEquals("cover.jpg", patch.getImgUrl());
        assertEquals("外滩", patch.getAddressName());
        assertEquals(Long.valueOf(5L), patch.getTemplateId());
        org.junit.jupiter.api.Assertions.assertNull(patch.getStatus(), "不能借 update 改审核态");
        org.junit.jupiter.api.Assertions.assertNull(patch.getPublishStatus(), "不能借 update 改上架态");
    }

    /** 票务改动不得把已删票 del_flag 改回 0。 */
    @Test
    void deletedTicket_isNotResurrected() {
        OmsTicket deleted = new OmsTicket();
        deleted.setId(88L);
        deleted.setOwnerType(2L);
        deleted.setOwnerId(ACTIVITY);
        deleted.setDelFlag(2);
        deleted.setName("已下线早鸟");
        deleted.setTotalInventory(10L);
        deleted.setRemainingInventory(10L);
        when(ticketMapper.selectOmsTicketById(88L)).thenReturn(deleted);
        when(ticketMapper.selectOmsTicketList(ArgumentMatchers.any())).thenReturn(Collections.emptyList());

        ActivityTicketRequest incoming = new ActivityTicketRequest();
        incoming.setId(88L);
        incoming.setName("已下线早鸟");
        incoming.setPrice(new BigDecimal("99.00"));
        incoming.setStartTime(new Date(1_800_000_000_000L));
        incoming.setEndTime(new Date(1_800_003_600_000L));
        incoming.setTotalStock(10);
        CmsActivityPublishRequest req = request();
        req.setTickets(Collections.singletonList(incoming));

        AjaxResult r = controller.update(req);

        assertEquals(200, ((Integer) r.get("code")).intValue(), "实际:" + r.get("msg"));
        ArgumentCaptor<OmsTicket> written = ArgumentCaptor.forClass(OmsTicket.class);
        verify(ticketMapper).insertOmsTicket(written.capture());
        org.junit.jupiter.api.Assertions.assertNull(written.getValue().getId(),
                "已删票不能原地复活,必须当新票插入");
        verify(ticketMapper, never()).updateOmsTicket(ArgumentMatchers.argThat(
                t -> t != null && Long.valueOf(88L).equals(t.getId())
                        && t.getDelFlag() != null && t.getDelFlag() == 0));
    }

    /**
     * 小程序更新走的 mapper 必须 {@code WHERE id=? AND member_id=?},不能只认 id。
     * ⚠️ 负控:where 改回 {@code where id = #{id}} ⇒ 本用例红。
     */
    @Test
    void mapperUpdateMustScopeByOwner() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get(System.getProperty("user.dir"),
                "../chengyinhub-system/src/main/resources/mapper/business/CmsActivityMapper.xml")),
                StandardCharsets.UTF_8);
        int start = xml.indexOf("<update id=\"updateCmsActivityByOwner\"");
        assertTrue(start >= 0, "必须有 updateCmsActivityByOwner,不能复用只认 id 的 updateCmsActivity");
        int end = xml.indexOf("</update>", start);
        String update = xml.substring(start, end).replaceAll("\\s+", " ").toLowerCase();
        assertTrue(update.contains("where id = #{id} and member_id = #{memberid}"),
                "实际:" + update);
        org.junit.jupiter.api.Assertions.assertFalse(
                update.contains("member_id = #{memberid},"),
                "member_id 只能出现在 WHERE,不能写进 SET 改归属");
    }
}
