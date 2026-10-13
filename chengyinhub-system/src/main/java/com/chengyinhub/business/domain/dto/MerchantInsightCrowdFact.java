package com.chengyinhub.business.domain.dto;

import java.util.Date;

/**
 * 商家店铺参谋:到过店(打卡或核销)的去重会员画像行。
 * 只取聚合用维度字段;绝不带 nickname/phone 等明细(PII 白名单闸的一部分)。
 *
 * @author chengyinhub
 */
public class MerchantInsightCrowdFact
{
    private Long memberId;
    /** ums_member.sex: 0未知 1男 2女 */
    private Integer sex;
    private Date birthday;
    /** player_growth.level,可空 */
    private Integer level;
    /** member_interest_profile.category_weights_json,可空 */
    private String categoryWeightsJson;

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Integer getSex() { return sex; }
    public void setSex(Integer sex) { this.sex = sex; }
    public Date getBirthday() { return birthday; }
    public void setBirthday(Date birthday) { this.birthday = birthday; }
    public Integer getLevel() { return level; }
    public void setLevel(Integer level) { this.level = level; }
    public String getCategoryWeightsJson() { return categoryWeightsJson; }
    public void setCategoryWeightsJson(String categoryWeightsJson) { this.categoryWeightsJson = categoryWeightsJson; }
}
