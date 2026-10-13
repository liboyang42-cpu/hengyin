package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * NPC 事件话术缓存实体。
 *
 * @author chengyinhub
 */
public class NpcLineCache
{
    private Long id;
    private Long profileId;
    private String eventType;
    private Long nodeId;
    private String variantsJson;
    private Integer auditStatus;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProfileId() { return profileId; }
    public void setProfileId(Long profileId) { this.profileId = profileId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public String getVariantsJson() { return variantsJson; }
    public void setVariantsJson(String variantsJson) { this.variantsJson = variantsJson; }
    public Integer getAuditStatus() { return auditStatus; }
    public void setAuditStatus(Integer auditStatus) { this.auditStatus = auditStatus; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
