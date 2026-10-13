package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

/** 玩家或队伍的剧情变量及唯一结局。 */
public class GameStoryState extends BaseEntity {
    private static final long serialVersionUID = 1L;
    private Long id;
    private Long gameSessionId;
    private String ownerType;
    private Long ownerId;
    private String variablesJson;
    private String appliedChoicesJson;
    private String endingCode;
    private String status;
    private Integer revision;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getGameSessionId() { return gameSessionId; }
    public void setGameSessionId(Long gameSessionId) { this.gameSessionId = gameSessionId; }
    public String getOwnerType() { return ownerType; }
    public void setOwnerType(String ownerType) { this.ownerType = ownerType; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public String getVariablesJson() { return variablesJson; }
    public void setVariablesJson(String variablesJson) { this.variablesJson = variablesJson; }
    public String getAppliedChoicesJson() { return appliedChoicesJson; }
    public void setAppliedChoicesJson(String appliedChoicesJson) { this.appliedChoicesJson = appliedChoicesJson; }
    public String getEndingCode() { return endingCode; }
    public void setEndingCode(String endingCode) { this.endingCode = endingCode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getRevision() { return revision; }
    public void setRevision(Integer revision) { this.revision = revision; }
}
