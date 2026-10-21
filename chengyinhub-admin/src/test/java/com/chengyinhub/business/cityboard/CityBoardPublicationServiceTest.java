package com.chengyinhub.business.cityboard;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityBoardPublicationServiceTest {

    @Test
    void draftReviewFreezeAndPlayerReadTheSameRelease() throws Exception {
        Fixture fixture = new Fixture(new CityBoardSourceProbe());
        CityBoardOutcome saved = fixture.service.save(7L, fixture.save("save-1", 0, null, doc()));
        assertEquals(200, saved.httpStatus);
        long draftId = num(saved.data.get("draftId"));
        assertEquals(1L, num(saved.data.get("version")));
        assertEquals("DRAFT", saved.data.get("status"));
        assertNull(saved.data.get("approvalHash"));
        Map<?, ?> dice = map(map(saved.data.get("document")).get("dice"));
        assertFalse(dice.containsKey("weights"));
        assertFalse(dice.containsKey("probability"));

        CityBoardOutcome replay = fixture.service.save(7L, fixture.save("save-1", 0, null, doc()));
        assertEquals(draftId, num(replay.data.get("draftId")));
        assertEquals(1, fixture.mapper.draftCount());

        CityBoardOutcome duplicate = fixture.service.save(7L, fixture.save("save-1", 0, null, doc().replace("Synthetic CITY", "Other CITY")));
        assertEquals(409, duplicate.httpStatus);
        assertEquals("DUPLICATE_REQUEST", duplicate.errorCode);
        assertEquals(draftId, num(map(duplicate.data.get("original")).get("draftId")));

        CityBoardOutcome self = fixture.service.validate(7L, fixture.draft("self-1", draftId, 1));
        assertEquals(403, self.httpStatus);
        assertEquals("SELF_APPROVAL", self.errorCode);

        CityBoardOutcome conflict = fixture.service.save(7L, fixture.save("conflict-1", 9, draftId, doc()));
        assertEquals(409, conflict.httpStatus);
        CityBoardOutcome conflictRead = fixture.service.readOperation("conflict-1");
        assertEquals(409, conflictRead.httpStatus);
        assertEquals("VERSION_CONFLICT", conflictRead.errorCode);
        assertEquals(1L, num(fixture.service.readDraft(7L, draftId, false).data.get("version")));

        CityBoardOutcome stranger = fixture.service.save(9L, fixture.save("stranger-1", 1, draftId, doc().replace("Synthetic CITY", "Hijack")));
        assertEquals(403, stranger.httpStatus);
        assertEquals("NOT_OWNER", stranger.errorCode);

        CityBoardOutcome approved = fixture.service.validate(8L, fixture.draft("approve-1", draftId, 1));
        assertEquals(200, approved.httpStatus);
        String approval = (String) approved.data.get("approvalHash");
        String contentHash = (String) approved.data.get("contentHash");
        assertEquals(64, approval.length());
        assertEquals(approval, CityBoardHashes.approval(contentHash, draftId, 1L, 8L, "approve-1"));

        CityBoardOutcome clientApproval = fixture.service.publish(9L, fixture.json(
                "{\"requestId\":\"pub-client\",\"draftId\":" + draftId + ",\"expectedVersion\":1,\"approvalHash\":\"aaaaaaaa\"}"));
        assertEquals(400, clientApproval.httpStatus);
        assertEquals("CLIENT_APPROVAL_REJECTED", clientApproval.errorCode);
        assertEquals(0, fixture.mapper.releaseCount());
        assertEquals(400, fixture.service.readOperation("pub-client").httpStatus);

        CityBoardOutcome published = fixture.service.publish(9L, fixture.draft("pub-1", draftId, 1));
        assertEquals(200, published.httpStatus);
        assertEquals("FROZEN", published.data.get("status"));
        assertEquals(contentHash, published.data.get("contentHash"));
        assertEquals(approval, published.data.get("approvalHash"));
        Map<?, ?> board = map(published.data.get("board"));
        assertEquals("FROZEN", board.get("lifecycle"));
        assertEquals(contentHash, board.get("rulesHash"));
        assertEquals("board-1", board.get("boardId"));
        assertEquals(1L, num(board.get("revision")));
        assertEquals("NOT_APPROVED", published.data.get("transport"));

        CityBoardOutcome lost = fixture.service.publish(9L, fixture.draft("pub-1", draftId, 1));
        assertEquals(num(published.data.get("releaseId")), num(lost.data.get("releaseId")));
        assertEquals(1, fixture.mapper.releaseCount());

        CityBoardOutcome current = fixture.service.playerCurrent(7L, Map.of("regionId", "city-1"));
        assertEquals(200, current.httpStatus);
        assertEquals("CITY_PLAYER_READ_V1", current.data.get("contract"));
        assertEquals("AVAILABLE", current.data.get("status"));
        assertEquals("UNAVAILABLE", current.data.get("participationStatus"));
        assertEquals("UNAVAILABLE", current.data.get("pointsStatus"));
        assertNull(current.data.get("participation"));
        assertNull(current.data.get("points"));
        assertEquals(contentHash, map(current.data.get("board")).get("rulesHash"));
        String currentJson = CityBoardDraftDocument.mapper().writeValueAsString(current.data);
        assertFalse(currentJson.contains("JOINED"));
        assertFalse(currentJson.contains("\"mine\""));

        Map<String, String> snapshot = Map.of(
                "regionId", "city-1",
                "boardId", "board-1",
                "seasonId", "season-1",
                "rulesReleaseId", (String) board.get("rulesReleaseId"),
                "revision", "1");
        CityBoardOutcome points = fixture.service.playerPoints(7L, snapshot);
        CityBoardOutcome participation = fixture.service.playerParticipation(7L, snapshot);
        assertEquals(503, points.httpStatus);
        assertEquals("CITY_POINTS_UNAVAILABLE", points.errorCode);
        assertNull(points.data);
        assertEquals(503, participation.httpStatus);
        assertEquals("CITY_PARTICIPATION_UNAVAILABLE", participation.errorCode);
        assertNull(participation.data);
        assertEquals(409, fixture.service.playerPoints(7L, Map.of(
                "regionId", "city-1", "boardId", "board-1", "seasonId", "season-1",
                "rulesReleaseId", (String) board.get("rulesReleaseId"), "revision", "2")).httpStatus);

        CityBoardOutcome empty = fixture.service.playerCurrent(7L, Map.of("regionId", "city-2"));
        assertEquals("NO_CURRENT_BOARD", empty.data.get("status"));
        assertNull(empty.data.get("board"));
        assertNull(empty.data.get("participationStatus"));
        assertNull(empty.data.get("pointsStatus"));
        assertEquals(400, fixture.service.playerCurrent(7L, Map.of("regionId", "city-1", "boardId", "board-1")).httpStatus);

        long releaseId = num(published.data.get("releaseId"));
        CityBoardOutcome reopened = fixture.service.reopen(7L, fixture.json(
                "{\"requestId\":\"reopen-1\",\"releaseId\":" + releaseId + ",\"expectedVersion\":1}"));
        assertEquals(200, reopened.httpStatus);
        assertEquals(contentHash, reopened.data.get("sourceContentHash"));
        long nextDraft = num(reopened.data.get("draftId"));
        CityBoardOutcome edited = fixture.service.save(7L, fixture.save("edit-1", 1, nextDraft, doc().replace("Synthetic CITY", "Edited CITY")));
        assertEquals(200, edited.httpStatus);
        assertEquals("DRAFT", edited.data.get("status"));
        assertNull(edited.data.get("approvalHash"));
        assertEquals(contentHash, map(fixture.service.playerCurrent(7L, Map.of("regionId", "city-1")).data.get("board")).get("rulesHash"));

        fixture.service.validate(8L, fixture.draft("approve-2", nextDraft, num(edited.data.get("version"))));
        CityBoardOutcome second = fixture.service.publish(9L, fixture.draft("pub-2", nextDraft, num(edited.data.get("version"))));
        assertEquals(200, second.httpStatus);
        assertFalse(contentHash.equals(second.data.get("contentHash")));
        assertEquals(second.data.get("contentHash"), map(fixture.service.playerCurrent(7L, Map.of("regionId", "city-1")).data.get("board")).get("rulesHash"));
        assertEquals(contentHash, fixture.service.readOperation("pub-1").data.get("contentHash"));
        assertEquals(0, fixture.mapper.selectReleaseById(releaseId).getCurrentFlag().intValue());
        assertEquals(1, fixture.mapper.selectCurrentRelease("city-1").getCurrentFlag().intValue());
        assertEquals(2, fixture.mapper.releaseCount());
    }

    @Test
    void rejectCancelAndMissingOrExternalSourcesDoNotPublish() throws Exception {
        Fixture fixture = new Fixture(new CityBoardSourceProbe());
        long draftId = num(fixture.service.save(7L, fixture.save("fee-1", 0, null, doc().replace("\"serviceFee\": {\"currency\": \"CNY\", \"amountMinor\": 100},", ""))).data.get("draftId"));
        CityBoardOutcome blocked = fixture.service.validate(8L, fixture.draft("fee-review", draftId, 1));
        assertEquals(400, blocked.httpStatus);
        assertEquals("PUBLICATION_BLOCKED", blocked.errorCode);
        assertTrue(CityBoardDraftDocument.mapper().writeValueAsString(blocked.data).contains("MISSING_FEE"));
        assertEquals("DRAFT", fixture.service.readDraft(8L, draftId, true).data.get("status"));
        assertNull(fixture.service.readDraft(8L, draftId, true).data.get("approvalHash"));

        long rejectedId = num(fixture.service.save(7L, fixture.save("rej-save", 0, null, doc())).data.get("draftId"));
        CityBoardOutcome rejected = fixture.service.reject(8L, fixture.json(
                "{\"requestId\":\"rej-1\",\"draftId\":" + rejectedId + ",\"expectedVersion\":1,\"reason\":\"范围不清\"}"));
        assertEquals("REJECTED", rejected.data.get("status"));
        assertEquals("范围不清", rejected.data.get("reason"));
        CityBoardOutcome rejectReplay = fixture.service.reject(8L, fixture.json(
                "{\"requestId\":\"rej-1\",\"draftId\":" + rejectedId + ",\"expectedVersion\":1,\"reason\":\"范围不清\"}"));
        assertEquals("REJECTED", rejectReplay.data.get("status"));
        assertEquals(rejectedId, num(fixture.service.readOperation("rej-1").data.get("draftId")));
        CityBoardOutcome reopened = fixture.service.reopen(7L, fixture.draft("rej-open", rejectedId, num(rejected.data.get("version"))));
        assertEquals("DRAFT", reopened.data.get("status"));

        long cancelId = num(fixture.service.save(7L, fixture.save("can-save", 0, null, doc())).data.get("draftId"));
        assertEquals("CANCELLED", fixture.service.cancel(7L, fixture.draft("can-1", cancelId, 1)).data.get("status"));
        assertEquals(403, fixture.service.cancel(9L, fixture.draft("can-2", cancelId, 2)).httpStatus);

        Fixture external = new Fixture(new CityBoardSourceProbe());
        long merchant = num(external.service.save(7L, external.save("mer-1", 0, null, merchantDoc())).data.get("draftId"));
        CityBoardOutcome unavailable = external.service.validate(8L, external.draft("mer-review", merchant, 1));
        assertEquals("PUBLICATION_BLOCKED", unavailable.errorCode);
        assertTrue(CityBoardDraftDocument.mapper().writeValueAsString(unavailable.data).contains("SOURCE_UNAVAILABLE"));
        assertEquals(0, external.mapper.releaseCount());

        Fixture denied = new Fixture(new ScriptedProbe(CityBoardSourceProbe.Verdict.DENIED));
        long deniedId = num(denied.service.save(7L, denied.save("den-1", 0, null, merchantDoc())).data.get("draftId"));
        assertTrue(CityBoardDraftDocument.mapper().writeValueAsString(
                denied.service.validate(8L, denied.draft("den-review", deniedId, 1)).data).contains("SOURCE_DENIED"));

        Fixture found = new Fixture(new ScriptedProbe(CityBoardSourceProbe.Verdict.FOUND));
        long foundId = num(found.service.save(7L, found.save("found-1", 0, null, merchantDoc())).data.get("draftId"));
        assertEquals(200, found.service.validate(8L, found.draft("found-review", foundId, 1)).httpStatus);
        CityBoardOutcome merchantRelease = found.service.publish(9L, found.draft("found-pub", foundId, 1));
        assertEquals(200, merchantRelease.httpStatus);
        String snapshot = found.mapper.selectReleaseById(num(merchantRelease.data.get("releaseId"))).getSnapshotJson();
        assertFalse(snapshot.contains("hours"));
        assertFalse(snapshot.contains("landRight"));
        assertFalse(snapshot.contains("ownership"));
        assertTrue(snapshot.contains("\"id\":\"shop-1\""));
    }

    @Test
    void unpublishedReadDoesNotInventMembership() {
        Fixture fixture = new Fixture(new CityBoardSourceProbe());
        CityBoardOutcome current = fixture.service.playerCurrent(7L, Map.of("regionId", "city-1"));
        assertEquals("NO_CURRENT_BOARD", current.data.get("status"));
        assertEquals(409, fixture.service.playerParticipation(7L, Map.of(
                "regionId", "city-1", "boardId", "board-1", "seasonId", "season-1",
                "rulesReleaseId", "rel1", "revision", "1")).httpStatus);
        assertEquals("CITY_SNAPSHOT_CHANGED", fixture.service.playerParticipation(7L, Map.of(
                "regionId", "city-1", "boardId", "board-1", "seasonId", "season-1",
                "rulesReleaseId", "rel1", "revision", "1")).errorCode);
    }

    private static long num(Object value) {
        return ((Number) value).longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static String doc() {
        return "{"
                + "\"title\": \"Synthetic CITY\","
                + "\"regionId\": \"city-1\","
                + "\"gameId\": \"game-1\","
                + "\"boardId\": \"board-1\","
                + "\"season\": {\"seasonId\": \"season-1\", \"budget\": {\"currency\": \"CNY\", \"amountMinor\": 10000}},"
                + "\"seasonSettlement\": {\"budgetCurrency\": \"CNY\", \"budgetAmountMinor\": 10000},"
                + "\"rules\": {\"rulesId\": \"rules-1\", \"text\": \"Walk every point.\"},"
                + "\"dice\": {\"diceId\": \"dice-1\", \"faces\": [1, 2, 3, 4]},"
                + "\"pointCost\": {\"unit\": \"POINT\", \"amount\": 0},"
                + "\"serviceFee\": {\"currency\": \"CNY\", \"amountMinor\": 100},"
                + "\"protection\": {\"challengeId\": \"protect-1\", \"text\": \"Stay on marked path\"},"
                + "\"safety\": {\"statement\": \"Operator confirmed the board text\", \"confirmed\": true},"
                + "\"pointRoles\": [{\"roleId\": \"walker\", \"title\": \"Walker\"}],"
                + "\"points\": [{\"pointId\": \"point-1\", \"title\": \"Gate\", \"latitude\": 31.2, \"longitude\": 121.5, \"roleId\": \"walker\", \"domain\": \"PLATFORM_VIRTUAL\"}],"
                + "\"tasks\": [{\"taskId\": \"task-1\", \"title\": \"Visit gate\", \"pointId\": \"point-1\", \"steps\": [\"Arrive\"]}],"
                + "\"locationPermits\": [],"
                + "\"permissionMatrix\": [{\"permission\": \"cityboard:player:read\", \"scope\": \"region:city-1\"}]"
                + "}";
    }

    private static String merchantDoc() {
        return doc().replace("\"domain\": \"PLATFORM_VIRTUAL\"",
                        "\"domain\": \"MERCHANT\", \"ref\": {\"domain\": \"MERCHANT\", \"source\": \"mms\", \"id\": \"shop-1\"}")
                .replace("\"locationPermits\": []",
                        "\"locationPermits\": [{\"permitId\": \"permit-1\", \"pointId\": \"point-1\", \"source\": \"place\"}], "
                                + "\"references\": [{\"domain\": \"MERCHANT\", \"source\": \"mms\", \"id\": \"shop-1\"}]");
    }

    private static final class ScriptedProbe extends CityBoardSourceProbe {
        private final Verdict verdict;

        private ScriptedProbe(Verdict verdict) {
            this.verdict = verdict;
        }

        @Override
        public Verdict probe(String domain, String source, String id) {
            return verdict;
        }
    }

    private static final class Fixture {
        private final CityBoardMemoryMapper mapper = new CityBoardMemoryMapper();
        private final CityBoardPublicationService service;

        private Fixture(CityBoardSourceProbe probe) {
            this.service = new CityBoardPublicationService(mapper, probe);
        }

        private JsonNode json(String text) throws Exception {
            return CityBoardDraftDocument.mapper().readTree(text);
        }

        private JsonNode save(String requestId, long version, Long draftId, String document) throws Exception {
            String draft = draftId == null ? "" : ",\"draftId\":" + draftId;
            return json("{\"requestId\":\"" + requestId + "\",\"expectedVersion\":" + version + draft + ",\"document\":" + document + "}");
        }

        private JsonNode draft(String requestId, long draftId, long version) throws Exception {
            return json("{\"requestId\":\"" + requestId + "\",\"draftId\":" + draftId + ",\"expectedVersion\":" + version + "}");
        }
    }
}
