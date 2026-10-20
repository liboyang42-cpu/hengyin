package com.chengyinhub.business.service.support.release;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.service.support.TopicPublicationValidator;
import com.chengyinhub.business.service.support.TopicStateKeyRefService;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/**
 * Transaction-local adapter over already scope-checked capture rows. No DB/service access,
 * mutation, Spring bean, or production caller outside the inactive capture builder.
 * This reports only the five extracted reference gates, never full publication readiness.
 * NPC IDs are admitted only from exact scope-checked captured rows; absent evidence stays blocked.
 */
final class TopicPublicationValidationContext {
    private final ContentCaptureRows rows;
    private final TopicPublicationValidator validator = new TopicPublicationValidator();
    private final Map<Long,String> configs = new LinkedHashMap<>();
    private final Set<Long> nodeIds = new LinkedHashSet<>();
    private final Set<Long> chapterIds = new LinkedHashSet<>();
    private final Set<Long> branchIds = new LinkedHashSet<>();
    private final Set<Long> outcomeIds = new LinkedHashSet<>();

    TopicPublicationValidationContext(ContentCaptureRows rows) {
        this.rows = rows;
        for (CmsTopicChapter chapter : rows.chapters) chapterIds.add(chapter.getId());
        for (CmsMemberTemplate template : rows.templates.values()) configs.put(template.getId(),template.getAdvancedConfigJson());
        for (CmsTopicNode node : rows.nodes) {
            nodeIds.add(node.getId());
            String raw = configs.get(node.getTemplateId());
            if (TopicPublicationValidator.blockIsBranch(raw)) branchIds.add(node.getId());
            if (TopicPublicationValidator.blockWritesOutcome(raw)) outcomeIds.add(node.getId());
        }
    }

    ObjectNode validate() {
        ObjectNode result = CanonicalContentJson.JSON.createObjectNode();
        result.put("contract", "questify.publication-reference-validation.v1");
        result.put("meaning", "CAPTURED_REFERENCE_CHECKS_ONLY_NOT_PUBLICATION_APPROVAL");
        result.put("npcScopeEvidence", rows.npcScopesCaptured ? "CAPTURED_SCOPE_ONLY" : "NOT_CAPTURED");
        result.put("sourceMode", "ALREADY_LOADED_NO_LIVE_READS");
        ArrayNode checks = result.putArray("checks");
        check(checks, "STATE_KEYS", () -> {
            TopicPublicationValidator.StateKeys state = validator.prepareStateKeys(rows.topic,
                    TopicStateKeyRefService.collectLoaded(rows.topic,rows.nodes,rows.templates,rows.chapters));
            if (state != null) {
                // Ordinary publication registers option tags before its final read. A read-only
                // capture cannot manufacture that evidence: absent tags remain real blockers.
                validator.assertRegisteredStateKeys(state, rows.keys);
            }
        });
        check(checks, "THOUGHT_REFERENCES", () -> validator.assertThoughtRefsResolvable(rows.topic,rows.chapters));
        check(checks, "JOURNEY_STORY_REFERENCES", () -> {
            String raw = rows.topic.getJourneyStoryJson();
            if (!StringUtils.isEmpty(raw)) {
                // An empty known set is fail-closed, unlike null (legacy test-only skip).
                validator.assertJourneyStoryReferences(raw,chapterIds,ContentCaptureNpcEvidence.availableIds(rows),nodeIds,branchIds,outcomeIds);
            }
        });
        check(checks, "NARRATIVE_NPC_REFERENCES", () -> {
            Map<Long,List<String>> declared = validator.templateNarrativeNpcReferences(rows.nodes, configs);
            validator.addChapterNarrativeNpcReferences(declared,rows.nodes,rows.chapters);
            validator.assertNarrativeNpcReferences(declared,ContentCaptureNpcEvidence.availableIds(rows));
        });
        check(checks, "BLOCK_NODE_REFERENCES", () -> validator.assertBlockSysVarReferences(rows.nodes,nodeIds,configs));
        boolean blocked = false;
        for (com.fasterxml.jackson.databind.JsonNode check : checks) {
            if ("BLOCKED".equals(check.path("status").asText())) blocked = true;
        }
        result.put("status", blocked ? "BLOCKED" : "CHECKED_SUBSET");
        result.put("completePublicationValidation", false);
        // Missing NPC data is not a claim that AI is enabled or required. No-reference
        // content passes these reference checks; AI knowledge/rights applicability stays separate.
        return result;
    }

    private void check(ArrayNode checks, String name, Runnable action) {
        ObjectNode check = checks.addObject(); check.put("gate",name);
        try {
            action.run(); check.put("status","CHECKED");
        } catch (RuntimeException e) {
            check.put("status","BLOCKED"); check.put("exception",e.getClass().getName());
            if (e instanceof ServiceException && ((ServiceException)e).getCode() != null) {
                check.put("legacyCode",((ServiceException)e).getCode());
            }
            check.put("message",e.getMessage());
            if (name.contains("NPC") || "JOURNEY_STORY_REFERENCES".equals(name)) {
                check.put("npcScopeEvidence", rows.npcScopesCaptured ? "CAPTURED_SCOPE_ONLY" : "NOT_CAPTURED");
            }
        }
    }
}
