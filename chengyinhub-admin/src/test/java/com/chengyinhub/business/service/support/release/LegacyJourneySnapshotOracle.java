package com.chengyinhub.business.service.support.release;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.support.*;
import com.chengyinhub.business.util.ChapterBlockReadSupport;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
/** Test-only unmodified algorithm copied from 4a0158e. Do not update to mirror new builder changes. */
final class LegacyJourneySnapshotOracle {
    private static final ObjectMapper JSON = new ObjectMapper();
    CmsTopicChapterMapper cmsTopicChapterMapper;
    ThemeStateKeyMapper themeStateKeyMapper;
    private Map<Long, ObjectNode> storyNarrativeByNode(CmsTopic topic) {
        Map<Long, ObjectNode> byNode = new LinkedHashMap<Long, ObjectNode>();
        if (cmsTopicChapterMapper == null || topic == null || topic.getId() == null) return byNode;
        CmsTopicChapter probe = new CmsTopicChapter();
        probe.setTopicId(topic.getId());
        List<CmsTopicChapter> chapters = cmsTopicChapterMapper.selectCmsTopicChapterList(probe);
        if (chapters == null) return byNode;
        for (CmsTopicChapter chapter : chapters) {
            if (chapter == null || chapter.getBlocksJson() == null || chapter.getBlocksJson().trim().isEmpty()) {
                continue;
            }
            byNode.putAll(ChapterNarrativeBlocks.assembleByNode(chapter.getBlocksJson()));
        }
        return byNode;
    }

    String freezeJourney(String snapshot, CmsTopic topic, List<CmsTopicNode> liveNodes,
                                 Map<Long, String> configByTemplate,
                                 Map<Long, CmsMemberTemplate> templatesById) {
        final JsonNode parsed;
        try {
            parsed = JSON.readTree(snapshot);
        } catch (Exception unusableSnapshot) {
            throw new IllegalStateException("路线快照不可用", unusableSnapshot);
        }
        if (parsed == null || !parsed.isObject()) throw new IllegalStateException("路线快照不可用");
        ObjectNode root = (ObjectNode) parsed;
        ObjectNode nodeJourney = root.putObject(NodeJourneySnapshot.NODE_JOURNEY_KEY);
        Map<Long, ObjectNode> storyByNode = storyNarrativeByNode(topic);
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
        try {
            root.set(NodeJourneySnapshot.JOURNEY_RULES_KEY,
                    JSON.valueToTree(journeyRules(topic)));
            JourneyInitialState.freezeKeys(root, themeStateKeyMapper == null ? null
                    : themeStateKeyMapper.selectByTopicId(topic.getId()), journeyRules(topic));
        } catch (IllegalArgumentException unusableRules) {
            throw new IllegalStateException("旅程数值规则不可用", unusableRules);
        }
        String story = topic == null ? null : topic.getJourneyStoryJson();
        root.put(NodeJourneySnapshot.JOURNEY_STORY_KEY, story == null ? "" : story.trim());
        root.put("topicCompleteRule", topic == null ? null : topic.getCompleteRuleJson());
        Long openingId = JourneyStory.openingChapterId(story);
        freezeThoughtChapters(root, topic, liveNodes, openingId);
        if (openingId != null) {
            CmsTopicChapter chapterProbe = new CmsTopicChapter(); chapterProbe.setTopicId(topic.getId());
            List<CmsTopicChapter> chapters = cmsTopicChapterMapper == null ? null
                    : cmsTopicChapterMapper.selectCmsTopicChapterList(chapterProbe);
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
        freezeNodeAnswers(root.putObject(NodeJourneySnapshot.NODE_ANSWERS_KEY), liveNodes, templatesById);
        return write(root);
    }

    private void freezeThoughtChapters(ObjectNode root, CmsTopic topic, List<CmsTopicNode> nodes, Long openingId) {
        com.fasterxml.jackson.databind.node.ArrayNode frozen = root.putArray("thoughtChapters");
        if (cmsTopicChapterMapper == null) return;
        CmsTopicChapter probe = new CmsTopicChapter(); probe.setTopicId(topic.getId());
        List<CmsTopicChapter> rows = cmsTopicChapterMapper.selectCmsTopicChapterList(probe);
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

    private void freezeNodeAnswers(ObjectNode nodeAnswers, List<CmsTopicNode> liveNodes,
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

    String linearSnapshot(List<CmsTopicNode> liveNodes) {
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
