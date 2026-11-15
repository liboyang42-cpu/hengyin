package com.chengyinhub.business.domain.vo;

import java.math.BigDecimal;

/**
 * 目录地点(roam_poi type=1 + 种子标签)的公开只读详情。
 * 只含展示字段:照片、引述、署名、坐标;没有模板、券、题目与答案(本期目录地点无挑战)。
 */
public class CatalogPlaceVO {
    private Long poiId;
    private String name;
    private String address;
    private String description;
    private BigDecimal lat;
    private BigDecimal lng;
    private Integer radiusM;
    private String cityCode;
    private Integer nodeLevel;
    private String tags;
    private String coverImg;
    private String galleryJson;
    private String quotesJson;
    private String photoCredit;
    private String badgeCode;

    public Long getPoiId() { return poiId; }
    public void setPoiId(Long value) { poiId = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getAddress() { return address; }
    public void setAddress(String value) { address = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public BigDecimal getLat() { return lat; }
    public void setLat(BigDecimal value) { lat = value; }
    public BigDecimal getLng() { return lng; }
    public void setLng(BigDecimal value) { lng = value; }
    public Integer getRadiusM() { return radiusM; }
    public void setRadiusM(Integer value) { radiusM = value; }
    public String getCityCode() { return cityCode; }
    public void setCityCode(String value) { cityCode = value; }
    public Integer getNodeLevel() { return nodeLevel; }
    public void setNodeLevel(Integer value) { nodeLevel = value; }
    public String getTags() { return tags; }
    public void setTags(String value) { tags = value; }
    public String getCoverImg() { return coverImg; }
    public void setCoverImg(String value) { coverImg = value; }
    public String getGalleryJson() { return galleryJson; }
    public void setGalleryJson(String value) { galleryJson = value; }
    public String getQuotesJson() { return quotesJson; }
    public void setQuotesJson(String value) { quotesJson = value; }
    public String getPhotoCredit() { return photoCredit; }
    public void setPhotoCredit(String value) { photoCredit = value; }
    public String getBadgeCode() { return badgeCode; }
    public void setBadgeCode(String value) { badgeCode = value; }
}
