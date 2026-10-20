package com.chengyinhub.business.service.support;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.service.support.*;
import com.chengyinhub.business.util.ChapterBlockReadSupport;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
/**
 * Pure legacy snapshot construction, shared by runtime and inactive content capture.
 * No reads, authorization, approval, canonicalization or reward decisions occur here.
 * Staged entry points preserve the runtime's existing READ_COMMITTED read/error order.
 */
public final class JourneyContentSnapshotBuilder {
    private static final ObjectMapper JSON = new ObjectMapper();

    public ObjectNode begin(String snapshot) {
        final JsonNode parsed;
        try { parsed = JSON.readTree(snapshot); }
        catch (Exception e) { throw new IllegalStateException("路线快照不可用", e); }
        if (parsed == null || !parsed.isObject()) throw new IllegalStateException("路线快照不可用");
        return (ObjectNode) parsed;
    }

    public String freezeJourney(String snapshot, CmsTopic topic, List<CmsTopicNode> nodes,
            Map<Long, String> configs, Map<Long, CmsMemberTemplate> templates,
            List<ThemeStateKey> keys, List<CmsTopicChapter> chapters) {
        ObjectNode root = begin(snapshot);
        freezeNodeJourney(root, nodes, configs, chapters);
        freezeJourneyRules(root, topic);
        freezeStateKeys(root, topic, keys);
        Long openingId = freezeStory(root, topic);
        freezeThoughtChapters(root, topic, nodes, openingId, chapters);
        if (openingId != null) freezeOpeningChapter(root, nodes, openingId, chapters);
        freezeNodeAnswers(root.putObject(NodeJourneySnapshot.NODE_ANSWERS_KEY), nodes, templates);
        return finish(root);
    }

    public void freezeNodeJourney(ObjectNode root, List<CmsTopicNode> liveNodes,
            Map<Long, String> configByTemplate, List<CmsTopicChapter> chapters) {
        ObjectNode nodeJourney = root.putObject(NodeJourneySnapshot.NODE_JOURNEY_KEY);
        Map<Long, ObjectNode> storyByNode = storyNarrativeByNode(chapters);
        for (CmsTopicNode node : liveNodes) {
            if (node.getId() == null || node.getTemplateId() == null || node.getTemplateId() <= 0L) continue;
            ObjectNode block = nodeJourney.putObject(String.valueOf(node.getId()));
            JsonNode blockConfig = readBlockConfig(configByTemplate.get(node.getTemplateId()));
            NodeJourneySnapshot.copyMechanicSegments(blockConfig, block);
            ObjectNode story = storyByNode.get(node.getId());
            if (story != null) {
                java.util.Iterator<String> segments = story.fieldNames();
                while (segments.hasNext()) {
                    String segment = segments.next();
                    block.set(segment, story.get(segment));
                }
            }
        }
    }

    public void freezeJourneyRules(ObjectNode root, CmsTopic topic) {
        try { root.set(NodeJourneySnapshot.JOURNEY_RULES_KEY, JSON.valueToTree(journeyRules(topic))); }
        catch (IllegalArgumentException e) { throw new IllegalStateException("旅程数值规则不可用", e); }
    }

    public void freezeStateKeys(ObjectNode root, CmsTopic topic, List<ThemeStateKey> keys) {
        try { JourneyInitialState.freezeKeys(root, keys, journeyRules(topic)); }
        catch (IllegalArgumentException e) { throw new IllegalStateException("旅程数值规则不可用", e); }
    }

    public Long freezeStory(ObjectNode root, CmsTopic topic) {
        String story = topic == null ? null : topic.getJourneyStoryJson();
        root.put(NodeJourneySnapshot.JOURNEY_STORY_KEY, story == null ? "" : story.trim());
        root.put("topicCompleteRule", topic == null ? null : topic.getCompleteRuleJson());
        return JourneyStory.openingChapterId(story);
    }

    public void freezeOpeningChapter(ObjectNode root, List<CmsTopicNode> liveNodes,
            Long openingId, List<CmsTopicChapter> chapters) {
        CmsTopicChapter opening = null;
        if (chapters != null) for (CmsTopicChapter chapter : chapters) {
            if (openingId.equals(chapter.getId())) { opening = chapter; break; }
        }
        if (opening == null) throw new IllegalStateException("开局流程不可用");
        ObjectNode frozen = root.putObject("openingChapter");
        frozen.put("chapterId", openingId); frozen.put("opening", true);
        frozen.put("name", opening.getName()); frozen.put("idxLabel", "序章");
        frozen.put("imgArr", opening.getImgArr()); frozen.put("audioUrl", opening.getAudioUrl());
        frozen.put("atmospherePreset", opening.getAtmospherePreset());
        frozen.set("blocks", JSON.valueToTree(ChapterBlockReadSupport.resolveFromTopicNodes(opening, liveNodes)));
    }

    public String finish(ObjectNode root) { return write(root); }

