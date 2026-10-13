package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * M8 轨迹草稿 track_draft。
 */
public class TrackDraft {
    private Long id;
    private Long memberId;
    private String name;
    private String polyline;
    private Integer distanceM;
    private Integer durationS;
    private String waypointJson;
    private String rawOssUrl;
    private Integer litBlocks;
    /** 0草稿 1已转路线 2已删 */
    private Integer status;
    private Date createdAt;
    private Date updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPolyline() { return polyline; }
    public void setPolyline(String polyline) { this.polyline = polyline; }
    public Integer getDistanceM() { return distanceM; }
    public void setDistanceM(Integer distanceM) { this.distanceM = distanceM; }
    public Integer getDurationS() { return durationS; }
    public void setDurationS(Integer durationS) { this.durationS = durationS; }
    public String getWaypointJson() { return waypointJson; }
    public void setWaypointJson(String waypointJson) { this.waypointJson = waypointJson; }
    public String getRawOssUrl() { return rawOssUrl; }
    public void setRawOssUrl(String rawOssUrl) { this.rawOssUrl = rawOssUrl; }
    public Integer getLitBlocks() { return litBlocks; }
    public void setLitBlocks(Integer litBlocks) { this.litBlocks = litBlocks; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
    public Date getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}
