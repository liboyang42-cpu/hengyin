package com.chengyinhub.business.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CmsTopicGameConfigSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void genericTopicSerializationIsWriteOnlyForGameModuleConfig() throws Exception {
        CmsTopic topic = new CmsTopic();
        topic.setGameModuleConfigJson("{\"roles\":[{\"clue\":\"SECRET\"}]}");
        topic.setGameModuleEnabled(true);

        String json = objectMapper.writeValueAsString(topic);
        CmsTopic incoming = objectMapper.readValue(
                "{\"gameModuleConfigJson\":\"{\\\"schemaVersion\\\":1}\",\"gameModuleEnabled\":true}",
                CmsTopic.class);

        assertFalse(json.contains("gameModuleConfigJson"));
        assertFalse(json.contains("SECRET"));
        assertTrue(json.contains("\"gameModuleEnabled\":true"));
        assertFalse(topic.toString().contains("SECRET"));
        assertEquals("{\"schemaVersion\":1}", incoming.getGameModuleConfigJson());
        assertNull(incoming.getGameModuleEnabled(), "入口开关只能由服务端投影，客户端不得写入");
    }

    /**
     * R22-SEC S2:任何返回裸 CmsTopic 的接口都不得带出 journeyRulesJson / journeyStoryJson
     * (剧情配置含结局条件与 optionalClues,是剧透)。
     *
     * <p>负控:去掉任一 @JsonProperty(access = WRITE_ONLY),本用例立刻红。
     */
    @Test
    void genericTopicSerializationIsWriteOnlyForJourneyConfigs() throws Exception {
        CmsTopic topic = new CmsTopic();
        topic.setJourneyRulesJson("{\"checkEnabled\":true,\"hp\":{\"enabled\":true}}");
        topic.setJourneyStoryJson("{\"ending\":{\"optionalClues\":[\"clue.qiao_receipt\"]}}");

        String json = objectMapper.writeValueAsString(topic);
        CmsTopic incoming = objectMapper.readValue(
                "{\"journeyRulesJson\":\"{\\\"checkEnabled\\\":false}\","
                        + "\"journeyStoryJson\":\"{\\\"schemaVersion\\\":1}\"}",
                CmsTopic.class);

        assertFalse(json.contains("journeyRulesJson"));
        assertFalse(json.contains("journeyStoryJson"));
        assertFalse(json.contains("clue.qiao_receipt"));
        assertEquals("{\"checkEnabled\":false}", incoming.getJourneyRulesJson(), "写入方向必须保留");
        assertEquals("{\"schemaVersion\":1}", incoming.getJourneyStoryJson(), "写入方向必须保留");
    }
}
