package com.chengyinhub.business.service.support;

import com.chengyinhub.common.utils.SecurityUtils;
import com.chengyinhub.system.service.ISysConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Explicit guard-review delegation; menu permission or platform-admin status alone never grants scope. */
@Component
public class GuardAppealReviewerScope {
    /** JSON object: {"sysUserId":["topicId", ...]}. IDs must be exact decimal strings, no wildcard/default. */
    public static final String CONFIG_KEY = "play.arrive.guard_appeal.reviewer_scope";
    private final ISysConfigService configService;

    public GuardAppealReviewerScope(ISysConfigService configService) { this.configService = configService; }

    public boolean canRead(Long topicId) { return currentAllowed(topicId, "business:audit:list"); }
    public boolean canReview(Long topicId) { return currentAllowed(topicId, "business:audit:review"); }
    public boolean canReview(Long reviewerId, Long topicId) {
        try { return java.util.Objects.equals(reviewerId, SecurityUtils.getUserId()) && canReview(topicId); }
        catch (RuntimeException unavailable) { return false; }
    }

    private boolean currentAllowed(Long topicId, String permission) {
        try {
            Long reviewerId = SecurityUtils.getUserId();
            return SecurityUtils.hasPermi(permission) && configured(configService.selectConfigByKey(CONFIG_KEY), reviewerId, topicId);
        } catch (RuntimeException unavailable) {
            return false; // Missing login/configuration cannot broaden authority, including for platform admins.
        }
    }

    /** Resolve before PageHelper starts: configuration lookup may itself execute SQL. */
    public java.util.List<Long> readableTopicIds() {
        try {
            if (!SecurityUtils.hasPermi("business:audit:list")) return java.util.List.of();
            return configuredTopics(configService.selectConfigByKey(CONFIG_KEY), SecurityUtils.getUserId());
        } catch (RuntimeException unavailable) { return java.util.List.of(); }
    }

    public static boolean configured(String config, Long reviewerId, Long topicId) {
        return topicId != null && topicId > 0 && configuredTopics(config, reviewerId).contains(topicId);
    }

    public static java.util.List<Long> configuredTopics(String config, Long reviewerId) {
        if (reviewerId == null || reviewerId <= 0 || config == null) return java.util.List.of();
        try {
            JsonNode root = new ObjectMapper().readTree(config);
            if (root == null || !root.isObject()) return java.util.List.of();
            JsonNode topics = root.get(reviewerId.toString());
            if (topics == null || !topics.isArray()) return java.util.List.of();
            java.util.Set<Long> ids = new java.util.LinkedHashSet<>();
            for (JsonNode value : topics) {
                if (!value.isTextual() || !value.asText().matches("[1-9][0-9]{0,18}")) return java.util.List.of();
                long parsed = Long.parseLong(value.asText());
                if (parsed <= 0) return java.util.List.of();
                ids.add(parsed);
            }
            return java.util.List.copyOf(ids);
        } catch (java.io.IOException | IllegalArgumentException invalid) { return java.util.List.of(); }
    }
}
