package com.chengyinhub.business.domain;

/** 已知违规图片指纹(Round5D),不存原图。 */
public class ContentSensitiveImage {
    private Long id;
    private String sha256;
    private Long dhash;
    private String category;
    private Integer enabled;
    private String note;
    private String createBy;
    private java.util.Date createTime;
    private java.util.Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public Long getDhash() { return dhash; }
    public void setDhash(Long dhash) { this.dhash = dhash; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }
    public java.util.Date getCreateTime() { return createTime; }
    public void setCreateTime(java.util.Date createTime) { this.createTime = createTime; }
    public java.util.Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(java.util.Date updateTime) { this.updateTime = updateTime; }
}
