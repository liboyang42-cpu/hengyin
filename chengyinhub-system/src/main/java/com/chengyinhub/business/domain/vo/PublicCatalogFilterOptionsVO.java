package com.chengyinhub.business.domain.vo;

import java.util.List;

/**
 * M7 公开目录的合法筛选项：全部来自真实数据，不用前端写死的城市/语言列表。
 *
 * <p>空集合就是空集合 —— 没有作品的城市、没有人翻过的语言不会出现，
 * 不能为了让筛选器「看起来完整」而补字典值。
 */
public class PublicCatalogFilterOptionsVO {

    private List<CityDiscoveryCatalogItemVO> cities;

    /** 真实存在的 product_type 值(1 经典定向 / 2 自由探索)。 */
    private List<Integer> productTypes;

    /** 真实推导出的交付形态：SELF_PLAY / GUIDED_SESSION / CLUB_HOSTED。 */
    private List<String> deliveryModes;

    /** 真实存在 APPROVED 译文的语言；原文语言(zh-CN)单独用 sourceLocale 表达。 */
    private List<String> approvedLocales;

    /** 原文语言声明集合(来自 content_landing_metadata.source_locale，缺省 zh-CN)。 */
    private List<String> sourceLocales;

    /** 是否存在任何已声明可承接团体的作品。 */
    private Boolean groupEligibleAvailable;

    /** 是否存在任何已声明可承接学校班级的作品。 */
    private Boolean schoolEligibleAvailable;

    public List<CityDiscoveryCatalogItemVO> getCities() { return cities; }

    public void setCities(List<CityDiscoveryCatalogItemVO> cities) { this.cities = cities; }

    public List<Integer> getProductTypes() { return productTypes; }

    public void setProductTypes(List<Integer> productTypes) { this.productTypes = productTypes; }

    public List<String> getDeliveryModes() { return deliveryModes; }

    public void setDeliveryModes(List<String> deliveryModes) { this.deliveryModes = deliveryModes; }

    public List<String> getApprovedLocales() { return approvedLocales; }

    public void setApprovedLocales(List<String> approvedLocales) { this.approvedLocales = approvedLocales; }

    public List<String> getSourceLocales() { return sourceLocales; }

    public void setSourceLocales(List<String> sourceLocales) { this.sourceLocales = sourceLocales; }

    public Boolean getGroupEligibleAvailable() { return groupEligibleAvailable; }

    public void setGroupEligibleAvailable(Boolean groupEligibleAvailable) {
        this.groupEligibleAvailable = groupEligibleAvailable;
    }

    public Boolean getSchoolEligibleAvailable() { return schoolEligibleAvailable; }

    public void setSchoolEligibleAvailable(Boolean schoolEligibleAvailable) {
        this.schoolEligibleAvailable = schoolEligibleAvailable;
    }
}
