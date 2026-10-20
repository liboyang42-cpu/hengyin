package com.chengyinhub.business.city.draft;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.chengyinhub.business.city.draft.CityBoardDraftService.object;
import static com.chengyinhub.business.city.draft.CityBoardDraftV2.*;

/** Synthetic selectors and proposed values only; no approved catalog, real people or runtime fixtures. */
public class CityBoardDraftV2Test {
    static final String HASH = String.join("", Collections.nCopies(64, "a"));
    static final String OTHER_HASH = String.join("", Collections.nCopies(64, "b"));
    static Map<String, Object> ref(String kind) { return object("kind", kind, "id", "test-" + kind, "version", 1,
            "hash", HASH, "regionId", "test-region", "scope", "CITY"); }
    static Map<String, Object> place(String id) { return object("placeId", id, "version", 1, "hash", HASH, "regionId", "test-region"); }
    static Map<String, Object> lot(String id, String role) {
        Map<String, Object> lot = object("gameLotId", id, "placeRef", place("place-" + id), "role", role,
                "taskRef", ref("TASK"), "rulesRef", ref("RULES"), "taskRewardPoints", 0,
                "capabilities", new ArrayList<>(Arrays.asList(object("capabilityId", "help", "taskRef", ref("TASK"),
                        "serviceFeePoints", 0, "taskRewardPoints", 0))));
        if (!"PUBLIC_NEUTRAL".equals(role)) lot.put("acquisitionCostPoints", 0);
        if ("MERCHANT_SERVICE".equals(role)) lot.put("merchantId", "test-merchant");
        return lot;
    }
    static Map<String, Object> fixture() {
        return object("schemaVersion", 2, "classification", "platform_rule", "status", "PROPOSED", "scope", "CITY", "entry", "MAP",
                "officialGameId", "permanent-game", "name", "Synthetic V2 proposal",
                "board", object("boardId", "board-A", "seasonId", "season-A", "version", 1, "regionId", "test-region",
                        "timezone", "Asia/Shanghai", "startsAt", "2026-10-03T00:00:00Z", "freezeAt", "2026-11-02T00:00:00Z", "endsAt", "2026-11-03T00:00:00Z",
                        "openWindows", Arrays.asList(object("opensAt", "2026-10-03T00:00:00Z", "closesAt", "2026-11-02T00:00:00Z"))),
                "ruleRefs", object("rulesRef", ref("RULES"), "taskRef", ref("TASK"), "diceRef", ref("DICE"), "challengeRef", ref("CHALLENGE"), "settlementRef", ref("SETTLEMENT")),
                "policy", object("actionBudget", 2, "actionRecoverySeconds", 3600, "maxHoldings", 1, "holdingSeconds", 7200,
                        "protectionSeconds", 100, "taskExpirySeconds", 900, "rewardBudgetPoints", 10, "privacyDelaySeconds", 60),
                "gameLots", new ArrayList<>(Arrays.asList(lot("neutral", "PUBLIC_NEUTRAL"), lot("public", "PUBLIC_OPERABLE"), lot("merchant", "MERCHANT_SERVICE"))),
                "sets", new ArrayList<>(Arrays.asList(object("setId", "set-A", "gameLotIds", Arrays.asList("public", "merchant"), "cooperationMode", "ACCEPTED_COOPERATION", "rulesRef", ref("RULES")))),
                "upgrades", new ArrayList<>(Arrays.asList(object("upgradeId", "upgrade-A", "gameLotId", "public", "level", 1,
                        "capabilityIds", Arrays.asList("help"), "taskRef", ref("TASK"), "rulesRef", ref("RULES"), "costPoints", 0, "protectionSeconds", 0))));
    }
    @SuppressWarnings("unchecked") static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") static List<Object> list(Object value) { return (List<Object>) value; }
    static Map<String, Object> first(Map<String, Object> value, String key) { return map(list(value.get(key)).get(0)); }
    static Draft parseFixture(Map<String, Object> value) { return parse(JSON.toJSONString(value)); }
    static void rejects(String code, Map<String, Object> value) { assertEquals(code, assertThrows(Failure.class, () -> parseFixture(value)).code); }
    static void rejectsJson(String code, String json) { assertEquals(code, assertThrows(Failure.class, () -> parse(json)).code); }
    static Map<String, Object> intent(Draft draft, boolean team) {
        return object("schemaVersion", 2, "officialGameId", draft.officialGameId, "boardId", draft.board.boardId,
                "boardVersion", draft.board.version, "boardHash", draft.canonicalHash, "participationId", "requested-participation",
                "actor", team ? object("actorKind", "TEAM", "memberId", "member-A", "teamId", "existing-team", "expectedMembershipVersion", 3)
                        : object("actorKind", "SOLO", "memberId", "member-A"));
    }
    @Test void parsesImmutableTypedProposalAndDistinctRoles() {
        Draft draft = parseFixture(fixture());
        assertEquals("PROPOSED", draft.status); assertEquals("MAP", draft.entry); assertEquals("CITY", draft.scope);
        assertEquals("permanent-game", draft.officialGameId); assertEquals(3, draft.gameLots.size()); assertEquals(5, draft.requestedReferences.size());
        assertNull(draft.gameLots.get(0).merchantId); assertNull(draft.gameLots.get(0).acquisitionCostPoints);
        assertEquals("test-merchant", draft.gameLots.get(2).merchantId);
        assertThrows(UnsupportedOperationException.class, () -> draft.gameLots.clear());
        assertThrows(UnsupportedOperationException.class, () -> draft.gameLots.get(0).capabilities.clear());
        assertThrows(UnsupportedOperationException.class, () -> draft.sets.get(0).gameLotIds.clear());
    }
    @Test void canonicalRoundTripAndKeyOrderAreStable() {
        Draft first = parseFixture(fixture()); Map<String, Object> reversed = new TreeMap<>(Collections.reverseOrder()); reversed.putAll(fixture());
        Draft second = parseFixture(reversed); assertEquals(first.canonicalJson(), second.canonicalJson()); assertEquals(first.canonicalHash, second.canonicalHash);
        assertEquals(first.canonicalHash, parse(first.canonicalJson()).canonicalHash);
        assertEquals(64, first.canonicalHash.length());
    }
    @Test void detachedFromCallerAndEveryProposedValueBindsHash() {
        Map<String, Object> input = fixture(); Draft original = parseFixture(input); input.put("name", "changed");
        assertEquals("Synthetic V2 proposal", original.name); assertNotEquals(original.canonicalHash, parseFixture(input).canonicalHash);
        input = fixture(); map(input.get("policy")).put("actionBudget", 3); assertNotEquals(original.canonicalHash, parseFixture(input).canonicalHash);
        input = fixture(); map(first(input, "gameLots").get("taskRef")).put("version", 2); assertNotEquals(original.canonicalHash, parseFixture(input).canonicalHash);
    }
    @Test void samePermanentGameCanHaveDifferentBoundedBoardsWithoutSilentHashReuse() {
        Draft first = parseFixture(fixture()); Map<String, Object> next = fixture(); map(next.get("board")).put("boardId", "board-B"); map(next.get("board")).put("seasonId", "season-B");
        Draft second = parseFixture(next); assertEquals(first.officialGameId, second.officialGameId); assertNotEquals(first.canonicalHash, second.canonicalHash);
    }
    @Test void exactVersionTwoRequiredAndV1PayloadIsNotAutoUpgraded() {
        for (Object value : Arrays.asList(1, 3, 0, -1, 2.0, "2", null)) {
            Map<String, Object> input = fixture(); input.put("schemaVersion", value); assertThrows(Failure.class, () -> parseFixture(input));
        }
        rejects("UNKNOWN_OR_MISSING_FIELD", CityBoardDraftPersistenceTest.payload());
    }
    @Test void allRootAndNestedFieldsAreRequiredWithoutDefaults() {
        for (String key : fixture().keySet()) { Map<String, Object> input = fixture(); input.remove(key); rejects("UNKNOWN_OR_MISSING_FIELD", input); }
        for (String section : Arrays.asList("board", "policy", "ruleRefs")) {
            for (String key : map(fixture().get(section)).keySet()) { Map<String, Object> input = fixture(); map(input.get(section)).remove(key); rejects("UNKNOWN_OR_MISSING_FIELD", input); }
        }
    }
    @Test void unknownApprovalPrivacyEconomyAndAssociationFieldsReject() {
        for (String key : Arrays.asList("approved", "safe", "licensed", "locationApproved", "taskSucceeded", "runtimeEnabled", "themeId", "topicId", "activityId", "roomId", "wallet", "coins", "initialGift", "debt", "passingFee", "opponentPosition", "lastLanding", "trajectory")) {
            Map<String, Object> input = fixture(); input.put(key, true); rejects("UNKNOWN_OR_MISSING_FIELD", input);
            input = fixture(); first(input, "gameLots").put(key, true); rejects("UNKNOWN_OR_MISSING_FIELD", input);
        }
    }
    @Test void wrongScopeEntryAndStatusCannotPretendToBeApproved() {
        for (String field : Arrays.asList("scope", "entry", "status", "classification")) {
            Map<String, Object> input = fixture(); input.put(field, "APPROVED"); assertThrows(Failure.class, () -> parseFixture(input));
        }
    }
    @Test void rawDuplicateKeysIncludingEscapedNamesReject() {
        String value = JSON.toJSONString(fixture());
        rejectsJson("DUPLICATE_JSON_KEY", value.replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"schemaVersion\":2"));
        rejectsJson("DUPLICATE_JSON_KEY", value.replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"schema\\u0056ersion\":2"));
        rejectsJson("DUPLICATE_JSON_KEY", value.replace("\"actionBudget\":2", "\"actionBudget\":2,\"actionBudget\":2"));
    }
    @Test void malformedNonJsonAndTrailingDataReject() {
        for (String value : Arrays.asList("", " ", "{", "{a:1}", "{'a':1}", "{\"x\":1,}", "[1,]", "{\"x\":01}", "{\"x\":NaN}", "{\"x\":Infinity}", "{\"x\":\"a\n\"}", "{}{}", "{} trailing", "/*x*/{}", "{\"x\":\"\\uD800\"}"))
            rejectsJson("MALFORMED_JSON", value);
    }
    @Test void oversizedAndDeepPayloadsAreBoundedBeforeBinding() {
        rejectsJson("JSON_TOO_LARGE", String.join("", Collections.nCopies(MAX_JSON_BYTES + 1, " ")));
        rejectsJson("JSON_TOO_DEEP", String.join("", Collections.nCopies(26, "[")) + "0" + String.join("", Collections.nCopies(26, "]")));
        rejectsJson("JSON_TOO_LARGE", "{\"name\":\"" + String.join("", Collections.nCopies(50000, "界")) + "\"}");
    }
    @Test void negativeHugeFractionExponentBooleanAndStringNumbersReject() {
        for (Object value : Arrays.asList(-1, Integer.MAX_VALUE + 1L, Long.MAX_VALUE, new BigInteger("9999999999999999999999999999"), 1.5, "2", true, null)) {
            Map<String, Object> input = fixture(); map(input.get("policy")).put("actionBudget", value); assertThrows(Failure.class, () -> parseFixture(input));
        }
        String value = JSON.toJSONString(fixture());
        rejectsJson("INTEGER_REQUIRED", value.replace("\"actionBudget\":2", "\"actionBudget\":2e0"));
        rejectsJson("INTEGER_REQUIRED", value.replace("\"actionBudget\":2", "\"actionBudget\":2.0"));
        rejectsJson("NONCANONICAL_INTEGER", value.replace("\"rewardBudgetPoints\":10", "\"rewardBudgetPoints\":-0"));
    }
    @Test void numericalEdgesAreExplicitAndNoRewardExceedsProposedBudget() {
        Map<String, Object> input = fixture(); map(input.get("policy")).put("rewardBudgetPoints", Integer.MAX_VALUE);
        first(input, "gameLots").put("taskRewardPoints", Integer.MAX_VALUE); parseFixture(input);
        map(input.get("policy")).put("rewardBudgetPoints", 0); rejects("INTEGER_OUT_OF_RANGE", input);
        input = fixture(); map(input.get("policy")).put("protectionSeconds", 7201); rejects("INTEGER_OUT_OF_RANGE", input);
        input = fixture(); first(input, "upgrades").put("protectionSeconds", 101); rejects("INTEGER_OUT_OF_RANGE", input);
        input = fixture(); map(input.get("policy")).put("maxHoldings", 3); rejects("HOLDING_LIMIT_EXCEEDS_LOTS", input);
    }
    @Test void explicitZeroEconomyAllowedButDoesNotIssuePoints() {
        Map<String, Object> input = fixture(); map(input.get("policy")).put("rewardBudgetPoints", 0);
        Draft draft = parseFixture(input); assertEquals(0, draft.policy.rewardBudgetPoints); assertEquals(0, draft.gameLots.get(0).taskRewardPoints);
    }
    @Test void boundedFiniteOrderedBoardAndOpenWindowsRequired() {
        for (String end : Arrays.asList("2026-10-03T00:00:00Z", "2026-10-02T00:00:00Z", "+999999999-12-31T23:59:59Z", "9999-12-31T23:59:59Z", "bad")) {
            Map<String, Object> input = fixture(); map(input.get("board")).put("endsAt", end); assertThrows(Failure.class, () -> parseFixture(input));
        }
        Map<String, Object> input = fixture(); map(input.get("board")).put("freezeAt", "2026-11-03T00:00:00Z"); rejects("INVALID_BOARD_WINDOW", input);
        input = fixture(); first(map(input.get("board")), "openWindows").put("closesAt", "2026-11-02T00:00:01Z"); rejects("INVALID_OPEN_WINDOW", input);
        input = fixture(); Map<String, Object> board = map(input.get("board")); board.put("openWindows", Arrays.asList(
                object("opensAt", "2026-10-04T00:00:00Z", "closesAt", "2026-10-05T00:00:00Z"), object("opensAt", "2026-10-03T00:00:00Z", "closesAt", "2026-10-04T00:00:00Z")));
        rejects("OVERLAPPING_OR_UNORDERED_WINDOWS", input);
    }
    @Test void utcCanonicalTimesAndValidTimezoneRequired() {
        for (String value : Arrays.asList("2026-10-03T08:00:00+08:00", "2026-10-03T00:00:00.000Z", "2026-10-03")) {
            Map<String, Object> input = fixture(); map(input.get("board")).put("startsAt", value); assertThrows(Failure.class, () -> parseFixture(input));
        }
        Map<String, Object> input = fixture(); map(input.get("board")).put("timezone", "not-a-zone"); rejects("INVALID_TIMEZONE", input);
    }
    @Test void exactDurationCeilingIncludesFractionalRemainder() {
        Map<String, Object> input = fixture(); Map<String, Object> board = map(input.get("board")); board.put("endsAt", "2027-10-04T00:00:00Z"); parseFixture(input);
        board.put("endsAt", "2027-10-04T00:00:00.001Z"); rejects("BOARD_WINDOW_TOO_LONG", input);
    }
    @Test void crossRegionPlaceReferenceAndWrongReferenceKindsReject() {
        Map<String, Object> input = fixture(); map(first(input, "gameLots").get("placeRef")).put("regionId", "other"); rejects("CROSS_REGION_PLACE", input);
        input = fixture(); map(first(input, "gameLots").get("taskRef")).put("regionId", "other"); rejects("CROSS_REGION_REFERENCE", input);
        input = fixture(); map(first(input, "gameLots").get("taskRef")).put("kind", "RULES"); rejects("REFERENCE_KIND_MISMATCH", input);
        input = fixture(); map(first(input, "gameLots").get("taskRef")).put("scope", "TOPIC"); rejects("CITY_REFERENCE_ONLY", input);
    }
    @Test void referenceVersionHashAndIdentityBindingsAreExact() {
        Map<String, Object> input = fixture(); map(first(input, "gameLots").get("taskRef")).put("hash", OTHER_HASH); rejects("CONFLICTING_REFERENCE_BINDING", input);
        input = fixture(); map(first(input, "gameLots").get("taskRef")).put("version", 0); rejects("INTEGER_OUT_OF_RANGE", input);
        input = fixture(); map(first(input, "gameLots").get("taskRef")).put("hash", HASH.toUpperCase(Locale.ROOT)); rejects("INVALID_HASH", input);
        input = fixture(); map(first(input, "gameLots").get("taskRef")).put("version", 2); assertEquals(6, parseFixture(input).requestedReferences.size());
    }
    @Test void duplicateLotPlaceCapabilitySetAndUpgradeIdsReject() {
        Map<String, Object> input = fixture(); list(input.get("gameLots")).add(first(input, "gameLots")); rejects("DUPLICATE_GAME_LOT_ID", input);
        input = fixture(); map(list(input.get("gameLots")).get(1)).put("placeRef", place("place-neutral")); rejects("DUPLICATE_PHYSICAL_PLACE", input);
        input = fixture(); List<Object> caps = list(first(input, "gameLots").get("capabilities")); caps.add(caps.get(0)); rejects("DUPLICATE_CAPABILITY_ID", input);
        input = fixture(); list(input.get("sets")).add(first(input, "sets")); rejects("DUPLICATE_SET_ID", input);
        input = fixture(); list(input.get("upgrades")).add(first(input, "upgrades")); rejects("DUPLICATE_UPGRADE_ID", input);
    }
    @Test void collectionCeilingsAreEnforced() {
        for (String section : Arrays.asList("gameLots", "sets", "upgrades")) {
            Map<String, Object> input = fixture(); Object item = first(input, section); input.put(section, Collections.nCopies(101, item)); rejects("ARRAY_SIZE_OUT_OF_RANGE", input);
        }
        Map<String, Object> input = fixture(); first(input, "gameLots").put("capabilities", Collections.nCopies(9, first(first(input, "gameLots"), "capabilities"))); rejects("ARRAY_SIZE_OUT_OF_RANGE", input);
    }
    @Test void publicLotsNeverNeedFakeMerchantAndMerchantRequiresExplicitAssociation() {
        Map<String, Object> input = fixture(); first(input, "gameLots").put("merchantId", "fake"); rejects("UNKNOWN_OR_MISSING_FIELD", input);
        input = fixture(); map(list(input.get("gameLots")).get(2)).remove("merchantId"); rejects("UNKNOWN_OR_MISSING_FIELD", input);
    }
    @Test void neutralPathCannotBeRemovedChargedAcquiredUpgradedOrOwnedAsASet() {
        Map<String, Object> input = fixture(); list(input.get("gameLots")).remove(0); rejects("FREE_NEUTRAL_PATH_REQUIRED", input);
        input = fixture(); first(first(input, "gameLots"), "capabilities").put("serviceFeePoints", 1); rejects("NEUTRAL_MUST_BE_FREE", input);
        input = fixture(); first(input, "gameLots").put("acquisitionCostPoints", 0); rejects("UNKNOWN_OR_MISSING_FIELD", input);
        input = fixture(); first(input, "upgrades").put("gameLotId", "neutral"); rejects("NEUTRAL_NOT_ACQUIRABLE_OR_UPGRADABLE", input);
        input = fixture(); first(input, "sets").put("gameLotIds", Arrays.asList("public", "neutral")); rejects("NEUTRAL_NOT_OPERATING_SET_MEMBER", input);
    }
    @Test void danglingSetUpgradeAndCapabilityReferencesReject() {
        Map<String, Object> input = fixture(); first(input, "sets").put("gameLotIds", Arrays.asList("public", "missing")); rejects("UNKNOWN_GAME_LOT", input);
        input = fixture(); first(input, "upgrades").put("gameLotId", "missing"); rejects("UNKNOWN_GAME_LOT", input);
        input = fixture(); first(input, "upgrades").put("capabilityIds", Arrays.asList("missing")); rejects("UNKNOWN_LOT_CAPABILITY", input);
        input = fixture(); first(input, "sets").put("gameLotIds", Arrays.asList("public", "public")); rejects("DUPLICATE_ID", input);
    }
    @Test void upgradeLevelsMustBeUniqueContiguousAndBounded() {
        Map<String, Object> input = fixture(); first(input, "upgrades").put("level", 2); rejects("NONCONTIGUOUS_UPGRADE_LEVELS", input);
        input = fixture(); Map<String, Object> other = new LinkedHashMap<>(first(input, "upgrades")); other.put("upgradeId", "other"); list(input.get("upgrades")).add(other); rejects("DUPLICATE_UPGRADE_LEVEL", input);
        input = fixture(); first(input, "upgrades").put("level", 9); rejects("INTEGER_OUT_OF_RANGE", input);
    }
    @Test void soloAndExistingTeamAreDifferentUntrustedIntents() {
        Draft draft = parseFixture(fixture());
        AdmissionIntent solo = parseAdmissionIntent(JSON.toJSONString(intent(draft, false))); assertEquals(ActorKind.SOLO, solo.actor.actorKind);
        assertNull(solo.actor.teamId); assertNull(solo.actor.expectedMembershipVersion); draft.requireBinding(solo);
        AdmissionIntent team = parseAdmissionIntent(JSON.toJSONString(intent(draft, true))); assertEquals("existing-team", team.actor.teamId); assertEquals(Long.valueOf(3), team.actor.expectedMembershipVersion); draft.requireBinding(team);
    }
    @Test void actorDiscriminantCannotInventTeamOrClaimMembershipApproval() {
        Draft draft = parseFixture(fixture());
        Map<String, Object> solo = intent(draft, false); map(solo.get("actor")).put("teamId", "fake"); assertThrows(Failure.class, () -> parseAdmissionIntent(JSON.toJSONString(solo)));
        for (String key : Arrays.asList("teamId", "expectedMembershipVersion", "memberId")) {
            Map<String, Object> team = intent(draft, true); map(team.get("actor")).remove(key); assertThrows(Failure.class, () -> parseAdmissionIntent(JSON.toJSONString(team)));
        }
        for (String key : Arrays.asList("approved", "consent", "currentMembership", "location", "roomId", "points")) {
            Map<String, Object> team = intent(draft, true); map(team.get("actor")).put(key, true); assertThrows(Failure.class, () -> parseAdmissionIntent(JSON.toJSONString(team)));
        }
    }
    @Test void admissionBindsExactPermanentGameBoardVersionAndHash() {
        Draft draft = parseFixture(fixture());
        for (String key : Arrays.asList("officialGameId", "boardId", "boardVersion", "boardHash")) {
            Map<String, Object> intent = intent(draft, false); intent.put(key, "boardVersion".equals(key) ? 2 : "boardHash".equals(key) ? OTHER_HASH : "different");
            AdmissionIntent parsed = parseAdmissionIntent(JSON.toJSONString(intent)); assertEquals("ADMISSION_DRAFT_BINDING_MISMATCH", assertThrows(Failure.class, () -> draft.requireBinding(parsed)).code);
        }
    }
    @Test void v1StrictParserStillRejectsV2AndExistingV1HashIsUnchanged() {
        CityBoardDraftPersistenceTest.TestAuthority authority = new CityBoardDraftPersistenceTest.TestAuthority();
        CityBoardDraftService v1 = CityBoardDraftPersistenceTest.service(CityBoardDraftPersistenceTest.database("mem-v1-not-written"), authority);
        Map<String, Object> command = CityBoardDraftPersistenceTest.command("v1-only", 0, null);
        assertEquals(true, v1.validate(command).get("valid"));
        assertEquals("bd60ae5ee56ab2836d34fae271e6c950c015b9cfa805aaa95b1952d8c7452e6b", CityBoardDraftService.hash(CityBoardDraftPersistenceTest.payload()));
        command.put("payload", fixture());
        assertEquals("UNKNOWN_OR_MISSING_FIELD", assertThrows(CityBoardDraftService.Failure.class, () -> v1.validate(command)).getMessage());
        Map<String, Object> v1Unknown = CityBoardDraftPersistenceTest.command("v1-unknown", 0, null); map(v1Unknown.get("payload")).put("schemaVersion", 1);
        assertEquals("UNKNOWN_OR_MISSING_FIELD", assertThrows(CityBoardDraftService.Failure.class, () -> v1.validate(v1Unknown)).getMessage());
    }

    @Test void libraryReferenceMetadataCannotReplaceExactSelectors() {
        for (String path : Arrays.asList("$.ruleRefs.taskRef", "$", "..", "@")) {
            Map<String, Object> input = fixture(); first(input, "gameLots").put("taskRef", object("$ref", path));
            rejects("UNKNOWN_OR_MISSING_FIELD", input);
            String escaped = JSON.toJSONString(input).replace("\"$ref\"", "\"$\\u0072ef\"");
            rejectsJson("UNKNOWN_OR_MISSING_FIELD", escaped);
            rejectsJson("UNKNOWN_OR_MISSING_FIELD", JSON.toJSONString(input).replace("\"$ref\"", "\"\\u0024ref\""));
        }
        Map<String, Object> input = fixture(); input.put("@type", "java.util.LinkedHashMap");
        rejects("UNKNOWN_OR_MISSING_FIELD", input);
        input = fixture(); input.put("name", "Literal $ref and @type are harmless text"); parseFixture(input);
    }
    @Test void unicodeEscapesRequireAsciiHexDigits() {
        String json = JSON.toJSONString(fixture());
        String escape = "\\" + "u";
        for (String digits : Arrays.asList("００４１", "٠٠٤١", "00４1", "00Ｆ1")) {
            rejectsJson("MALFORMED_JSON", json.replace("Synthetic V2 proposal", escape + digits));
        }
        for (String invalid : Arrays.asList(escape + "D800", escape + "DC00", escape + "DC00" + escape + "D800",
                String.valueOf((char) 0xD800), String.valueOf((char) 0xDC00))) {
            rejectsJson("MALFORMED_JSON", json.replace("Synthetic V2 proposal", invalid));
        }
        Draft ascii = parse(json.replace("Synthetic V2 proposal", escape + "0041")); assertEquals("A", ascii.name);
        Draft supplementary = parse(json.replace("Synthetic V2 proposal", escape + "D83D" + escape + "DE00"));
        assertEquals("😀", supplementary.name);
    }
    @Test void historicalBoardProposalParsesWithoutClaimingCurrentAdmission() {
        Map<String, Object> input = fixture(); Map<String, Object> board = map(input.get("board"));
        board.put("startsAt", "2020-01-01T00:00:00Z"); board.put("freezeAt", "2020-01-02T00:00:00Z");
        board.put("endsAt", "2020-01-03T00:00:00Z");
        board.put("openWindows", Arrays.asList(object("opensAt", "2020-01-01T00:00:00Z", "closesAt", "2020-01-02T00:00:00Z")));
        Draft historical = parseFixture(input); assertEquals("PROPOSED", historical.status);
        historical.requireBinding(parseAdmissionIntent(JSON.toJSONString(intent(historical, false))));
    }
}
