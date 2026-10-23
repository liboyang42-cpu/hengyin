package com.chengyinhub.business.domain;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/** 城市定向节点 WGS84 坐标归一结果(orient_node_geo);脏值 geoStatus=1、坐标为 null。 */
public class OrientNodeGeo implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long nodeId;
    private Long topicId;
    private String sourceCoordSys;
    private String sourceLng;
    private String sourceLat;
    private String sourceFingerprint;
    private BigDecimal wgs84Lng;
    private BigDecimal wgs84Lat;
    private Integer geoStatus;
    private String dirtyReason;
    private Date createTime;
    private Date updateTime;

    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public String getSourceCoordSys() { return sourceCoordSys; }
    public void setSourceCoordSys(String sourceCoordSys) { this.sourceCoordSys = sourceCoordSys; }
    public String getSourceLng() { return sourceLng; }
    public void setSourceLng(String sourceLng) { this.sourceLng = sourceLng; }
    public String getSourceLat() { return sourceLat; }
    public void setSourceLat(String sourceLat) { this.sourceLat = sourceLat; }
    public String getSourceFingerprint() { return sourceFingerprint; }
    public void setSourceFingerprint(String sourceFingerprint) { this.sourceFingerprint = sourceFingerprint; }
    public BigDecimal getWgs84Lng() { return wgs84Lng; }
    public void setWgs84Lng(BigDecimal wgs84Lng) { this.wgs84Lng = wgs84Lng; }
    public BigDecimal getWgs84Lat() { return wgs84Lat; }
    public void setWgs84Lat(BigDecimal wgs84Lat) { this.wgs84Lat = wgs84Lat; }
    public Integer getGeoStatus() { return geoStatus; }
    public void setGeoStatus(Integer geoStatus) { this.geoStatus = geoStatus; }
    public String getDirtyReason() { return dirtyReason; }
    public void setDirtyReason(String dirtyReason) { this.dirtyReason = dirtyReason; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
