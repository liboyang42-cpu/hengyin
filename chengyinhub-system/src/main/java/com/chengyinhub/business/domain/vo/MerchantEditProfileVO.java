package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.MmsMerchant;

/**
 * 已认证商家资料编辑器的读取白名单。
 *
 * <p>只在 {@code /api/merchant/coop-profile} 返回(需登录 + 本店 COOP_MANAGE)。匿名接口继续使用公开 VO；
 * businessLicense / status / accountStatus / delFlag / reson 及审计字段不得加入本类型。</p>
 *
 * <p>owner 2026-10-22:商家可在「门店信息」自助维护联系电话 / 门店微信号 / 门店二维码,
 * 所以这三项进本店编辑白名单;公开 VO({@link PublicMerchantVO} 及其子类)仍不承载它们。</p>
 */
public class MerchantEditProfileVO extends PublicMerchantDiscoveryVO {
    private String derivatives;
    private String derivativeBenefits;
    public String getDerivativeBenefits() { return derivativeBenefits; }
    public void setDerivativeBenefits(String value) { this.derivativeBenefits = value; }
    private String website;
    private String preference;
    private Integer categoryId;
    /** 行业类型多选(逗号分隔 id),categoryId 是其中第一个。 */
    private String categoryIds;
    private String phone;
    /** 门店微信号(文本 ID)。 */
    private String wechat;
    /** 门店微信二维码图片 URL。 */
    private String wechatQrcode;
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
        vo.setCategoryIds(merchant.getCategoryIds());
        vo.setPhone(merchant.getPhone());
        vo.setWechat(merchant.getWechat());
        vo.setWechatQrcode(merchant.getWechatQrcode());
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
    public String getCategoryIds() { return categoryIds; }
    public void setCategoryIds(String categoryIds) { this.categoryIds = categoryIds; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getWechat() { return wechat; }
    public void setWechat(String wechat) { this.wechat = wechat; }
    public String getWechatQrcode() { return wechatQrcode; }
    public void setWechatQrcode(String wechatQrcode) { this.wechatQrcode = wechatQrcode; }
    public Integer getFeaturedType() { return featuredType; }
    public void setFeaturedType(Integer featuredType) { this.featuredType = featuredType; }
    public Long getFeaturedId() { return featuredId; }
    public void setFeaturedId(Long featuredId) { this.featuredId = featuredId; }
    public Integer getLocationVerified() { return locationVerified; }
    public void setLocationVerified(Integer locationVerified) { this.locationVerified = locationVerified; }
}
