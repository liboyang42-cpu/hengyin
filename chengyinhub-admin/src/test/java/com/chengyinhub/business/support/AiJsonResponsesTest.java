package com.chengyinhub.business.support;

import com.alibaba.fastjson2.JSONException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** B:P2-041:AI JSON 解析只留一份实现,两个 AI 服务都走它。 */
class AiJsonResponsesTest
{
    @Test
    void stripsMarkdownFenceWithOrWithoutLanguageTag()
    {
        assertEquals("{\"a\":1}", AiJsonResponses.stripJsonFence("```json\n{\"a\":1}\n```"));
        assertEquals("{\"a\":1}", AiJsonResponses.stripJsonFence("  ```\n{\"a\":1}\n```  "));
        assertEquals("{\"a\":1}", AiJsonResponses.stripJsonFence("```json\n{\"a\":1}"));
        assertEquals("{\"a\":1}", AiJsonResponses.stripJsonFence(" {\"a\":1} "));
        assertEquals("```", AiJsonResponses.stripJsonFence("```"));
        assertNull(AiJsonResponses.stripJsonFence(null));
    }

    @Test
    void parsesFencedJsonAndRejectsEmptyAsJsonException()
    {
        assertEquals(1, AiJsonResponses.parseAiJson("```json\n{\"a\":1}\n```").getIntValue("a"));
        assertThrows(JSONException.class, () -> AiJsonResponses.parseAiJson(null));
        assertThrows(JSONException.class, () -> AiJsonResponses.parseAiJson(""));
        assertThrows(JSONException.class, () -> AiJsonResponses.parseAiJson("not json"));
    }

    @Test
    void parseErrorCopyIsUnchanged()
    {
        assertEquals("AI 响应格式异常,请重试", AiJsonResponses.PARSE_ERROR_MSG);
    }

    @Test
    void aiServicesNoLongerCarryTheirOwnCopies() throws Exception
    {
        String base = "../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/";
        for (String file : new String[] {"AiCreatorServiceImpl.java", "AiTopicPlanServiceImpl.java"})
        {
            String src = Files.readString(Paths.get(base + file), StandardCharsets.UTF_8);
            assertFalse(src.contains("String stripJsonFence("), file + " 仍有私有 stripJsonFence");
            assertFalse(src.contains("JSONObject parseAiJson("), file + " 仍有私有 parseAiJson");
            assertFalse(src.contains("String PARSE_ERROR_MSG ="), file + " 仍有私有 PARSE_ERROR_MSG");
        }
    }
}
