package com.chengyinhub.business.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 第十一轮 g3 第 1 项(j3 F9 / F12):通行证只有一个有效期;核销时间带钟点下发。 */
class R11G3OrderTruthTest {

    private static Date at(String text) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return f.parse(text);
    }

    @Test
    void passExpiryStopsAtTopicFulfilmentEnd() throws Exception {
        CmsTopic topic = new CmsTopic();
        topic.setEndDate(at("2026-12-04 00:00:00"));
        Date now = at("2026-11-03 23:10:00");
        assertEquals(at("2026-12-05 00:00:00"), CmsRegistrationServiceImpl.selfPlayPassExpiry(topic, now));
    }

    @Test
    void passExpiryKeepsNinetyDaysWhenTopicEndsLaterOrHasNoEnd() throws Exception {
        Date now = at("2026-11-03 23:10:00");
        CmsTopic longTopic = new CmsTopic();
        longTopic.setEndDate(at("2027-06-30 00:00:00"));
        assertEquals(at("2027-02-01 23:10:00"), CmsRegistrationServiceImpl.selfPlayPassExpiry(longTopic, now));
        assertEquals(at("2027-02-01 23:10:00"), CmsRegistrationServiceImpl.selfPlayPassExpiry(new CmsTopic(), now));
    }

    @Test
    void topicTotalTimeIsStoredInSeconds() {
        // R11 j3 F7:节点 nodeTime 是分钟,cms_topic.total_time 是秒;27 站 × 10 分钟 = 16200 秒,不是 270
        java.util.List<com.chengyinhub.business.domain.dto.NodeDTO> nodes = new java.util.ArrayList<>();
        for (int i = 0; i < 27; i++) {
            com.chengyinhub.business.domain.dto.NodeDTO n = new com.chengyinhub.business.domain.dto.NodeDTO();
            n.setNodeTime(10);
            nodes.add(n);
        }
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        assertEquals(Long.valueOf(270), service.calculateTotalTime(nodes));
        assertEquals(Long.valueOf(16200), service.totalTimeSeconds(nodes));
    }

    @Test
    void verificationTimeKeepsItsClockOnTheWire() throws Exception {
        CmsRegistration r = new CmsRegistration();
        r.setVerificationTime(at("2026-11-04 23:40:00"));
        String json = new ObjectMapper().setTimeZone(TimeZone.getTimeZone("Asia/Shanghai")).writeValueAsString(r);
        assertTrue(json.contains("\"verificationTime\":\"2026-11-04 23:40:00\""), json);
    }
}
