package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable evidence for the complete projection batch reviewed outside row locks. */
public final class TemplateLibraryRepublishReview {
    // Match the existing initial-publish free-text review contract, in the same order.
    private static final String[] FREE_TEXT_FIELDS = ("title description players usageLocation requiredMaterials "
            + "ruleInstructions questionName questionAnswer feedbackText medalName questionA questionB questionC questionD").split(" ");
    private final Long memberTemplateId;
    private final Long memberId;
    private final Map<Long, String> snapshots;
    private final List<ReviewedText> reviewedTexts;

    private TemplateLibraryRepublishReview(Long templateId, Long ownerId,
                                           Map<Long, String> snapshots, List<ReviewedText> texts) {
        this.memberTemplateId = templateId;
        this.memberId = ownerId;
        this.snapshots = Collections.unmodifiableMap(new LinkedHashMap<>(snapshots));
        this.reviewedTexts = Collections.unmodifiableList(new ArrayList<>(texts));
    }

    public static TemplateLibraryRepublishReview freeze(Long templateId, Long ownerId,
                                                        List<CmsTemplateLibrary> records) {
        Map<Long, String> snapshots = capture(templateId, ownerId, records, false);
        if (snapshots == null) throw new ServiceException("上架复检暂不可用，请刷新后重试", 500);
        List<ReviewedText> texts = new ArrayList<>();
        // Read moderation text from the frozen snapshot itself, never reread mutable rows.
        try {
            ObjectMapper json = new ObjectMapper();
            for (String snapshot : snapshots.values()) {
                JsonNode row = json.readTree(snapshot);
                List<String> freeTextFields = new ArrayList<>();
                for (String field : FREE_TEXT_FIELDS) freeTextFields.add(text(row, field));
                texts.add(new ReviewedText(text(row, "title"), text(row, "description"),
                        text(row, "advancedConfigJson"), freeTextFields));
            }
        } catch (java.io.IOException invalid) {
            throw new ServiceException("上架复检暂不可用，请刷新后重试", 500);
        }
        return new TemplateLibraryRepublishReview(templateId, ownerId, snapshots, texts);
    }

    private static String text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    public List<ReviewedText> getReviewedTexts() { return reviewedTexts; }

    public boolean matches(Long templateId, Long ownerId, List<CmsTemplateLibrary> lockedRecords) {
        if (!memberTemplateId.equals(templateId) || !memberId.equals(ownerId)) return false;
        Map<Long, String> current = capture(templateId, ownerId, lockedRecords, true);
        return current != null && snapshots.equals(current);
    }

    private static Map<Long, String> capture(Long templateId, Long ownerId,
                                             List<CmsTemplateLibrary> records, boolean allowDeleted) {
        if (templateId == null || templateId <= 0 || ownerId == null || ownerId <= 0
                || records == null || records.isEmpty()) return null;
        Map<Long, String> result = new LinkedHashMap<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (CmsTemplateLibrary record : records) {
            if (record == null || record.getId() == null || record.getId() <= 0
                    || !seen.add(record.getId())) return null;
            if (allowDeleted && Integer.valueOf(2).equals(record.getDelFlag())) continue;
            if (!Integer.valueOf(0).equals(record.getDelFlag())
                    || !templateId.equals(record.getMemberTemplateId())
                    || !ownerId.equals(record.getMemberId())) return null;
            result.put(record.getId(), TemplateAuditSnapshot.market(record));
        }
        return result.isEmpty() ? null : result;
    }

    /** Strings are immutable; no mutable domain object is retained or exposed. */
    public static final class ReviewedText {
        private final String title;
        private final String description;
        private final String advancedConfigJson;
        private final List<String> freeTextFields;
        private ReviewedText(String title, String description, String advancedConfigJson, List<String> freeTextFields) {
            this.title = title; this.description = description; this.advancedConfigJson = advancedConfigJson;
            this.freeTextFields = Collections.unmodifiableList(new ArrayList<>(freeTextFields));
        }
        public String getTitle() { return title; }
        public String getDescription() { return description; }
        public String getAdvancedConfigJson() { return advancedConfigJson; }
        public List<String> getFreeTextFields() { return freeTextFields; }
    }
}
