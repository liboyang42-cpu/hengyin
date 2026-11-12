package com.chengyinhub.business.domain.vo;

/**
 * M7 城市目录行：只由真实已公开主题聚合，没有作品的城市不会出现。
 *
 * <p>「8000 城市页 / 9 语言」是竞品样本规模，不是城瘾的造页目标：本目录
 * <b>不</b>从城市字典生成空页，也不为没有供给的城市编占位内容。
 *
 * <p>{@code cityName} 只来自真实数据(俱乐部所在城市)，且仅当同一 city_code 下
 * 该名称唯一一致时给出；冲突或缺失一律 null，由前端显示城市编码或「未标注」，
 * 不用推测或翻译补名。
 */
public class CityDiscoveryCatalogItemVO {

    /** 6 位城市编码；null 表示该主题未标注城市(单独一个「未标注」桶)。 */
    private String cityCode;

    private String cityName;

    /** 稳定城市路由；cityCode 为 null 时也为 null(不造伪编码)。 */
    private String cityPath;

    private Long topicCount;

    /** 其中生命周期在售且未过售止的数量；不含免费主题可得性(需既有 CommercialTopicPolicy 评估)。 */
    private Long onSaleCount;

    /** 其中已声明可承接团体的数量。 */
    private Long groupEligibleCount;

    /** 其中已声明可承接学校班级的数量。 */
    private Long schoolEligibleCount;

    public String getCityCode() { return cityCode; }

    public void setCityCode(String cityCode) { this.cityCode = cityCode; }

    public String getCityName() { return cityName; }

    public void setCityName(String cityName) { this.cityName = cityName; }

    public String getCityPath() { return cityPath; }

    public void setCityPath(String cityPath) { this.cityPath = cityPath; }

    public Long getTopicCount() { return topicCount; }

    public void setTopicCount(Long topicCount) { this.topicCount = topicCount; }

    public Long getOnSaleCount() { return onSaleCount; }

    public void setOnSaleCount(Long onSaleCount) { this.onSaleCount = onSaleCount; }

    public Long getGroupEligibleCount() { return groupEligibleCount; }

    public void setGroupEligibleCount(Long groupEligibleCount) { this.groupEligibleCount = groupEligibleCount; }

    public Long getSchoolEligibleCount() { return schoolEligibleCount; }

    public void setSchoolEligibleCount(Long schoolEligibleCount) { this.schoolEligibleCount = schoolEligibleCount; }
}
