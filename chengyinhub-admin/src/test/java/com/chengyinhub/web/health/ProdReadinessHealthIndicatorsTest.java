package com.chengyinhub.web.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import com.chengyinhub.business.externalgame.ExternalGameSigner;
import com.chengyinhub.business.mapper.channel.platform.ChannelWebhookMapper;
import com.chengyinhub.business.payout.CreatorPayoutProvider;
import com.chengyinhub.business.payout.DisabledCreatorPayoutProvider;
import com.chengyinhub.business.payout.PayoutSwitches;
import com.chengyinhub.business.payout.WechatEcommercePayGateway;
import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.service.rewardpledge.CashPayoutSwitch;

class ProdReadinessHealthIndicatorsTest
{
    @Test
    void webhookQueueHealthReportsDepthAndFailsWhenDispatcherEnabledWithoutCryptoKey()
    {
        ChannelWebhookMapper mapper = mock(ChannelWebhookMapper.class);
        when(mapper.countDeliveriesByState()).thenReturn(List.of(
                Map.of("state", "PENDING", "cnt", 12),
                Map.of("state", "INFLIGHT", "cnt", 3),
                Map.of("state", "FAILED", "cnt", 4),
                Map.of("state", "DEAD", "cnt", 2)));
        PiiCryptoService noKey = new PiiCryptoService("", "");

        ChannelWebhookQueueHealthIndicator disabled = new ChannelWebhookQueueHealthIndicator(mapper, noKey, false, 10);
        Health up = disabled.health();
        assertEquals(Status.UP, up.getStatus());
        assertEquals(15L, up.getDetails().get("queueDepth"));
        assertEquals(12L, up.getDetails().get("pending"));
        assertEquals(3L, up.getDetails().get("inflight"));
        assertEquals(4L, up.getDetails().get("failedRetryWaiting"));
        assertEquals(true, up.getDetails().get("backlogWarning"));

        ChannelWebhookQueueHealthIndicator misconfigured = new ChannelWebhookQueueHealthIndicator(mapper, noKey, true, 100);
        Health down = misconfigured.health();
        assertEquals(Status.DOWN, down.getStatus());
        assertEquals("PII_CRYPTO_KEY_MISSING", down.getDetails().get("reason"));
    }

    @Test
    void webhookQueueAndDlqHealthReturnDownOnMapperFailureWithoutLeakingMessage()
    {
        ChannelWebhookMapper mapper = mock(ChannelWebhookMapper.class);
        when(mapper.countDeliveriesByState()).thenThrow(new RuntimeException("secret-url-jdbc-token"));
        PiiCryptoService noKey = new PiiCryptoService("", "");

        Health qDown = new ChannelWebhookQueueHealthIndicator(mapper, noKey, false, 100).health();
        assertEquals(Status.DOWN, qDown.getStatus());
        assertEquals("RuntimeException", qDown.getDetails().get("error"));
        assertFalse(qDown.getDetails().toString().contains("secret-url-jdbc-token"));

        Health dlqDown = new ChannelWebhookDlqHealthIndicator(mapper, 50).health();
        assertEquals(Status.DOWN, dlqDown.getStatus());
        assertEquals("RuntimeException", dlqDown.getDetails().get("error"));
    }

    @Test
    void webhookDlqHealthAggregatesDeadAndUnknownCounts()
    {
        ChannelWebhookMapper mapper = mock(ChannelWebhookMapper.class);
        when(mapper.countDeliveriesByState()).thenReturn(List.of(
                Map.of("state", "DEAD", "cnt", 5),
                Map.of("state", "UNKNOWN", "cnt", 2)));

        Health h = new ChannelWebhookDlqHealthIndicator(mapper, 5).health();
        assertEquals(Status.UP, h.getStatus());
        assertEquals(5L, h.getDetails().get("dlqSize"));
        assertEquals(2L, h.getDetails().get("unknownCount"));
        assertEquals(true, h.getDetails().get("dlqAlert"));
    }

