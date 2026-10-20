package com.chengyinhub.business.domain.dto;

import java.util.List;

/**
 * UGC 发布安全门禁入参(合规实施方案 §3.2)。
 * 控制器只提交"内容类型、发布者、摘要、风险字段",不接触检测供应商 / 状态映射 / 审核任务细节。
 */
public class PublishSafetyRequest {
    /**
     * 审核模式(2026-10-20 分级审核):
     * POST_REVIEW = 先发后审(明确违规仍 BLOCK,审不了只打标不拦);
     * PRE_REVIEW  = 先审后发(今天的语义,社区推文/回复必须显式传它);
     * EXEMPT      = 豁免门禁。
     * 无法识别的取值按 PRE_REVIEW 处理(见 ContentSafetyGateImpl#resolveMode),绝不因脏值 fail-open。
     */
    public enum ReviewMode { POST_REVIEW, PRE_REVIEW, EXEMPT }

    /** 内容类型,取 AuditTask.BIZ_*(SQUARE/COMMENT/CLUB_POST/CLUB_POST_COMMENT/TOPIC/ACTIVITY/IM_MESSAGE) */
    private String contentType;
    /** 内容主键(已入库的 bizId) */
    private Long contentId;
    /** 发布者 member_id */
    private Long memberId;
    /** 发布者 openid(文本 msgSecCheck v2 需要;控制器从会话上下文带入) */
    private String openid;
    /** 待检文本(标题+正文拼接) */
    private String text;
    /** 图片 url 列表(有图 → 异步送检 + 先 HOLD) */
    private List<String> mediaUrls;
    /** 是否带地理点位(路线/点位) */
    private boolean hasGeo;
    /** 是否带价格 */
    private boolean hasPrice;
    /** 是否带报名 */
    private boolean hasSignup;
    /** 审核单快照 JSON(供人工审核回溯) */
    private String snapshot;
    /** 审核模式,取 ReviewMode 名称 */
    private String reviewMode = ReviewMode.POST_REVIEW.name();

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getContentId() { return contentId; }
    public void setContentId(Long contentId) { this.contentId = contentId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getOpenid() { return openid; }
    public void setOpenid(String openid) { this.openid = openid; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public List<String> getMediaUrls() { return mediaUrls; }
    public void setMediaUrls(List<String> mediaUrls) { this.mediaUrls = mediaUrls; }
    public boolean isHasGeo() { return hasGeo; }
    public void setHasGeo(boolean hasGeo) { this.hasGeo = hasGeo; }
    public boolean isHasPrice() { return hasPrice; }
    public void setHasPrice(boolean hasPrice) { this.hasPrice = hasPrice; }
    public boolean isHasSignup() { return hasSignup; }
    public void setHasSignup(boolean hasSignup) { this.hasSignup = hasSignup; }
    public String getSnapshot() { return snapshot; }
    public void setSnapshot(String snapshot) { this.snapshot = snapshot; }
    public String getReviewMode() { return reviewMode; }
    public void setReviewMode(String reviewMode) { this.reviewMode = reviewMode; }
}
