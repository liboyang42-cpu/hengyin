package com.chengyinhub.business.domain;

/** 分群重算的最小收件人投影；刻意不包含手机号、金额等敏感字段。 */
public class MerchantCrmCampaignCandidate {
    private Long memberId;
    private String name;

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { memberId = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
}
