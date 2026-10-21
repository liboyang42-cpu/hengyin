package com.chengyinhub.business.domain.map;

import java.math.BigDecimal;
import java.util.Date;

/** Mapper 行。只含已经对玩家公开的点位字段，审核材料和联系方式不进这个类型。 */
public class MapPublicRow {

    private String sourceType;
    private Long sourceId;
    private String name;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String address;
    private String cover;
    private String npcAvatar;
    private Integer poiType;
    private Boolean found;
    private Boolean pendingRedeem;
    private Integer businessStatus;
    /** 来源行上的可见版本。roam_poi 读 visibility_version；门店和活动读 update_time。空值不是 1。 */
    private String visibilityVersion;
    private Integer locationVerified;
    private Date startDate;
    private Date endDate;

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getLatitude() { return latitude; }
    public void setLatitude(BigDecimal latitude) { this.latitude = latitude; }
    public BigDecimal getLongitude() { return longitude; }
    public void setLongitude(BigDecimal longitude) { this.longitude = longitude; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getCover() { return cover; }
    public void setCover(String cover) { this.cover = cover; }
    public String getNpcAvatar() { return npcAvatar; }
    public void setNpcAvatar(String npcAvatar) { this.npcAvatar = npcAvatar; }
    public Integer getPoiType() { return poiType; }
    public void setPoiType(Integer poiType) { this.poiType = poiType; }
    public Boolean getFound() { return found; }
    public void setFound(Boolean found) { this.found = found; }
    public Boolean getPendingRedeem() { return pendingRedeem; }
    public void setPendingRedeem(Boolean pendingRedeem) { this.pendingRedeem = pendingRedeem; }
    public Integer getBusinessStatus() { return businessStatus; }
    public void setBusinessStatus(Integer businessStatus) { this.businessStatus = businessStatus; }
    public String getVisibilityVersion() { return visibilityVersion; }
    public void setVisibilityVersion(String visibilityVersion) { this.visibilityVersion = visibilityVersion; }
    public Integer getLocationVerified() { return locationVerified; }
    public void setLocationVerified(Integer locationVerified) { this.locationVerified = locationVerified; }
    public Date getStartDate() { return startDate; }
    public void setStartDate(Date startDate) { this.startDate = startDate; }
    public Date getEndDate() { return endDate; }
    public void setEndDate(Date endDate) { this.endDate = endDate; }
}
