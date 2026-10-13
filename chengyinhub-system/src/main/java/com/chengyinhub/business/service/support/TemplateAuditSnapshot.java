package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 提交和审核共用内容快照；计数、审核状态与更新时间不属于被审内容。 */
public final class TemplateAuditSnapshot {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] CONTENT = ("memberId categoryId title description imgUrl players usageLocation "
            + "requiredMaterials duration difficulty ruleInstructions merchantGuide rewardRulesJson validationMethod "
            + "sensorType sensorConfig questionName questionAnswer feedbackText couponId medalImg medalName medalStyle "
            + "questionA questionB questionC questionD correctAnswer questionImg questionAudio questionOptionMediaJson "
            + "preferenceJson advancedConfigJson activityCategoryids storyText storyImg storyJson hint1 hint2 "
            + "answerReveal photoRequireDesc photoReview audioUrl audioDuration packType xpValue interactionType originalTemplateId").split(" ");
    private TemplateAuditSnapshot() { }

    public static String personal(CmsMemberTemplate template) {
        return snapshot(template, AuditTask.BIZ_TEMPLATE, template.getId(), null);
    }

    public static String market(CmsTemplateLibrary projection) {
        return snapshot(projection, AuditTask.BIZ_TEMPLATE_LIBRARY, projection.getId(), projection.getMemberTemplateId());
    }

    private static String snapshot(Object value, String type, Long id, Long templateId) {
        JsonNode source = JSON.valueToTree(value);
        ObjectNode result = JSON.createObjectNode();
        result.put("bizType", type); result.put("bizId", id);
        if (templateId != null) result.put("memberTemplateId", templateId);
        for (String field : CONTENT) result.set(field, source.path(field).isMissingNode() ? JSON.nullNode() : source.get(field));
        return result.toString();
    }

    public static boolean matches(String submitted, String current) {
        try { return JSON.readTree(current).equals(JSON.readTree(submitted)); }
        catch (Exception invalid) { return false; }
    }
}
