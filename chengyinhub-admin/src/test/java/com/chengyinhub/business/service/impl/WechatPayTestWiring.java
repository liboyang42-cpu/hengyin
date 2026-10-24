package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.*;
import java.util.Map;
import java.util.WeakHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

/** Only fixture composition: keeps the original SDK doubles and real owner/transaction test bodies. */
public final class WechatPayTestWiring {
    private WechatPayTestWiring() { }
    private static final Map<WechatPayServiceImpl, Map<String, Object>> owners = new WeakHashMap<>();

    public static WechatPayServiceImpl facade() {
        WechatPayServiceImpl facade = new WechatPayServiceImpl();
        sdk(facade);
        return facade;
    }

    private static WechatPaymentSdkGateway sdk(WechatPayServiceImpl facade) {
        WechatPaymentSdkGateway gateway = (WechatPaymentSdkGateway) ReflectionTestUtils.getField(facade, "gateway");
        if (gateway == null) {
            gateway = new WechatPaymentSdkGateway();
            WechatPaymentIntentStore intents = new WechatPaymentIntentStore();
            ReflectionTestUtils.setField(intents, "businessOwner", new JdbcWechatPaymentOrderOwner());
            ReflectionTestUtils.setField(gateway, "intents", intents);
            WechatVerifiedFactTestSupport.installUnitFacts(gateway);
            ReflectionTestUtils.setField(facade, "gateway", gateway);
        }
        return gateway;
    }

    @SuppressWarnings("unchecked")
    public static void setField(Object target, String name, Object value) {
        if (!(target instanceof WechatPayServiceImpl facade)) {
            ReflectionTestUtils.setField(target, name, value); return;
        }
        if (java.util.Set.of("registrationService", "merchantCommerceService", "coopInviteService",
                "clubMembershipServiceProvider", "refundBizService").contains(name)) {
            synchronized (owners) {
                Map<String, Object> configured = owners.computeIfAbsent(facade, ignored -> new java.util.HashMap<>());
                configured.put(name, value);
                ObjectProvider<IClubMembershipService> club = (ObjectProvider<IClubMembershipService>) configured.get("clubMembershipServiceProvider");
                ReflectionTestUtils.setField(facade, "callbacks", new WechatPaymentCallbackRouter(
                        club == null ? null : club.getIfAvailable(),
                        (IMerchantCommerceService) configured.get("merchantCommerceService"),
                        (ICoopInviteService) configured.get("coopInviteService"),
                        (ICmsRegistrationService) configured.get("registrationService"),
                        (IRefundService) configured.get("refundBizService")));
            }
            return;
        }
        if ("alarmService".equals(name)) { ReflectionTestUtils.setField(facade, name, value); return; }
        Object actual = fieldTarget(facade, name);
        ReflectionTestUtils.setField(actual, name, value);
        if (actual instanceof WechatPaymentSdkGateway gateway && "verifiedFacts".equals(name)) {
            ReflectionTestUtils.setField(ReflectionTestUtils.getField(gateway,"intents"),"verifiedFacts",value);
        }
        if (actual instanceof WechatPaymentSdkGateway gateway
                && java.util.Set.of("wxPayConfig","jsapiService","refundService").contains(name)) {
            WechatVerifiedFactTestSupport.refreshSdkBinding(gateway);
        }
        if ("paymentIntentJdbc".equals(name)) {
            ReflectionTestUtils.setField(ReflectionTestUtils.getField(actual, "businessOwner"), name, value);
            ReflectionTestUtils.setField(ReflectionTestUtils.getField(sdk(facade),"verifiedFacts"),"factIntentJdbc",value);
        }
    }

    public static Object getField(Object target, String name) {
        if (target instanceof WechatPayServiceImpl facade && java.util.Set.of("registrationService",
                "merchantCommerceService", "coopInviteService", "clubMembershipServiceProvider", "refundBizService").contains(name)) {
            synchronized (owners) { return owners.getOrDefault(facade, java.util.Collections.emptyMap()).get(name); }
        }
        return ReflectionTestUtils.getField(target instanceof WechatPayServiceImpl facade ? fieldTarget(facade, name) : target, name);
    }

    public static <T> T invokeMethod(Object target, String name, Object... arguments) {
        if (target instanceof WechatPayServiceImpl facade && !"freezePaymentIntent".equals(name)) target = sdk(facade);
        return ReflectionTestUtils.invokeMethod(target, name, arguments);
    }

    private static Object fieldTarget(WechatPayServiceImpl facade, String name) {
        if ("paymentIntentJdbc".equals(name) || "paymentIntentTransactions".equals(name))
            return ReflectionTestUtils.getField(sdk(facade), "intents");
        if ("gateway".equals(name) || "callbacks".equals(name) || "alarmService".equals(name)) return facade;
        return sdk(facade);
    }
}
