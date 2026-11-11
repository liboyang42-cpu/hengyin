package com.chengyinhub.business.service.ai;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** P0-30:上游 usage 解析;缺失/非法为 null(未知),不得当作 0。 */
class OpenAiCompatibleLlmClientUsageTest {

    @Test
    void readsPromptAndCompletionTokens() {
        assertArrayEquals(new int[] {120, 34},
                OpenAiCompatibleLlmClient.usageOf(JSON.parseObject("{\"prompt_tokens\":120,\"completion_tokens\":34,\"total_tokens\":154}")));
    }

    @Test
    void missingOrInvalidUsageIsUnknown() {
        assertNull(OpenAiCompatibleLlmClient.usageOf(null));
        assertNull(OpenAiCompatibleLlmClient.usageOf("12"));
        assertNull(OpenAiCompatibleLlmClient.usageOf(JSON.parseObject("{\"prompt_tokens\":5}")));
        assertNull(OpenAiCompatibleLlmClient.usageOf(JSON.parseObject("{\"prompt_tokens\":-1,\"completion_tokens\":2}")));
        assertNull(OpenAiCompatibleLlmClient.usageOf(JSON.parseObject("{\"prompt_tokens\":\"7\",\"completion_tokens\":2}")));
    }
}
