package com.chengyinhub.business.payout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.pay.java.core.cipher.PrivacyEncryptor;
import com.wechat.pay.java.core.http.HttpClient;
import com.wechat.pay.java.core.http.HttpRequest;
import com.wechat.pay.java.core.http.HttpResponse;
import com.wechat.pay.java.core.http.JsonRequestBody;
import com.wechat.pay.java.core.util.GsonUtil;
import com.wechat.pay.java.service.ecommerceprofitsharing.EcommerceProfitSharingService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WechatEcommercePersonalReceiverGatewayTest {
    @Test
    void bindingSendsPlatformScopedPersonalIdentityAndRequiresExactAcceptance() throws Exception {
        Fixture f = new Fixture();
        f.response = "{\"type\":\"PERSONAL_OPENID\",\"account\":\"verified-openid\"}";
        f.gateway.addPersonalReceiver("verified-openid", "wx-platform");
        assertTrue(f.requests.getFirst().getUrl().getPath().endsWith("/ecommerce/profitsharing/receivers/add"));
        JsonNode body = f.body();
        assertEquals("wx-platform", body.path("appid").asText());
        assertEquals("PERSONAL_OPENID", body.path("type").asText());
        assertEquals("verified-openid", body.path("account").asText());
        assertEquals("OTHERS", body.path("relation_type").asText());
        assertFalse(body.has("name"));

        f.response = "{\"type\":\"MERCHANT_ID\",\"account\":\"verified-openid\"}";
        assertThrows(IllegalStateException.class, () -> f.gateway.addPersonalReceiver("verified-openid", "wx-platform"));
        f.response = "{\"type\":\"PERSONAL_OPENID\",\"account\":\"different-owner\"}";
        assertThrows(IllegalStateException.class, () -> f.gateway.addPersonalReceiver("verified-openid", "wx-platform"));
        f.response = "null";
        assertThrows(IllegalStateException.class, () -> f.gateway.addPersonalReceiver("verified-openid", "wx-platform"));
        f.failure = new IllegalStateException("provider rejected receiver");
        assertSame(f.failure, assertThrows(IllegalStateException.class,
                () -> f.gateway.addPersonalReceiver("verified-openid", "wx-platform")));
    }

    @Test
    void bindingAndSharingRejectWrongAppidOrClosedSwitchBeforeHttp() throws Exception {
        Fixture f = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> f.gateway.addPersonalReceiver("verified-openid", "wx-client"));
        assertThrows(IllegalArgumentException.class, () -> f.gateway.addPersonalReceiver(" ", "wx-platform"));
        assertThrows(IllegalArgumentException.class, () -> f.gateway.createProfitSharing("seller", "tx", "share",
                List.of(new ProfitSharePlanner.Receiver("PERSONAL_OPENID", "verified-openid", "wx-client", 10)), false));
        var finishUnsupported = assertThrows(IllegalArgumentException.class,
                () -> f.gateway.createProfitSharing("seller", "tx", "share", List.of(personal()), true));
        assertEquals("PERSONAL_PROFITSHARING_FINISH_UNSUPPORTED", finishUnsupported.getMessage());
        when(f.switches.personalClubEnabled()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> f.gateway.addPersonalReceiver("verified-openid", "wx-platform"));
        assertThrows(IllegalStateException.class, () -> f.gateway.createProfitSharing("seller", "tx", "share",
                List.of(personal()), false));
        assertTrue(f.requests.isEmpty());
    }

    @Test
    void sharingUsesBothTypesAndParsesPersonalScopeWithoutMerchantCoercion() throws Exception {
        Fixture f = new Fixture();
        f.response = "{\"status\":\"FINISHED\",\"order_id\":\"wx-share\",\"receivers\":["
                + "{\"type\":\"PERSONAL_OPENID\",\"receiver_account\":\"verified-openid\",\"amount\":10,\"result\":\"SUCCESS\"},"
                + "{\"receiver_mchid\":\"1900000002\",\"amount\":20,\"result\":\"SUCCESS\"}]}";
        var created = f.gateway.createProfitSharing("seller", "tx", "share",
                List.of(personal(), new ProfitSharePlanner.Receiver("1900000002", 20)), false);
        JsonNode body = f.body();
        assertEquals("wx-platform", body.path("appid").asText());
        assertEquals("PERSONAL_OPENID", body.path("receivers").get(0).path("type").asText());
        assertEquals("verified-openid", body.path("receivers").get(0).path("receiver_account").asText());
        assertFalse(body.path("receivers").get(0).has("receiver_mchid"));
        assertEquals("MERCHANT_ID", body.path("receivers").get(1).path("type").asText());
        assertEquals("1900000002", body.path("receivers").get(1).path("receiver_account").asText());
        var personal = created.receivers().getFirst();
        assertEquals("PERSONAL_OPENID", personal.receiverType());
        assertEquals("verified-openid", personal.account());
        assertEquals("wx-platform", personal.appid());
        assertEquals("MERCHANT_ID", created.receivers().get(1).receiverType());
        assertNull(created.receivers().get(1).appid());
        assertEquals("1900000002", created.receivers().get(1).receiverMchid());
        assertEquals(created.receivers(), f.gateway.queryProfitSharing("seller", "tx", "share").receivers());
    }

    @Test
    void unknownOrAmbiguousQueryIdentityIsNotAssumedToBeMerchant() throws Exception {
        Fixture f = new Fixture();
        f.response = "{\"status\":\"FINISHED\",\"receivers\":[{\"receiver_account\":\"unknown\",\"amount\":10}]}";
        assertThrows(IllegalStateException.class, () -> f.gateway.queryProfitSharing("seller", "tx", "share"));
        f.response = "{\"status\":\"FINISHED\",\"receivers\":[{\"type\":\"PERSONAL_SUB_OPENID\",\"receiver_account\":\"unknown\"}]}";
        assertThrows(IllegalStateException.class, () -> f.gateway.queryProfitSharing("seller", "tx", "share"));
    }

    @Test
    void personalReturnsFailClosedAndMerchantReturnPayloadRemainsCompatible() throws Exception {
        Fixture f = new Fixture();
        var unsupported = assertThrows(UnsupportedOperationException.class,
                () -> f.gateway.createReturn("seller", "share", "return", personal(), 10, "refund"));
        assertEquals("PERSONAL_PROFITSHARING_RETURN_UNSUPPORTED", unsupported.getMessage());
        assertThrows(UnsupportedOperationException.class,
                () -> f.gateway.queryReturn("seller", "share", "return", personal()));
        assertTrue(f.requests.isEmpty());
        f.response = "{\"result\":\"SUCCESS\",\"return_no\":\"wx-return\",\"return_mchid\":\"1900000002\"}";
        assertEquals("SUCCESS", f.gateway.createReturn("seller", "share", "return", "1900000002", 20, "refund").result());
        JsonNode body = f.body();
        assertEquals("1900000002", body.path("return_mchid").asText());
        assertEquals(20, body.path("amount").asInt());
        assertFalse(body.has("return_openid"));
        var merchant = new ProfitSharePlanner.Receiver("1900000002", 20);
        assertEquals("SUCCESS", f.gateway.queryReturn("seller", "share", "return", merchant).result());
        f.response = "{\"result\":\"SUCCESS\",\"return_mchid\":\"different-merchant\"}";
        assertThrows(IllegalStateException.class,
                () -> f.gateway.queryReturn("seller", "share", "return", merchant));
    }

    private static ProfitSharePlanner.Receiver personal() {
        return new ProfitSharePlanner.Receiver("PERSONAL_OPENID", "verified-openid", "wx-platform", 10);
    }

    private static final class Fixture {
        final PayoutSwitches switches = mock(PayoutSwitches.class);
        final HttpClient http = mock(HttpClient.class);
        final List<HttpRequest> requests = new ArrayList<>();
        final WechatEcommercePayGateway gateway;
        String response;
        RuntimeException failure;

        @SuppressWarnings({"unchecked", "rawtypes"})
        Fixture() throws Exception {
            when(switches.ecommerceEnabled()).thenReturn(true);
            when(switches.personalClubEnabled()).thenReturn(true);
            when(switches.spAppid()).thenReturn("wx-platform");
            PrivacyEncryptor encryptor = mock(PrivacyEncryptor.class);
            when(encryptor.getWechatpaySerial()).thenReturn("fixture-serial");
            EcommerceProfitSharingService sharing = new EcommerceProfitSharingService.Builder()
                    .httpClient(http).encryptor(encryptor).build();
            doAnswer(invocation -> {
                requests.add(invocation.getArgument(0));
                if (failure != null) throw failure;
                Class<?> responseType = invocation.getArgument(1);
                HttpResponse result = mock(HttpResponse.class);
                when(result.getServiceResponse()).thenReturn(GsonUtil.getGson().fromJson(response, responseType));
                return result;
            }).when(http).execute(any(HttpRequest.class), any(Class.class));
            gateway = new WechatEcommercePayGateway(switches, "", "", "", "", "");
            Class<?> clientsType = Class.forName(WechatEcommercePayGateway.class.getName() + "$Clients");
            Constructor<?> constructor = clientsType.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            ReflectionTestUtils.setField(gateway, "clients", constructor.newInstance(null, http, null, sharing, null, null, null));
        }

        JsonNode body() throws Exception {
            return new ObjectMapper().readTree(((JsonRequestBody) requests.getLast().getBody()).getBody());
        }
    }
}
