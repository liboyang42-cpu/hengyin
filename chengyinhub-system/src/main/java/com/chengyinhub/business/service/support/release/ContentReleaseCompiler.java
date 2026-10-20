package com.chengyinhub.business.service.support.release;

import com.chengyinhub.business.service.support.TopicRouteConfigValidator;
import com.chengyinhub.business.service.support.TopicRoutePublishGate;
import com.chengyinhub.business.service.support.release.ContentReleaseManifest.UnresolvedDependency;
import com.chengyinhub.business.service.support.release.ContentReleaseSource.NpcBinding;
import com.chengyinhub.business.service.support.release.ContentReleaseSource.TemplateSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Inactive, side-effect-free compiler at the existing snapshot seam. No Spring bean, mapper,
 * runtime, publication, authorization or payment integration. Compiling is not approving.
 */
public final class ContentReleaseCompiler {
    private static final Set<String> ROUTE_FIELDS = new HashSet<String>(Arrays.asList(
            "schemaVersion", "startNodeId", "terminalNodeIds", "variables", "edges", "fallbacks",
            "nodeRequirements", "nodeJourney", "nodeAnswers", "journeyRules", "journeyStory",
            "topicCompleteRule", "thoughtChapters", "openingChapter", "stateKeys"));

    public ContentReleaseManifest compile(ContentReleaseSource source) {
        return compile(source, Collections.<TemplateSource>emptyList(), Collections.<NpcBinding>emptyList());
    }

    public ContentReleaseManifest compile(ContentReleaseSource source, List<TemplateSource> templates,
                                          List<NpcBinding> bindings) {
        if (source == null || templates == null || bindings == null) throw invalid("source and metadata required");
        JsonNode route = CanonicalContentJson.object(source.routeSnapshot(), "route snapshot");
        requireVersion(route);
        Iterator<String> fields = route.fieldNames();
        while (fields.hasNext()) {
            if (!ROUTE_FIELDS.contains(fields.next())) throw invalid("unsupported route snapshot field");
        }
        Set<Long> nodeIds = nodeIds(route.path("nodeAnswers"));
        validateRoute(route, nodeIds);
        validateFrozenReferences(route, nodeIds);

        ObjectNode manifest = CanonicalContentJson.JSON.createObjectNode();
        manifest.put("schemaVersion", ContentReleaseManifest.SCHEMA_VERSION);
        manifest.put("contract", "questify.content-snapshot-foundation.v1");
        manifest.put("topicId", source.topicId());
        manifest.put("sourceConfigVersion", source.sourceConfigVersion());
        manifest.put("completeRelease", false);
        ObjectNode frozen = manifest.putObject("frozenComponents");
        component(frozen, "topicRouteSnapshot", route);

        Set<UnresolvedDependency> unresolved = new TreeSet<UnresolvedDependency>();
        // Existing snapshot code explicitly does not freeze all rewards/rights/rule dependencies.
        unresolved.add(UnresolvedDependency.REWARD_RULES);
        unresolved.add(UnresolvedDependency.ASSET_RIGHTS);
        unresolved.add(UnresolvedDependency.REGISTERED_RULE_REFERENCES);
        if (!route.has("edges")) unresolved.add(UnresolvedDependency.LINEAR_NODE_ORDER);
        if (source.activityModuleSnapshot() != null) {
            JsonNode module = CanonicalContentJson.object(source.activityModuleSnapshot(), "activity module snapshot");
            requireVersion(module);
            component(frozen, "activityModuleSnapshot", module);
            // Do not duplicate GameSessionRuntimeServiceImpl's private module validator here.
            unresolved.add(UnresolvedDependency.ACTIVITY_MODULE_REFERENCES);
        }

        manifest.set("templateSources", templateSources(templates, nodeIds));
        if (!templates.isEmpty()) unresolved.add(UnresolvedDependency.TEMPLATE_RIGHTS);
        manifest.set("npcBindings", npcBindings(bindings, source.topicId(), nodeIds, unresolved));
        ArrayNode dependencies = manifest.putArray("unresolvedLiveDependencies");
        for (UnresolvedDependency dependency : unresolved) dependencies.add(dependency.name());
        return new ContentReleaseManifest(source.topicId(), source.sourceConfigVersion(),
                CanonicalContentJson.write(manifest), new ArrayList<UnresolvedDependency>(unresolved));
    }

