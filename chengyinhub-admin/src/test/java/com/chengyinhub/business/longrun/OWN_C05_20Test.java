package com.chengyinhub.business.longrun;

import com.chengyinhub.business.service.ai.OpenAiCompatibleLlmClient;
import com.chengyinhub.business.service.ai.impl.AiGatewayServiceImpl;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN: actual production truncators, not duplicate implementations. */
class OWN_C05_20Test {
    @Test
    void auditAndHttpErrorBriefsNeverSplitAnExistingSurrogatePair() {
        Object[] owners = {new AiGatewayServiceImpl(), new OpenAiCompatibleLlmClient(
                "deepseek", "https://unused.invalid", "unused", "model", true, 1)};
        for (Object owner : owners) {
            for (int limit : new int[] {100, 500}) {
                String prefix = String.join("", Collections.nCopies(limit - 1, "a"));
                String input = prefix + "\uD83D\uDE00tail";
                String truncated = ReflectionTestUtils.invokeMethod(owner, "truncate", input, limit);
                assertEquals(prefix, truncated);
                assertTrue(truncated.length() <= limit);
                assertEquals(input, ReflectionTestUtils.invokeMethod(owner, "truncate", input, input.length()));
                assertEquals(prefix + "\uD83D\uDE00",
                        ReflectionTestUtils.invokeMethod(owner, "truncate", input, limit + 1));
            }
            assertEquals("", ReflectionTestUtils.invokeMethod(owner, "truncate", "a", 0));
            assertEquals("", ReflectionTestUtils.invokeMethod(owner, "truncate", null, 500));
        }
    }
}
