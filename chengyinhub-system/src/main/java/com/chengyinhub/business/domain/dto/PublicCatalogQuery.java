package com.chengyinhub.business.domain.dto;

import java.util.List;

/**
 * M7 公开内容目录查询入参(严格枚举，未知值 fail-closed 为 400，退化为「全部」)。
 *
 * <p>没有 {@code viewerMemberId}：目录是公开/SEO 分发面，会员专属主题(club_member_only=1)
 * 一律不出现，与 {@code ContentSearchMapper.topicStaticPublicGate} 的口径一致。
 * 已购/成员可见内容仍走既有 /api/topic 详情。
 *
 * <p>{@code topicIds} 用于「ID 批量补取」形态：与列表/地图投影分开，避免首页为拿
 * 全部卡而拉全量正文。
 */
public class PublicCatalogQuery {

    /** 6 位城市编码(adcode 城市级)，如 310100。 */
    private String cityCode;

    /** 请求语言(BCP-47)；缺省 zh-CN。 */
    private String locale;

    /** 关键词：只匹配标题/副标题/城市名，不搜正文。 */
    private String keyword;

    /** 1 经典定向 / 2 自由探索；未知值 400。 */
    private Integer productType;

    /** SELF_PLAY / GUIDED_SESSION / CLUB_HOSTED；未知值 400。 */
    private String deliveryMode;

    /** 1=只看已声明可承接团体；缺省不限。 */
    private Integer groupEligible;

    /** 1=只看已声明可承接学校班级；缺省不限。 */
    private Integer schoolEligible;

    /** 只看建议年龄覆盖该年龄的作品；无年龄声明的作品不参与该筛选。 */
    private Integer audienceAge;

    /** 最短/最长时长(分钟)，用于「人数/时长」筛选。 */
    private Integer minMinutes;

    private Integer maxMinutes;

    /** NEWEST / PRICE_ASC / DURATION_ASC；未知值 400。 */
    private String sort;

    /** ID 批量补取形态的显式 id 列表；与分页互斥使用。 */
    private List<Long> topicIds;

    public String getCityCode() { return cityCode; }

    public void setCityCode(String cityCode) { this.cityCode = cityCode; }

    public String getLocale() { return locale; }

    public void setLocale(String locale) { this.locale = locale; }

    public String getKeyword() { return keyword; }

    public void setKeyword(String keyword) { this.keyword = keyword; }

    public Integer getProductType() { return productType; }

    public void setProductType(Integer productType) { this.productType = productType; }

    public String getDeliveryMode() { return deliveryMode; }

    public void setDeliveryMode(String deliveryMode) { this.deliveryMode = deliveryMode; }

    public Integer getGroupEligible() { return groupEligible; }

    public void setGroupEligible(Integer groupEligible) { this.groupEligible = groupEligible; }

    public Integer getSchoolEligible() { return schoolEligible; }

    public void setSchoolEligible(Integer schoolEligible) { this.schoolEligible = schoolEligible; }

    public Integer getAudienceAge() { return audienceAge; }

    public void setAudienceAge(Integer audienceAge) { this.audienceAge = audienceAge; }

    public Integer getMinMinutes() { return minMinutes; }

    public void setMinMinutes(Integer minMinutes) { this.minMinutes = minMinutes; }

    public Integer getMaxMinutes() { return maxMinutes; }

    public void setMaxMinutes(Integer maxMinutes) { this.maxMinutes = maxMinutes; }

    public String getSort() { return sort; }

    public void setSort(String sort) { this.sort = sort; }

    public List<Long> getTopicIds() { return topicIds; }

    public void setTopicIds(List<Long> topicIds) { this.topicIds = topicIds; }
}
