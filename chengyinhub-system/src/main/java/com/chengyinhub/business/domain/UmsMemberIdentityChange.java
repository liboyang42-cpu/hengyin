package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 发布者实名改绑申请单 ums_member_identity_change(X036)。
 *
 * realName / idCard 在库里是 AES-GCM 密文(仅 PENDING 期间保留),由 mapper 的 typeHandler 加解密,
 * Java 侧拿到的是明文 —— 与 {@link UmsMemberIdentity} 同一条纪律:<b>永远不进日志、toString 或接口响应</b>。
 */
public class UmsMemberIdentityChange {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private Long id;
    private Long memberId;
    private String realName;
    private String idCard;
    private String idCardHash;
    private String oldIdCardHash;
    private String reason;
    private String status;
    private String reviewNote;
    private Long reviewerUserId;
    private Date reviewTime;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getRealName() { return realName; }
    public void setRealName(String realName) { this.realName = realName; }
    public String getIdCard() { return idCard; }
    public void setIdCard(String idCard) { this.idCard = idCard; }
    public String getIdCardHash() { return idCardHash; }
    public void setIdCardHash(String idCardHash) { this.idCardHash = idCardHash; }
    public String getOldIdCardHash() { return oldIdCardHash; }
    public void setOldIdCardHash(String oldIdCardHash) { this.oldIdCardHash = oldIdCardHash; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
    public Long getReviewerUserId() { return reviewerUserId; }
    public void setReviewerUserId(Long reviewerUserId) { this.reviewerUserId = reviewerUserId; }
    public Date getReviewTime() { return reviewTime; }
    public void setReviewTime(Date reviewTime) { this.reviewTime = reviewTime; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }

    /** 刻意不写 toString():这个对象一旦被打进日志,PII 就漏了。 */
}
