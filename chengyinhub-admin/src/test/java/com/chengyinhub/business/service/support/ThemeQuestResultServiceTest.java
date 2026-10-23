package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ThemeQuestResultServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> LEGACY = Map.of(
            "QUEST_START", "gameplay-receipt:58e5d33cbc7cfbc9582b6ef264c7164b",
            "QUEST_CANCEL", "gameplay-receipt:892a5bc35d62528a4c45eddeec9b6b59",
            "QUEST_RESOLVE", "gameplay-receipt:cf4376b65445f8015210ee8277ff2bb0");
    private static final Map<String, String> PROOFS = Map.of(
            "QUEST_START", "e9146450180f1add1125b68d58f0fafac56de0be53b8d24ff8cdfa5198fee6b6",
            "QUEST_CANCEL", "153b2051d07908a4fb2de139ca7884b73dc1a9e1b59aa8d5a1bb1a00e3ccf797",
            "QUEST_RESOLVE", "5cac9cd989836327592b38baf7c4f216f7a947a737c3b46fd9211ca524d0dbeb");
    private TopicRouteSessionMapper mapper;
    private ThemeQuestResultService service;
    private TopicRouteSession session;
    private TopicRouteAction action;

    @BeforeEach void setup() throws Exception {
        mapper = mock(TopicRouteSessionMapper.class);
        service = new ThemeQuestResultService(mapper);
        session = new TopicRouteSession();
        session.setId(99L); session.setMemberId(7L); session.setTopicId(10L); session.setActivityId(0L);
        session.setVersion(99); session.setStatus(2); session.setStateJson("must not read current runtime");
        when(mapper.selectAccessibleById(99L, 7L)).thenReturn(session);
        install("QUEST_RESOLVE");
    }

    @Test void allThreeKindsRecoverTheOriginalOldRunResultWithoutWritesOrProofLeakage() throws Exception {
        for (String kind : List.of("QUEST_START", "QUEST_RESOLVE", "QUEST_CANCEL")) {
            install(kind);
            String stored = action.getResponseJson();
            Map<String, Object> result = query(kind, payload(kind));
            assertEquals("FOUND", result.get("status"));
            assertEquals("TYPED_QUEST_REQUEST_V1", result.get("matchLevel"));
            JsonNode receipt = JSON.valueToTree(result.get("receipt"));
            ObjectNode expected = (ObjectNode) JSON.readTree(stored);
            expected.remove(ThemeQuestActionContract.PROOF_FIELD);
            assertEquals(expected, receipt);
            assertEquals(3, receipt.path("version").intValue());
            assertFalse(receipt.has(ThemeQuestActionContract.PROOF_FIELD));
            assertEquals(stored, action.getResponseJson());
            assertEquals(99, session.getVersion());
            assertEquals("must not read current runtime", session.getStateJson());
        }
        onlyReads(3);
    }

    @Test void resultProofUsesIndependentCanonicalVectorsForEveryKind() throws Exception {
        for (String kind : List.of("QUEST_START", "QUEST_RESOLVE", "QUEST_CANCEL"))
            assertEquals(PROOFS.get(kind), proof(kind, payload(kind)));
        verifyNoInteractions(mapper);
    }

    @Test void reorderedPayloadAndRewardRowKeysRemainTheSameTypedIntent() throws Exception {
        JsonNode reordered = JSON.readTree("{\"previewVersion\":\"server-preview\",\"discardRewards\":[{\"quantity\":1,\"itemId\":\"food\"}],\"costOption\":1,\"choiceKey\":\"food\",\"contentId\":\"north\"}");
        assertEquals(proof("QUEST_RESOLVE", payload("QUEST_RESOLVE")), proof("QUEST_RESOLVE", reordered));
        assertEquals("FOUND", query("QUEST_RESOLVE", reordered).get("status"));
        onlyReads(1);
    }

    @Test void changedPayloadVersionAndMissingVersusEmptyFieldsDoNotRecoverAnotherRequest() throws Exception {
        ObjectNode base = (ObjectNode) payload("QUEST_RESOLVE");
        List<ObjectNode> changed = List.of(base.deepCopy().put("contentId", "south"), base.deepCopy().put("choiceKey", "other"),
                base.deepCopy().put("costOption", 0), base.deepCopy().put("previewVersion", "other-preview"));
        int reads = 0;
        for (ObjectNode other : changed) {
            assertNotEquals(proof("QUEST_RESOLVE", base), proof("QUEST_RESOLVE", other));
            assertEquals(Map.of("status", "UNKNOWN"), query("QUEST_RESOLVE", other)); reads++;
        }
        ObjectNode absent = base.deepCopy(); absent.remove("discardRewards");
        ObjectNode empty = base.deepCopy(); empty.putArray("discardRewards");
        assertNotEquals(proof("QUEST_RESOLVE", absent), proof("QUEST_RESOLVE", empty));
        assertEquals(Map.of("status", "UNKNOWN"), query("QUEST_RESOLVE", absent)); reads++;
        assertEquals(Map.of("status", "UNKNOWN"), query("QUEST_RESOLVE", empty)); reads++;
        assertEquals(Map.of("status", "UNKNOWN"), service.query(7L, 10L, 0L, 99L, 3, "quest-1", "QUEST_RESOLVE", base)); reads++;
        onlyReads(reads);
    }

    @Test void oldRecordsWithoutProofAndCorruptProofsStayUnknownWithoutBeingUpgraded() throws Exception {
        ObjectNode stored = (ObjectNode) JSON.readTree(action.getResponseJson());
        ObjectNode legacy = stored.deepCopy(); legacy.remove(ThemeQuestActionContract.PROOF_FIELD);
        action.setResponseJson(legacy.toString());
        assertEquals(Map.of("status", "UNKNOWN"), query("QUEST_RESOLVE", payload("QUEST_RESOLVE")));
        assertEquals(legacy.toString(), action.getResponseJson());
        for (JsonNode bad : List.of(JSON.getNodeFactory().nullNode(), JSON.createObjectNode(), JSON.getNodeFactory().textNode("wrong"))) {
            ObjectNode changed = stored.deepCopy(); changed.set(ThemeQuestActionContract.PROOF_FIELD, bad);
            action.setResponseJson(changed.toString());
            assertEquals(Map.of("status", "UNKNOWN"), query("QUEST_RESOLVE", payload("QUEST_RESOLVE")));
        }
        onlyReads(4);
    }

    @Test void typedProofSeparatesAbsentChoiceFromTheLegacyLiteralNullCollision() throws Exception {
        ObjectNode absent = payload("QUEST_RESOLVE"); absent.remove("choiceKey");
        ObjectNode literal = absent.deepCopy().put("choiceKey", "null");
        assertNotEquals(proof("QUEST_RESOLVE", absent), proof("QUEST_RESOLVE", literal));
        // Both use the same original legacy string, but only the exact stored typed request can recover it.
        action.setReceiptId("gameplay-receipt:3f88dbff0ea92268a4b2dc5b3294b092");
        ObjectNode original = (ObjectNode) JSON.readTree(action.getResponseJson());
        original.put(ThemeQuestActionContract.PROOF_FIELD, proof("QUEST_RESOLVE", absent));
        action.setResponseJson(original.toString());
        assertEquals("FOUND", query("QUEST_RESOLVE", absent).get("status"));
        assertEquals(Map.of("status", "UNKNOWN"), query("QUEST_RESOLVE", literal));
        onlyReads(2);
    }

    @Test void proofMustMatchEveryActualScopeAndRequestTupleMember() throws Exception {
        JsonNode payload = payload("QUEST_RESOLVE");
        List<String> wrong = List.of(
                ThemeQuestActionContract.fingerprint(8L, 10L, 0L, 99L, 2, "quest-1", "QUEST_RESOLVE", payload),
                ThemeQuestActionContract.fingerprint(7L, 11L, 0L, 99L, 2, "quest-1", "QUEST_RESOLVE", payload),
                ThemeQuestActionContract.fingerprint(7L, 10L, 1L, 99L, 2, "quest-1", "QUEST_RESOLVE", payload),
                ThemeQuestActionContract.fingerprint(7L, 10L, 0L, 100L, 2, "quest-1", "QUEST_RESOLVE", payload),
                ThemeQuestActionContract.fingerprint(7L, 10L, 0L, 99L, 3, "quest-1", "QUEST_RESOLVE", payload),
                ThemeQuestActionContract.fingerprint(7L, 10L, 0L, 99L, 2, "other", "QUEST_RESOLVE", payload));
        ObjectNode original = (ObjectNode) JSON.readTree(action.getResponseJson());
        for (String fingerprint : wrong) {
            action.setResponseJson(original.deepCopy().put(ThemeQuestActionContract.PROOF_FIELD, fingerprint).toString());
            assertEquals(Map.of("status", "UNKNOWN"), query("QUEST_RESOLVE", payload));
        }
        onlyReads(wrong.size());
    }

    @Test void missingAndDamagedOriginalResultsStayUnknown() throws Exception {
        when(mapper.selectActionByAction(99L, 7L, "gameplay:quest-1")).thenReturn(null);
        assertEquals(Map.of("status", "UNKNOWN"), query("QUEST_RESOLVE", payload("QUEST_RESOLVE")));
        when(mapper.selectActionByAction(99L, 7L, "gameplay:quest-1")).thenReturn(action);
        String valid = action.getResponseJson();
        int reads = 1;
        for (String damaged : List.of("", " ", "null", "[]", "{}", "broken", valid + " true",
                valid.replace("\"sessionId\":99", "\"sessionId\":100,\"sessionId\":99"),
                valid.replace("\"version\":3", "\"version\":2,\"version\":3"))) {
            action.setResponseJson(damaged);
            assertEquals(Map.of("status", "UNKNOWN"), query("QUEST_RESOLVE", payload("QUEST_RESOLVE"))); reads++;
        }
        ObjectNode original = (ObjectNode) JSON.readTree(valid);
        for (String field : List.of("sessionId", "topicId", "activityId", "version", "status", "contents", "pending", "settlement")) {
            ObjectNode partial = original.deepCopy(); partial.remove(field); action.setResponseJson(partial.toString());
            assertEquals(Map.of("status", "UNKNOWN"), query("QUEST_RESOLVE", payload("QUEST_RESOLVE"))); reads++;
        }
        ObjectNode emptySettlement = original.deepCopy(); emptySettlement.putObject("settlement");
        for (ObjectNode partial : List.of(original.deepCopy().put("version", 2), original.deepCopy().put("version", 4),
                original.deepCopy().put("sessionId", 100), original.deepCopy().put("topicId", 11), original.deepCopy().put("activityId", 1),
                original.deepCopy().put("status", "UNKNOWN"), original.deepCopy().put("version", 3.5),
                emptySettlement)) {
            action.setResponseJson(partial.toString());
            assertEquals(Map.of("status", "UNKNOWN"), query("QUEST_RESOLVE", payload("QUEST_RESOLVE"))); reads++;
        }
        action.setResponseJson(null);
        assertEquals(Map.of("status", "UNKNOWN"), query("QUEST_RESOLVE", payload("QUEST_RESOLVE"))); reads++;
        onlyReads(reads);
    }

    @Test void mismatchedNamespaceOperationAndLegacyDigestDoNotReturnAResult() throws Exception {
        action.setActionId("theme:quest-1");
        assertEquals(Map.of("status", "UNKNOWN"), query("QUEST_RESOLVE", payload("QUEST_RESOLVE")));
        action.setActionId("gameplay:quest-1"); action.setOutcomeCode("QUEST_START");
        assertEquals(Map.of("status", "UNKNOWN"), query("QUEST_RESOLVE", payload("QUEST_RESOLVE")));
        action.setOutcomeCode("QUEST_RESOLVE"); action.setReceiptId("gameplay-receipt:wrong");
        assertEquals(Map.of("status", "UNKNOWN"), query("QUEST_RESOLVE", payload("QUEST_RESOLVE")));
        onlyReads(3);
    }

    @Test void actorTopicActivityAndSessionAreExactBeforeLookingUpAnAction() throws Exception {
        JsonNode payload = payload("QUEST_RESOLVE");
        assertScope(() -> service.query(8L, 10L, 0L, 99L, 2, "quest-1", "QUEST_RESOLVE", payload));
        assertScope(() -> service.query(7L, 11L, 0L, 99L, 2, "quest-1", "QUEST_RESOLVE", payload));
        assertScope(() -> service.query(7L, 10L, 1L, 99L, 2, "quest-1", "QUEST_RESOLVE", payload));
        assertScope(() -> service.query(7L, 10L, 0L, 100L, 2, "quest-1", "QUEST_RESOLVE", payload));
        verify(mapper, never()).selectActionByAction(anyLong(), anyLong(), anyString());
        action.setMemberId(8L);
        assertScope(() -> query("QUEST_RESOLVE", payload));
        action.setMemberId(7L); action.setSessionId(100L);
        assertScope(() -> query("QUEST_RESOLVE", payload));
    }

    @Test void invalidContextAndPayloadIncludingResolveWithoutPreviewNeverTouchTheMapper() throws Exception {
        JsonNode valid = payload("QUEST_RESOLVE");
        assertThrows(IllegalArgumentException.class, () -> service.query(7L, 10L, null, 99L, 2, "quest-1", "QUEST_RESOLVE", valid));
        assertThrows(IllegalArgumentException.class, () -> service.query(7L, 10L, 0L, 99L, Integer.MAX_VALUE, "quest-1", "QUEST_RESOLVE", valid));
        assertThrows(IllegalArgumentException.class, () -> service.query(7L, 10L, 0L, 99L, -1, "quest-1", "QUEST_RESOLVE", valid));
        ObjectNode noPreview = (ObjectNode) valid.deepCopy(); noPreview.remove("previewVersion");
        assertThrows(IllegalArgumentException.class, () -> query("QUEST_RESOLVE", noPreview));
        assertThrows(IllegalArgumentException.class, () -> query("QUEST_START", valid));
        assertThrows(IllegalArgumentException.class, () -> query("UNKNOWN", valid));
        assertThrows(IllegalArgumentException.class, () -> query("QUEST_RESOLVE", ((ObjectNode) valid.deepCopy()).put("reward", 99)));
        assertThrows(IllegalArgumentException.class, () -> query("QUEST_RESOLVE", null));
        verifyNoInteractions(mapper);
    }

    @Test void lookupIsDeclaredReadOnly() throws Exception {
        assertTrue(ThemeQuestResultService.class.getMethod("query", Long.class, Long.class, Long.class, Long.class,
                Integer.class, String.class, String.class, JsonNode.class).getAnnotation(Transactional.class).readOnly());
    }

    private void install(String kind) throws Exception {
        action = new TopicRouteAction();
        action.setSessionId(99L); action.setMemberId(7L); action.setActionId("gameplay:quest-1"); action.setOutcomeCode(kind);
        action.setReceiptId(LEGACY.get(kind));
        ObjectNode original = JSON.createObjectNode().put("sessionId", 99).put("topicId", 10).put("activityId", 0)
                .put("version", 3).put("status", "QUEST_RESOLVE".equals(kind) ? "COMPLETED" : "ACTIVE");
        original.putArray("contents"); original.putObject("pending");
        original.putObject("settlement").put("QUEST_START".equals(kind) ? "activeQuest" : "QUEST_CANCEL".equals(kind) ? "cancelledQuest" : "questKey", "north");
        original.put(ThemeQuestActionContract.PROOF_FIELD, proof(kind, payload(kind)));
        original.putObject("originalOnly").put("retained", true);
        action.setResponseJson(original.toString());
        when(mapper.selectActionByAction(99L, 7L, "gameplay:quest-1")).thenReturn(action);
    }

    private static ObjectNode payload(String kind) throws Exception {
        return (ObjectNode) JSON.readTree("QUEST_RESOLVE".equals(kind)
                ? "{\"contentId\":\"north\",\"choiceKey\":\"food\",\"costOption\":1,\"discardRewards\":[{\"itemId\":\"food\",\"quantity\":1}],\"previewVersion\":\"server-preview\"}"
                : "{\"contentId\":\"north\"}");
    }

    private static String proof(String kind, JsonNode payload) {
        return ThemeQuestActionContract.fingerprint(7L, 10L, 0L, 99L, 2, "quest-1", kind, payload);
    }

    private Map<String, Object> query(String kind, JsonNode payload) {
        return service.query(7L, 10L, 0L, 99L, 2, "quest-1", kind, payload);
    }

    private static void assertScope(org.junit.jupiter.api.function.Executable query) {
        assertEquals(Integer.valueOf(403), assertThrows(ServiceException.class, query).getCode());
    }

    private void onlyReads(int count) {
        verify(mapper, times(count)).selectAccessibleById(99L, 7L);
        verify(mapper, times(count)).selectActionByAction(99L, 7L, "gameplay:quest-1");
        verifyNoMoreInteractions(mapper);
    }
}