    @Test
    void externalGameSigningKeyHealthReflectsPresenceAndRequirement() throws Exception
    {
        ExternalGameSigner missing = new ExternalGameSigner("", "ed25519-1");
        Health optional = new ExternalGameSigningKeyHealthIndicator(missing, false).health();
        assertEquals(Status.UP, optional.getStatus());
        assertEquals(false, optional.getDetails().get("configured"));

        Health requiredMissing = new ExternalGameSigningKeyHealthIndicator(missing, true).health();
        assertEquals(Status.DOWN, requiredMissing.getStatus());
        assertEquals("SIGNING_KEY_MISSING", requiredMissing.getDetails().get("reason"));

        KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
        KeyPair pair = gen.generateKeyPair();
        String pkcs8Base64 = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        ExternalGameSigner configured = new ExternalGameSigner(pkcs8Base64, "ed25519-2026");

        Health ready = new ExternalGameSigningKeyHealthIndicator(configured, true).health();
        assertEquals(Status.UP, ready.getStatus());
        assertEquals(true, ready.getDetails().get("configured"));
        assertEquals("ed25519-2026", ready.getDetails().get("keyId"));
        assertEquals("Ed25519", ready.getDetails().get("algorithm"));
        assertFalse(ready.getDetails().toString().contains(pkcs8Base64));
    }

    @Test
    void payoutProviderHealthValidatesSwitchesProvidersAndSecretRedaction()
    {
        CreatorPayoutProvider disabledProvider = new DisabledCreatorPayoutProvider();
        CashPayoutSwitch cashOff = CashPayoutSwitch.off();

        Health allOff = new PayoutProviderHealthIndicator(
                PayoutSwitches.allOff(),
                List.of(disabledProvider),
                cashOff,
                new WechatEcommercePayGateway(PayoutSwitches.allOff(), "", "", "", "", ""))
                .health();
        assertEquals(Status.UP, allOff.getStatus());
        assertEquals(false, allOff.getDetails().get("creatorEnabled"));
        assertEquals(false, allOff.getDetails().get("ecommerceEnabled"));

        PayoutSwitches missingCreatorSwitches = new PayoutSwitches(false, false, false, "", "", "", 30, true, "bank");
        Health missingCreator = new PayoutProviderHealthIndicator(
                missingCreatorSwitches, List.of(disabledProvider), cashOff, null).health();
        assertEquals(Status.DOWN, missingCreator.getStatus());
        assertEquals("CREATOR_PAYOUT_PROVIDER_MISSING", missingCreator.getDetails().get("reason"));

        PayoutSwitches missingEcomCreds = new PayoutSwitches(true, false, false, "1900009999", "wx1234", "", 30, false, "none");
        Health badEcom = new PayoutProviderHealthIndicator(
                missingEcomCreds,
                List.of(disabledProvider),
                cashOff,
                new WechatEcommercePayGateway(missingEcomCreds, "/secret/apiclient_key.pem", "", "", "", ""))
                .health();
        assertEquals(Status.DOWN, badEcom.getStatus());
        assertEquals("ECOMMERCE_CREDENTIAL_MISSING", badEcom.getDetails().get("reason"));
        assertFalse(badEcom.getDetails().toString().contains("1900009999"));
        assertFalse(badEcom.getDetails().toString().contains("/secret/apiclient_key.pem"));

        PayoutSwitches validEcom = new PayoutSwitches(true, true, true, "1900009999", "wx1234", "1900008888", 30, true, "wechat");
        CreatorPayoutProvider mockWechat = mock(CreatorPayoutProvider.class);
        when(mockWechat.code()).thenReturn("wechat");
        WechatEcommercePayGateway configuredGateway = new WechatEcommercePayGateway(
                validEcom, "/secret/apiclient_key.pem", "SERIAL-123", "v3key-secret-value", "https://cb", "https://rf");
        Health healthy = new PayoutProviderHealthIndicator(
                validEcom, List.of(disabledProvider, mockWechat), cashOff, configuredGateway).health();
        assertEquals(Status.UP, healthy.getStatus());
        assertEquals(true, healthy.getDetails().get("creatorProviderResolved"));
        assertEquals(true, healthy.getDetails().get("ecommerceCredentialsConfigured"));
        String serialized = healthy.getDetails().toString();
        assertTrue(serialized.contains("wechat"));
        assertFalse(serialized.contains("v3key-secret-value"));
        assertFalse(serialized.contains("SERIAL-123"));
        assertFalse(serialized.contains("1900009999"));
    }
}
