package com.chengyinhub.business.service.orienteering;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishedRuleSelectorTest {

    private final PublishedRuleSelector selector = new PublishedRuleSelector();

    @Test
    void ordinaryPublishedCityCourseFreezesPublishedVersionNotDraft() {
        CmsTopic course = publishedCity(7L, 9L);
        PublishedRuleSelector.FrozenPublishedRule frozen = selector.select(course);
        assertEquals(7L, frozen.publishedConfigVersion);
        assertEquals("LINEAR", frozen.routeMode);
        assertTrue(frozen.digest.matches("[0-9a-f]{64}"));
        selector.requireAck(frozen, 7L);
        OrienteeringEventException stale = assertThrows(OrienteeringEventException.class,
                () -> selector.requireAck(frozen, course.getConfigVersion()));
        assertEquals(OrienteeringEventException.RULE_VERSION_MISMATCH, stale.getErrorCode());
    }

    @Test
    void draftOrExploreOrUnpublishedAreRejected() {
        CmsTopic draft = publishedCity(0L, 1L);
        assertEquals(OrienteeringEventException.RULE_NOT_FROZEN,
                assertThrows(OrienteeringEventException.class, () -> selector.select(draft)).getErrorCode());

        CmsTopic explore = publishedCity(3L, 3L);
        explore.setProductType(ProductType.FREE_EXPLORE);
        assertEquals(OrienteeringEventException.NOT_ORDINARY_CITY,
                assertThrows(OrienteeringEventException.class, () -> selector.select(explore)).getErrorCode());

        CmsTopic shelf = publishedCity(3L, 3L);
        shelf.setUserStatus(0);
        assertEquals(OrienteeringEventException.NOT_PUBLISHED,
                assertThrows(OrienteeringEventException.class, () -> selector.select(shelf)).getErrorCode());
    }

    @Test
    void laterDraftEditDoesNotChangeAlreadySelectedDigest() {
        CmsTopic course = publishedCity(7L, 7L);
        PublishedRuleSelector.FrozenPublishedRule first = selector.select(course);
        course.setConfigVersion(12L);
        course.setRouteGraphJson("{\"routeMode\":\"LINEAR\",\"dirty\":true}");
        PublishedRuleSelector.FrozenPublishedRule still = selector.select(course);
        assertEquals(first.digest, still.digest);
        assertEquals(first.publishedConfigVersion, still.publishedConfigVersion);
        course.setPublishedConfigVersion(8L);
        course.setPublishedRouteGraphJson("{\"runtimeSnapshotVersion\":2,\"routeMode\":\"LINEAR\"}");
        assertNotEquals(first.digest, selector.select(course).digest);
    }

    static CmsTopic publishedCity(long publishedVersion, long draftVersion) {
        CmsTopic topic = new CmsTopic();
        topic.setId(9001L);
        topic.setProductType(ProductType.ORIENTEERING);
        topic.setStatus(1);
        topic.setUserStatus(1);
        topic.setDelFlag(0);
        topic.setPublishedConfigVersion(publishedVersion);
        topic.setConfigVersion(draftVersion);
        topic.setRouteMode("LINEAR");
        topic.setPublishedRouteGraphJson("{\"runtimeSnapshotVersion\":1,\"routeMode\":\"LINEAR\"}");
        topic.setCompleteRuleJson("{\"nodeCompletion\":\"ALL\"}");
        return topic;
    }
}
