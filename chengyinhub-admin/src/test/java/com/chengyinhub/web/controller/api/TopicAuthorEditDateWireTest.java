package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.framework.config.ApplicationConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ResourceLock("java.util.TimeZone.default")
class TopicAuthorEditDateWireTest {
    private static Date china(String value) {
        return Date.from(LocalDateTime.parse(value.replace(' ', 'T'))
                .atZone(ZoneId.of("Asia/Shanghai")).toInstant());
    }

    private static ObjectMapper httpMapper() {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new ApplicationConfig().jacksonObjectMapperCustomization().customize(builder);
        return builder.build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Asia/Shanghai", "America/Los_Angeles"})
    void authorHttpWireRetainsCanonicalTimesAndTemplateIdsWithoutChangingPublicDates(String zone) throws Exception {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            ObjectMapper mapper = httpMapper();
            for (String start : List.of("2026-10-01 00:00:00", "2026-10-29 10:37:45", "2026-10-31 23:59:59")) {
                CmsTopic topic = new CmsTopic();
                topic.setId(7L); topic.setMemberId(11L); topic.setName("author-owned route");
                topic.setStartDate(china(start)); topic.setEndDate(china("2026-11-01 23:59:59"));
                topic.setJourneyRulesJson("{\"natAuto\":false}");
                OmsTicket ticket = new OmsTicket();
                ticket.setStartTime(china(start)); ticket.setEndTime(topic.getEndDate());
                ticket.setSaleStartTime(china("2026-09-30 23:59:59"));
                ticket.setSaleEndTime(china("2026-11-01 00:00:00"));
                CmsTopicNode node = new CmsTopicNode();
                node.setId(19L); node.setTemplateId(9007199254740993L);
                CmsTopicChapter chapter = new CmsTopicChapter();
                chapter.setId(13L); chapter.setCmsTopicNodeList(List.of(node));
                Map<String, Object> original = Map.of("topic", topic, "tickets", List.of(ticket),
                        "chapters", List.of(chapter), "isDraft", true, "collaboratorIds", List.of(17L));
                ApiTopicController controller = spy(new ApiTopicController());
                ReflectionTestUtils.setField(controller, "responseObjectMapper", mapper);
                doAnswer(invocation -> AjaxResult.success(original)).when(controller).topicEditDetail("7", "");

                AjaxResult result = controller.authorEditDetail("7", "");
                JsonNode data = mapper.readTree(mapper.writeValueAsString(result)).path("data");
                assertEquals(200, result.get("code"));
                assertEquals(start, data.path("topic").path("startDate").asText());
                assertEquals("2026-11-01 23:59:59", data.path("topic").path("endDate").asText());
                JsonNode wireTicket = data.path("tickets").get(0);
                assertEquals(start, wireTicket.path("startTime").asText());
                assertEquals("2026-11-01 23:59:59", wireTicket.path("endTime").asText());
                assertEquals("2026-09-30 23:59:59", wireTicket.path("saleStartTime").asText());
                assertEquals("2026-11-01 00:00:00", wireTicket.path("saleEndTime").asText());
                JsonNode templateId = data.path("chapters").get(0).path("cmsTopicNodeList").get(0).path("templateId");
                assertTrue(templateId.isTextual()); assertEquals("9007199254740993", templateId.asText());
                assertEquals(11L, data.path("topic").path("memberId").asLong());
                assertFalse(data.path("topic").has("journeyRulesJson"));
                assertEquals("{\"natAuto\":false}", topic.getJourneyRulesJson());
                assertTrue(data.path("isDraft").asBoolean());
                assertEquals(17L, data.path("collaboratorIds").get(0).asLong());
                assertSame(topic, original.get("topic"));
                // Legacy edit-detail and public entity retain their date-only shape and numeric templateId.
                JsonNode legacy = mapper.readTree(mapper.writeValueAsString(controller.topicEditDetail("7", ""))).path("data");
                assertEquals(start.substring(0, 10), legacy.path("topic").path("startDate").asText());
                assertEquals("2026-11-01", legacy.path("topic").path("endDate").asText());
                assertTrue(legacy.path("chapters").get(0).path("cmsTopicNodeList").get(0).path("templateId").isIntegralNumber());
            }
        } finally { TimeZone.setDefault(previous); }
    }

    @Test
    void absentDatesRemainNullInAuthorProjection() throws Exception {
        CmsTopic topic = new CmsTopic(); topic.setId(7L);
        ObjectMapper mapper = httpMapper();
        ApiTopicController controller = spy(new ApiTopicController());
        ReflectionTestUtils.setField(controller, "responseObjectMapper", mapper);
        doReturn(AjaxResult.success(Map.of("topic", topic))).when(controller).topicEditDetail("7", "");
        JsonNode data = mapper.readTree(mapper.writeValueAsString(controller.authorEditDetail("7", ""))).path("data");
        assertTrue(data.path("topic").path("startDate").isNull());
        assertTrue(data.path("topic").path("endDate").isNull());
    }

    @Test
    void normalOwnershipRejectionDoesNotExposeAnAuthorProjection() {
        ApiTopicController controller = spy(new ApiTopicController());
        doReturn(11L).when(controller).getAppUserId();
        ICmsTopicService topics = mock(ICmsTopicService.class);
        CmsTopic foreign = new CmsTopic(); foreign.setId(7L); foreign.setMemberId(12L);
        when(topics.selectCmsTopicById(7L)).thenReturn(foreign);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topics);
        // No mapper injected: rejection must return before any author conversion.
        AjaxResult denied = controller.authorEditDetail("7", "");
        assertNotEquals(200, denied.get("code"));
        assertEquals("无权编辑该主题", denied.get("msg"));
        assertNull(denied.get("data"));
        verify(topics, never()).selectTopicIsDraft(anyLong());
    }
}
