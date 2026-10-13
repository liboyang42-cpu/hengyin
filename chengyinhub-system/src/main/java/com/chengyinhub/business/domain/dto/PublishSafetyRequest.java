package com.chengyinhub.business.domain.dto;

import java.util.List;

/**
 * UGC 发布安全门禁入参(合规实施方案 §3.2)。
 * 控制器只提交"内容类型、发布者、摘要、风险字段",不接触检测供应商 / 状态映射 / 审核任务细节。
 */
public class PublishSafetyRequest {
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
}
