package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 发布者实名登记 ums_member_identity。
 *
 * realName / idCard 在库里是 AES-GCM 密文,由 mapper 的 typeHandler 负责加解密,
 * Java 侧看到的始终是明文 —— 所以这两个值**永远不要放进日志、toString 或任何接口响应**。
 * idCardHash 是同一号码的 HMAC 盲索引,唯一用途是「这张证是不是绑在别的账号上」的人工核对。
 */
public class UmsMemberIdentity {

    private Long id;
    private Long memberId;
    private String realName;
    private String idCard;
    private String idCardHash;
    private String source;
    private Date consentTime;
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
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Date getConsentTime() { return consentTime; }
    public void setConsentTime(Date consentTime) { this.consentTime = consentTime; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }

    /** 刻意不写 toString():这个对象一旦被打进日志,PII 就漏了。 */
}
