package com.chengyinhub.web.health;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.chengyinhub.business.mapper.channel.platform.ChannelWebhookMapper;

/** Shared state-count aggregation for webhook queue and DLQ Actuator health indicators. */
final class WebhookHealthSupport
{
    private WebhookHealthSupport()
    {
    }

    static Map<String, Long> countsByState(ChannelWebhookMapper mapper)
    {
        Map<String, Long> counts = new LinkedHashMap<>();
        List<Map<String, Object>> rows = mapper.countDeliveriesByState();
        if (rows == null) return counts;
        for (Map<String, Object> row : rows)
        {
            if (row == null) continue;
            Object rawState = row.get("state");
            if (rawState == null) continue;
            Object rawCnt = row.get("cnt");
            long cnt = rawCnt instanceof Number num ? num.longValue() : 0L;
            counts.merge(String.valueOf(rawState), cnt, Long::sum);
        }
        return counts;
    }
}
