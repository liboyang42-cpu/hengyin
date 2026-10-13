package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.PublishSafetyRequest;
import com.chengyinhub.business.domain.dto.SafetyDecision;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IContentSafetyGate;
import com.chengyinhub.business.service.IWxContentSecurityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 统一 UGC 安全门禁实现(合规实施方案 §3)。
 * 文本检测复用现有 IWxContentSecurityService(msgSecCheck v2),不新建第二套检测;
 * HOLD 复用现有审核工作台(IAuditTaskService),不新建第二套审核系统。
 *
 * 裁决规则(优先级从上到下):
 *  1) 文本命中风险            → BLOCK(不公开)
 *  2) 文本检测异常            → HOLD(fail-safe,绝不 fail-open 直发)
 *  3) 带结构化商业字段(点位/价格/报名)→ HOLD(强制人工复核后上架)
 *  4) 含图片                  → HOLD(仅作者可见,异步图片检测/人工复核后再公开)
 *  5) 文本检测被关闭          → HOLD(fail-safe)
 *  6) 纯文本且通过            → PASS
 */
@Service
public class ContentSafetyGateImpl implements IContentSafetyGate {

    private static final Logger logger = LoggerFactory.getLogger(ContentSafetyGateImpl.class);

    @Autowired
    private IWxContentSecurityService wxContentSecurityService;

    @Autowired
    private IAuditTaskService auditTaskService;

    @Override
    public SafetyDecision decide(PublishSafetyRequest req) {
        if (req == null || req.getContentType() == null) {
            return SafetyDecision.block("INVALID_REQUEST");
        }
        boolean hasText = req.getText() != null && !req.getText().trim().isEmpty();
        boolean enabled = wxContentSecurityService.isEnabled();

        // 1)/2) 文本检测:命中 → BLOCK;异常 → fail-safe HOLD
        if (hasText && enabled) {
            if (req.getOpenid() == null || req.getOpenid().trim().isEmpty()) {
                // App 用户不一定拥有与小程序 appid 同源的 openid。缺失时不能伪造身份调用
                // msgSecCheck v2，也不能把供应商参数错误误判成内容违规，统一进入人工复核。
                return hold(req, "IDENTITY_REVIEW", "NORMAL");
            }
            try {
                String risk = wxContentSecurityService.checkText(req.getText(), req.getOpenid());
                // 合并 #1057×master(RUN-29 语义):checkText 的「审不了」现在回 MSG_BUSY / MSG_UNAVAILABLE
                // 而非 REVIEW_REQUIRED；两者与真人工复审一样进 HOLD，绝不能落进下面的「违规 BLOCK」。
                if (IWxContentSecurityService.REVIEW_REQUIRED.equals(risk)
                        || IWxContentSecurityService.isDetectionUnavailable(risk)) {
                    return hold(req, "TEXT_REVIEW", "NORMAL");
                }
                if (risk != null) {
                    logger.info("UGC门禁 BLOCK 文本风险 contentType={} id={} reason={}",
                            req.getContentType(), req.getContentId(), risk);
                    return SafetyDecision.block("TEXT_RISK");
                }
            } catch (Exception e) {
                logger.error("UGC门禁 文本检测异常,fail-safe HOLD contentType={} id={}",
                        req.getContentType(), req.getContentId(), e);
                return hold(req, "DETECT_ERROR", "HIGH");
            }
        }

        // 3) 结构化商业字段 → 强制人工复核
        if (req.isHasGeo() || req.isHasPrice() || req.isHasSignup()) {
            return hold(req, "STRUCTURED_REVIEW", "NORMAL");
        }

        // 4) 含图片 → 先 HOLD,仅作者可见(图片异步送检 / 人工复核后再公开)
        if (req.getMediaUrls() != null && !req.getMediaUrls().isEmpty()) {
            return hold(req, "MEDIA_PENDING", "NORMAL");
        }

        // 5) 有文本但检测被关闭 → fail-safe HOLD,不 fail-open 直发
        if (hasText && !enabled) {
            return hold(req, "DETECT_DISABLED", "NORMAL");
        }

        // 6) 纯文本通过(或无内容)→ PASS
        return SafetyDecision.pass();
    }

    private SafetyDecision hold(PublishSafetyRequest req, String reasonCode, String riskLevel) {
        Long taskId = auditTaskService.submitSafetyHold(
                req.getContentType(), req.getContentId(), req.getMemberId(), riskLevel, req.getSnapshot());
        logger.info("UGC门禁 HOLD contentType={} id={} reason={} auditTaskId={}",
                req.getContentType(), req.getContentId(), reasonCode, taskId);
        return SafetyDecision.hold(reasonCode, taskId);
    }
}
