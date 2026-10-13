package com.chengyinhub.business.domain;

import java.util.Date;

/** 对外协议的版本化文档。生效版本只读，更新时应新增版本而非原地覆盖。 */
public class AgreementDocument {
    private Long id;
    private String docType;
    private String version;
    private String title;
    private String content;
    private Integer status;
    private Date publishTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDocType() { return docType; }
    public void setDocType(String docType) { this.docType = docType; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Date getPublishTime() { return publishTime; }
    public void setPublishTime(Date publishTime) { this.publishTime = publishTime; }
}
