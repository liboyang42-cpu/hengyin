package com.chengyinhub.business.domain;

import java.util.Date;

/** P2 漫游会话 roam_session。 */
public class RoamSession {
    private Long id;
    private Long memberId;
    private String clientSessionKey;
    private String finishResultJson;
    private Date startedAt;
    private Date finishedAt;
    private Date locationClearedAt;
    private Integer distanceM;
    private Integer tilesRevealed;
    private Integer poisFound;
    private Integer xpAwarded;
    /** 完成后召回发送成功时间；空表示尚未成功送达。 */
    private Date recallSentAt;
    /** 召回尝试次数，最多 3 次。 */
    private Integer recallAttempts;
    /** 最近一次抢占发送的时间；失败后至少间隔 6 小时重试。 */
    private Date recallLastAttemptAt;
    /** 最近一次上报的位置(已截到 3 位小数 ≈ 100m 网格,截位在 ApiRoamController.presence 做)。 */
    private java.math.BigDecimal lastLat;
    private java.math.BigDecimal lastLng;
    /** 最近一次上报时间;超过 PRESENCE_TTL_MIN 就不再算「此刻在走」。 */
    private Date lastReportAt;
    /** 客户端算的本次探索度 0-99。服务端只存不算 —— 它是本机 20×20 格的口径,后端没有等价物。 */
    private Integer explorePct;
    private Boolean presenceVisible;
    /** 0 进行中 1 已结算 */
    private Integer status;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getClientSessionKey() { return clientSessionKey; }
    public void setClientSessionKey(String clientSessionKey) { this.clientSessionKey = clientSessionKey; }
    public String getFinishResultJson() { return finishResultJson; }
    public void setFinishResultJson(String finishResultJson) { this.finishResultJson = finishResultJson; }
    public Date getStartedAt() { return startedAt; }
    public void setStartedAt(Date startedAt) { this.startedAt = startedAt; }
    public Date getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Date finishedAt) { this.finishedAt = finishedAt; }
    public Date getLocationClearedAt() { return locationClearedAt; }
    public void setLocationClearedAt(Date value) { locationClearedAt = value; }
    public Integer getDistanceM() { return distanceM; }
    public void setDistanceM(Integer distanceM) { this.distanceM = distanceM; }
    public Integer getTilesRevealed() { return tilesRevealed; }
    public void setTilesRevealed(Integer tilesRevealed) { this.tilesRevealed = tilesRevealed; }
    public Integer getPoisFound() { return poisFound; }
    public void setPoisFound(Integer poisFound) { this.poisFound = poisFound; }
    public Integer getXpAwarded() { return xpAwarded; }
    public void setXpAwarded(Integer xpAwarded) { this.xpAwarded = xpAwarded; }
    public Date getRecallSentAt() { return recallSentAt; }
    public void setRecallSentAt(Date recallSentAt) { this.recallSentAt = recallSentAt; }
    public Integer getRecallAttempts() { return recallAttempts; }
    public void setRecallAttempts(Integer recallAttempts) { this.recallAttempts = recallAttempts; }
    public Date getRecallLastAttemptAt() { return recallLastAttemptAt; }
    public void setRecallLastAttemptAt(Date recallLastAttemptAt) { this.recallLastAttemptAt = recallLastAttemptAt; }
    public java.math.BigDecimal getLastLat() { return lastLat; }
    public void setLastLat(java.math.BigDecimal lastLat) { this.lastLat = lastLat; }
    public java.math.BigDecimal getLastLng() { return lastLng; }
    public void setLastLng(java.math.BigDecimal lastLng) { this.lastLng = lastLng; }
    public Date getLastReportAt() { return lastReportAt; }
    public void setLastReportAt(Date lastReportAt) { this.lastReportAt = lastReportAt; }
    public Integer getExplorePct() { return explorePct; }
    public void setExplorePct(Integer explorePct) { this.explorePct = explorePct; }

    public Boolean getPresenceVisible() { return presenceVisible; }
    public void setPresenceVisible(Boolean presenceVisible) { this.presenceVisible = presenceVisible; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
