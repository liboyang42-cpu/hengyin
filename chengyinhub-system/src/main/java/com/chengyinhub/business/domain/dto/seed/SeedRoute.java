package com.chengyinhub.business.domain.dto.seed;

import java.math.BigDecimal;

/** Draft official route (cms_topic) written by the route import. */
public class SeedRoute {
    private Long id;
    private Long memberId;
    private String name;
    private String description;
    private String addressName;
    private Integer locationCount;
    private Integer templateCount;
    private BigDecimal totalMileage;
    private String journeyRulesJson;
    private String createBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAddressName() { return addressName; }
    public void setAddressName(String addressName) { this.addressName = addressName; }
    public Integer getLocationCount() { return locationCount; }
    public void setLocationCount(Integer locationCount) { this.locationCount = locationCount; }
    public Integer getTemplateCount() { return templateCount; }
    public void setTemplateCount(Integer templateCount) { this.templateCount = templateCount; }
    public BigDecimal getTotalMileage() { return totalMileage; }
    public void setTotalMileage(BigDecimal totalMileage) { this.totalMileage = totalMileage; }
    public String getJourneyRulesJson() { return journeyRulesJson; }
    public void setJourneyRulesJson(String journeyRulesJson) { this.journeyRulesJson = journeyRulesJson; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }
}
