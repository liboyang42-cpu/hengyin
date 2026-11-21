package com.chengyinhub.business.sponsoredquest;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Admin create / edit body for one sponsored quest campaign. The label is not a field: it is fixed in code. */
public class SponsoredQuestCampaign {
    private Long id;
    private String sponsorName;
    private Long sponsorMerchantId;
    private String title;
    private String content;
    private String coverImg;
    private Long budgetCent;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date startTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date endTime;
    private List<Long> placeIds = new ArrayList<>();
    /** W7 MERCHANT-15: why this create / edit is made; required, stored only in the audit row. */
    private String reason;

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSponsorName() { return sponsorName; }
    public void setSponsorName(String sponsorName) { this.sponsorName = sponsorName; }
    public Long getSponsorMerchantId() { return sponsorMerchantId; }
    public void setSponsorMerchantId(Long sponsorMerchantId) { this.sponsorMerchantId = sponsorMerchantId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getCoverImg() { return coverImg; }
    public void setCoverImg(String coverImg) { this.coverImg = coverImg; }
    public Long getBudgetCent() { return budgetCent; }
    public void setBudgetCent(Long budgetCent) { this.budgetCent = budgetCent; }
    public Date getStartTime() { return startTime; }
    public void setStartTime(Date startTime) { this.startTime = startTime; }
    public Date getEndTime() { return endTime; }
    public void setEndTime(Date endTime) { this.endTime = endTime; }
    public List<Long> getPlaceIds() { return placeIds; }
    public void setPlaceIds(List<Long> placeIds) { this.placeIds = placeIds == null ? new ArrayList<>() : placeIds; }
}
