package com.chengyinhub.business.longrun;

import com.chengyinhub.business.service.ai.OpenAiCompatibleLlmClient;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN: readiness is local and does not execute HTTP. */
class OWN_C05_16Test {
    @Test
    void missingOrWhitespaceOnlyKeyIsRejectedLocally() {
        for (String key : new String[] {null, "", " ", "\t\r\n"}) {
            OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                    "deepseek", "https://unused.invalid", key, "model", true, 1);
            assertThrows(IllegalStateException.class, client::requireReady);
        }
        OpenAiCompatibleLlmClient configured = new OpenAiCompatibleLlmClient(
                "deepseek", "https://unused.invalid", "synthetic-key", "model", true, 1);
        assertDoesNotThrow(configured::requireReady);
    }
}
