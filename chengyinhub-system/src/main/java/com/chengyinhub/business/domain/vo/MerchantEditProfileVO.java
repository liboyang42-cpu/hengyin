package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.MmsMerchant;

/**
 * 已认证商家资料编辑器的读取白名单。
 *
 * <p>只在 {@code /api/merchant/coop-profile} 返回。匿名接口继续使用公开 VO；
 * phone / wechat / businessLicense / status / accountStatus / delFlag / reson 及审计字段
 * 不得加入本类型。</p>
 */
public class MerchantEditProfileVO extends PublicMerchantDiscoveryVO {
    private String derivatives;
    private String derivativeBenefits;
    public String getDerivativeBenefits() { return derivativeBenefits; }
    public void setDerivativeBenefits(String value) { this.derivativeBenefits = value; }
    private String website;
    private String preference;
    private Integer categoryId;
    private Integer featuredType;
    private Long featuredId;
    private Integer locationVerified;

    public static MerchantEditProfileVO from(MmsMerchant merchant) {
        MerchantEditProfileVO vo = fill(merchant, new MerchantEditProfileVO());
        vo.setChargeType(merchant.getChargeType());
        vo.setCoopOpen(merchant.getCoopOpen());
        vo.setServiceTag(merchant.getServiceTag());
        vo.setServiceText(merchant.getServiceText());
        vo.setDerivatives(merchant.getDerivatives());
        vo.setDerivativeBenefits(merchant.getDerivativeBenefits());
        vo.setWebsite(merchant.getWebsite());
        vo.setPreference(merchant.getPreference());
        vo.setCategoryId(merchant.getCategoryId());
        vo.setFeaturedType(merchant.getFeaturedType());
        vo.setFeaturedId(merchant.getFeaturedId());
        vo.setLocationVerified(merchant.getLocationVerified());
        return vo;
    }

    public String getDerivatives() { return derivatives; }
    public void setDerivatives(String derivatives) { this.derivatives = derivatives; }
    public String getWebsite() { return website; }
    public void setWebsite(String website) { this.website = website; }
    public String getPreference() { return preference; }
    public void setPreference(String preference) { this.preference = preference; }
    public Integer getCategoryId() { return categoryId; }
    public void setCategoryId(Integer categoryId) { this.categoryId = categoryId; }
    public Integer getFeaturedType() { return featuredType; }
    public void setFeaturedType(Integer featuredType) { this.featuredType = featuredType; }
    public Long getFeaturedId() { return featuredId; }
    public void setFeaturedId(Long featuredId) { this.featuredId = featuredId; }
    public Integer getLocationVerified() { return locationVerified; }
    public void setLocationVerified(Integer locationVerified) { this.locationVerified = locationVerified; }
}
