package com.chengyinhub.business.domain;

import java.util.Date;

/** 个人资料一次成功提交的版本记录(#25)。只记字段名与头像引用,不复制文本内容。 */
public class UmsMemberProfileVersion
{
    private Long id;
    private Long memberId;
    private Integer versionNo;
    private String changedFields;
    private String avatarRef;
    private String avatarMediaHash;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getChangedFields() { return changedFields; }
    public void setChangedFields(String changedFields) { this.changedFields = changedFields; }
    public String getAvatarRef() { return avatarRef; }
    public void setAvatarRef(String avatarRef) { this.avatarRef = avatarRef; }
    public String getAvatarMediaHash() { return avatarMediaHash; }
    public void setAvatarMediaHash(String avatarMediaHash) { this.avatarMediaHash = avatarMediaHash; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
