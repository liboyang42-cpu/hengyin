package com.chengyinhub.business.longrun;

import com.chengyinhub.business.service.ai.OpenAiCompatibleLlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN: executes the production parser without starting HTTP. */
class OWN_C05_15Test {
    @Test
    void onlyNonblankStringContentCanBeReturnedAsModelText() {
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                "deepseek", "https://unused.invalid", "unused", "model", true, 1);
        for (String content : new String[] {"{}", "[]", "42", "true", "null", "\"\"", "\"  \""}) {
            String response = "{\"choices\":[{\"message\":{\"content\":" + content + "}}]}";
            assertThrows(RuntimeException.class, () -> ReflectionTestUtils.invokeMethod(client, "responseText", response));
        }
        for (String response : new String[] {"null", "{}", "{\"choices\":[]}",
                "{\"choices\":[null]}", "{\"choices\":[{\"message\":\"wrong\"}]}",
                "{\"choices\":\"wrong\"}"}) {
            assertThrows(RuntimeException.class, () -> ReflectionTestUtils.invokeMethod(client, "responseText", response));
        }
        String response = "{\"choices\":[{\"message\":{\"content\":\"{\\\"pass\\\":true}\"}}]}";
        assertEquals("{\"pass\":true}", ReflectionTestUtils.invokeMethod(client, "responseText", response));
    }
}
