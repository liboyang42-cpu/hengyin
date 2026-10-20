package com.chengyinhub.business.service.support.release;

import com.chengyinhub.business.domain.GameSession;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.service.support.NodeJourneySnapshot;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.chengyinhub.business.service.support.release.ContentReleaseManifest.UnresolvedDependency;
import com.chengyinhub.business.service.support.release.ContentReleaseSource.NpcBinding;
import com.chengyinhub.business.service.support.release.ContentReleaseSource.TemplateSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.*;

class ContentReleaseCompilerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HASH = String.join("", Collections.nCopies(64, "a"));
    private final ContentReleaseCompiler compiler = new ContentReleaseCompiler();

    @Test void sameSnapshotProducesStableHashAndFrozenComponentHash() throws Exception {
        ContentReleaseManifest first = compile(fixture("route-snapshot-v1.json"));
        ContentReleaseManifest second = compile(fixture("route-snapshot-v1.json"));
        assertEquals(first.getContentHash(), second.getContentHash());
        assertEquals(fixture("route-snapshot-v1.sha256").trim(), first.getContentHash());
        assertTrue(first.getContentHash().matches("[a-f0-9]{64}"));
        JsonNode component = tree(first).path("frozenComponents").path("topicRouteSnapshot");
        assertEquals(CanonicalContentJson.sha256(CanonicalContentJson.write(component.path("content"))),
                component.path("contentHash").asText());
        assertEquals(1, first.getSchemaVersion());
        assertEquals(42, first.getTopicId());
        assertEquals(7, first.getSourceConfigVersion());
    }

    @Test void objectOrderingWhitespaceAndMetadataOrderingDoNotChangeHash() throws Exception {
        String raw = fixture("route-snapshot-v1.json");
        List<TemplateSource> templates = Arrays.asList(template(1, "v1"), template(2, "v2"));
        List<NpcBinding> npcs = Arrays.asList(npc(1, "guide", false), npc(2, "host", false));
        ContentReleaseManifest first = compiler.compile(source(raw), templates, npcs);
        List<TemplateSource> reversedTemplates = new ArrayList<TemplateSource>(templates);
        List<NpcBinding> reversedNpcs = new ArrayList<NpcBinding>(npcs);
        Collections.reverse(reversedTemplates); Collections.reverse(reversedNpcs);
        ContentReleaseManifest second = compiler.compile(source(reverseObjects(JSON.readTree(raw)).toString()),
                reversedTemplates, reversedNpcs);
        assertEquals(first.getContentHash(), second.getContentHash());
    }

    @Test void unicodeNfcAndEquivalentNumbersProduceSameHash() {
        String raw = fixture("route-snapshot-v1.json");
        String equivalent = raw.replace("Café", "Cafe\u0301").replace("\"clues\": 1", "\"clues\": 1.00e0");
        assertEquals(compile(raw).getContentHash(), compile(equivalent).getContentHash());
        assertEquals("{\"café\":0,\"值\":9007199254740993.0001}",
                CanonicalContentJson.write(CanonicalContentJson.object(
                        "{\"cafe\\u0301\":-0.00,\"值\":9007199254740993.000100}", "fixture")));
    }

    @Test void distinctPreciseNumbersStayDistinct() {
        JsonNode first = CanonicalContentJson.object("{\"n\":9007199254740993.0001}", "fixture");
        JsonNode second = CanonicalContentJson.object("{\"n\":9007199254740993.0002}", "fixture");
        assertNotEquals(CanonicalContentJson.sha256(CanonicalContentJson.write(first)),
                CanonicalContentJson.sha256(CanonicalContentJson.write(second)));
    }

    @Test void gameplayArraysKeepTheirOrder() throws Exception {
        ObjectNode original = (ObjectNode) JSON.readTree(fixture("route-snapshot-v1.json"));
        ((ObjectNode) original.path("nodeJourney").path("1")).putArray("effects")
                .addObject().put("value", 1);
        ((com.fasterxml.jackson.databind.node.ArrayNode) original.path("nodeJourney").path("1").path("effects"))
                .addObject().put("value", 2);
        ObjectNode changed = original.deepCopy();
        ((ObjectNode) changed.path("nodeJourney").path("1")).putArray("effects")
                .addObject().put("value", 2);
        ((com.fasterxml.jackson.databind.node.ArrayNode) changed.path("nodeJourney").path("1").path("effects"))
                .addObject().put("value", 1);
        assertNotEquals(compile(original.toString()).getContentHash(), compile(changed.toString()).getContentHash());
    }

    @Test void changedAnswerOrSourceVersionChangesHash() {
        String raw = fixture("route-snapshot-v1.json");
        assertNotEquals(compile(raw).getContentHash(), compile(raw.replace("Café", "Tea")).getContentHash());
        TopicRouteSession route = route(raw); route.setConfigVersion(8L);
        assertNotEquals(compile(raw).getContentHash(),
                compiler.compile(ContentReleaseSource.fromSnapshots(route, null)).getContentHash());
    }

    @Test void capturesImmutableSourceAndDoesNotHashPlayerTeamOrProgressState() {
        String raw = fixture("route-snapshot-v1.json");
        TopicRouteSession route = route(raw);
        ContentReleaseSource captured = ContentReleaseSource.fromSnapshots(route, null);
        String originalHash = compiler.compile(captured).getContentHash();
        route.setMemberId(999L); route.setStateJson("{\"secretPlayerMemory\":true}");
        route.setSeed(123L); route.setVersion(99); route.setActivityId(888L);
        assertEquals(originalHash, compiler.compile(ContentReleaseSource.fromSnapshots(route, null)).getContentHash());
        route.setConfigSnapshotJson(raw.replace("Café", "changed"));
        assertEquals(originalHash, compiler.compile(captured).getContentHash());
        assertNotEquals(originalHash, compiler.compile(ContentReleaseSource.fromSnapshots(route, null)).getContentHash());
        assertFalse(compiler.compile(captured).serverOnlyJson().contains("secretPlayerMemory"));
    }

    @Test void candidateIsNeverPublishableWhileDependenciesRemainLive() {
        ContentReleaseManifest manifest = compile(fixture("route-snapshot-v1.json"));
        assertFalse(manifest.isCompleteRelease());
        assertTrue(manifest.getUnresolvedDependencies().containsAll(Arrays.asList(
                UnresolvedDependency.REWARD_RULES, UnresolvedDependency.ASSET_RIGHTS,
                UnresolvedDependency.REGISTERED_RULE_REFERENCES)));
        assertThrows(IllegalStateException.class, manifest::requireCompleteRelease);
        assertThrows(UnsupportedOperationException.class, () -> manifest.getUnresolvedDependencies().clear());
    }

    @Test void secretSnapshotIsExcludedFromDefaultSerialization() throws Exception {
        ContentReleaseManifest manifest = compile(fixture("route-snapshot-v1.json"));
        assertTrue(manifest.serverOnlyJson().contains("SECRET_ENDING"));
        String serialized = JSON.writeValueAsString(manifest);
        assertFalse(serialized.contains("SECRET_ENDING"));
        assertFalse(serialized.contains("serverManifestJson"));
        assertFalse(serialized.contains("questionAnswer"));
        String fastJson = com.alibaba.fastjson2.JSON.toJSONString(manifest);
        assertFalse(fastJson.contains("SECRET_ENDING"));
        assertFalse(fastJson.contains("questionAnswer"));
    }

    @Test void branchSnapshotStillWorksWithExistingReadersAndEngine() throws Exception {
        String raw = fixture("route-snapshot-v1.json");
        String frozen = tree(compile(raw)).path("frozenComponents").path("topicRouteSnapshot").path("content").toString();
        assertEquals(NodeJourneySnapshot.answer(raw, 1L), NodeJourneySnapshot.answer(frozen, 1L));
        assertEquals(NodeJourneySnapshot.block(raw, 1L), NodeJourneySnapshot.block(frozen, 1L));
        assertEquals(new TopicRouteEngine().initial(raw).getCurrentNodeId(),
                new TopicRouteEngine().initial(frozen).getCurrentNodeId());
        assertEquals(new TopicRouteEngine().initial(raw).getNodeStates(),
                new TopicRouteEngine().initial(frozen).getNodeStates());
    }

    @Test void linearSnapshotKeepsNoEdgesAndDisclosesMissingFrozenOrder() throws Exception {
        ContentReleaseManifest manifest = compile(fixture("linear-snapshot-v1.json"));
        assertFalse(tree(manifest).path("frozenComponents").path("topicRouteSnapshot").path("content").has("edges"));
        assertTrue(manifest.getUnresolvedDependencies().contains(UnresolvedDependency.LINEAR_NODE_ORDER));
    }

    @Test void legacyLiveFallbackRemainsReadableButCannotBeCertified() {
        String legacy = "{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[1],\"variables\":{}}";
        assertNull(NodeJourneySnapshot.answer(legacy, 1L));
        assertNull(NodeJourneySnapshot.block(legacy, 1L));
        assertThrows(IllegalArgumentException.class, () -> compile(legacy));
    }

    @Test void malformedDuplicateOrTrailingJsonIsRejectedWithoutLeakingContent() {
        for (String raw : Arrays.asList("[]", "{", "{} {}", "{\"secret\":1,\"secret\":2}",
                "{\"é\":1,\"e\\u0301\":2}", "{\"text\":\"\\ud800\"}", "{\"n\":1e10001}")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> compile(raw));
            assertFalse(error.getMessage().contains("secret"));
            assertNull(error.getCause());
        }
    }

    @Test void deeplyNestedCreatorPayloadIsRejectedBeforeTreeMaterialization() {
        StringBuilder nested = new StringBuilder();
        for (int i = 0; i < 20000; i++) nested.append("{\"secret\":");
        nested.append('0');
        for (int i = 0; i < 20000; i++) nested.append('}');
        assertTrue(nested.length() < 1024 * 1024);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CanonicalContentJson.object(nested.toString(), "route snapshot"));
        assertEquals("Invalid content-release route snapshot", error.getMessage());
        assertNull(error.getCause());
    }

    @Test void boundedStreamingPreflightStillAcceptsSupportedNesting() {
        StringBuilder nested = new StringBuilder();
        for (int i = 0; i < 100; i++) nested.append("{\"x\":");
        nested.append('0');
        for (int i = 0; i < 100; i++) nested.append('}');
        assertEquals(nested.toString(), CanonicalContentJson.write(CanonicalContentJson.object(nested.toString(), "fixture")));
    }

    @Test void streamingPreflightBoundsTokenCountAndNumberLength() {
        StringBuilder tokens = new StringBuilder("{\"items\":[0");
        for (int i = 0; i < 100001; i++) tokens.append(",0");
        tokens.append("]}");
        assertThrows(IllegalArgumentException.class, () -> CanonicalContentJson.object(tokens.toString(), "fixture"));
        StringBuilder number = new StringBuilder("{\"n\":1");
        for (int i = 0; i < 2000; i++) number.append('0');
        number.append('}');
        assertThrows(IllegalArgumentException.class, () -> CanonicalContentJson.object(number.toString(), "fixture"));
    }

    @Test void unsupportedOrOverflowingVersionIsRejected() {
        String raw = fixture("route-snapshot-v1.json");
        for (String version : Arrays.asList("2", "4294967297", "9223372036854775809", "\"1\"")) {
            assertThrows(IllegalArgumentException.class, () -> compile(raw.replace("\"schemaVersion\": 1", "\"schemaVersion\": " + version)));
        }
    }

    @Test void danglingUnreachableAndNonterminatingRoutesAreRejected() throws Exception {
        ObjectNode raw = (ObjectNode) JSON.readTree(fixture("route-snapshot-v1.json"));
        ((ObjectNode) raw.path("edges").get(0)).put("toNodeId", 99);
        assertThrows(IllegalArgumentException.class, () -> compile(raw.toString()));
        ObjectNode disconnected = (ObjectNode) JSON.readTree(fixture("route-snapshot-v1.json"));
        ((ObjectNode) disconnected.path("nodeAnswers")).putObject("4");
        assertThrows(IllegalArgumentException.class, () -> compile(disconnected.toString()));
        ObjectNode deadEnd = (ObjectNode) JSON.readTree(fixture("route-snapshot-v1.json"));
        ((com.fasterxml.jackson.databind.node.ArrayNode) deadEnd.path("edges")).remove(1);
        assertThrows(IllegalArgumentException.class, () -> compile(deadEnd.toString()));
    }

    @Test void frozenNodeAndChapterReferencesMustResolve() throws Exception {
        ObjectNode raw = (ObjectNode) JSON.readTree(fixture("route-snapshot-v1.json"));
        ((ObjectNode) raw.path("nodeJourney")).putObject("99");
        assertThrows(IllegalArgumentException.class, () -> compile(raw.toString()));
        ObjectNode chapter = (ObjectNode) JSON.readTree(fixture("route-snapshot-v1.json"));
        ((ObjectNode) chapter.path("thoughtChapters").get(0)).putArray("nodeIds").add(99);
        assertThrows(IllegalArgumentException.class, () -> compile(chapter.toString()));
        ObjectNode opening = (ObjectNode) JSON.readTree(fixture("route-snapshot-v1.json"));
        opening.putObject("openingChapter").put("chapterId", 99);
        assertThrows(IllegalArgumentException.class, () -> compile(opening.toString()));
    }

    @Test void branchNodeReferencesCannotTruncateOrOverflow() throws Exception {
        for (String value : Arrays.asList("1.5", "18446744073709551617", "\"1\"")) {
            ObjectNode raw = (ObjectNode) JSON.readTree(fixture("route-snapshot-v1.json"));
            ((ObjectNode) raw.path("edges").get(0)).set("fromNodeId", JSON.readTree(value));
            assertThrows(IllegalArgumentException.class, () -> compile(raw.toString()));
        }
    }

    @Test void ambiguousNodeKeysAndInjectedRuntimeFieldsAreRejected() throws Exception {
        ObjectNode raw = (ObjectNode) JSON.readTree(fixture("route-snapshot-v1.json"));
        ((ObjectNode) raw.path("nodeAnswers")).putObject("01");
        assertThrows(IllegalArgumentException.class, () -> compile(raw.toString()));
        ObjectNode state = (ObjectNode) JSON.readTree(fixture("route-snapshot-v1.json"));
        state.putObject("stateJson").put("player", "secret");
        assertThrows(IllegalArgumentException.class, () -> compile(state.toString()));
    }

    @Test void optionalAiDoesNotRequireKnowledgeWhenDisabled() {
        ContentReleaseManifest noAi = compiler.compile(source(fixture("route-snapshot-v1.json")),
                Collections.<TemplateSource>emptyList(), Collections.singletonList(npc(1, "guide", false)));
        assertFalse(noAi.getUnresolvedDependencies().contains(UnresolvedDependency.NPC_KNOWLEDGE));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(source(fixture("route-snapshot-v1.json")),
                Collections.<TemplateSource>emptyList(), Collections.singletonList(npc(1, "guide", true))));
    }

    @Test void knowledgeReferenceIsRequiredWhenEnabledButIsNotAReadinessClaim() {
        NpcBinding binding = new NpcBinding(42, 1, "guide", true, "knowledge:10", "v2", HASH);
        ContentReleaseManifest manifest = compiler.compile(source(fixture("route-snapshot-v1.json")),
                Collections.<TemplateSource>emptyList(), Collections.singletonList(binding));
        assertTrue(manifest.getUnresolvedDependencies().contains(UnresolvedDependency.NPC_KNOWLEDGE));
        assertThrows(IllegalStateException.class, manifest::requireCompleteRelease);
    }

    @Test void crossTopicDanglingOrDuplicateNpcBindingsAreRejected() {
        ContentReleaseSource source = source(fixture("route-snapshot-v1.json"));
        List<TemplateSource> noTemplates = Collections.emptyList();
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(source, noTemplates,
                Collections.singletonList(new NpcBinding(43, 1, "guide", false, null, null, null))));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(source, noTemplates,
                Collections.singletonList(npc(99, "guide", false))));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(source, noTemplates,
                Arrays.asList(npc(1, "guide", false), npc(2, "guide", false))));
    }

    @Test void enabledNpcRequiresAllKnowledgeMetadataAndValidHash() {
        ContentReleaseSource source = source(fixture("route-snapshot-v1.json"));
        for (NpcBinding binding : Arrays.asList(
                new NpcBinding(42, 1, "guide", true, null, "v1", HASH),
                new NpcBinding(42, 1, "guide", true, "knowledge:1", null, HASH),
                new NpcBinding(42, 1, "guide", true, "knowledge:1", "v1", "invalid"))) {
            assertThrows(IllegalArgumentException.class, () -> compiler.compile(source,
                    Collections.<TemplateSource>emptyList(), Collections.singletonList(binding)));
        }
    }

    @Test void canonicallyEquivalentNpcKeysCannotCreateTwoBindings() {
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(source(fixture("route-snapshot-v1.json")),
                Collections.<TemplateSource>emptyList(), Arrays.asList(npc(1, "café", false), npc(2, "cafe\u0301", false))));
    }

    @Test void unpublishedAndCrossSchemaActivitySnapshotsAreRejected() {
        TopicRouteSession route = route(fixture("route-snapshot-v1.json"));
        GameSession game = activity();
        game.setModuleConfigSnapshotJson("{\"schemaVersion\":2}");
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(ContentReleaseSource.fromSnapshots(route, game)));
        route.setConfigVersion(0L);
        assertThrows(IllegalArgumentException.class, () -> ContentReleaseSource.fromSnapshots(route, null));
    }

    @Test void templateProvenanceDoesNotClaimALicenseAndChangesHash() {
        ContentReleaseSource source = source(fixture("route-snapshot-v1.json"));
        ContentReleaseManifest first = compiler.compile(source, Collections.singletonList(template(1, "v1")), Collections.<NpcBinding>emptyList());
        ContentReleaseManifest second = compiler.compile(source, Collections.singletonList(template(1, "v2")), Collections.<NpcBinding>emptyList());
        assertTrue(first.getUnresolvedDependencies().contains(UnresolvedDependency.TEMPLATE_RIGHTS));
        assertFalse(first.serverOnlyJson().contains("licensed"));
        assertNotEquals(first.getContentHash(), second.getContentHash());
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(source,
                Arrays.asList(template(1, "v1"), template(1, "v1")), Collections.<NpcBinding>emptyList()));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(source,
                Collections.singletonList(template(99, "v1")), Collections.<NpcBinding>emptyList()));
    }

    @Test void sameScopeActivityModuleIsCapturedWithoutTeamOrOperatorIds() throws Exception {
        TopicRouteSession route = route(fixture("route-snapshot-v1.json"));
        GameSession game = activity();
        ContentReleaseManifest first = compiler.compile(ContentReleaseSource.fromSnapshots(route, game));
        game.setClubId(12345L); game.setPreparedBy(54321L); game.setRevision(999);
        assertEquals(first.getContentHash(), compiler.compile(ContentReleaseSource.fromSnapshots(route, game)).getContentHash());
        assertTrue(tree(first).path("frozenComponents").has("activityModuleSnapshot"));
        assertTrue(first.getUnresolvedDependencies().contains(UnresolvedDependency.ACTIVITY_MODULE_REFERENCES));
        assertFalse(first.serverOnlyJson().contains("preparedBy"));
    }

    @Test void mismatchedMissingAndUnversionedSourceSnapshotsAreRejected() {
        TopicRouteSession route = route(fixture("route-snapshot-v1.json"));
        GameSession game = activity(); game.setTopicId(99L);
        assertThrows(IllegalArgumentException.class, () -> ContentReleaseSource.fromSnapshots(route, game));
        game.setTopicId(42L); game.setActivityId(999L);
        assertThrows(IllegalArgumentException.class, () -> ContentReleaseSource.fromSnapshots(route, game));
        game.setActivityId(100L); game.setModuleConfigSnapshotJson(null);
        assertThrows(IllegalArgumentException.class, () -> ContentReleaseSource.fromSnapshots(route, game));
        route.setConfigVersion(null);
        assertThrows(IllegalArgumentException.class, () -> ContentReleaseSource.fromSnapshots(route, null));
    }

    private ContentReleaseManifest compile(String raw) { return compiler.compile(source(raw)); }
    private ContentReleaseSource source(String raw) { return ContentReleaseSource.fromSnapshots(route(raw), null); }
    private TopicRouteSession route(String raw) {
        TopicRouteSession route = new TopicRouteSession();
        route.setTopicId(42L); route.setActivityId(100L); route.setMemberId(77L);
        route.setConfigVersion(7L); route.setConfigSnapshotJson(raw);
        return route;
    }
    private GameSession activity() {
        GameSession game = new GameSession();
        game.setTopicId(42L); game.setActivityId(100L);
        game.setModuleConfigSnapshotJson(fixture("activity-module-snapshot-v1.json"));
        return game;
    }
    private TemplateSource template(long nodeId, String version) { return new TemplateSource(nodeId, "cms_member_template:9", version, HASH); }
    private NpcBinding npc(long nodeId, String role, boolean enabled) { return new NpcBinding(42, nodeId, role, enabled, null, null, null); }
    private JsonNode tree(ContentReleaseManifest manifest) throws Exception { return JSON.readTree(manifest.serverOnlyJson()); }
    private String fixture(String name) {
        try (InputStream stream = getClass().getResourceAsStream("/contracts/content-release/" + name);
             Scanner scanner = new Scanner(stream, "UTF-8")) { return scanner.useDelimiter("\\A").next(); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private JsonNode reverseObjects(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            List<String> names = new ArrayList<String>();
            Iterator<String> fields = node.fieldNames(); fields.forEachRemaining(names::add);
            Collections.reverse(names);
            for (String name : names) result.set(name, reverseObjects(node.get(name)));
            return result;
        }
        if (node.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode result = JSON.createArrayNode();
            for (JsonNode child : node) result.add(reverseObjects(child));
            return result;
        }
        return node;
    }
}
