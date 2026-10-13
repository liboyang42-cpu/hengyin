package com.chengyinhub.business.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.MerchantCircleSupplyProfile;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 第二组圈层城市实例的 8 选 3—4 发布不变量。 */
public final class CircleThemePublishPolicy {
    private CircleThemePublishPolicy() { }

    public static void assertPublishable(CmsTopic topic, List<CmsTopicChapter> chapters) {
        if (topic == null || blank(topic.getCircleThemeCode())) return;

        Set<String> candidateCodes = new HashSet<>();
        Set<String> roles = new HashSet<>();
        int enabled = 0;
        if (chapters != null) {
            for (CmsTopicChapter chapter : chapters) {
                if (chapter == null || !Integer.valueOf(1).equals(chapter.getCircleEnabled())) continue;
                enabled++;
                String code = trim(chapter.getCircleCandidateCode());
                if (code == null || !candidateCodes.add(code)) {
                    throw new IllegalStateException("同一供给角色候选不能重复启用");
                }
                String role = trim(chapter.getCircleRole());
                if (role != null) roles.add(role);
            }
        }
        if (enabled < 3 || enabled > 4) {
            throw new IllegalStateException("圈层城市实例必须启用3—4个供给角色");
        }
        if (!roles.contains("EXPERIENCE") || !roles.contains("REST") || !roles.contains("EXPRESSION")) {
            throw new IllegalStateException("圈层城市实例必须同时覆盖主体验、补给/休息、社交/表达");
        }
    }

    public static void assertSupplied(CmsTopic topic, List<CmsTopicChapter> chapters,
                                      List<MerchantCircleSupplyProfile> supplies) {
        assertPublishable(topic, chapters);
        if (topic == null || blank(topic.getCircleThemeCode())) return;
        Set<String> enabledCodes = new HashSet<>();
        for (CmsTopicChapter chapter : chapters == null ? java.util.Collections.<CmsTopicChapter>emptyList() : chapters) {
            if (chapter != null && Integer.valueOf(1).equals(chapter.getCircleEnabled())) {
                enabledCodes.add(trim(chapter.getCircleCandidateCode()));
            }
        }
        Set<String> suppliedCodes = new HashSet<>();
        Set<Long> merchants = new HashSet<>();
        int count = 0;
        for (MerchantCircleSupplyProfile supply : supplies == null
                ? java.util.Collections.<MerchantCircleSupplyProfile>emptyList() : supplies) {
            count++;
            if (supply == null || !enabledCodes.contains(trim(supply.getCandidateCode()))
                    || !suppliedCodes.add(trim(supply.getCandidateCode()))
                    || supply.getMerchantId() == null || !merchants.add(supply.getMerchantId())) {
                throw new IllegalStateException("每个启用角色必须且只能绑定一家不同商家的有效供给");
            }
        }
        if (count != enabledCodes.size() || !suppliedCodes.equals(enabledCodes)) {
            throw new IllegalStateException("发布前每个启用角色都必须绑定一家有效商家供给");
        }
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private static String trim(String value) { return blank(value) ? null : value.trim(); }
}
