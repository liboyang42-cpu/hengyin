package com.chengyinhub.business.cityboard;

import java.util.Date;

public class CityBoardReleaseRow {
    private Long id;
    private String regionId;
    private Long draftId;
    private Long revision;
    private String rulesReleaseId;
    private String gameId;
    private String boardId;
    private String seasonId;
    private String title;
    private String contentHash;
    private String approvalHash;
    private Long approverUserId;
    private String snapshotJson;
    private Integer currentFlag;
    private Long publisherUserId;
    private Date createTime;

    public CityBoardReleaseRow copy() {
        CityBoardReleaseRow row = new CityBoardReleaseRow();
        row.id = id;
        row.regionId = regionId;
        row.draftId = draftId;
        row.revision = revision;
        row.rulesReleaseId = rulesReleaseId;
        row.gameId = gameId;
        row.boardId = boardId;
        row.seasonId = seasonId;
        row.title = title;
        row.lifecycle = lifecycle;
        row.contentHash = contentHash;
        row.approvalHash = approvalHash;
        row.approverUserId = approverUserId;
        row.snapshotJson = snapshotJson;
        row.currentFlag = currentFlag;
        row.publisherUserId = publisherUserId;
        row.createTime = createTime;
        return row;
    }

    private String lifecycle;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRegionId() { return regionId; }
    public void setRegionId(String regionId) { this.regionId = regionId; }
    public Long getDraftId() { return draftId; }
    public void setDraftId(Long draftId) { this.draftId = draftId; }
    public Long getRevision() { return revision; }
    public void setRevision(Long revision) { this.revision = revision; }
    public String getRulesReleaseId() { return rulesReleaseId; }
    public void setRulesReleaseId(String rulesReleaseId) { this.rulesReleaseId = rulesReleaseId; }
    public String getGameId() { return gameId; }
    public void setGameId(String gameId) { this.gameId = gameId; }
    public String getBoardId() { return boardId; }
    public void setBoardId(String boardId) { this.boardId = boardId; }
    public String getSeasonId() { return seasonId; }
    public void setSeasonId(String seasonId) { this.seasonId = seasonId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getLifecycle() { return lifecycle; }
    public void setLifecycle(String lifecycle) { this.lifecycle = lifecycle; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getApprovalHash() { return approvalHash; }
    public void setApprovalHash(String approvalHash) { this.approvalHash = approvalHash; }
    public Long getApproverUserId() { return approverUserId; }
    public void setApproverUserId(Long approverUserId) { this.approverUserId = approverUserId; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }
    public Integer getCurrentFlag() { return currentFlag; }
    public void setCurrentFlag(Integer currentFlag) { this.currentFlag = currentFlag; }
    public Long getPublisherUserId() { return publisherUserId; }
    public void setPublisherUserId(Long publisherUserId) { this.publisherUserId = publisherUserId; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
