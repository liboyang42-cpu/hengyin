package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 漫游无形陪伴 V1 的运营内容。
 *
 * <p>这不是模型输出缓存。路线故事与地点意义都只能来自独立审核后的短内容，
 * 客户端没有内容时必须静默，不得回退到 roam_poi.description。</p>
 */
public class RoamAiContent {
    public static final String TYPE_ROUTE_STORY = "ROUTE_STORY";
    public static final String TYPE_POI_MEANING = "POI_MEANING";
    public static final int MAX_TEXT_LENGTH = 80;

    private Long id;
    private String contentType;
    private String contentKey;
    private Long poiId;
    private String contentText;
    /** 0 待审 / 1 通过 / 2 驳回。 */
    private Integer auditStatus;
    /** 0 停用 / 1 启用。 */
    private Integer enabled;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public String getContentKey() { return contentKey; }
    public void setContentKey(String contentKey) { this.contentKey = contentKey; }
    public Long getPoiId() { return poiId; }
    public void setPoiId(Long poiId) { this.poiId = poiId; }
    public String getContentText() { return contentText; }
    public void setContentText(String contentText) { this.contentText = contentText; }
    public Integer getAuditStatus() { return auditStatus; }
    public void setAuditStatus(Integer auditStatus) { this.auditStatus = auditStatus; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
