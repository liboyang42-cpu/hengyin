package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.vo.ai.NpcChatResp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 Chat 的 HTTP 终态契约。
 *
 * 这不是 service 实现细节测试：小程序只能靠这些字段区分成功、限流、拦截、重试与迟到响应，
 * 所以用真实 JSON 序列化验证，而不是只验证 Java getter。
 */
class NpcChatRespContractTest {

    @Test
    void exposesPhaseThreeTerminalFields() throws Exception {
        JsonNode node = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(new NpcChatResp()));

        assertTrue(node.has("requestId"));
        assertTrue(node.has("outcomeStatus"));
        assertTrue(node.has("safetyDecision"));
        assertTrue(node.has("errorCode"));
        assertTrue(node.has("retryable"));
        assertTrue(node.has("safeText"));
        assertTrue(node.has("audioUrl"));
        assertTrue(node.has("ai"));
    }
}
