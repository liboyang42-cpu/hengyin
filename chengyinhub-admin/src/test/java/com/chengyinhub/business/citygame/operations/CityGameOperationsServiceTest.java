package com.chengyinhub.business.citygame.operations;

import com.chengyinhub.business.citygame.operations.mapper.CityGameOperationsMapper;
import com.chengyinhub.business.service.IPointsAwardService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityGameOperationsServiceTest {

    @Test
    void unauthorizedCallerWritesNothing() {
        Fixture fixture = fixture();
        CityGameOperationsOutcome denied = fixture.service.trace(4L, json(trace("tr-1")));
        assertEquals(403, denied.httpStatus);
        assertEquals("NO_AUTHORITY", denied.errorCode);
        assertEquals(List.of(), denied.data.get("actions"));
        assertEquals(0, fixture.mapper.caseCount());
        assertEquals(0, fixture.mapper.requestCount());
        assertEquals(0, fixture.mapper.auditCount());
        assertEquals(0, fixture.mapper.storeCount());
    }

    @Test
    void traceMarksMissingEvidenceAndReplaysTheSameKey() throws Exception {
        Fixture fixture = fixture();
        grantDesk(fixture);
        CityGameOperationsOutcome traced = fixture.service.trace(7L, json(trace("tr-1")));
        assertEquals(200, traced.httpStatus);
        String body = CityGameOperationsTexts.write(traced.data);
        assertFalse(body.contains("\"fulfilled\""));
        assertFalse(body.contains("fulfilment"));
        Map<?, ?> trace = (Map<?, ?>) traced.data.get("trace");
        Map<?, ?> evidence = (Map<?, ?>) ((Map<?, ?>) trace.get("slots")).get("evidence");
        assertEquals(Boolean.TRUE, evidence.get("missing"));
        assertEquals("NO_EVIDENCE_SOURCE", evidence.get("reason"));
        assertNull(evidence.get("value"));
        assertEquals("VERIFIED", traced.data.get("bindingStatus"));
        assertEquals("desk", traced.data.get("roleKeys"));

        CityGameOperationsOutcome replay = fixture.service.trace(7L, json(
                "{\"sourceRequestId\":\"src-1\",\"regionId\":\"city-1\",\"actionId\":\"act-1\",\"expectedVersion\":0,\"requestId\":\"tr-1\"}"));
        assertEquals(200, replay.httpStatus);
        assertEquals(((Number) traced.data.get("caseId")).longValue(), ((Number) replay.data.get("caseId")).longValue());
        assertEquals(1, fixture.mapper.caseCount());

        CityGameOperationsOutcome conflict = fixture.service.trace(7L, json(trace("tr-1").replace("src-1", "src-2")));
        assertEquals(409, conflict.httpStatus);
        assertEquals("CONFLICT", conflict.errorCode);
        assertEquals(1, fixture.mapper.caseCount());

        fixture.grant.grant(9L, "other", CityGameOperationsPermissions.TRACE);
        CityGameOperationsOutcome otherAccount = fixture.service.trace(9L, json(trace("tr-1")));
        assertEquals(403, otherAccount.httpStatus);
        assertEquals(List.of(), otherAccount.data.get("actions"));
        assertEquals(1, fixture.mapper.caseCount());

        CityGameOperationsService restarted = new CityGameOperationsService(
                fixture.mapper, fixture.facts, fixture.grant, fixture.assets, fixture.commands);
        CityGameOperationsOutcome read = restarted.readRequest(7L, "tr-1");
        assertEquals(200, read.httpStatus);
        assertEquals(((Number) traced.data.get("caseId")).longValue(), ((Number) read.data.get("caseId")).longValue());
        assertEquals(Boolean.FALSE, read.data.get("resent"));
    }

    @Test
    void pauseRestoreAndCorrectStayOnThatStore() {
        Fixture fixture = fixture();
        grantDesk(fixture);
        fixture.grant.grant(8L, "reviewer", CityGameOperationsPermissions.REVIEW,
                CityGameOperationsPermissions.APPLY, CityGameOperationsPermissions.QUERY);
        fixture.grant.grant(7L, "desk", CityGameOperationsPermissions.TRACE, CityGameOperationsPermissions.PROPOSE,
                CityGameOperationsPermissions.QUERY, CityGameOperationsPermissions.REVIEW);
        long caseId = caseId(fixture.service.trace(7L, json(trace("tr-1"))));
        assertEquals(200, fixture.service.propose(7L, json(pause("pr-1", caseId, 1))).httpStatus);
        CityGameOperationsOutcome rejected = fixture.service.review(7L, json(review("rv-self", caseId, 2, "APPROVE")));
        assertEquals("SELF_APPROVAL", rejected.errorCode);
        assertEquals(2L, ((Number) fixture.service.readCase(8L, caseId).data.get("version")).longValue());

        assertEquals(200, fixture.service.review(8L, json(review("rv-1", caseId, 2, "APPROVE"))).httpStatus);
        CityGameOperationsOutcome applied = fixture.service.apply(8L, json(apply("ap-1", caseId, 3)));
        assertEquals(200, applied.httpStatus);
        assertEquals("PAUSE_MERCHANT", applied.data.get("correctionState"));
        assertEquals("CONFIG_ONLY", applied.data.get("execution"));
        assertEquals("PAUSED", ((Map<?, ?>) applied.data.get("storeOverlay")).get("factStatus"));
        assertFalse(CityGameOperationsTexts.write(applied.data.get("storeOverlay")).contains("pointRate"));
        assertEquals(0, fixture.awards.calls);
        assertEquals(1, fixture.mapper.storeCount());

        CityGameOperationsOutcome again = fixture.service.apply(8L, json(apply("ap-1", caseId, 3)));
        assertEquals(200, again.httpStatus);
        assertEquals("PAUSED", fixture.mapper.selectStore("m-1").getFactStatus());
        assertEquals(1L, fixture.mapper.selectStore("m-1").getVersion().longValue());
        assertNull(fixture.mapper.selectStore("m-2"));

        CityGameOperationsOutcome otherStore = fixture.service.propose(7L, json(pause("pr-2", caseId, 4).replace("m-1", "m-2")));
        assertEquals("OBJECT_SCOPE", otherStore.errorCode);
        assertNull(fixture.mapper.selectStore("m-2"));

        assertEquals(200, fixture.service.propose(7L, json(restore("pr-3", caseId, 4))).httpStatus);
        assertEquals(200, fixture.service.review(8L, json(review("rv-2", caseId, 5, "APPROVE"))).httpStatus);
        CityGameOperationsOutcome restored = fixture.service.apply(8L, json(apply("ap-2", caseId, 6)));
        assertEquals("RESTORED", fixture.mapper.selectStore("m-1").getFactStatus());
        assertEquals("RESTORE", restored.data.get("correctionState"));
        assertNull(fixture.mapper.selectStore("m-2"));

        CityGameOperationsOutcome missing = fixture.service.readStore(8L, "m-2");
        assertEquals(Boolean.TRUE, missing.data.get("missing"));
        assertNull(missing.data.get("storeFact"));
    }

    @Test
    void compensateIsNotSentTwiceAndUnknownIsNotResent() {
        Fixture fixture = fixture();
        grantDesk(fixture);
        fixture.grant.grant(8L, "reviewer", CityGameOperationsPermissions.REVIEW, CityGameOperationsPermissions.APPLY);
        long caseId = caseId(fixture.service.trace(7L, json(trace("tr-9"))));
        assertEquals(200, fixture.service.propose(7L, json(compensate("pr-9", caseId, 1))).httpStatus);
        assertEquals(200, fixture.service.review(8L, json(review("rv-9", caseId, 2, "APPROVE"))).httpStatus);
        CityGameOperationsOutcome recorded = fixture.service.apply(8L, json(apply("ap-9", caseId, 3)));
        assertEquals(200, recorded.httpStatus);
        assertEquals("COMPENSATE", recorded.data.get("correctionState"));
        assertEquals("NON_EXECUTABLE", recorded.data.get("execution"));
        assertEquals("ASSET_EXECUTION_DISABLED", recorded.data.get("blocker"));
        assertEquals(1, fixture.assets.executions());
        assertEquals(0, fixture.awards.calls);
        assertEquals(0, fixture.mapper.storeCount());
        fixture.service.apply(8L, json(apply("ap-9", caseId, 3)));
        assertEquals(1, fixture.assets.executions());

        long paused = caseId(fixture.service.trace(7L, json(trace("tr-8").replace("act-1", "act-8").replace("tr-9", "tr-8"))));
        assertEquals(200, fixture.service.propose(7L, json(pause("pr-8", paused, 1))).httpStatus);
        assertEquals(200, fixture.service.review(8L, json(review("rv-8", paused, 2, "APPROVE"))).httpStatus);
        fixture.mapper.failTerminalUpdate = true;
        int audits = fixture.mapper.auditCount();
        CityGameOperationsOutcome unknown = fixture.service.apply(8L, json(apply("ap-8", paused, 3)));
        assertEquals("UNKNOWN", unknown.errorCode);
        assertEquals(Boolean.FALSE, unknown.data.get("resent"));
        assertEquals(1L, fixture.mapper.selectStore("m-1").getVersion().longValue());
        CityGameOperationsOutcome reread = fixture.service.apply(8L, json(apply("ap-8", paused, 3)));
        assertEquals("UNKNOWN", reread.errorCode);
        assertEquals(1L, fixture.mapper.selectStore("m-1").getVersion().longValue());
        assertEquals(audits + 1, fixture.mapper.auditCount());
        assertEquals("UNKNOWN", fixture.service.readRequest(8L, "ap-8").errorCode);
    }

    @Test
    void correctThenRevokeAndAssetFieldsDoNotWrite() {
        Fixture fixture = fixture();
        grantDesk(fixture);
        fixture.grant.grant(8L, "reviewer", CityGameOperationsPermissions.REVIEW, CityGameOperationsPermissions.APPLY);
        long caseId = caseId(fixture.service.trace(7L, json(trace("tr-3"))));
        CityGameOperationsOutcome rate = fixture.service.propose(7L, json(pause("pr-rate", caseId, 1).replace("}", ",\"teamId\":\"team-b\"}")));
        assertEquals("ASSET_SCOPE_REJECTED", rate.errorCode);
        assertEquals(0, fixture.mapper.storeCount());

        assertEquals(200, fixture.service.propose(7L, json(correct("pr-c", caseId, 1))).httpStatus);
        assertEquals(200, fixture.service.review(8L, json(review("rv-c", caseId, 2, "APPROVE"))).httpStatus);
        CityGameOperationsOutcome corrected = fixture.service.apply(8L, json(apply("ap-c", caseId, 3)));
        assertEquals("desk-note", corrected.data.get("classification"));
        assertEquals(200, fixture.service.propose(7L, json(revoke("pr-v", caseId, 4))).httpStatus);
        assertEquals(200, fixture.service.review(8L, json(review("rv-v", caseId, 5, "APPROVE"))).httpStatus);
        CityGameOperationsOutcome revoked = fixture.service.apply(8L, json(apply("ap-v", caseId, 6)));
        assertEquals("REVOKE", revoked.data.get("correctionState"));
        assertNull(revoked.data.get("classification"));
        assertEquals(0, fixture.awards.calls);

        CityGameOperationsOutcome ledger = fixture.service.propose(7L, json(
                "{\"requestId\":\"pr-l\",\"expectedVersion\":7,\"caseId\":" + caseId
                        + ",\"reason\":\"no\",\"originalRequestId\":\"src-1\",\"objectType\":\"LEDGER\",\"objectId\":\"act-1\","
                        + "\"ownerUserId\":3,\"objectRole\":\"merchant-operator\",\"kind\":\"DISABLE\"}"));
        assertEquals("ASSET_EXECUTION_DISABLED", ledger.errorCode);
        assertEquals(0, fixture.mapper.storeCount());
    }

    @Test
    void versionConflictStaysReadableAndAuditIsAppendOnly() {
        Fixture fixture = fixture();
        grantDesk(fixture);
        long caseId = caseId(fixture.service.trace(7L, json(trace("tr-4"))));
        CityGameOperationsOutcome conflict = fixture.service.propose(7L, json(pause("pr-x", caseId, 9)));
        assertEquals("VERSION_CONFLICT", conflict.errorCode);
        assertEquals(1L, ((Number) fixture.service.readCase(7L, caseId).data.get("version")).longValue());
        CityGameOperationsOutcome read = fixture.service.readRequest(7L, "pr-x");
        assertEquals("VERSION_CONFLICT", read.errorCode);
        for (Method method : CityGameOperationsMapper.class.getDeclaredMethods()) {
            String name = method.getName().toLowerCase();
            assertFalse(name.contains("delete"), name);
            assertFalse(name.startsWith("updateaudit"), name);
        }
        List<?> audit = (List<?>) read.data.get("audit");
        assertTrue(audit.size() >= 2);
    }

    private static void grantDesk(Fixture fixture) {
        fixture.grant.grant(7L, "desk", CityGameOperationsPermissions.TRACE, CityGameOperationsPermissions.PROPOSE,
                CityGameOperationsPermissions.QUERY);
    }

    private static long caseId(CityGameOperationsOutcome outcome) {
        assertEquals(200, outcome.httpStatus, outcome.errorCode);
        return ((Number) outcome.data.get("caseId")).longValue();
    }

    private static Fixture fixture() {
        CityGameOperationsMemoryMapper mapper = new CityGameOperationsMemoryMapper();
        ScriptedFacts facts = new ScriptedFacts();
        facts.slots.put("ownership", CityGameOperationsFactPort.Slot.present(Map.of(
                "ownerUserId", 3L, "objectRole", "merchant-operator", "sourceRequestId", "src-1", "teamId", "team-a")));
        facts.slots.put("placeFacts", CityGameOperationsFactPort.Slot.present(Map.of("merchantId", "m-1", "label", "North")));
        facts.slots.put("evidence", CityGameOperationsFactPort.Slot.missing("NO_EVIDENCE_SOURCE"));
        facts.slots.put("rule", CityGameOperationsFactPort.Slot.present(Map.of(
                "rulesReleaseId", "rules-1", "rulesHash", HASH, "lifecycle", "FROZEN", "title", "Synthetic")));
        facts.slots.put("task", CityGameOperationsFactPort.Slot.present(Map.of("taskId", "task-1", "steps", List.of("Arrive"))));
        facts.slots.put("dice", CityGameOperationsFactPort.Slot.present(Map.of("diceId", "dice-1", "faces", List.of(1, 2, 3, 4))));
        facts.slots.put("quote", CityGameOperationsFactPort.Slot.present(Map.of("quoteId", "quote-1")));
        facts.slots.put("ledger", CityGameOperationsFactPort.Slot.present(Map.of("memberId", 3L, "detailRows", 2L)));
        facts.slots.put("timeline", CityGameOperationsFactPort.Slot.present(Map.of(
                "events", List.of(Map.of("eventId", "ev-1", "kind", "seen")))));
        Grant grant = new Grant();
        AwardProbe awards = new AwardProbe();
        CityGameOperationsAssetExecution assets = new CityGameOperationsAssetExecution(awards, new CityGameOperationsExecutionPolicy());
        List<CityGameOperationsCommand> commands = List.of(
                new CityGameOperationsCompensateCommand(), new CityGameOperationsRevokeCommand(),
                new CityGameOperationsDisableCommand(), new CityGameOperationsRestoreCommand(),
                new CityGameOperationsCorrectCommand());
        return new Fixture(mapper, facts, grant, awards, assets, commands,
                new CityGameOperationsService(mapper, facts, grant, assets, commands));
    }

    private static JsonNode json(String text) {
        try {
            return CityGameOperationsTexts.mapper().readTree(text);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String trace(String requestId) {
        return "{\"requestId\":\"" + requestId + "\",\"expectedVersion\":0,\"actionId\":\"act-1\",\"regionId\":\"city-1\",\"sourceRequestId\":\"src-1\"}";
    }

    private static String pause(String requestId, long caseId, long version) {
        return command(requestId, caseId, version, "PAUSE_MERCHANT", "MERCHANT_STORE", "m-1") + ",\"merchantId\":\"m-1\"}";
    }

    private static String restore(String requestId, long caseId, long version) {
        return command(requestId, caseId, version, "RESTORE", "MERCHANT_STORE", "m-1") + ",\"merchantId\":\"m-1\"}";
    }

    private static String compensate(String requestId, long caseId, long version) {
        return command(requestId, caseId, version, "COMPENSATE", "ACTION", "act-1") + "}";
    }

    private static String correct(String requestId, long caseId, long version) {
        return command(requestId, caseId, version, "CORRECT", "ACTION", "act-1")
                + ",\"classification\":\"desk-note\",\"opsNote\":\"checked\"}";
    }

    private static String revoke(String requestId, long caseId, long version) {
        return command(requestId, caseId, version, "REVOKE", "ACTION", "act-1") + "}";
    }

    private static String command(String requestId, long caseId, long version, String kind, String type, String objectId) {
        return "{\"requestId\":\"" + requestId + "\",\"expectedVersion\":" + version + ",\"caseId\":" + caseId
                + ",\"reason\":\"operator reason\",\"originalRequestId\":\"src-1\",\"objectType\":\"" + type
                + "\",\"objectId\":\"" + objectId + "\",\"ownerUserId\":3,\"objectRole\":\"merchant-operator\",\"kind\":\"" + kind + "\"";
    }

    private static String review(String requestId, long caseId, long version, String decision) {
        return "{\"requestId\":\"" + requestId + "\",\"expectedVersion\":" + version + ",\"caseId\":" + caseId
                + ",\"reason\":\"human check\",\"decision\":\"" + decision + "\"}";
    }

    private static String apply(String requestId, long caseId, long version) {
        return "{\"requestId\":\"" + requestId + "\",\"expectedVersion\":" + version + ",\"caseId\":" + caseId + "}";
    }

    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static final class Fixture {
        final CityGameOperationsMemoryMapper mapper;
        final ScriptedFacts facts;
        final Grant grant;
        final AwardProbe awards;
        final CityGameOperationsAssetExecution assets;
        final List<CityGameOperationsCommand> commands;
        final CityGameOperationsService service;

        Fixture(CityGameOperationsMemoryMapper mapper, ScriptedFacts facts, Grant grant, AwardProbe awards,
                CityGameOperationsAssetExecution assets, List<CityGameOperationsCommand> commands,
                CityGameOperationsService service) {
            this.mapper = mapper;
            this.facts = facts;
            this.grant = grant;
            this.awards = awards;
            this.assets = assets;
            this.commands = commands;
            this.service = service;
        }
    }

    private static final class ScriptedFacts implements CityGameOperationsFactPort {
        final Map<String, Slot> slots = new HashMap<String, Slot>();

        @Override
        public Slot read(String slot, String actionId, String regionId) {
            return slots.getOrDefault(slot, Slot.missing("MISSING"));
        }
    }

    private static final class Grant implements CityGameOperationsPermissionPort {
        final Map<Long, Set<String>> allowed = new HashMap<Long, Set<String>>();
        final Map<Long, String> roles = new HashMap<Long, String>();

        void grant(long actor, String role, String... permissions) {
            allowed.put(actor, new HashSet<String>(Arrays.asList(permissions)));
            roles.put(actor, role);
        }

        @Override
        public boolean allows(long actorUserId, String permission) {
            return allowed.getOrDefault(actorUserId, Set.of()).contains(permission);
        }

        @Override
        public String roleKeys(long actorUserId) {
            return roles.getOrDefault(actorUserId, "");
        }
    }

    private static final class AwardProbe implements IPointsAwardService {
        int calls;

        @Override
        public int award(Long memberId, Long eventType, Long eventId, String reason) {
            calls++;
            return 0;
        }

        @Override
        public int awardPublisherSettlement(Long memberId, Long topicId) {
            calls++;
            return 0;
        }
    }
}
