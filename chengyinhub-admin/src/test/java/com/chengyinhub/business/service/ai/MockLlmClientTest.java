package com.chengyinhub.business.service.ai;

import org.junit.jupiter.api.Test;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MockLlmClient 测试:6 种 biz type 均返回可解析 JSON。
 */
class MockLlmClientTest {

    private final MockLlmClient client = new MockLlmClient();

    @Test
    void nameReturnsMock() {
        assertEquals("mock", client.name());
    }

    @Test
    void merchantPlanReturnsValidJson() {
        String ctx = "{\"idea\":\"night walk\",\"durationMin\":90,\"candidates\":[" +
                "{\"candidateId\":1,\"merchantName\":\"Shop1\",\"address\":\"Addr1\",\"longitude\":\"121.47\",\"latitude\":\"31.23\",\"businessTime\":\"10:00-22:00\"}," +
                "{\"candidateId\":2,\"merchantName\":\"Shop2\",\"address\":\"Addr2\",\"longitude\":\"121.48\",\"latitude\":\"31.24\",\"businessTime\":\"09:00-21:00\"}," +
                "{\"candidateId\":3,\"merchantName\":\"Shop3\",\"address\":\"Addr3\",\"longitude\":\"121.49\",\"latitude\":\"31.25\",\"businessTime\":\"11:00-23:00\"}" +
                "]}";
        String result = client.chat("[BIZ:merchant_plan]\n", ctx);

        assertNotNull(result);
        assertFalse(result.isEmpty());

        JSONObject json = JSON.parseObject(result);
        assertNotNull(json);
        JSONArray plans = json.getJSONArray("plans");
        assertNotNull(plans);
        assertTrue(plans.size() >= 1);

        // verify first plan has required fields
        JSONObject plan0 = plans.getJSONObject(0);
        assertNotNull(plan0.getString("title"));
        assertNotNull(plan0.getString("subtitle"));
        assertNotNull(plan0.getJSONArray("nodes"));
        assertTrue(plan0.getJSONArray("nodes").size() > 0);
    }

    @Test
    void themeDraftReturnsValidJson() {
        String ctx = "{\"idea\":\"city walk\"}";
        String result = client.chat("[BIZ:theme_draft]\n", ctx);

        assertNotNull(result);
        assertFalse(result.isEmpty());

        JSONObject json = JSON.parseObject(result);
        assertNotNull(json);
        JSONArray plans = json.getJSONArray("plans");
        assertNotNull(plans);
        assertEquals(1, plans.size());

        JSONObject plan0 = plans.getJSONObject(0);
        assertNotNull(plan0.getString("title"));
        assertNotNull(plan0.getJSONArray("nodes"));
    }

    @Test
    void nodeGenerateReturnsValidJson() {
        String ctx = "{\"nodeName\":\"Jing'An\",\"validationMethod\":3}";
        String result = client.chat("[BIZ:node_generate]\n", ctx);

        assertNotNull(result);
        assertFalse(result.isEmpty());

        JSONObject json = JSON.parseObject(result);
        assertNotNull(json);
        JSONObject node = json.getJSONObject("node");
        assertNotNull(node);
        assertNotNull(node.getInteger("validationMethod"));
        assertNotNull(node.getString("questionName"));
    }

    @Test
    void safetyPrecheckReturnsValidJson() {
        String ctx = "{\"title\":\"good title\",\"nodes\":[{\"task\":\"do this\"}]}";
        String result = client.chat("[BIZ:safety_precheck]\n", ctx);

        assertNotNull(result);
        assertFalse(result.isEmpty());

        JSONObject json = JSON.parseObject(result);
        assertNotNull(json);
        // pass should be a boolean
        assertNotNull(json.get("pass"));
        assertNotNull(json.getJSONArray("issues"));
    }

    @Test
    void safetyPrecheckMissingTitleReturnsFail() {
        String ctx = "{\"title\":\"\",\"nodes\":[{\"task\":\"do this\"}]}";
        String result = client.chat("[BIZ:safety_precheck]\n", ctx);

        JSONObject json = JSON.parseObject(result);
        assertFalse(json.getBooleanValue("pass", true));
        assertTrue(json.getJSONArray("issues").size() > 0);
    }

    @Test
    void merchantTaskSuggestReturnsValidJson() {
        String ctx = "{\"merchantContext\":\"coffee shop\",\"goal\":\"theme fit\"}";
        String result = client.chat("[BIZ:merchant_task_suggest]\n", ctx);

        assertNotNull(result);
        assertFalse(result.isEmpty());

        JSONObject json = JSON.parseObject(result);
        assertNotNull(json);
        JSONArray suggestions = json.getJSONArray("suggestions");
        assertNotNull(suggestions);
        assertTrue(suggestions.size() > 0);

        JSONObject s0 = suggestions.getJSONObject(0);
        assertNotNull(s0.getString("title"));
        assertNotNull(s0.getString("themeFit"));
        assertNotNull(s0.getString("taskRole"));
        assertNotNull(s0.getString("taskIdea"));
        assertNotNull(s0.getString("coopScript"));
    }

    @Test
    void clubDesignReturnsValidJson() {
        String ctx = "{\"idea\":\"night coffee\",\"clubStyle\":\"artsy\"}";
        String result = client.chat("[BIZ:club_design]\n", ctx);

        assertNotNull(result);
        assertFalse(result.isEmpty());

        JSONObject json = JSON.parseObject(result);
        assertNotNull(json);

        // plan
        JSONObject plan = json.getJSONObject("plan");
        assertNotNull(plan);
        assertNotNull(plan.getString("title"));
        assertNotNull(plan.getJSONArray("nodes"));

        // merchantSuggestions
        JSONArray merchantSuggestions = json.getJSONArray("merchantSuggestions");
        assertNotNull(merchantSuggestions);
        assertTrue(merchantSuggestions.size() > 0);

        // promoCopy
        String promoCopy = json.getString("promoCopy");
        assertNotNull(promoCopy);
        assertFalse(promoCopy.isEmpty());
    }

    @Test
    void unknownBizFallsBackToThemeDraft() {
        String result = client.chat("[BIZ:unknown_biz]\n", "{}");
        assertNotNull(result);
        assertFalse(result.isEmpty());
        JSONObject json = JSON.parseObject(result);
        assertNotNull(json.getJSONArray("plans"));
    }

    @Test
    void nullSystemPromptReturnsThemeDraft() {
        String result = client.chat(null, "{\"idea\":\"test\"}");
        assertNotNull(result);
        assertFalse(result.isEmpty());
        JSONObject json = JSON.parseObject(result);
        assertNotNull(json);
    }

    @Test
    void emptyUserPromptHandled() {
        String result = client.chat("[BIZ:theme_draft]\n", "");
        assertNotNull(result);
        assertFalse(result.isEmpty());
        JSONObject json = JSON.parseObject(result);
        assertNotNull(json);
    }
}