    private static void component(ObjectNode target, String name, JsonNode content) {
        ObjectNode component = target.putObject(name);
        component.put("contentHash", CanonicalContentJson.sha256(CanonicalContentJson.write(content)));
        component.set("content", content);
    }

    private static Set<Long> nodeIds(JsonNode answers) {
        if (!answers.isObject() || answers.size() == 0) {
            throw invalid("complete frozen nodeAnswers required; legacy live fallback cannot be certified");
        }
        Set<Long> ids = new LinkedHashSet<Long>();
        Iterator<Map.Entry<String, JsonNode>> fields = answers.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            long id = parseNodeKey(field.getKey());
            if (!field.getValue().isObject() || !ids.add(id)) throw invalid("frozen nodeAnswers");
        }
        return ids;
    }

    private static void validateRoute(JsonNode route, Set<Long> nodes) {
        requireNode(route.path("startNodeId"), nodes);
        nodeArray(route.path("terminalNodeIds"), nodes, true);
        if (route.has("edges")) {
            strictBranchReferences(route.path("edges"), nodes);
            if (route.has("fallbacks")) strictBranchReferences(route.path("fallbacks"), nodes);
            if (route.has("nodeRequirements")) {
                if (!route.path("nodeRequirements").isArray()) throw invalid("nodeRequirements");
                for (JsonNode requirement : route.path("nodeRequirements")) {
                    requireNode(requirement.path("nodeId"), nodes);
                }
            }
            try {
                String normalized = new TopicRouteConfigValidator().validateAndNormalize(route.toString(), nodes);
                new TopicRoutePublishGate().assertPublishable(normalized);
            } catch (IllegalArgumentException invalid) {
                throw invalid("route references, reachability or deterministic gate");
            }
        } else if (route.has("fallbacks") || route.has("nodeRequirements")) {
            throw invalid("linear snapshot must not carry branch-only fields");
        }
    }

    /** The legacy validator coerces IDs with asLong; a new immutable contract must not truncate. */
    private static void strictBranchReferences(JsonNode edges, Set<Long> nodes) {
        if (!edges.isArray()) throw invalid("branch edges");
        for (JsonNode edge : edges) {
            requireNode(edge.path("fromNodeId"), nodes);
            requireNode(edge.path("toNodeId"), nodes);
            for (JsonNode condition : edge.path("conditions")) {
                if ("NODE_COMPLETED".equals(condition.path("op").asText())) {
                    requireNode(condition.path("nodeId"), nodes);
                }
            }
        }
    }

    private static void validateFrozenReferences(JsonNode route, Set<Long> nodes) {
        JsonNode journey = route.path("nodeJourney");
        if (!journey.isMissingNode()) {
            if (!journey.isObject()) throw invalid("nodeJourney");
            Iterator<Map.Entry<String, JsonNode>> rows = journey.fields();
            while (rows.hasNext()) {
                Map.Entry<String, JsonNode> row = rows.next();
                if (!nodes.contains(parseNodeKey(row.getKey())) || !row.getValue().isObject()) {
                    throw invalid("nodeJourney reference");
                }
            }
        }
        JsonNode chapters = route.path("thoughtChapters");
        Set<Long> chapterIds = new HashSet<Long>();
        if (!chapters.isMissingNode()) {
            if (!chapters.isArray()) throw invalid("thoughtChapters");
            for (JsonNode chapter : chapters) {
                if (!chapter.isObject() || !chapterIds.add(positiveId(chapter.path("chapterId")))) {
                    throw invalid("thought chapter reference");
                }
                nodeArray(chapter.path("nodeIds"), nodes, false);
                nodeArray(chapter.path("requiredBefore"), nodes, false);
            }
        }
        JsonNode opening = route.path("openingChapter");
        if (!opening.isMissingNode() && (!opening.isObject()
                || !chapterIds.contains(positiveId(opening.path("chapterId"))))) {
            throw invalid("opening chapter reference");
        }
    }

    private static ArrayNode templateSources(List<TemplateSource> sources, Set<Long> nodes) {
        Map<Long, ObjectNode> rows = new TreeMap<Long, ObjectNode>();
        for (TemplateSource source : sources) {
            if (source == null || !nodes.contains(source.nodeId)) throw invalid("template node reference");
            ObjectNode row = CanonicalContentJson.JSON.createObjectNode();
            row.put("nodeId", source.nodeId);
            row.put("sourceReference", requiredText(source.sourceReference));
            optionalText(row, "sourceVersion", source.sourceVersion);
            optionalHash(row, "sourceContentHash", source.sourceContentHash);
            if (rows.put(source.nodeId, row) != null) throw invalid("duplicate template node reference");
        }
        ArrayNode result = CanonicalContentJson.JSON.createArrayNode();
        for (ObjectNode row : rows.values()) result.add(row);
        return result;
    }

    private static ArrayNode npcBindings(List<NpcBinding> bindings, long topicId, Set<Long> nodes,
                                         Set<UnresolvedDependency> unresolved) {
        Map<String, ObjectNode> rows = new TreeMap<String, ObjectNode>();
        for (NpcBinding binding : bindings) {
            if (binding == null || binding.topicId != topicId || !nodes.contains(binding.nodeId)) {
                throw invalid("NPC topic/node reference");
            }
            String role = requiredText(binding.roleKey);
            ObjectNode row = CanonicalContentJson.JSON.createObjectNode();
            row.put("topicId", topicId); row.put("nodeId", binding.nodeId); row.put("roleKey", role);
            row.put("aiEnabled", binding.aiEnabled);
            if (binding.aiEnabled) {
                requiredText(binding.knowledgeReference); requiredText(binding.knowledgeVersion);
                if (binding.knowledgeContentHash == null) throw invalid("enabled NPC knowledge hash required");
                unresolved.add(UnresolvedDependency.NPC_KNOWLEDGE);
            }
            optionalText(row, "knowledgeReference", binding.knowledgeReference);
            optionalText(row, "knowledgeVersion", binding.knowledgeVersion);
            optionalHash(row, "knowledgeContentHash", binding.knowledgeContentHash);
            if (rows.put(role, row) != null) throw invalid("duplicate NPC role");
        }
        ArrayNode result = CanonicalContentJson.JSON.createArrayNode();
        for (ObjectNode row : rows.values()) result.add(row);
        return result;
    }

    private static void optionalText(ObjectNode row, String field, String value) {
        if (value != null) row.put(field, requiredText(value));
    }

    private static void optionalHash(ObjectNode row, String field, String value) {
        if (value == null) return;
        if (!value.matches("[0-9a-fA-F]{64}")) throw invalid("content hash");
        row.put(field, value.toLowerCase(Locale.ROOT));
    }

    private static String requiredText(String value) {
        if (value == null || value.trim().isEmpty() || value.length() > 256) throw invalid("reference metadata");
        return CanonicalContentJson.normalize(value.trim());
    }

    private static void requireVersion(JsonNode root) {
        if (!root.path("schemaVersion").isIntegralNumber() || !root.path("schemaVersion").canConvertToLong()
                || root.path("schemaVersion").longValue() != 1L) {
            throw invalid("unsupported snapshot schemaVersion");
        }
    }

    private static void nodeArray(JsonNode array, Set<Long> nodes, boolean nonempty) {
        if (!array.isArray() || (nonempty && array.size() == 0)) throw invalid("node reference array");
        Set<Long> seen = new HashSet<Long>();
        for (JsonNode node : array) {
            long id = requireNode(node, nodes);
            if (!seen.add(id)) throw invalid("duplicate node reference");
        }
    }

    private static long requireNode(JsonNode node, Set<Long> nodes) {
        long id = positiveId(node);
        if (!nodes.contains(id)) throw invalid("dangling node reference");
        return id;
    }

    private static long positiveId(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() <= 0) {
            throw invalid("positive integer ID required");
        }
        return node.longValue();
    }

    private static long parseNodeKey(String key) {
        try {
            long id = Long.parseLong(key);
            if (id <= 0 || !Long.toString(id).equals(key)) throw invalid("node key");
            return id;
        } catch (NumberFormatException invalid) { throw invalid("node key"); }
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Invalid content-release " + reason);
    }
}
