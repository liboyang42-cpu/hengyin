package com.chengyinhub.business.service.support;

import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicCompletionRulePolicyTest {

    private final TopicCompletionRulePolicy policy = new TopicCompletionRulePolicy();

    @Test
    void atLeastRuleUsesServerNodeTotal() {
        String json = policy.validateAndNormalize(
                "{\"nodeCompletion\":{\"mode\":\"AT_LEAST\",\"requiredCount\":3}}",
                5, ProductType.ORIENTEERING);

        assertFalse(policy.isCompleted(json, 5, 2));
        assertTrue(policy.isCompleted(json, 5, 3));
        assertEquals(3, policy.requiredCount(json, 5));
    }

    @Test
    void blankAndAllKeepLegacyAllNodesRule() {
        assertEquals(4, policy.requiredCount(null, 4));
        assertTrue(policy.isCompleted("{\"nodeCompletion\":{\"mode\":\"ALL\"}}", 4, 4));
        assertFalse(policy.isCompleted("{\"nodeCompletion\":{\"mode\":\"ALL\"}}", 4, 3));
    }

    @Test
    void rejectsImpossibleThreshold() {
        assertThrows(IllegalArgumentException.class, () -> policy.validateAndNormalize(
                "{\"nodeCompletion\":{\"mode\":\"AT_LEAST\",\"requiredCount\":6}}",
                5, ProductType.ORIENTEERING));
    }

    @Test
    void freeExploreCannotReplaceEntitlementCompletion() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> policy.validateAndNormalize(
                        "{\"minimumMerchants\":2,\"nodeCompletion\":{\"mode\":\"AT_LEAST\",\"requiredCount\":1}}",
                        5, ProductType.FREE_EXPLORE));
        assertTrue(error.getMessage().contains("自由探索"));
    }

    @Test
    void preservesExistingFreeExploreRuleWhenNodeRuleAbsent() {
        String json = policy.validateAndNormalize("{\"minimumMerchants\":2}", 5, ProductType.FREE_EXPLORE);
        assertTrue(json.contains("minimumMerchants"));
    }
}
