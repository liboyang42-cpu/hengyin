package com.chengyinhub.business.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.util.Date;

/**
 * M7 地图轻投影：只为「附近的局」和目录地图用，不含摘要正文、不含价格明细。
 *
 * <p>落点只取该主题第一个<b>公开(status=1)</b>节点的坐标 —— 与
 * {@code CmsTopicMapper.selectNearbyTopics} 同一口径；没有坐标就是 null，
 * 不用城市中心或商家地址猜。
 */
public class PublicContentCatalogMapPointVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String canonicalPath;

    private String name;

    private String cityCode;

    private String imgUrl;

    private Long locationCount;

    private BigDecimal latitude;

    private BigDecimal longitude;

    private Date updatedAt;

    public Long getId() { return id; }

    public void setId(Long id) { this.id = id; }

    public String getCanonicalPath() { return canonicalPath; }

    public void setCanonicalPath(String canonicalPath) { this.canonicalPath = canonicalPath; }

    public String getName() { return name; }

    public void setName(String name) { this.name = name; }

    public String getCityCode() { return cityCode; }

    public void setCityCode(String cityCode) { this.cityCode = cityCode; }

    public String getImgUrl() { return imgUrl; }

    public void setImgUrl(String imgUrl) { this.imgUrl = imgUrl; }

    public Long getLocationCount() { return locationCount; }

    public void setLocationCount(Long locationCount) { this.locationCount = locationCount; }

    public BigDecimal getLatitude() { return latitude; }

    public void setLatitude(BigDecimal latitude) { this.latitude = latitude; }

    public BigDecimal getLongitude() { return longitude; }

    public void setLongitude(BigDecimal longitude) { this.longitude = longitude; }

    public Date getUpdatedAt() { return updatedAt; }

    public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}
