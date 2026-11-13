package com.chengyinhub.business.platformpass;

import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.platformpass.mapper.SelfPlayXpayMapper;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.business.service.IWeChatMiniSessionService;
import com.chengyinhub.common.constant.PurchaseKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 自玩票虚拟支付：路由由服务端决定、首次签发冻结、仅回查已付才开通、发货可补偿、渠道退款关闭报名、重复/迟到幂等。 */
class SelfPlayXpayServiceTest {
    private static final long MEMBER = 42L;
    private static final long REG_ID = 900L;
    private static final String REG_NO = "T20261113ABCD1234";

    private SelfPlayXpayMapper mapper;
    private SelfPlayXpayProperties props;
    private PlatformPassProperties pass;
    private SelfPlayXpayService service;
    private XpayGateway xpay;
    private IWeChatMiniSessionService sessions;
    private IProductRoleService roles;
    private ICmsRegistrationService registrations;
    private IRefundService refunds;

    @BeforeEach
    void setUp() {
        mapper = mock(SelfPlayXpayMapper.class);
        props = new SelfPlayXpayProperties();
        props.setEnabled(true);
        props.setGoods("3990=goods_3990");
        pass = new PlatformPassProperties();
        pass.getXpay().setEnabled(true);
        pass.getXpay().setOfferId("offer-1");
        pass.getXpay().setAppKey("app-key");
        pass.getXpay().setEnv(1);
        service = new SelfPlayXpayService(mapper, props, pass);
        xpay = mock(XpayGateway.class);
        sessions = mock(IWeChatMiniSessionService.class);
        roles = mock(IProductRoleService.class);
        registrations = mock(ICmsRegistrationService.class);
        refunds = mock(IRefundService.class);
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        UmsMember member = new UmsMember();
        member.setWxOpenId("openid-1");
        when(members.selectUmsMemberById(MEMBER)).thenReturn(member);
        ReflectionTestUtils.setField(service, "xpay", xpay);
        ReflectionTestUtils.setField(service, "miniSessions", sessions);
        ReflectionTestUtils.setField(service, "memberMapper", members);
        ReflectionTestUtils.setField(service, "roles", roles);
        ReflectionTestUtils.setField(service, "registrations", registrations);
        ReflectionTestUtils.setField(service, "refunds", refunds);
        when(roles.isPlatformOwned(500L)).thenReturn(true);
        when(registrations.selectCmsRegistrationById(REG_ID)).thenReturn(registration());
        // MyBatis 对查无结果返回 null；Mockito 对 Map 返回类型默认给空 Map，必须显式对齐。
        when(mapper.selectByRegistrationId(anyLong())).thenReturn(null);
        when(mapper.selectByRegistrationNo(anyString())).thenReturn(null);
    }

    private static CmsRegistration registration() {
        CmsRegistration r = new CmsRegistration();
        r.setId(REG_ID);
        r.setRegistrationNo(REG_NO);
        r.setMemberId(MEMBER);
        r.setOwnerType(1L);
        r.setTopicId(500L);
        r.setPurchaseKind(PurchaseKind.SELF_PLAY_PASS);
        r.setRegistrationStatus(1);
        r.setPaymentStatus(0);
        r.setWechatPaymentAmount(new BigDecimal("39.90"));
        r.setPayableAmount(new BigDecimal("39.90"));
        return r;
    }

