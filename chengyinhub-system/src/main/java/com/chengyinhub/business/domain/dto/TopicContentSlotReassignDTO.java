package com.chengyinhub.business.domain.dto;

/** 高风险内容归属改判请求；主体校验由服务层与角色槽服务共同完成。 */
public class TopicContentSlotReassignDTO {

    private String entityType;
    private Long entityId;

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public Long getEntityId() {
        return entityId;
    }

    public void setEntityId(Long entityId) {
        this.entityId = entityId;
    }
}
