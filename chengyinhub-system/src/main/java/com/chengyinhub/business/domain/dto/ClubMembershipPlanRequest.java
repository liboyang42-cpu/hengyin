package com.chengyinhub.business.domain.dto;

import java.util.List;

public class ClubMembershipPlanRequest {
    private Long id;
    private Long clubId;
    private String name;
    private Integer durationDays;
    private Long priceFen;
    private List<String> benefits;
    private Integer graceDays;
    private Integer pastDueDays;
    private String exitPolicy;
    private String refundPolicy;
    private String status;
    private Integer version;
    private String requestId;
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
    public List<String> getBenefits() { return benefits; }
    public void setBenefits(List<String> benefits) { this.benefits = benefits; }
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
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
