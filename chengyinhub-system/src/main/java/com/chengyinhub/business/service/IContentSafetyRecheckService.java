package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.PublishSafetyRequest;
import com.chengyinhub.business.domain.dto.SafetyDecision;

import java.util.List;

/**
 * Round5C:机审没给出结论(检测关闭/繁忙/缺 openid/异常)或先审带图的内容,不再等人工,
 * 由定时任务自动复检:通过 → 走既有审核口自动发布;违规或 review → 自动驳回下架并通知作者;
 * 重试有界,耗尽仍不公开并通知作者。复检只管内容安全;价格/地点/报名等业务审批仍由人工决定。
 */
public interface IContentSafetyRecheckService {

    /** 门禁 HOLD 原因里属于「机审没结论」的;这些都能靠自动重试解决,不需要人。 */
    java.util.Set<String> UNCHECKED_REASONS = java.util.Collections.unmodifiableSet(new java.util.HashSet<String>(
            java.util.Arrays.asList("IDENTITY_REVIEW", "TEXT_REVIEW", "DETECT_ERROR", "DETECT_DISABLED",
                    "MEDIA_UNCHECKED", "MEDIA_PENDING")));

    static boolean needsRecheck(SafetyDecision decision) {
        return decision != null && decision.isHold() && UNCHECKED_REASONS.contains(decision.getReasonCode());
    }

    /**
     * 进队(同一内容已有待复检行则刷新为最新内容)。
     * @param businessReview true = 复检通过后仍留给人工做业务审批,不自动发布
     */
    void enqueue(String bizType, Long bizId, Long memberId, String text, List<String> mediaUrls,
                 boolean businessReview);

    /** 按门禁请求进队:先审 + 结构化商业字段 = 业务审批仍需人工。 */
    void enqueue(PublishSafetyRequest request, Long contentId);

    /** 定时任务入口:处理到期行,单轮有上限。返回本轮处理行数。 */
    int runDue();
}
