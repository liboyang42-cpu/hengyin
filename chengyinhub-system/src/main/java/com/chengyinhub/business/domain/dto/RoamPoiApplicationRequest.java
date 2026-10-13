package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;

/** 商家 roam 据点投放申请；申请当前态直接写入 roam_poi。 */
public class RoamPoiApplicationRequest {
    private Long targetPoiId;
    private String name;
    private String address;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private Integer radiusM;
    private String cityCode;
    private Long templateId;
    private Long couponId;
    private String tags;
    private String coverImg;
    private String description;

    public Long getTargetPoiId() { return targetPoiId; }
    public void setTargetPoiId(Long targetPoiId) { this.targetPoiId = targetPoiId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public BigDecimal getLatitude() { return latitude; }
    public void setLatitude(BigDecimal latitude) { this.latitude = latitude; }
    public BigDecimal getLongitude() { return longitude; }
    public void setLongitude(BigDecimal longitude) { this.longitude = longitude; }
    public Integer getRadiusM() { return radiusM; }
    public void setRadiusM(Integer radiusM) { this.radiusM = radiusM; }
    public String getCityCode() { return cityCode; }
    public void setCityCode(String cityCode) { this.cityCode = cityCode; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Long getCouponId() { return couponId; }
    public void setCouponId(Long couponId) { this.couponId = couponId; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getCoverImg() { return coverImg; }
    public void setCoverImg(String coverImg) { this.coverImg = coverImg; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
