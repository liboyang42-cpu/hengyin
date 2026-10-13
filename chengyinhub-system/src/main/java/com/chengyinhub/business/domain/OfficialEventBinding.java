package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * V2 官方活动内容/空间绑定。内容引用与承接关系必须分表，不能由 merchant_ids 推导。
 */
public class OfficialEventBinding {
    public static final String TYPE_THEME = "THEME";
    public static final String TYPE_ROAM_POI = "ROAM_POI";
    public static final String TYPE_ROAM_AREA = "ROAM_AREA";
    public static final String TYPE_MERCHANT_NODE = "MERCHANT_NODE";
    public static final String TYPE_CLUB_SESSION = "CLUB_SESSION";
    public static final String TYPE_EVENT_OWNED_POINT = "EVENT_OWNED_POINT";
    public static final String TYPE_DISPLAY_ONLY = "DISPLAY_ONLY";

    public static final String SOURCE_REFERENCE = "REFERENCE";
    public static final String SOURCE_EVENT_OWNED = "EVENT_OWNED";

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    private Long id;
    private Long eventId;
    private String bindingCode;
    private String bindingType;
    private String sourceMode;
    private Long sourceRefId;
    private String city;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private Integer radiusM;
    private String geometryJson;
    private String displayJson;
    private String snapshotJson;
    private String status;
    private Integer sort;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
    public String getBindingCode() { return bindingCode; }
    public void setBindingCode(String bindingCode) { this.bindingCode = bindingCode; }
    public String getBindingType() { return bindingType; }
    public void setBindingType(String bindingType) { this.bindingType = bindingType; }
    public String getSourceMode() { return sourceMode; }
    public void setSourceMode(String sourceMode) { this.sourceMode = sourceMode; }
    public Long getSourceRefId() { return sourceRefId; }
    public void setSourceRefId(Long sourceRefId) { this.sourceRefId = sourceRefId; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public BigDecimal getLatitude() { return latitude; }
    public void setLatitude(BigDecimal latitude) { this.latitude = latitude; }
    public BigDecimal getLongitude() { return longitude; }
    public void setLongitude(BigDecimal longitude) { this.longitude = longitude; }
    public Integer getRadiusM() { return radiusM; }
    public void setRadiusM(Integer radiusM) { this.radiusM = radiusM; }
    public String getGeometryJson() { return geometryJson; }
    public void setGeometryJson(String geometryJson) { this.geometryJson = geometryJson; }
    public String getDisplayJson() { return displayJson; }
    public void setDisplayJson(String displayJson) { this.displayJson = displayJson; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
