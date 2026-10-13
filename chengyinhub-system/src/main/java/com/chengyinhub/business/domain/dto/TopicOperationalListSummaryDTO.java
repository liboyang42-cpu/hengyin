package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * 主题列表的批量运营摘要。
 *
 * <p>它只承载列表决策所需的公开状态和聚合数字；不复用完整工作台对象，避免列表页泄露
 * 收款、核销码、成员或原始单据字段。
 */
public class TopicOperationalListSummaryDTO {

    private Long topicId;
    private String contentPublisherName;
    private String contentPublisherLeaderName;
    private int pendingClubApplicationCount;
    private int pendingClubInviteCount;
    private int signedClubCooperationCount;
    private int activeLeadSessionCount;
    private int candidateCount;
    private int pendingCandidateCount;
    private int approvedCandidateCount;
    private int awardedCandidateCount;
    private int merchantCooperationCount;
    private int activeOfferCount;
    private int chapterCount;
    private int chapterWithoutNodeCount;
    private int requiredChapterCount;
    private int suppliedRequiredChapterCount;
    private int missingRequiredChapterCount;
    /** oms_ticket 原始票种数；ticketCount 会在服务层加上有效自玩通行证。 */
    private int standardTicketCount;
    private int ticketCount;
    private int guidedTicketCount;
    private boolean selfPlayPassConfigured;
    private boolean selfPlayPassConfigurationInvalid;
    private BigDecimal selfPlayPassPrice;
    private Integer selfPlayPassQuota;
    private int selfPlayPassOccupiedCount;
    private Integer selfPlayPassRemainingInventory;
    private BigDecimal minTicketPrice;
    private int totalTicketInventory;
    private int remainingTicketInventory;
    private int paidRegistrationCount;
    private int verifiedHeadCount;
    private BigDecimal totalSales = BigDecimal.ZERO;
    private BigDecimal verifiedSales = BigDecimal.ZERO;
    private int pendingSettlementCount;
    private int openComplaintCount;
    private ClubFulfillmentState clubFulfillmentState = ClubFulfillmentState.UNCONFIRMED;
    private MerchantSupplyState merchantSupplyState = MerchantSupplyState.NOT_REQUIRED;
    private TopicWorkspaceDTO.RunReadinessState runReadiness = TopicWorkspaceDTO.RunReadinessState.AT_RISK;
    private List<TopicWorkspaceDTO.RunBlocker> runBlockers = Collections.emptyList();

    public enum ClubFulfillmentState {
        NOT_REQUIRED,
        APPLYING,
        INVITED,
        SIGNED,
        LEADING,
        UNCONFIRMED
    }

