package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.domain.dto.CmsTopicEditDTO;
import com.chengyinhub.framework.config.ApplicationConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Instant;
import java.util.Date;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistrationTimeContractTest {
    @Test
    void dateOnlyTopicInputAndCouponDisplayKeepChinaCalendarAcrossServerTimeZones() throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : new String[]{"America/Los_Angeles", "Asia/Shanghai", "UTC"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
                new ApplicationConfig().jacksonObjectMapperCustomization().customize(builder);
                ObjectMapper mapper = builder.build();
                CmsTopicEditDTO topic = mapper.readValue("{\"startDate\":\"2026-09-18\",\"endDate\":\"2026-09-19\"}", CmsTopicEditDTO.class);
                assertEquals(Instant.parse("2026-09-17T16:00:00Z"), topic.getStartDate().toInstant(), zone);
                JsonNode topicJson = mapper.readTree(mapper.writeValueAsString(topic));
                assertEquals("2026-09-18", topicJson.path("startDate").asText(), zone);
                assertEquals("2026-09-19", topicJson.path("endDate").asText(), zone);
                SmsCouponHistory coupon = new SmsCouponHistory();
                coupon.setStartTime(Date.from(Instant.parse("2026-09-17T16:00:01Z")));
                coupon.setEndTime(Date.from(Instant.parse("2026-09-18T15:59:59Z")));
                JsonNode couponJson = mapper.readTree(mapper.writeValueAsString(coupon));
                assertEquals("2026-09-18", couponJson.path("startTime").asText(), zone);
                assertEquals("2026-09-18", couponJson.path("endTime").asText(), zone);
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void orderInstantsKeepChinaClockAndSecondsAcrossServerTimeZones() throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : new String[]{"America/Los_Angeles", "Asia/Shanghai", "UTC"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
                new ApplicationConfig().jacksonObjectMapperCustomization().customize(builder);
                ObjectMapper mapper = builder.build();
                Date paid = Date.from(Instant.parse("2026-09-13T09:27:06Z"));
                Date start = Date.from(Instant.parse("2026-09-17T18:26:14Z"));
                CmsActivity activity = new CmsActivity();
                activity.setStartDate(start);
                CmsRegistration order = new CmsRegistration();
                order.setCmsActivity(activity);
                order.setCreateTime(paid);
                order.setPaymentTime(paid);
                JsonNode json = mapper.readTree(mapper.writeValueAsString(order));
                assertEquals("2026-09-18 02:26:14", json.path("cmsActivity").path("startDate").asText(), zone);
                assertEquals("2026-09-13 17:27:06", json.path("createTime").asText(), zone);
                assertEquals("2026-09-13 17:27:06", json.path("paymentTime").asText(), zone);
                CmsRegistration reread = mapper.readValue(mapper.writeValueAsString(order), CmsRegistration.class);
                assertEquals(paid, reread.getPaymentTime(), zone);
                assertEquals(start, reread.getCmsActivity().getStartDate(), zone);
                order.setPaymentTime(null);
                assertTrue(mapper.readTree(mapper.writeValueAsString(order)).path("paymentTime").isNull(), zone);
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