    private Map<Long, ObjectNode> storyNarrativeByNode(List<CmsTopicChapter> chapters) {
        Map<Long, ObjectNode> byNode = new LinkedHashMap<Long, ObjectNode>();
        if (chapters == null) return byNode;
        for (CmsTopicChapter chapter : chapters) {
            if (chapter == null || chapter.getBlocksJson() == null || chapter.getBlocksJson().trim().isEmpty()) {
                continue;
            }
            byNode.putAll(ChapterNarrativeBlocks.assembleByNode(chapter.getBlocksJson()));
        }
        return byNode;
    }

    public void freezeThoughtChapters(ObjectNode root, CmsTopic topic, List<CmsTopicNode> nodes, Long openingId, List<CmsTopicChapter> rows) {
        com.fasterxml.jackson.databind.node.ArrayNode frozen = root.putArray("thoughtChapters");
        if (rows == null) return;
        List<CmsTopicChapter> chapters = new ArrayList<CmsTopicChapter>(rows);
        chapters.sort(Comparator.comparing(CmsTopicChapter::getSortId, Comparator.nullsLast(Long::compareTo))
                .thenComparing(CmsTopicChapter::getId, Comparator.nullsLast(Long::compareTo)));
        Set<Long> endingIds = JourneyStory.endingChapterIds(topic.getJourneyStoryJson());
        Set<Long> requiredBefore = new LinkedHashSet<Long>();
        for (CmsTopicChapter chapter : chapters) {
            if (chapter.getId() == null || endingIds.contains(chapter.getId())) continue;
            Set<Long> nodeIds = new LinkedHashSet<Long>();
            for (CmsTopicNode node : nodes) if (chapter.getId().equals(node.getChapterId())) nodeIds.add(node.getId());
            ObjectNode row = frozen.addObject();
            row.put("chapterId", chapter.getId());
            row.put("opening", chapter.getId().equals(openingId));
            row.set("nodeIds", JSON.valueToTree(nodeIds));
            row.set("requiredBefore", JSON.valueToTree(requiredBefore));
            row.set("blocks", JSON.valueToTree(ChapterBlockReadSupport.resolveFromTopicNodes(chapter, nodes)));
            if (ChapterBlockReadSupport.isRequiredChapter(chapter)) requiredBefore.addAll(nodeIds);
        }
    }

    public void freezeNodeAnswers(ObjectNode nodeAnswers, List<CmsTopicNode> liveNodes,
                                   Map<Long, CmsMemberTemplate> templates) {
        Map<Long, CmsMemberTemplate> byId = templates == null
                ? Collections.<Long, CmsMemberTemplate>emptyMap() : templates;
        for (CmsTopicNode node : liveNodes) {
            if (node.getId() == null) continue;
            ObjectNode target = nodeAnswers.putObject(String.valueOf(node.getId()));
            if (node.getTemplateId() == null || node.getTemplateId() <= 0L) continue;
            CmsMemberTemplate template = byId.get(node.getTemplateId());
            if (template == null) continue;
            if (template.getValidationMethod() != null) {
                target.put("validationMethod", template.getValidationMethod().intValue());
            }
            if (template.getPhotoReview() != null) {
                target.put("photoReview", template.getPhotoReview().intValue());
            }
            putAnswerText(target, "questionAnswer", template.getQuestionAnswer());
            putAnswerText(target, "correctAnswer", template.getCorrectAnswer());
            putAnswerText(target, "answerReveal", template.getAnswerReveal());
            putAnswerText(target, "questionA", template.getQuestionA());
            putAnswerText(target, "questionB", template.getQuestionB());
            putAnswerText(target, "questionC", template.getQuestionC());
            putAnswerText(target, "questionD", template.getQuestionD());
            putAnswerText(target, "preferenceJson", template.getPreferenceJson());
            putAnswerText(target, "sensorType", template.getSensorType());
            putAnswerText(target, "sensorConfig", template.getSensorConfig());
        }
    }

    private static void putAnswerText(ObjectNode target, String field, String value) {
        if (value != null) target.put(field, value);
    }

    private JsonNode readBlockConfig(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            JsonNode config = JSON.readTree(raw);
            return config != null && config.isObject() ? config : null;
        } catch (Exception unusableBlockConfig) {
            return null;
        }
    }

    private JourneyRules journeyRules(CmsTopic topic) {
        return JourneyRules.parse(topic == null ? null : topic.getJourneyRulesJson());
    }

    public String linearSnapshot(List<CmsTopicNode> liveNodes) {
        ObjectNode snapshot = JSON.createObjectNode();
        snapshot.put("schemaVersion", 1);
        if (!liveNodes.isEmpty()) snapshot.put("startNodeId", liveNodes.get(0).getId().longValue());
        ArrayNode terminals = snapshot.putArray("terminalNodeIds");
        if (!liveNodes.isEmpty()) terminals.add(liveNodes.get(liveNodes.size() - 1).getId().longValue());
        snapshot.putObject("variables");
        return write(snapshot);
    }

    private String write(Object value) { try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("路线状态序列化失败", e); } }
}
