package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.util.Date;

/**
 * P3 会员兴趣画像对象 member_interest_profile。
 */
public class MemberInterestProfile extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long memberId;
    private String cityCode;
    private String categoryWeightsJson;
    private String creatorWeightsJson;
    private Date lastEventAt;

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getCityCode() { return cityCode; }
    public void setCityCode(String cityCode) { this.cityCode = cityCode; }
    public String getCategoryWeightsJson() { return categoryWeightsJson; }
    public void setCategoryWeightsJson(String categoryWeightsJson) { this.categoryWeightsJson = categoryWeightsJson; }
    public String getCreatorWeightsJson() { return creatorWeightsJson; }
    public void setCreatorWeightsJson(String creatorWeightsJson) { this.creatorWeightsJson = creatorWeightsJson; }
    public Date getLastEventAt() { return lastEventAt; }
    public void setLastEventAt(Date lastEventAt) { this.lastEventAt = lastEventAt; }
}
