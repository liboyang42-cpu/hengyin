package com.chengyinhub.business.domain.dto.seed;

import java.math.BigDecimal;
import java.util.Date;

/** An official place row read for import, export or the density report (roam_poi type=1). */
public class SeedPlace {
    private Long id;
    private String name;
    private Integer type;
    private String category;
    private BigDecimal lat;
    private BigDecimal lng;
    private String address;
    private String coverImg;
    private String galleryJson;
    private String tags;
    private String openingHours;
    private String description;
    private String cityCode;
    private Integer status;
    private Integer auditStatus;
    private Long merchantId;
    private Long templateId;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getType() { return type; }
    public void setType(Integer type) { this.type = type; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public BigDecimal getLat() { return lat; }
    public void setLat(BigDecimal lat) { this.lat = lat; }
    public BigDecimal getLng() { return lng; }
    public void setLng(BigDecimal lng) { this.lng = lng; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getCoverImg() { return coverImg; }
    public void setCoverImg(String coverImg) { this.coverImg = coverImg; }
    public String getGalleryJson() { return galleryJson; }
    public void setGalleryJson(String galleryJson) { this.galleryJson = galleryJson; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getOpeningHours() { return openingHours; }
    public void setOpeningHours(String openingHours) { this.openingHours = openingHours; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCityCode() { return cityCode; }
    public void setCityCode(String cityCode) { this.cityCode = cityCode; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getAuditStatus() { return auditStatus; }
    public void setAuditStatus(Integer auditStatus) { this.auditStatus = auditStatus; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
