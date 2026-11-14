package com.chengyinhub.web.health;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import com.chengyinhub.business.mapper.channel.platform.ChannelWebhookMapper;
import com.chengyinhub.business.security.PiiCryptoService;

/**
 * Exposes outbound IM webhook delivery queue depth and dispatcher crypto readiness via {@code /actuator/health}.
 */
@Component("webhookQueue")
public class ChannelWebhookQueueHealthIndicator implements HealthIndicator
{
    private final ChannelWebhookMapper webhooks;
    private final PiiCryptoService crypto;
    private final boolean dispatchEnabled;
    private final long warnThreshold;

    @Autowired
    public ChannelWebhookQueueHealthIndicator(
            ChannelWebhookMapper webhooks,
            PiiCryptoService crypto,
            @Value("${chengyin.im.webhook.dispatch.enabled:false}") boolean dispatchEnabled,
            @Value("${chengyin.health.webhook.queue-warn-threshold:1000}") long warnThreshold)
    {
        this.webhooks = webhooks;
        this.crypto = crypto;
        this.dispatchEnabled = dispatchEnabled;
        this.warnThreshold = Math.max(1L, warnThreshold);
    }

    @Override
    public Health health()
    {
        try
        {
            Map<String, Long> counts = WebhookHealthSupport.countsByState(webhooks);
            long pending = counts.getOrDefault("PENDING", 0L);
            long inflight = counts.getOrDefault("INFLIGHT", 0L);
            long failed = counts.getOrDefault("FAILED", 0L);
            long queueDepth = pending + inflight;
            boolean cryptoReady = crypto != null && crypto.isEnabled();

            Health.Builder builder = (dispatchEnabled && !cryptoReady)
                    ? Health.down().withDetail("reason", "PII_CRYPTO_KEY_MISSING")
                    : Health.up();
            return builder
                    .withDetail("dispatchEnabled", dispatchEnabled)
                    .withDetail("cryptoReady", cryptoReady)
                    .withDetail("queueDepth", queueDepth)
                    .withDetail("pending", pending)
                    .withDetail("inflight", inflight)
                    .withDetail("failedRetryWaiting", failed)
                    .withDetail("warnThreshold", warnThreshold)
                    .withDetail("backlogWarning", queueDepth >= warnThreshold)
                    .build();
        }
        catch (Exception ex)
        {
            return Health.down()
                    .withDetail("error", ex.getClass().getSimpleName())
                    .build();
        }
    }
}
