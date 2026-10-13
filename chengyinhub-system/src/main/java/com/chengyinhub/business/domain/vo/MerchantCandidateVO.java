package com.chengyinhub.business.domain.vo;

/**
 * 候选商家 VO —— M1 组题时 Haversine 查询返回每条候选节点。
 * 字段按 ApiAiMapper.xml SELECT 别名匹配(resultType,非 resultMap)。
 *
 * @author chengyinhub
 */
public class MerchantCandidateVO
{
    private Long candidateId;
    private Long merchantId;
    private Long topicId;
    private Long nodeId;
    private String addressName;
    private String address;
    private String longitude;
    private String latitude;
    private String picUrl;
    private String startDate;
    private String endDate;
    private Long limitNum;
    private Long couponId;
    private Integer mode;
    /** 商家档案:名称(JOIN mms_merchant.name) */
    private String merchantName;
    private Long categoryId;
    private String businessTime;
    private Long capacity;
    private String suitActivityTypes;
    private Integer coopOpen;
    /** 券相关信息(JOIN sms_coupon,LEFT):名称/金额/库存 */
    private String couponName;
    private java.math.BigDecimal couponAmount;
    private Long couponStock;
    /** Haversine 计算距离(米) */
    private Double distance;
    /** 合作互评平均分(0~5,无评价为0) */
    private Double rating;
    /** 规则层距离×评分×库存加权分(仅运行时,不入库) */
    private Double matchScore;

    public Long getCandidateId() { return candidateId; }
    public void setCandidateId(Long candidateId) { this.candidateId = candidateId; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public String getAddressName() { return addressName; }
    public void setAddressName(String addressName) { this.addressName = addressName; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getLongitude() { return longitude; }
    public void setLongitude(String longitude) { this.longitude = longitude; }
    public String getLatitude() { return latitude; }
    public void setLatitude(String latitude) { this.latitude = latitude; }
    public String getPicUrl() { return picUrl; }
    public void setPicUrl(String picUrl) { this.picUrl = picUrl; }
    public String getStartDate() { return startDate; }
    public void setStartDate(String startDate) { this.startDate = startDate; }
    public String getEndDate() { return endDate; }
    public void setEndDate(String endDate) { this.endDate = endDate; }
    public Long getLimitNum() { return limitNum; }
    public void setLimitNum(Long limitNum) { this.limitNum = limitNum; }
    public Long getCouponId() { return couponId; }
    public void setCouponId(Long couponId) { this.couponId = couponId; }
    public Integer getMode() { return mode; }
    public void setMode(Integer mode) { this.mode = mode; }
    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public String getBusinessTime() { return businessTime; }
    public void setBusinessTime(String businessTime) { this.businessTime = businessTime; }
    public Long getCapacity() { return capacity; }
    public void setCapacity(Long capacity) { this.capacity = capacity; }
    public String getSuitActivityTypes() { return suitActivityTypes; }
    public void setSuitActivityTypes(String suitActivityTypes) { this.suitActivityTypes = suitActivityTypes; }
    public Integer getCoopOpen() { return coopOpen; }
    public void setCoopOpen(Integer coopOpen) { this.coopOpen = coopOpen; }
    public String getCouponName() { return couponName; }
    public void setCouponName(String couponName) { this.couponName = couponName; }
    public java.math.BigDecimal getCouponAmount() { return couponAmount; }
    public void setCouponAmount(java.math.BigDecimal couponAmount) { this.couponAmount = couponAmount; }
    public Long getCouponStock() { return couponStock; }
    public void setCouponStock(Long couponStock) { this.couponStock = couponStock; }
    public Double getDistance() { return distance; }
    public void setDistance(Double distance) { this.distance = distance; }
    public Double getRating() { return rating; }
    public void setRating(Double rating) { this.rating = rating; }
    public Double getMatchScore() { return matchScore; }
    public void setMatchScore(Double matchScore) { this.matchScore = matchScore; }
}