    public enum MerchantSupplyState {
        NOT_REQUIRED,
        RECRUITING,
        PENDING_ALLOCATION,
        GAP,
        PARTIALLY_READY,
        READY
    }

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public String getContentPublisherName() { return contentPublisherName; }
    public void setContentPublisherName(String contentPublisherName) { this.contentPublisherName = contentPublisherName; }
    public String getContentPublisherLeaderName() { return contentPublisherLeaderName; }
    public void setContentPublisherLeaderName(String contentPublisherLeaderName) { this.contentPublisherLeaderName = contentPublisherLeaderName; }
    public int getPendingClubApplicationCount() { return pendingClubApplicationCount; }
    public void setPendingClubApplicationCount(int pendingClubApplicationCount) { this.pendingClubApplicationCount = pendingClubApplicationCount; }
    public int getPendingClubInviteCount() { return pendingClubInviteCount; }
    public void setPendingClubInviteCount(int pendingClubInviteCount) { this.pendingClubInviteCount = pendingClubInviteCount; }
    public int getSignedClubCooperationCount() { return signedClubCooperationCount; }
    public void setSignedClubCooperationCount(int signedClubCooperationCount) { this.signedClubCooperationCount = signedClubCooperationCount; }
    public int getActiveLeadSessionCount() { return activeLeadSessionCount; }
    public void setActiveLeadSessionCount(int activeLeadSessionCount) { this.activeLeadSessionCount = activeLeadSessionCount; }
    public int getCandidateCount() { return candidateCount; }
    public void setCandidateCount(int candidateCount) { this.candidateCount = candidateCount; }
    public int getPendingCandidateCount() { return pendingCandidateCount; }
    public void setPendingCandidateCount(int pendingCandidateCount) { this.pendingCandidateCount = pendingCandidateCount; }
    public int getApprovedCandidateCount() { return approvedCandidateCount; }
    public void setApprovedCandidateCount(int approvedCandidateCount) { this.approvedCandidateCount = approvedCandidateCount; }
    public int getAwardedCandidateCount() { return awardedCandidateCount; }
    public void setAwardedCandidateCount(int awardedCandidateCount) { this.awardedCandidateCount = awardedCandidateCount; }
    public int getMerchantCooperationCount() { return merchantCooperationCount; }
    public void setMerchantCooperationCount(int merchantCooperationCount) { this.merchantCooperationCount = merchantCooperationCount; }
    public int getActiveOfferCount() { return activeOfferCount; }
    public void setActiveOfferCount(int activeOfferCount) { this.activeOfferCount = activeOfferCount; }
    public int getChapterCount() { return chapterCount; }
    public void setChapterCount(int chapterCount) { this.chapterCount = chapterCount; }
    public int getChapterWithoutNodeCount() { return chapterWithoutNodeCount; }
    public void setChapterWithoutNodeCount(int chapterWithoutNodeCount) { this.chapterWithoutNodeCount = chapterWithoutNodeCount; }
    public int getRequiredChapterCount() { return requiredChapterCount; }
    public void setRequiredChapterCount(int requiredChapterCount) { this.requiredChapterCount = requiredChapterCount; }
    public int getSuppliedRequiredChapterCount() { return suppliedRequiredChapterCount; }
    public void setSuppliedRequiredChapterCount(int suppliedRequiredChapterCount) { this.suppliedRequiredChapterCount = suppliedRequiredChapterCount; }
    public int getMissingRequiredChapterCount() { return missingRequiredChapterCount; }
    public void setMissingRequiredChapterCount(int missingRequiredChapterCount) { this.missingRequiredChapterCount = missingRequiredChapterCount; }
    public int getStandardTicketCount() { return standardTicketCount; }
    public void setStandardTicketCount(int standardTicketCount) { this.standardTicketCount = standardTicketCount; }
    public int getTicketCount() { return ticketCount; }
    public void setTicketCount(int ticketCount) { this.ticketCount = ticketCount; }
    public int getGuidedTicketCount() { return guidedTicketCount; }
    public void setGuidedTicketCount(int guidedTicketCount) { this.guidedTicketCount = guidedTicketCount; }
    public boolean isSelfPlayPassConfigured() { return selfPlayPassConfigured; }
    public void setSelfPlayPassConfigured(boolean selfPlayPassConfigured) { this.selfPlayPassConfigured = selfPlayPassConfigured; }
    public boolean isSelfPlayPassConfigurationInvalid() { return selfPlayPassConfigurationInvalid; }
    public void setSelfPlayPassConfigurationInvalid(boolean selfPlayPassConfigurationInvalid) { this.selfPlayPassConfigurationInvalid = selfPlayPassConfigurationInvalid; }
    public BigDecimal getSelfPlayPassPrice() { return selfPlayPassPrice; }
    public void setSelfPlayPassPrice(BigDecimal selfPlayPassPrice) { this.selfPlayPassPrice = selfPlayPassPrice; }
    public Integer getSelfPlayPassQuota() { return selfPlayPassQuota; }
    public void setSelfPlayPassQuota(Integer selfPlayPassQuota) { this.selfPlayPassQuota = selfPlayPassQuota; }
    public int getSelfPlayPassOccupiedCount() { return selfPlayPassOccupiedCount; }
    public void setSelfPlayPassOccupiedCount(int selfPlayPassOccupiedCount) { this.selfPlayPassOccupiedCount = selfPlayPassOccupiedCount; }
    public Integer getSelfPlayPassRemainingInventory() { return selfPlayPassRemainingInventory; }
    public void setSelfPlayPassRemainingInventory(Integer selfPlayPassRemainingInventory) { this.selfPlayPassRemainingInventory = selfPlayPassRemainingInventory; }
    public BigDecimal getMinTicketPrice() { return minTicketPrice; }
    public void setMinTicketPrice(BigDecimal minTicketPrice) { this.minTicketPrice = minTicketPrice; }
    public int getTotalTicketInventory() { return totalTicketInventory; }
    public void setTotalTicketInventory(int totalTicketInventory) { this.totalTicketInventory = totalTicketInventory; }
    public int getRemainingTicketInventory() { return remainingTicketInventory; }
    public void setRemainingTicketInventory(int remainingTicketInventory) { this.remainingTicketInventory = remainingTicketInventory; }
    public int getPaidRegistrationCount() { return paidRegistrationCount; }
    public void setPaidRegistrationCount(int paidRegistrationCount) { this.paidRegistrationCount = paidRegistrationCount; }
    public int getVerifiedHeadCount() { return verifiedHeadCount; }
    public void setVerifiedHeadCount(int verifiedHeadCount) { this.verifiedHeadCount = verifiedHeadCount; }
    public BigDecimal getTotalSales() { return totalSales; }
    public void setTotalSales(BigDecimal totalSales) { this.totalSales = totalSales == null ? BigDecimal.ZERO : totalSales; }
    public BigDecimal getVerifiedSales() { return verifiedSales; }
    public void setVerifiedSales(BigDecimal verifiedSales) { this.verifiedSales = verifiedSales == null ? BigDecimal.ZERO : verifiedSales; }
    public int getPendingSettlementCount() { return pendingSettlementCount; }
    public void setPendingSettlementCount(int pendingSettlementCount) { this.pendingSettlementCount = pendingSettlementCount; }
    public int getOpenComplaintCount() { return openComplaintCount; }
    public void setOpenComplaintCount(int openComplaintCount) { this.openComplaintCount = openComplaintCount; }
    public ClubFulfillmentState getClubFulfillmentState() { return clubFulfillmentState; }
    public void setClubFulfillmentState(ClubFulfillmentState clubFulfillmentState) { this.clubFulfillmentState = clubFulfillmentState; }
    public MerchantSupplyState getMerchantSupplyState() { return merchantSupplyState; }
    public void setMerchantSupplyState(MerchantSupplyState merchantSupplyState) { this.merchantSupplyState = merchantSupplyState; }
    public TopicWorkspaceDTO.RunReadinessState getRunReadiness() { return runReadiness; }
    public void setRunReadiness(TopicWorkspaceDTO.RunReadinessState runReadiness) { this.runReadiness = runReadiness; }
    public List<TopicWorkspaceDTO.RunBlocker> getRunBlockers() { return runBlockers; }
    public void setRunBlockers(List<TopicWorkspaceDTO.RunBlocker> runBlockers) {
        this.runBlockers = runBlockers == null ? Collections.<TopicWorkspaceDTO.RunBlocker>emptyList() : runBlockers;
    }
}
