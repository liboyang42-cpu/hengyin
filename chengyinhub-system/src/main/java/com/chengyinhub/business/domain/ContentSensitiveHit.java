package com.chengyinhub.business.domain;

/** 敏感内容命中记录(Round5D):只有类型/ID/分类,不含命中词与个人数据。 */
public class ContentSensitiveHit {
    private Long id;
    private String source;
    private String contentType;
    private Long contentId;
    private String category;
    private String action;
    private String scanVersion;
    private java.util.Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getContentId() { return contentId; }
    public void setContentId(Long contentId) { this.contentId = contentId; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getScanVersion() { return scanVersion; }
    public void setScanVersion(String scanVersion) { this.scanVersion = scanVersion; }
    public java.util.Date getCreateTime() { return createTime; }
    public void setCreateTime(java.util.Date createTime) { this.createTime = createTime; }
}
