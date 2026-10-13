package com.chengyinhub.business.domain.dto;

import java.util.Date;

/** NF-15 稳定游标查询；cursor 为上一页最后一条 id。 */
public class BusinessAuditLogQueryDTO {
    private String domainType;
    private Long domainId;
    private Long actorId;
    private String actorType;
    private String action;
    private String targetType;
    private Long targetId;
    private Date fromTime;
    private Date toTime;
    private Long cursor;
    private Integer limit;
    private Integer fetchLimit;

    public String getDomainType() { return domainType; }
    public void setDomainType(String domainType) { this.domainType = domainType; }
    public Long getDomainId() { return domainId; }
    public void setDomainId(Long domainId) { this.domainId = domainId; }
    public Long getActorId() { return actorId; }
    public void setActorId(Long actorId) { this.actorId = actorId; }
    public String getActorType() { return actorType; }
    public void setActorType(String actorType) { this.actorType = actorType; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public Date getFromTime() { return fromTime; }
    public void setFromTime(Date fromTime) { this.fromTime = fromTime; }
    public Date getToTime() { return toTime; }
    public void setToTime(Date toTime) { this.toTime = toTime; }
    public Long getCursor() { return cursor; }
    public void setCursor(Long cursor) { this.cursor = cursor; }
    public Integer getLimit() { return limit; }
    public void setLimit(Integer limit) { this.limit = limit; }
    public Integer getFetchLimit() { return fetchLimit; }
    public void setFetchLimit(Integer fetchLimit) { this.fetchLimit = fetchLimit; }
}
