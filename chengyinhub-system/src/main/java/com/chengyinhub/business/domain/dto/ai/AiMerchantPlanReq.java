package com.chengyinhub.business.domain.dto.ai;

/**
 * M1 商家组题请求。
 */
public class AiMerchantPlanReq
{
    private String idea;
    private Double lat;
    private Double lng;
    /** 预计时长(分钟) */
    private Integer durationMin;
    /** 搜索半径(米,默认2000) */
    private Integer radiusM;
    /** 是否需要优惠券节点 */
    private Boolean needCoupon;

    public String getIdea() { return idea; }
    public void setIdea(String idea) { this.idea = idea; }
    public Double getLat() { return lat; }
    public void setLat(Double lat) { this.lat = lat; }
    public Double getLng() { return lng; }
    public void setLng(Double lng) { this.lng = lng; }
    public Integer getDurationMin() { return durationMin; }
    public void setDurationMin(Integer durationMin) { this.durationMin = durationMin; }
    public Integer getRadiusM() { return radiusM; }
    public void setRadiusM(Integer radiusM) { this.radiusM = radiusM; }
    public Boolean getNeedCoupon() { return needCoupon; }
    public void setNeedCoupon(Boolean needCoupon) { this.needCoupon = needCoupon; }
}
