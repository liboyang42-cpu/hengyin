package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.PublishSafetyRequest;
import com.chengyinhub.business.domain.dto.PublishSafetyRequest.ReviewMode;
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
 * 裁决规则(优先级从上到下;「PRE / POST」= 请求的 reviewMode):
 *  0) EXEMPT                  → PASS
 *  1) 文本命中风险            → BLOCK(不公开,两种模式都拦)
 *  2) 机审要人工/审不了/异常/缺 openid → PRE: HOLD(fail-safe);POST: PASS 打标(passFlagged,留待抽检)
 *  3) 带结构化商业字段(点位/价格/报名)→ PRE: HOLD(强制人工复核后上架);POST: 放行
 *  4) 含图片                  → PRE: HOLD(仅作者可见);POST: 放行(调用方保留异步图片检测 + 违规回调下架)
 *  5) 文本检测被关闭          → PRE: HOLD(fail-safe);POST: PASS 打标
 *  6) 纯文本且通过            → PASS
 * reviewMode 为空或无法识别 → 按 PRE_REVIEW:门禁分不出调用方,PRE_REVIEW 是全部存量调用方今天的语义,
 * 也是最严的一档,脏值绝不 fail-open 成先发后审/豁免。
 * PRE 下若 contentId 为空(内容尚未落库,如活动发布的前置裁决),HOLD 不建审核单,由调用方落库后自行建单,
 * 避免产生 bizId 为空的孤儿单。
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
        ReviewMode mode = resolveMode(req.getReviewMode());
        if (mode == ReviewMode.EXEMPT) {
            return SafetyDecision.pass();
        }
        boolean pre = mode == ReviewMode.PRE_REVIEW;
        boolean hasText = req.getText() != null && !req.getText().trim().isEmpty();
        boolean enabled = wxContentSecurityService.isEnabled();

        // 1)/2) 文本检测:命中 → BLOCK;异常 → PRE: fail-safe HOLD / POST: 打标放行
        if (hasText && enabled) {
            if (req.getOpenid() == null || req.getOpenid().trim().isEmpty()) {
                // App 用户不一定拥有与小程序 appid 同源的 openid。缺失时不能伪造身份调用
                // msgSecCheck v2，也不能把供应商参数错误误判成内容违规，统一进入人工复核。
                return unsure(req, pre, "IDENTITY_REVIEW", "NORMAL");
            }
            try {
                String risk = wxContentSecurityService.checkText(req.getText(), req.getOpenid());
                // 合并 #1057×master(RUN-29 语义):checkText 的「审不了」现在回 MSG_BUSY / MSG_UNAVAILABLE
                // 而非 REVIEW_REQUIRED；两者与真人工复审一样进 HOLD，绝不能落进下面的「违规 BLOCK」。
                if (IWxContentSecurityService.REVIEW_REQUIRED.equals(risk)
                        || IWxContentSecurityService.isDetectionUnavailable(risk)) {
                    return unsure(req, pre, "TEXT_REVIEW", "NORMAL");
                }
                if (risk != null) {
                    logger.info("UGC门禁 BLOCK 文本风险 contentType={} id={} reason={}",
                            req.getContentType(), req.getContentId(), risk);
                    return SafetyDecision.block("TEXT_RISK");
                }
            } catch (Exception e) {
                logger.error("UGC门禁 文本检测异常,fail-safe contentType={} id={} mode={}",
                        req.getContentType(), req.getContentId(), mode, e);
                return unsure(req, pre, "DETECT_ERROR", "HIGH");
            }
        }

        // 3) 结构化商业字段 → 先审模式强制人工复核
        if (pre && (req.isHasGeo() || req.isHasPrice() || req.isHasSignup())) {
            return hold(req, "STRUCTURED_REVIEW", "NORMAL");
        }

        // 4) 含图片 → 先审模式先 HOLD,仅作者可见(图片异步送检 / 人工复核后再公开)
        if (pre && req.getMediaUrls() != null && !req.getMediaUrls().isEmpty()) {
            return hold(req, "MEDIA_PENDING", "NORMAL");
        }

        // 5) 有文本但检测被关闭 → 先审 fail-safe HOLD;先发后审打标,不当作「已审过」
        if (hasText && !enabled) {
            return unsure(req, pre, "DETECT_DISABLED", "NORMAL");
        }

        // 6) 纯文本通过(或无内容)→ PASS
        return SafetyDecision.pass();
    }

    /** 空值 / 无法识别 → PRE_REVIEW(见类注释),不做大小写或空白归一,避免脏值被「宽容」成 EXEMPT。 */
    static ReviewMode resolveMode(String raw) {
        if (raw != null) {
            for (ReviewMode m : ReviewMode.values()) {
                if (m.name().equals(raw)) return m;
            }
        }
        if (raw != null) {
            logger.warn("UGC门禁 未知 reviewMode={},按 PRE_REVIEW 处理", raw);
        }
        return ReviewMode.PRE_REVIEW;
    }

    /** 机审没给出确定结论:先审 → HOLD;先发后审 → 公开但打标 */
    private SafetyDecision unsure(PublishSafetyRequest req, boolean pre, String reasonCode, String riskLevel) {
        if (pre) {
            return hold(req, reasonCode, riskLevel);
        }
        logger.info("UGC门禁 POST_REVIEW 打标放行 contentType={} id={} reason={}",
                req.getContentType(), req.getContentId(), reasonCode);
        return SafetyDecision.passFlagged(reasonCode);
    }

    private SafetyDecision hold(PublishSafetyRequest req, String reasonCode, String riskLevel) {
        if (req.getContentId() == null) {
            logger.info("UGC门禁 HOLD(未落库,由调用方建单) contentType={} reason={}", req.getContentType(), reasonCode);
            return SafetyDecision.hold(reasonCode, null);
        }
        Long taskId = auditTaskService.submitSafetyHold(
                req.getContentType(), req.getContentId(), req.getMemberId(), riskLevel, req.getSnapshot());
        logger.info("UGC门禁 HOLD contentType={} id={} reason={} auditTaskId={}",
                req.getContentType(), req.getContentId(), reasonCode, taskId);
        return SafetyDecision.hold(reasonCode, taskId);
    }
}
