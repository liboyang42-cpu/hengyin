package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopicNode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicRouteCandidatePolicyTest {
    private static final Clock NOON = Clock.fixed(Instant.parse("2026-08-23T04:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Test
    void noRequirementsKeepsExistingGraphAvailable() {
        TopicRouteCandidatePolicy policy = new TopicRouteCandidatePolicy(NOON, RouteReachabilityProvider.UNKNOWN);
        TopicRouteCandidatePolicy.Evaluation result = policy.evaluate(graph(""), facts(7L, 1, true, "NAVIGATOR"),
                8L, node(1L, "121.4700", "31.2300", null), Arrays.asList(node(2L, null, null, null)));

        assertEquals(TopicRouteCandidatePolicy.Status.AVAILABLE, result.getNodeStatuses().get(2L));
        assertTrue(result.getUnavailableNodeIds().isEmpty());
    }

    @Test
    void ticketAndPurchaseFactsDistinguishAllowedRejectedAndUnknown() {
        String requirement = "{\"nodeId\":2,\"allowedTicketIds\":[7],\"allowedPurchaseKinds\":[1]}";
        TopicRouteCandidatePolicy policy = new TopicRouteCandidatePolicy(NOON, RouteReachabilityProvider.UNKNOWN);
        CmsTopicNode current = node(1L, null, null, null);
        CmsTopicNode target = node(2L, null, null, null);

        assertEquals(TopicRouteCandidatePolicy.Status.AVAILABLE,
                policy.evaluate(graph(requirement), facts(7L, 1, true, null), 5L, current,
                        Collections.singletonList(target)).getNodeStatuses().get(2L));
        TopicRouteCandidatePolicy.Evaluation rejected = policy.evaluate(graph(requirement),
                facts(8L, 1, true, null), 5L, current, Collections.singletonList(target));
        assertEquals(TopicRouteCandidatePolicy.Status.UNAVAILABLE, rejected.getNodeStatuses().get(2L));
        assertTrue(rejected.getReasonCodes().get(2L).contains("TICKET_NOT_ALLOWED"));
        TopicRouteCandidatePolicy.Evaluation unknown = policy.evaluate(graph(requirement),
                facts(null, 1, true, null), 5L, current, Collections.singletonList(target));
        assertEquals(TopicRouteCandidatePolicy.Status.UNKNOWN, unknown.getNodeStatuses().get(2L));
        assertTrue(unknown.getReasonCodes().get(2L).contains("TICKET_UNKNOWN"));
        assertTrue(unknown.getUnavailableNodeIds().contains(2L));
    }

    @Test
    void anyCurrentRegistrationCanSatisfyTicketAndPurchaseRequirements() {
        String requirement = "{\"nodeId\":2,\"allowedTicketIds\":[7],\"allowedPurchaseKinds\":[1]}";
        TopicRouteCandidatePolicy policy = new TopicRouteCandidatePolicy(NOON, RouteReachabilityProvider.UNKNOWN);
        TopicRouteCandidatePolicy.Facts facts = new TopicRouteCandidatePolicy.Facts(
                Arrays.asList(8L, 7L), Arrays.asList(2, 1), true, "NAVIGATOR");

        assertEquals(TopicRouteCandidatePolicy.Status.AVAILABLE,
                policy.evaluate(graph(requirement), facts, 5L, node(1L, null, null, null),
                        Collections.singletonList(node(2L, null, null, null))).getNodeStatuses().get(2L));
    }

    @Test
    void requiredRoleUsesActivityRoleAndSelfPlayIsUnknown() {
        String requirement = "{\"nodeId\":2,\"requiredRoleCodes\":[\"NAVIGATOR\"]}";
        TopicRouteCandidatePolicy policy = new TopicRouteCandidatePolicy(NOON, RouteReachabilityProvider.UNKNOWN);
        CmsTopicNode target = node(2L, null, null, null);

        assertEquals(TopicRouteCandidatePolicy.Status.AVAILABLE,
                policy.evaluate(graph(requirement), facts(7L, 1, true, "NAVIGATOR"), 9L, target,
                        Collections.singletonList(target)).getNodeStatuses().get(2L));
        assertEquals(TopicRouteCandidatePolicy.Status.UNAVAILABLE,
                policy.evaluate(graph(requirement), facts(7L, 1, true, "OBSERVER"), 9L, target,
                        Collections.singletonList(target)).getNodeStatuses().get(2L));
        TopicRouteCandidatePolicy.Evaluation selfPlay = policy.evaluate(graph(requirement),
                facts(7L, 2, false, null), 0L, target, Collections.singletonList(target));
        assertEquals(TopicRouteCandidatePolicy.Status.UNKNOWN, selfPlay.getNodeStatuses().get(2L));
        assertTrue(selfPlay.getReasonCodes().get(2L).contains("ROLE_UNKNOWN"));
    }

    @Test
    void businessHoursSupportSameDayAndCrossMidnightAndFailClosedOnInvalidText() {
        String requirement = "{\"nodeId\":2,\"requireOpen\":true}";
        TopicRouteCandidatePolicy noon = new TopicRouteCandidatePolicy(NOON, RouteReachabilityProvider.UNKNOWN);
        assertEquals(TopicRouteCandidatePolicy.Status.AVAILABLE,
                noon.evaluate(graph(requirement), facts(7L, 1, true, null), 5L, node(1L, null, null, null),
                        Collections.singletonList(node(2L, null, null, "09:00-18:00"))).getNodeStatuses().get(2L));
        assertEquals(TopicRouteCandidatePolicy.Status.UNAVAILABLE,
                noon.evaluate(graph(requirement), facts(7L, 1, true, null), 5L, node(1L, null, null, null),
                        Collections.singletonList(node(2L, null, null, "20:00-02:00"))).getNodeStatuses().get(2L));

        Clock late = Clock.fixed(Instant.parse("2026-08-23T16:30:00Z"), ZoneId.of("Asia/Shanghai"));
        TopicRouteCandidatePolicy midnight = new TopicRouteCandidatePolicy(late, RouteReachabilityProvider.UNKNOWN);
        assertEquals(TopicRouteCandidatePolicy.Status.AVAILABLE,
                midnight.evaluate(graph(requirement), facts(7L, 1, true, null), 5L, node(1L, null, null, null),
                        Collections.singletonList(node(2L, null, null, "20:00至02:00"))).getNodeStatuses().get(2L));
        Clock afterMidnight = Clock.fixed(Instant.parse("2026-08-22T16:30:00Z"), ZoneId.of("Asia/Shanghai"));
        TopicRouteCandidatePolicy saturdayNight = new TopicRouteCandidatePolicy(afterMidnight,
                RouteReachabilityProvider.UNKNOWN);
        assertEquals(TopicRouteCandidatePolicy.Status.AVAILABLE,
                saturdayNight.evaluate(graph(requirement), facts(7L, 1, true, null), 5L,
                        node(1L, null, null, null),
                        Collections.singletonList(node(2L, null, null, "周六 20:00-02:00")))
                        .getNodeStatuses().get(2L));
        TopicRouteCandidatePolicy.Evaluation invalid = noon.evaluate(graph(requirement), facts(7L, 1, true, null),
                5L, node(1L, null, null, null), Collections.singletonList(node(2L, null, null, "全天")));
        assertEquals(TopicRouteCandidatePolicy.Status.UNKNOWN, invalid.getNodeStatuses().get(2L));
        assertTrue(invalid.getReasonCodes().get(2L).contains("BUSINESS_TIME_UNKNOWN"));
    }

    @Test
    void businessHoursHonorWeekdayPrefixAndUnknownWeekdayTextFailsClosed() {
        String requirement = "{\"nodeId\":2,\"requireOpen\":true}";
        TopicRouteCandidatePolicy policy = new TopicRouteCandidatePolicy(NOON, RouteReachabilityProvider.UNKNOWN);
        CmsTopicNode current = node(1L, null, null, null);

        assertEquals(TopicRouteCandidatePolicy.Status.UNAVAILABLE,
                policy.evaluate(graph(requirement), facts(7L, 1, true, null), 5L, current,
                        Collections.singletonList(node(2L, null, null, "周一至周五 09:00-18:00")))
                        .getNodeStatuses().get(2L));
        assertEquals(TopicRouteCandidatePolicy.Status.AVAILABLE,
                policy.evaluate(graph(requirement), facts(7L, 1, true, null), 5L, current,
                        Collections.singletonList(node(2L, null, null, "周日 09:00-18:00")))
                        .getNodeStatuses().get(2L));
        assertEquals(TopicRouteCandidatePolicy.Status.AVAILABLE,
                policy.evaluate(graph(requirement), facts(7L, 1, true, null), 5L, current,
                        Collections.singletonList(node(2L, null, null, "周一、三、日 09:00-18:00")))
                        .getNodeStatuses().get(2L));
        TopicRouteCandidatePolicy.Evaluation unknown = policy.evaluate(graph(requirement),
                facts(7L, 1, true, null), 5L, current,
                Collections.singletonList(node(2L, null, null, "周八 09:00-18:00")));
        assertEquals(TopicRouteCandidatePolicy.Status.UNKNOWN, unknown.getNodeStatuses().get(2L));
        assertTrue(unknown.getReasonCodes().get(2L).contains("BUSINESS_TIME_UNKNOWN"));
    }

    @Test
    void segmentDistanceHonorsBoundaryAndMissingCoordinatesAreUnknown() {
        String requirement = "{\"nodeId\":2,\"maxDistanceMeters\":0.1}";
        TopicRouteCandidatePolicy policy = new TopicRouteCandidatePolicy(NOON, RouteReachabilityProvider.UNKNOWN);
        CmsTopicNode current = node(1L, "121.4700", "31.2300", null);

        assertEquals(TopicRouteCandidatePolicy.Status.AVAILABLE,
                policy.evaluate(graph(requirement), facts(7L, 1, true, null), 5L, current,
                        Collections.singletonList(node(2L, "121.4700", "31.2300", null))).getNodeStatuses().get(2L));
        TopicRouteCandidatePolicy.Evaluation tooFar = policy.evaluate(graph(requirement.replace("0.1", "100")),
                facts(7L, 1, true, null), 5L, current,
                Collections.singletonList(node(2L, "121.4800", "31.2300", null)));
        assertEquals(TopicRouteCandidatePolicy.Status.UNAVAILABLE, tooFar.getNodeStatuses().get(2L));
        assertTrue(tooFar.getReasonCodes().get(2L).contains("SEGMENT_DISTANCE_EXCEEDED"));
        TopicRouteCandidatePolicy.Evaluation missing = policy.evaluate(graph(requirement),
                facts(7L, 1, true, null), 5L, current,
                Collections.singletonList(node(2L, null, null, null)));
        assertEquals(TopicRouteCandidatePolicy.Status.UNKNOWN, missing.getNodeStatuses().get(2L));
        assertTrue(missing.getReasonCodes().get(2L).contains("SEGMENT_DISTANCE_UNKNOWN"));
    }

    @Test
    void reachabilityProviderCanRejectAndMissingOrFailedProviderIsUnknown() {
        String requirement = "{\"nodeId\":2,\"requireReachable\":true}";
        CmsTopicNode current = node(1L, null, null, null);
        CmsTopicNode target = node(2L, null, null, null);
        TopicRouteCandidatePolicy blocked = new TopicRouteCandidatePolicy(NOON,
                (activityId, from, to) -> RouteReachabilityProvider.Reachability.UNREACHABLE);
        assertEquals(TopicRouteCandidatePolicy.Status.UNAVAILABLE,
                blocked.evaluate(graph(requirement), facts(7L, 1, true, null), 5L, current,
                        Collections.singletonList(target)).getNodeStatuses().get(2L));

        TopicRouteCandidatePolicy unknown = new TopicRouteCandidatePolicy(NOON, null);
        assertEquals(TopicRouteCandidatePolicy.Status.UNKNOWN,
                unknown.evaluate(graph(requirement), facts(7L, 1, true, null), 5L, current,
                        Collections.singletonList(target)).getNodeStatuses().get(2L));
        TopicRouteCandidatePolicy failed = new TopicRouteCandidatePolicy(NOON, (activityId, from, to) -> {
            throw new RuntimeException("timeout");
        });
        TopicRouteCandidatePolicy.Evaluation failure = failed.evaluate(graph(requirement),
                facts(7L, 1, true, null), 5L, current, Collections.singletonList(target));
        assertEquals(TopicRouteCandidatePolicy.Status.UNKNOWN, failure.getNodeStatuses().get(2L));
        assertTrue(failure.getReasonCodes().get(2L).contains("REACHABILITY_UNKNOWN"));
    }

    private String graph(String requirement) {
        return "{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2]," +
                "\"edges\":[{\"id\":\"go\",\"fromNodeId\":1,\"toNodeId\":2," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}]" +
                (requirement.isEmpty() ? "" : ",\"nodeRequirements\":[" + requirement + "]") + "}";
    }

    private TopicRouteCandidatePolicy.Facts facts(Long ticketId, Integer purchaseKind,
                                                   boolean roleKnown, String roleCode) {
        return new TopicRouteCandidatePolicy.Facts(ticketId, purchaseKind, roleKnown, roleCode);
    }

    private CmsTopicNode node(Long id, String longitude, String latitude, String businessTime) {
        CmsTopicNode node = new CmsTopicNode(); node.setId(id); node.setTopicId(10L); node.setStatus(1L);
        node.setLongitude(longitude); node.setLatitude(latitude); node.setBusinessTime(businessTime); return node;
    }
}