    private static Map<String, Object> row(int state, int delivered) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", 7L);
        m.put("registration_id", REG_ID);
        m.put("registration_no", REG_NO);
        m.put("member_id", MEMBER);
        m.put("product_id", "goods_3990");
        m.put("xpay_env", 1);
        m.put("price_fen", 3990L);
        m.put("state", state);
        m.put("delivery_confirmed", delivered);
        return m;
    }

    private static JSONObject remote(int status, long paid, long fee, String wxOrderId) {
        JSONObject order = new JSONObject();
        order.put("status", status);
        order.put("order_id", REG_NO);
        order.put("paid_fee", paid);
        order.put("order_fee", fee);
        order.put("refund_fee", paid);
        order.put("wx_order_id", wxOrderId);
        JSONObject out = new JSONObject();
        out.put("errcode", 0);
        out.put("order", order);
        return out;
    }

    // ---- 路由 ----

    @Test
    void routesToXpayOnlyForNewPlatformOwnedSelfPlayWithConfiguredGoods() {
        assertEquals(SelfPlayXpayService.Route.XPAY, service.routeFor(registration()));
    }

    @Test
    void flagOffKeepsEverythingOnJsapi() {
        props.setEnabled(false);
        assertEquals(SelfPlayXpayService.Route.JSAPI, service.routeFor(registration()));
    }

    @Test
    void notPlatformOwnedPriceWithoutGoodsOrActivityStayJsapi() {
        when(roles.isPlatformOwned(500L)).thenReturn(false);
        assertEquals(SelfPlayXpayService.Route.JSAPI, service.routeFor(registration()));
        when(roles.isPlatformOwned(500L)).thenReturn(true);
        CmsRegistration otherPrice = registration();
        otherPrice.setWechatPaymentAmount(new BigDecimal("49.90"));
        assertEquals(SelfPlayXpayService.Route.JSAPI, service.routeFor(otherPrice));
        CmsRegistration activity = registration();
        activity.setPurchaseKind(PurchaseKind.GUIDED_TICKET);
        assertEquals(SelfPlayXpayService.Route.JSAPI, service.routeFor(activity));
    }

    @Test
    void oldJsapiAttemptKeepsJsapiEvenWhenFlagTurnedOn() {
        CmsRegistration paying = registration();
        paying.setPaymentStatus(1);
        assertEquals(SelfPlayXpayService.Route.JSAPI, service.routeFor(paying));
    }

    @Test
    void frozenRowKeepsXpayEvenWhenFlagTurnedOffLater() {
        props.setEnabled(false);
        when(mapper.selectByRegistrationId(REG_ID)).thenReturn(row(0, 0));
        assertEquals(SelfPlayXpayService.Route.XPAY, service.routeFor(registration()));
    }

    // ---- 签发与冻结 ----

    private void sessionOk() {
        when(sessions.exchange("code-1")).thenReturn(
                new IWeChatMiniSessionService.WeChatMiniSession("openid-1", "session-key"));
    }

    @Test
    void firstSigningFreezesGoodsEnvAndPriceThenSignsWithFrozenValues() {
        sessionOk();
        when(mapper.selectByRegistrationId(REG_ID)).thenReturn(null, row(0, 0));
        Map<String, Object> out = service.prepare(MEMBER, registration(), "code-1");
        verify(mapper).insertFrozen(REG_ID, REG_NO, MEMBER, "goods_3990", 1, 3990L);
        assertEquals("XPAY", out.get("payChannel"));
        @SuppressWarnings("unchecked")
        Map<String, Object> vp = (Map<String, Object>) out.get("virtualPayment");
        JSONObject sign = JSONObject.parseObject((String) vp.get("signData"));
        assertEquals("goods_3990", sign.getString("productId"));
        assertEquals(3990L, sign.getLongValue("goodsPrice"));
        assertEquals(REG_NO, sign.getString("outTradeNo"));
        assertEquals(1, sign.getIntValue("env"));
    }

    @Test
    void laterConfigChangeNeverRewritesFrozenProductOrPrice() {
        sessionOk();
        props.setGoods("3990=other_goods");
        when(mapper.selectByRegistrationId(REG_ID)).thenReturn(row(0, 0));
        Map<String, Object> out = service.prepare(MEMBER, registration(), "code-1");
        verify(mapper, never()).insertFrozen(anyLong(), anyString(), anyLong(), anyString(), anyInt(), anyLong());
        @SuppressWarnings("unchecked")
        Map<String, Object> vp = (Map<String, Object>) out.get("virtualPayment");
        assertEquals("goods_3990", JSONObject.parseObject((String) vp.get("signData")).getString("productId"));
    }

    @Test
    void blankJsCodeFreezesNothing() {
        when(mapper.selectByRegistrationId(REG_ID)).thenReturn(null);
        SelfPlayXpayException e = assertThrows(SelfPlayXpayException.class,
                () -> service.prepare(MEMBER, registration(), " "));
        assertEquals("JS_CODE_REQUIRED", e.getCode());
        verify(mapper, never()).insertFrozen(anyLong(), anyString(), anyLong(), anyString(), anyInt(), anyLong());
    }

    @Test
    void envChangedAfterFreezeRefusesToSignInsteadOfMixingEnvironments() {
        sessionOk();
        pass.getXpay().setEnv(0);
        when(mapper.selectByRegistrationId(REG_ID)).thenReturn(row(0, 0));
        assertEquals("XPAY_ENV_CHANGED", assertThrows(SelfPlayXpayException.class,
                () -> service.prepare(MEMBER, registration(), "code-1")).getCode());
    }

    @Test
    void anotherMembersOpenIdIsRejected() {
        when(sessions.exchange("code-1")).thenReturn(
                new IWeChatMiniSessionService.WeChatMiniSession("someone-else", "session-key"));
        when(mapper.selectByRegistrationId(REG_ID)).thenReturn(row(0, 0));
        assertEquals("SESSION_MISMATCH", assertThrows(SelfPlayXpayException.class,
                () -> service.prepare(MEMBER, registration(), "code-1")).getCode());
    }

    // ---- 仅已验证支付才开通 ----

    @Test
    void unpaidRemoteNeverEntitles() {
        Map<String, Object> r = row(0, 0);
        when(mapper.selectByRegistrationId(REG_ID)).thenReturn(r);
        when(xpay.queryOrder("openid-1", REG_NO, 1)).thenReturn(remote(1, 0, 3990, "wx1"));
        assertFalse(service.confirmInternal(r, false));
        verify(registrations, never()).completeXpayPayment(anyString(), anyString(), anyLong());
    }

    @Test
    void amountMismatchIsConflictAndNeverEntitles() {
        Map<String, Object> r = row(0, 0);
        when(xpay.queryOrder("openid-1", REG_NO, 1)).thenReturn(remote(2, 100, 3990, "wx1"));
        assertThrows(SelfPlayXpayException.class, () -> service.confirmInternal(r, false));
        verify(registrations, never()).completeXpayPayment(anyString(), anyString(), anyLong());
    }

    @Test
    void paidRemoteEntitlesOnceThenReportsDeliveryAndDuplicateNoticeIsIdempotent() {
        Map<String, Object> r = row(0, 0);
        when(mapper.selectByRegistrationId(REG_ID)).thenReturn(row(1, 0));
        when(xpay.queryOrder("openid-1", REG_NO, 1)).thenReturn(remote(2, 3990, 3990, "wx1"));
        when(registrations.completeXpayPayment(REG_NO, "wx1", 3990L)).thenReturn("SUCCESS");
        JSONObject ok = new JSONObject();
        ok.put("errcode", 0);
        when(xpay.notifyProvideGoods(REG_NO, 1)).thenReturn(ok);
        assertTrue(service.confirmInternal(r, true));
        verify(registrations).completeXpayPayment(REG_NO, "wx1", 3990L);
        verify(mapper).markPaid(eq(7L), eq("wx1"), any(Date.class), eq(0));
        verify(xpay).notifyProvideGoods(REG_NO, 1);
        verify(mapper).markDelivered(7L);
        // 迟到/重复通知：已是已付且发货已确认，不再回查不再重复开通
        assertTrue(service.confirmInternal(row(1, 1), true));
        verify(registrations).completeXpayPayment(anyString(), anyString(), anyLong());
    }

    @Test
    void deliveryFailureLeavesRowForCompensationAndDoesNotUndoEntitlement() {
        Map<String, Object> r = row(0, 0);
        when(mapper.selectByRegistrationId(REG_ID)).thenReturn(row(1, 0));
        when(xpay.queryOrder("openid-1", REG_NO, 1)).thenReturn(remote(2, 3990, 3990, "wx1"));
        when(registrations.completeXpayPayment(REG_NO, "wx1", 3990L)).thenReturn("SUCCESS");
        when(xpay.notifyProvideGoods(REG_NO, 1)).thenThrow(new IllegalStateException("net"));
        assertTrue(service.confirmInternal(r, false));
        verify(mapper, never()).markDelivered(anyLong());
        // 对账：已付未确认发货 → 再补报
        JSONObject ok = new JSONObject();
        ok.put("errcode", 0);
        org.mockito.Mockito.doReturn(ok).when(xpay).notifyProvideGoods(REG_NO, 1);
        service.reconcileOne(row(1, 0));
        verify(mapper).markDelivered(7L);
    }

    @Test
    void paidAfterRegistrationClosedIsRecordedAndAutoRefunded() {
        Map<String, Object> r = row(0, 0);
        when(mapper.selectByRegistrationId(REG_ID)).thenReturn(row(4, 0));
        when(xpay.queryOrder("openid-1", REG_NO, 1)).thenReturn(remote(2, 3990, 3990, "wx1"));
        when(registrations.completeXpayPayment(REG_NO, "wx1", 3990L)).thenReturn("CLOSED");
        when(mapper.markRefundRequested(7L, "XR" + REG_NO)).thenReturn(1);
        JSONObject ok = new JSONObject();
        ok.put("errcode", 0);
        when(xpay.refundOrder(eq("openid-1"), eq(REG_NO), eq("XR" + REG_NO), eq(3990L), eq(3990L), anyString(), eq(1)))
                .thenReturn(ok);
        assertTrue(service.confirmInternal(r, false));
        verify(mapper).markPaidOrphan(eq(7L), eq("wx1"), any(Date.class));
        verify(xpay).refundOrder(eq("openid-1"), eq(REG_NO), eq("XR" + REG_NO), eq(3990L), eq(3990L), anyString(), eq(1));
        verify(mapper, never()).markPaid(anyLong(), anyString(), any(Date.class), anyInt());
    }

    // ---- 推送 ----

    @Test
    void passOrdersAndUnknownOrdersAreNotClaimed() {
        JSONObject passEvent = new JSONObject();
        passEvent.put("Event", "xpay_goods_deliver_notify");
        passEvent.put("OutTradeNo", "PPO2026111300000000000000000000");
        assertFalse(service.handleXpayPush(passEvent));
        JSONObject unknown = new JSONObject();
        unknown.put("Event", "xpay_goods_deliver_notify");
        unknown.put("OutTradeNo", "T20261113ZZZZZZZZ");
        assertFalse(service.handleXpayPush(unknown));
    }

    @Test
    void deliverNoticeTriggersRecheckNotTrust() {
        when(mapper.selectByRegistrationNo(REG_NO)).thenReturn(row(0, 0));
        when(xpay.queryOrder("openid-1", REG_NO, 1)).thenReturn(remote(1, 0, 3990, "wx1"));
        JSONObject event = new JSONObject();
        event.put("Event", "xpay_goods_deliver_notify");
        event.put("OutTradeNo", REG_NO);
        assertFalse(service.handleXpayPush(event));
        verify(registrations, never()).completeXpayPayment(anyString(), anyString(), anyLong());
    }

    // ---- 渠道退款 ----

    @Test
    void channelRefundClosesRegistrationStopsPlayAndSettlesRefundOnce() {
        Map<String, Object> r = row(1, 1);
        CmsRegistration paid = registration();
        paid.setPaymentStatus(2);
        paid.setRegistrationStatus(2);
        when(registrations.selectCmsRegistrationById(REG_ID)).thenReturn(paid);
        when(registrations.applyXpayChannelRefund(REG_NO)).thenReturn(true);
        when(xpay.queryOrder("openid-1", REG_NO, 1)).thenReturn(remote(5, 3990, 3990, "wx1"));
        assertTrue(service.refreshRefund(r));
        verify(registrations).applyXpayChannelRefund(REG_NO);
        verify(mapper).markRefunded(eq(7L), any(Date.class));
        verify(refunds).settleChannelRefundSuccess(REG_ID);
        // 重复通知：已退款行直接幂等
        assertTrue(service.refreshRefund(row(3, 1)));
        verify(registrations).applyXpayChannelRefund(anyString());
    }

    @Test
    void partialChannelRefundIsConflictNotSilentlyClosed() {
        Map<String, Object> r = row(1, 1);
        JSONObject partial = remote(5, 3990, 3990, "wx1");
        partial.getJSONObject("order").put("refund_fee", 1000);
        when(xpay.queryOrder("openid-1", REG_NO, 1)).thenReturn(partial);
        assertThrows(SelfPlayXpayException.class, () -> service.refreshRefund(r));
        verify(registrations, never()).applyXpayChannelRefund(anyString());
    }

    @Test
    void platformRefundIsFullOnlyAndUsesStableRefundNo() {
        when(mapper.selectByRegistrationId(REG_ID)).thenReturn(row(1, 1));
        when(mapper.markRefundRequested(7L, "XR" + REG_NO)).thenReturn(1);
        JSONObject ok = new JSONObject();
        ok.put("errcode", 0);
        when(xpay.refundOrder(eq("openid-1"), eq(REG_NO), eq("XR" + REG_NO), eq(3990L), eq(3990L), anyString(), eq(1)))
                .thenReturn(ok);
        assertEquals(SelfPlayXpayService.RefundOutcome.REJECTED,
                service.submitRefundForPayout(REG_ID, new BigDecimal("10.00")));
        assertEquals(SelfPlayXpayService.RefundOutcome.ACCEPTED,
                service.submitRefundForPayout(REG_ID, new BigDecimal("39.90")));
    }

    // ---- 关单围栏 ----

    @Test
    void closeFenceBlocksPaidAndAllowsJsapiAndConfirmedUnpaid() {
        when(mapper.selectByRegistrationId(1L)).thenReturn(null);
        assertTrue(service.readyToClose(1L));
        when(mapper.selectByRegistrationId(REG_ID)).thenReturn(row(1, 1));
        assertFalse(service.readyToClose(REG_ID));
        when(mapper.selectByRegistrationId(REG_ID)).thenReturn(row(0, 0));
        when(xpay.queryOrder("openid-1", REG_NO, 1)).thenReturn(remote(1, 0, 3990, "wx1"));
        assertTrue(service.readyToClose(REG_ID));
        verify(mapper).markUnpaidReleased(7L);
    }

    @Test
    void closeFenceHoldsWhenRemoteUnreachable() {
        when(mapper.selectByRegistrationId(REG_ID)).thenReturn(row(0, 0));
        when(xpay.queryOrder("openid-1", REG_NO, 1)).thenThrow(new IllegalStateException("net"));
        assertFalse(service.readyToClose(REG_ID));
        verify(mapper, never()).markUnpaidReleased(anyLong());
    }
}
