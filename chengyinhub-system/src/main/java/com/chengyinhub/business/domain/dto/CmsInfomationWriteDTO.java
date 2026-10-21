package com.chengyinhub.business.domain.dto;

/**
 * 后台玩法说明新增/编辑请求白名单。
 *
 * <p>变更说明仅用于后台专用审计，不能混入 {@code CmsInfomation}，否则会随小程序公开读取接口返回。
 */
public class CmsInfomationWriteDTO {

    private Long id;

    /** 仅传入专用审计，不写入公开玩法说明。 */
    private String auditReason;

    private String title;

    private String subtitle;

    private Long sortId;

    private String contents;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAuditReason() { return auditReason; }
    public void setAuditReason(String auditReason) { this.auditReason = auditReason; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public Long getSortId() { return sortId; }
    public void setSortId(Long sortId) { this.sortId = sortId; }
    public String getContents() { return contents; }
    public void setContents(String contents) { this.contents = contents; }
}
