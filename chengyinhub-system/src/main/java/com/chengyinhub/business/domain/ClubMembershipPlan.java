package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

/** 可售会费计划；付款账单会冻结全部价格、权益与退出政策。 */
public class ClubMembershipPlan extends BaseEntity {
    private static final long serialVersionUID = 1L;
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";
    public static final String POLICY_NON_REFUNDABLE = "NON_REFUNDABLE";
    public static final String POLICY_MANUAL_REVIEW = "MANUAL_REVIEW";
    /** 首版唯一退出政策：退出、封禁或解散后立即停止付费权益；退款另按冻结退款政策处理。 */
    public static final String EXIT_POLICY_END_IMMEDIATELY = "END_IMMEDIATELY";

    private Long id;
    private Long clubId;
    private String name;
    private Integer durationDays;
    private Long priceFen;
    private String benefitsJson;
    private Integer graceDays;
    private Integer pastDueDays;
    private String exitPolicy;
    private String refundPolicy;
    private String status;
    private Integer version;
    private Long createdByMemberId;
    private Integer delFlag;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getDurationDays() { return durationDays; }
    public void setDurationDays(Integer durationDays) { this.durationDays = durationDays; }
    public Long getPriceFen() { return priceFen; }
    public void setPriceFen(Long priceFen) { this.priceFen = priceFen; }
    public String getBenefitsJson() { return benefitsJson; }
    public void setBenefitsJson(String benefitsJson) { this.benefitsJson = benefitsJson; }
    public Integer getGraceDays() { return graceDays; }
    public void setGraceDays(Integer graceDays) { this.graceDays = graceDays; }
    public Integer getPastDueDays() { return pastDueDays; }
    public void setPastDueDays(Integer pastDueDays) { this.pastDueDays = pastDueDays; }
    public String getExitPolicy() { return exitPolicy; }
    public void setExitPolicy(String exitPolicy) { this.exitPolicy = exitPolicy; }
    public String getRefundPolicy() { return refundPolicy; }
    public void setRefundPolicy(String refundPolicy) { this.refundPolicy = refundPolicy; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Long getCreatedByMemberId() { return createdByMemberId; }
    public void setCreatedByMemberId(Long createdByMemberId) { this.createdByMemberId = createdByMemberId; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
}
