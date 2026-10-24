package com.chengyinhub.business.domain.vo;

import java.math.BigDecimal;

/**
 * 漫游商家节点视图:roam_poi(type=2) + mms_merchant + cms_member_template 联合。
 * 供 /api/city/nodes 列表与详情;completed/favorited 由控制器按登录态填充。
 */
public class CityNodeVO {
    private Double averageRating;
    private Integer ratingCount;
    public Double getAverageRating() { return averageRating; }
    public void setAverageRating(Double value) { averageRating = value; }
    public Integer getRatingCount() { return ratingCount; }
    public void setRatingCount(Integer value) { ratingCount = value; }
    private String galleryJson;
    private Boolean photoRequired;
    private String photoRequireDesc;
    private String badgeCode;
    public String getGalleryJson() { return galleryJson; }
    public void setGalleryJson(String value) { galleryJson = value; }
    public Boolean getPhotoRequired() { return photoRequired; }
    public void setPhotoRequired(Boolean value) { photoRequired = value; }
    public String getPhotoRequireDesc() { return photoRequireDesc; }
    public void setPhotoRequireDesc(String value) { photoRequireDesc = value; }
    public String getBadgeCode() { return badgeCode; }
    public void setBadgeCode(String value) { badgeCode = value; }
    private Long poiId;
    private String name;
    private String description;
    private BigDecimal lat;
    private BigDecimal lng;
    private Integer radiusM;
    private Integer nodeLevel;
    private String tags;
    private String coverImg;
    private String cityCode;
    private Integer status;
    private Double distance;

    // 商家
    private Long merchantId;
    private String merchantName;
    private String merchantLogo;
    private String merchantAddress;
    private String merchantPhone;

    // 模板 / 玩法
    private Long templateId;
    private String templateTitle;
    private String interactionType;
    private Integer validationMethod;
    private Long couponId;
    // vm=3 选项问答的题面与四个选项。
    // ⚠️ 这个 VO 是**玩家侧投影**,correctAnswer 永远不在这里 —— 判定在服务端读模板实体做。
    private String questionName;
    private String questionA;
    private String questionB;
    private String questionC;
    private String questionD;

    // 用户态(控制器填充)
    private Boolean completed;
    private Boolean favorited;
    private Boolean needRedeem;

    public Long getPoiId() { return poiId; }
    public void setPoiId(Long poiId) { this.poiId = poiId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getLat() { return lat; }
    public void setLat(BigDecimal lat) { this.lat = lat; }
    public BigDecimal getLng() { return lng; }
    public void setLng(BigDecimal lng) { this.lng = lng; }
    public Integer getRadiusM() { return radiusM; }
    public void setRadiusM(Integer radiusM) { this.radiusM = radiusM; }
    public Integer getNodeLevel() { return nodeLevel; }
    public void setNodeLevel(Integer nodeLevel) { this.nodeLevel = nodeLevel; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getCoverImg() { return coverImg; }
    public void setCoverImg(String coverImg) { this.coverImg = coverImg; }
    public String getCityCode() { return cityCode; }
    public void setCityCode(String cityCode) { this.cityCode = cityCode; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Double getDistance() { return distance; }
    public void setDistance(Double distance) { this.distance = distance; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
    public String getMerchantLogo() { return merchantLogo; }
    public void setMerchantLogo(String merchantLogo) { this.merchantLogo = merchantLogo; }
    public String getMerchantAddress() { return merchantAddress; }
    public void setMerchantAddress(String merchantAddress) { this.merchantAddress = merchantAddress; }
    public String getMerchantPhone() { return merchantPhone; }
    public void setMerchantPhone(String merchantPhone) { this.merchantPhone = merchantPhone; }

    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public String getTemplateTitle() { return templateTitle; }
    public void setTemplateTitle(String templateTitle) { this.templateTitle = templateTitle; }
    public String getInteractionType() { return interactionType; }
    public void setInteractionType(String interactionType) { this.interactionType = interactionType; }
    public Integer getValidationMethod() { return validationMethod; }
    public void setValidationMethod(Integer validationMethod) { this.validationMethod = validationMethod; }
    public Long getCouponId() { return couponId; }
    public void setCouponId(Long couponId) { this.couponId = couponId; }
    public String getQuestionName() { return questionName; }
    public void setQuestionName(String questionName) { this.questionName = questionName; }
    public String getQuestionA() { return questionA; }
    public void setQuestionA(String questionA) { this.questionA = questionA; }
    public String getQuestionB() { return questionB; }
    public void setQuestionB(String questionB) { this.questionB = questionB; }
    public String getQuestionC() { return questionC; }
    public void setQuestionC(String questionC) { this.questionC = questionC; }
    public String getQuestionD() { return questionD; }
    public void setQuestionD(String questionD) { this.questionD = questionD; }

    public Boolean getCompleted() { return completed; }
    public void setCompleted(Boolean completed) { this.completed = completed; }
    public Boolean getFavorited() { return favorited; }
    public void setFavorited(Boolean favorited) { this.favorited = favorited; }
    public Boolean getNeedRedeem() { return needRedeem; }
    public void setNeedRedeem(Boolean needRedeem) { this.needRedeem = needRedeem; }
}
