package com.chengyinhub.web.health;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import com.chengyinhub.business.mapper.channel.platform.ChannelWebhookMapper;

/**
 * Exposes outbound IM webhook dead-letter queue (DEAD / UNKNOWN) size via {@code /actuator/health}.
 */
@Component("webhookDlq")
public class ChannelWebhookDlqHealthIndicator implements HealthIndicator
{
    private final ChannelWebhookMapper webhooks;
    private final long warnThreshold;

    @Autowired
    public ChannelWebhookDlqHealthIndicator(
            ChannelWebhookMapper webhooks,
            @Value("${chengyin.health.webhook.dlq-warn-threshold:100}") long warnThreshold)
    {
        this.webhooks = webhooks;
        this.warnThreshold = Math.max(1L, warnThreshold);
    }

    @Override
    public Health health()
    {
        try
        {
            Map<String, Long> counts = WebhookHealthSupport.countsByState(webhooks);
            long dlqSize = counts.getOrDefault("DEAD", 0L);
            long unknownCount = counts.getOrDefault("UNKNOWN", 0L);
            return Health.up()
                    .withDetail("dlqSize", dlqSize)
                    .withDetail("unknownCount", unknownCount)
                    .withDetail("warnThreshold", warnThreshold)
                    .withDetail("dlqAlert", dlqSize >= warnThreshold)
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
