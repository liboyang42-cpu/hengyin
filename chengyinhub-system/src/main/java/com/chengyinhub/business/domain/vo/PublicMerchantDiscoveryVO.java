package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.dto.MerchantSearchQuery;

/** 匿名发现/旧详情路由的公开扩展，不承载任何资质、审核或联系人字段。 */
public class PublicMerchantDiscoveryVO extends PublicMerchantVO {
    /** 0 免费 / 1 收费。 */
    private Integer chargeType;
    /** 是否公开接受合作。 */
    private Integer coopOpen;
    private String serviceTag;
    private String serviceText;
    /** 搜索者到门店的直线距离（米）。 */
    private Double distance;

    public static PublicMerchantDiscoveryVO from(MmsMerchant merchant) {
        PublicMerchantDiscoveryVO vo = fill(merchant, new PublicMerchantDiscoveryVO());
        vo.setChargeType(merchant.getChargeType());
        vo.setCoopOpen(merchant.getCoopOpen());
        vo.setServiceTag(merchant.getServiceTag());
        vo.setServiceText(merchant.getServiceText());
        if (merchant instanceof MerchantSearchQuery) {
            vo.setDistance(((MerchantSearchQuery) merchant).getDistance());
        }
        return vo;
    }

    public Integer getChargeType() { return chargeType; }
    public void setChargeType(Integer chargeType) { this.chargeType = chargeType; }
    public Integer getCoopOpen() { return coopOpen; }
    public void setCoopOpen(Integer coopOpen) { this.coopOpen = coopOpen; }
    public String getServiceTag() { return serviceTag; }
    public void setServiceTag(String serviceTag) { this.serviceTag = serviceTag; }
    public String getServiceText() { return serviceText; }
    public void setServiceText(String serviceText) { this.serviceText = serviceText; }
    public Double getDistance() { return distance; }
    public void setDistance(Double distance) { this.distance = distance; }
}
