package com.chengyinhub.business.domain.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 主题运营工作台只读快照。
 *
 * <p>所有工作台轴均经过白名单投影，禁止把收款主体、主体内部标识、核销码、坐标、
 * 邮箱、验证答案、权益快照或原始报名快照等事实透给普通运营查询。</p>
 */
public class TopicWorkspaceDTO {

    private Topic topic;
    private Publisher publisher = new Publisher();
    private OfficialActivityRelation officialActivityRelation = new OfficialActivityRelation();
    private Operation operation = new Operation();
    private Governance governance = new Governance();
    private MerchantSupply merchantSupply = new MerchantSupply();
    private List<Chapter> chapters = Collections.emptyList();
    private List<Ticket> tickets = Collections.emptyList();
    private List<Candidate> candidates = Collections.emptyList();
    private List<Cooperation> cooperations = Collections.emptyList();
    private List<Settlement> settlements = Collections.emptyList();
    private Fulfillment fulfillment = new Fulfillment();
    private ClubFulfillment clubFulfillment = new ClubFulfillment();
    private Capabilities capabilities = new Capabilities();

    public Topic getTopic() { return topic; }
    public void setTopic(Topic topic) { this.topic = topic; }
    public Publisher getPublisher() { return publisher; }
    public void setPublisher(Publisher publisher) { this.publisher = publisher; }
    public OfficialActivityRelation getOfficialActivityRelation() { return officialActivityRelation; }
    public void setOfficialActivityRelation(OfficialActivityRelation officialActivityRelation) { this.officialActivityRelation = officialActivityRelation; }
    public Operation getOperation() { return operation; }
    public void setOperation(Operation operation) { this.operation = operation; }
    public Governance getGovernance() { return governance; }
    public void setGovernance(Governance governance) { this.governance = governance; }
    public MerchantSupply getMerchantSupply() { return merchantSupply; }
    public void setMerchantSupply(MerchantSupply merchantSupply) { this.merchantSupply = merchantSupply; }
    public List<Chapter> getChapters() { return chapters; }
    public void setChapters(List<Chapter> chapters) { this.chapters = chapters; }
    public List<Ticket> getTickets() { return tickets; }
    public void setTickets(List<Ticket> tickets) { this.tickets = tickets; }
    public List<Candidate> getCandidates() { return candidates; }
    public void setCandidates(List<Candidate> candidates) { this.candidates = candidates; }
    public List<Cooperation> getCooperations() { return cooperations; }
    public void setCooperations(List<Cooperation> cooperations) { this.cooperations = cooperations; }
    public List<Settlement> getSettlements() { return settlements; }
    public void setSettlements(List<Settlement> settlements) { this.settlements = settlements; }
    public Fulfillment getFulfillment() { return fulfillment; }
    public void setFulfillment(Fulfillment fulfillment) { this.fulfillment = fulfillment; }
    public ClubFulfillment getClubFulfillment() { return clubFulfillment; }
    public void setClubFulfillment(ClubFulfillment clubFulfillment) { this.clubFulfillment = clubFulfillment; }
    public Capabilities getCapabilities() { return capabilities; }
    public void setCapabilities(Capabilities capabilities) { this.capabilities = capabilities; }

    /** 工作台主题身份白名单；不透传收款账号、审核内部字段或完整主题实体。 */
    public static class Topic {
        private Long id;
        private String name;
        private String addressName;
        private String imgUrl;
        private Integer isSettlement;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getAddressName() { return addressName; }
        public void setAddressName(String addressName) { this.addressName = addressName; }
        public String getImgUrl() { return imgUrl; }
        public void setImgUrl(String imgUrl) { this.imgUrl = imgUrl; }
        public Integer getIsSettlement() { return isSettlement; }
        public void setIsSettlement(Integer isSettlement) { this.isSettlement = isSettlement; }
    }

    /** 内容结构白名单；节点坐标、核销码和原始模板关联不进入运营工作台。 */
    public static class Chapter {
        private Long id;
        private String name;
        private String description;
        private List<Node> nodes = Collections.emptyList();

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public List<Node> getNodes() { return nodes; }
        public void setNodes(List<Node> nodes) { this.nodes = nodes; }
    }

    public static class Node {
        private Long id;
        private String name;
        private String address;
        private Integer nodeTime;
        private Long status;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public Integer getNodeTime() { return nodeTime; }
        public void setNodeTime(Integer nodeTime) { this.nodeTime = nodeTime; }
        public Long getStatus() { return status; }
        public void setStatus(Long status) { this.status = status; }
    }

    /** 票务售卖白名单；不透传票归属、集合坐标、退款规则和报名明细。 */
    public static class Ticket {
        /** STANDARD_TICKET=oms_ticket，SELF_PLAY_PASS=主题级自玩通行证。 */
        private String kind;
        private Long id;
        private String name;
        private BigDecimal price;
        private Date startTime;
        private Date endTime;
        /** null 明确表示不限量，不能序列化成零库存。 */
        private Long totalInventory;
        private Long remainingInventory;

        public String getKind() { return kind; }
        public void setKind(String kind) { this.kind = kind; }
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public BigDecimal getPrice() { return price; }
        public void setPrice(BigDecimal price) { this.price = price; }
        public Date getStartTime() { return startTime; }
        public void setStartTime(Date startTime) { this.startTime = startTime; }
        public Date getEndTime() { return endTime; }
        public void setEndTime(Date endTime) { this.endTime = endTime; }
        public Long getTotalInventory() { return totalInventory; }
        public void setTotalInventory(Long totalInventory) { this.totalInventory = totalInventory; }
        public Long getRemainingInventory() { return remainingInventory; }
        public void setRemainingInventory(Long remainingInventory) { this.remainingInventory = remainingInventory; }
    }

    /** 内容发布主体；无 CONTENT 槽的存量主题必须显式落为历史待归属。 */
    public static class Publisher {
        private String entityType;
        private String displayName;
        private boolean historicalPending = true;

        public String getEntityType() { return entityType; }
        public void setEntityType(String entityType) { this.entityType = entityType; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public boolean isHistoricalPending() { return historicalPending; }
        public void setHistoricalPending(boolean historicalPending) { this.historicalPending = historicalPending; }
    }


    /**
     * 官方活动关系尚未有可验证的正式模型时必须显式标为未关联，不能把执行场次或主题本身冒充官方活动。
     */
    public static class OfficialActivityRelation {
        private OfficialActivityRelationState state = OfficialActivityRelationState.UNLINKED;
        private String message = "未关联官方活动";

        public OfficialActivityRelationState getState() { return state; }
        public void setState(OfficialActivityRelationState state) { this.state = state; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    /** 可开跑判断只表达运营结论；详情由 runBlockers 给出。 */
    public enum RunReadinessState {
        READY,
        AT_RISK,
        BLOCKED
    }


    /**
     * 俱乐部承接并非二元开关：仅在存在需带队票种且没有任何合同/场次承接时才是不确定。
     */
    public enum ClubFulfillmentState {
        NOT_REQUIRED,
        APPLYING,
        INVITED,
        SIGNED,
        LEADING,
        UNCONFIRMED
    }


    /** 官方活动没有可验证关系时的显式状态。 */
    public enum OfficialActivityRelationState {
        UNLINKED
    }

    /** 可开跑判断的可审计阻塞原因，不能由前端用零值反推。 */
    public enum RunBlocker {
        LIFECYCLE_NOT_READY,
        LIFECYCLE_TERMINAL,
        CONTENT_STRUCTURE_MISSING,
        TICKET_CONFIGURATION_MISSING,
        SELF_PLAY_PASS_CONFIGURATION_MISSING,
        REQUIRED_CHAPTER_SUPPLY_MISSING,
        CLUB_INVITE_PENDING,
        CLUB_FULFILLMENT_UNCONFIRMED
    }

    /** 页面可提示的下一步，不等同于绕过服务端门禁的写命令。 */
    public enum NextAction {
        EDIT_CONTENT,
        HANDLE_RECRUITMENT,
        RESOLVE_SUPPLY_GAPS,
        LOCK_LINEUP,
        MONITOR_FULFILLMENT,
        SETTLE_AND_REVIEW,
        VIEW_REVIEW
    }

    /** 普通内容编辑受生命周期控制；权限仍由服务端写接口强制校验。 */
    public enum EditPolicy {
        FULL,
        CONTENT_CORRECTION_ONLY,
        LOCKED
    }

    /** 主题玩法、阶段、关键日期和可开跑结论的白名单投影。 */
    public static class Operation {
        private Integer productType;
        private Integer lifecycle;
        private Integer merchantPoolStatus;
        private Integer clubPoolStatus;
        private Date merchantSignUpStartDate;
        private Date merchantSignUpEndDate;
        private Date recruitDeadline;
        private Date startDate;
        private Date endDate;
        private String lastEditedBy;
        private Date lastEditedAt;
        private RunReadinessState runReadiness = RunReadinessState.AT_RISK;
        private List<RunBlocker> runBlockers = Collections.emptyList();
        private NextAction nextAction;
        private EditPolicy editPolicy = EditPolicy.LOCKED;
        private Responsibility responsibility = new Responsibility();

        public Integer getProductType() { return productType; }
        public void setProductType(Integer productType) { this.productType = productType; }
        public Integer getLifecycle() { return lifecycle; }
        public void setLifecycle(Integer lifecycle) { this.lifecycle = lifecycle; }
        public Integer getMerchantPoolStatus() { return merchantPoolStatus; }
        public void setMerchantPoolStatus(Integer merchantPoolStatus) { this.merchantPoolStatus = merchantPoolStatus; }
        public Integer getClubPoolStatus() { return clubPoolStatus; }
        public void setClubPoolStatus(Integer clubPoolStatus) { this.clubPoolStatus = clubPoolStatus; }
        public Date getMerchantSignUpStartDate() { return merchantSignUpStartDate; }
        public void setMerchantSignUpStartDate(Date merchantSignUpStartDate) { this.merchantSignUpStartDate = merchantSignUpStartDate; }
        public Date getMerchantSignUpEndDate() { return merchantSignUpEndDate; }
        public void setMerchantSignUpEndDate(Date merchantSignUpEndDate) { this.merchantSignUpEndDate = merchantSignUpEndDate; }
        public Date getRecruitDeadline() { return recruitDeadline; }
        public void setRecruitDeadline(Date recruitDeadline) { this.recruitDeadline = recruitDeadline; }
        public Date getStartDate() { return startDate; }
        public void setStartDate(Date startDate) { this.startDate = startDate; }
        public Date getEndDate() { return endDate; }
        public void setEndDate(Date endDate) { this.endDate = endDate; }
        public String getLastEditedBy() { return lastEditedBy; }
        public void setLastEditedBy(String lastEditedBy) { this.lastEditedBy = lastEditedBy; }
        public Date getLastEditedAt() { return lastEditedAt; }
        public void setLastEditedAt(Date lastEditedAt) { this.lastEditedAt = lastEditedAt; }
        public RunReadinessState getRunReadiness() { return runReadiness; }
        public void setRunReadiness(RunReadinessState runReadiness) { this.runReadiness = runReadiness; }
        public List<RunBlocker> getRunBlockers() { return runBlockers; }
        public void setRunBlockers(List<RunBlocker> runBlockers) { this.runBlockers = runBlockers; }
        public NextAction getNextAction() { return nextAction; }
        public void setNextAction(NextAction nextAction) { this.nextAction = nextAction; }
        public EditPolicy getEditPolicy() { return editPolicy; }
        public void setEditPolicy(EditPolicy editPolicy) { this.editPolicy = editPolicy; }
        public Responsibility getResponsibility() { return responsibility; }
        public void setResponsibility(Responsibility responsibility) { this.responsibility = responsibility; }
    }

    /** 主办与实际履约的明确口径；不把收款主体或公开池开关伪装成责任归属。 */
    public static class Responsibility {
        private String organizerLabel;
        private String organizerState;
        private List<String> actualFulfillers = Collections.emptyList();

        public String getOrganizerLabel() { return organizerLabel; }
        public void setOrganizerLabel(String organizerLabel) { this.organizerLabel = organizerLabel; }
        public String getOrganizerState() { return organizerState; }
        public void setOrganizerState(String organizerState) { this.organizerState = organizerState; }
        public List<String> getActualFulfillers() { return actualFulfillers; }
        public void setActualFulfillers(List<String> actualFulfillers) { this.actualFulfillers = actualFulfillers; }
    }

    /** 审核与用户端可见性状态的白名单投影。 */
    public static class Governance {
        private Integer checkStatus;
        private Integer userStatus;
        private String reason;

        public Integer getCheckStatus() { return checkStatus; }
        public void setCheckStatus(Integer checkStatus) { this.checkStatus = checkStatus; }
        public Integer getUserStatus() { return userStatus; }
        public void setUserStatus(Integer userStatus) { this.userStatus = userStatus; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    /**
     * 商家候选与章节供给的分层投影。报名、调配、合同与生效供给不得混为一个计数。
     */
    public static class MerchantSupply {
        private int candidateCount;
        private int pendingReviewCount;
        private int approvedCount;
        private int awardedCount;
        private int activeOfferCount;
        private int inactiveOfferCount;
        private int requiredChapterCount;
        private int suppliedRequiredChapterCount;
        private int missingRequiredChapterCount;
        private List<ChapterSupply> chapters = Collections.emptyList();

        public int getCandidateCount() { return candidateCount; }
        public void setCandidateCount(int candidateCount) { this.candidateCount = candidateCount; }
        public int getPendingReviewCount() { return pendingReviewCount; }
        public void setPendingReviewCount(int pendingReviewCount) { this.pendingReviewCount = pendingReviewCount; }
        public int getApprovedCount() { return approvedCount; }
        public void setApprovedCount(int approvedCount) { this.approvedCount = approvedCount; }
        public int getAwardedCount() { return awardedCount; }
        public void setAwardedCount(int awardedCount) { this.awardedCount = awardedCount; }
        public int getActiveOfferCount() { return activeOfferCount; }
        public void setActiveOfferCount(int activeOfferCount) { this.activeOfferCount = activeOfferCount; }
        public int getInactiveOfferCount() { return inactiveOfferCount; }
        public void setInactiveOfferCount(int inactiveOfferCount) { this.inactiveOfferCount = inactiveOfferCount; }
        public int getRequiredChapterCount() { return requiredChapterCount; }
        public void setRequiredChapterCount(int requiredChapterCount) { this.requiredChapterCount = requiredChapterCount; }
        public int getSuppliedRequiredChapterCount() { return suppliedRequiredChapterCount; }
        public void setSuppliedRequiredChapterCount(int suppliedRequiredChapterCount) { this.suppliedRequiredChapterCount = suppliedRequiredChapterCount; }
        public int getMissingRequiredChapterCount() { return missingRequiredChapterCount; }
        public void setMissingRequiredChapterCount(int missingRequiredChapterCount) { this.missingRequiredChapterCount = missingRequiredChapterCount; }
        public List<ChapterSupply> getChapters() { return chapters; }
        public void setChapters(List<ChapterSupply> chapters) { this.chapters = chapters; }

        /** 单章节供给缺口；target 未定义时缺口保持 null，不能伪造为零。 */
        public static class ChapterSupply {
            private Long chapterId;
            private String chapterName;
            private boolean required;
            private Integer targetMerchantCount;
            private int candidateCount;
            private int activeOfferCount;
            private int inactiveOfferCount;
            private Integer missingMerchantCount;
            private List<Offer> offers = Collections.emptyList();

            public Long getChapterId() { return chapterId; }
            public void setChapterId(Long chapterId) { this.chapterId = chapterId; }
            public String getChapterName() { return chapterName; }
            public void setChapterName(String chapterName) { this.chapterName = chapterName; }
            public boolean isRequired() { return required; }
            public void setRequired(boolean required) { this.required = required; }
            public Integer getTargetMerchantCount() { return targetMerchantCount; }
            public void setTargetMerchantCount(Integer targetMerchantCount) { this.targetMerchantCount = targetMerchantCount; }
            public int getCandidateCount() { return candidateCount; }
            public void setCandidateCount(int candidateCount) { this.candidateCount = candidateCount; }
            public int getActiveOfferCount() { return activeOfferCount; }
            public void setActiveOfferCount(int activeOfferCount) { this.activeOfferCount = activeOfferCount; }
            public int getInactiveOfferCount() { return inactiveOfferCount; }
            public void setInactiveOfferCount(int inactiveOfferCount) { this.inactiveOfferCount = inactiveOfferCount; }
            public Integer getMissingMerchantCount() { return missingMerchantCount; }
            public void setMissingMerchantCount(Integer missingMerchantCount) { this.missingMerchantCount = missingMerchantCount; }
            public List<Offer> getOffers() { return offers; }
            public void setOffers(List<Offer> offers) { this.offers = offers; }
        }

        /** 已读到的章节 offer；不暴露商家联系方式、证照或收益快照。 */
        public static class Offer {
            private Long id;
            private Long merchantId;
            private String merchantName;
            private Long cooperationId;
            private int verifiedGroupCount;
            private Integer status;
            private boolean active;
            private Integer quotaTotal;
            private Integer quotaUsed;
            private Date validEnd;
            private String termsMode;

            public Long getId() { return id; }
            public void setId(Long id) { this.id = id; }
            public Long getMerchantId() { return merchantId; }
            public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
            public String getMerchantName() { return merchantName; }
            public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
            public Long getCooperationId() { return cooperationId; }
            public void setCooperationId(Long cooperationId) { this.cooperationId = cooperationId; }
            public int getVerifiedGroupCount() { return verifiedGroupCount; }
            public void setVerifiedGroupCount(int verifiedGroupCount) { this.verifiedGroupCount = verifiedGroupCount; }
            public Integer getStatus() { return status; }
            public void setStatus(Integer status) { this.status = status; }
            public boolean isActive() { return active; }
            public void setActive(boolean active) { this.active = active; }
            public Integer getQuotaTotal() { return quotaTotal; }
            public void setQuotaTotal(Integer quotaTotal) { this.quotaTotal = quotaTotal; }
            public Integer getQuotaUsed() { return quotaUsed; }
            public void setQuotaUsed(Integer quotaUsed) { this.quotaUsed = quotaUsed; }
            public Date getValidEnd() { return validEnd; }
            public void setValidEnd(Date validEnd) { this.validEnd = validEnd; }
            public String getTermsMode() { return termsMode; }
            public void setTermsMode(String termsMode) { this.termsMode = termsMode; }
        }
    }


    /**
     * 俱乐部承接的连续状态：申请是意向、邀约是待确认、合作单才是合同、带队场次才是履约。
     *
     * <p>场次仅投影运营需要的集合事实，不携带报名者或到场者名册。</p>
     */
    public static class ClubFulfillment {
        private int pendingApplicationCount;
        private int pendingInviteCount;
        private int signedCooperationCount;
        private int activeSessionCount;
        private int attendanceCount;
        private ClubFulfillmentState state = ClubFulfillmentState.UNCONFIRMED;
        private DepositSummary depositSummary = new DepositSummary();
        private List<ClubRelationship> relationships = Collections.emptyList();
        private List<Session> sessions = Collections.emptyList();

        public int getPendingApplicationCount() { return pendingApplicationCount; }
        public void setPendingApplicationCount(int pendingApplicationCount) { this.pendingApplicationCount = pendingApplicationCount; }
        public int getPendingInviteCount() { return pendingInviteCount; }
        public void setPendingInviteCount(int pendingInviteCount) { this.pendingInviteCount = pendingInviteCount; }
        public int getSignedCooperationCount() { return signedCooperationCount; }
        public void setSignedCooperationCount(int signedCooperationCount) { this.signedCooperationCount = signedCooperationCount; }
        public int getActiveSessionCount() { return activeSessionCount; }
        public void setActiveSessionCount(int activeSessionCount) { this.activeSessionCount = activeSessionCount; }
        public int getAttendanceCount() { return attendanceCount; }
        public void setAttendanceCount(int attendanceCount) { this.attendanceCount = attendanceCount; }
        public ClubFulfillmentState getState() { return state; }
        public void setState(ClubFulfillmentState state) { this.state = state; }
        public DepositSummary getDepositSummary() { return depositSummary; }
        public void setDepositSummary(DepositSummary depositSummary) { this.depositSummary = depositSummary; }
        public List<ClubRelationship> getRelationships() { return relationships; }
        public void setRelationships(List<ClubRelationship> relationships) { this.relationships = relationships; }
        public List<Session> getSessions() { return sessions; }
        public void setSessions(List<Session> sessions) { this.sessions = sessions; }

        /** 已接受俱乐部合作的保证金状态汇总；0 仅在锁价前表示待锁价。 */
        public static class DepositSummary {
            private int awaitingPricingCount;
            private int payingCount;
            private int frozenCount;
            private int deductedCount;
            private int refundedCount;
            private int payableCount;
            private int unknownCount;

            public int getAwaitingPricingCount() { return awaitingPricingCount; }
            public void setAwaitingPricingCount(int awaitingPricingCount) { this.awaitingPricingCount = awaitingPricingCount; }
            public int getPayingCount() { return payingCount; }
            public void setPayingCount(int payingCount) { this.payingCount = payingCount; }
            public int getFrozenCount() { return frozenCount; }
            public void setFrozenCount(int frozenCount) { this.frozenCount = frozenCount; }
            public int getDeductedCount() { return deductedCount; }
            public void setDeductedCount(int deductedCount) { this.deductedCount = deductedCount; }
            public int getRefundedCount() { return refundedCount; }
            public void setRefundedCount(int refundedCount) { this.refundedCount = refundedCount; }
            public int getPayableCount() { return payableCount; }
            public void setPayableCount(int payableCount) { this.payableCount = payableCount; }
            public int getUnknownCount() { return unknownCount; }
            public void setUnknownCount(int unknownCount) { this.unknownCount = unknownCount; }
        }

        /** 一张关系卡可同时呈现意向、邀约、合同、保证金与履约，不跨层替代。 */
        public static class ClubRelationship {
            private Long clubId;
            private String clubName;
            private String leaderName;
            private String capabilityTags;
            private Integer applicationStatus;
            private String applicationMessage;
            private Date applicationTime;
            private Long inviteId;
            private Integer inviteStatus;
            private String inviteMessage;
            private Date inviteTime;
            private Long cooperationId;
            private Date cooperationTime;
            private Integer depositStatus;
            private BigDecimal depositAmount;
            private int sessionCount;

            public Long getClubId() { return clubId; }
            public void setClubId(Long clubId) { this.clubId = clubId; }
            public String getClubName() { return clubName; }
            public void setClubName(String clubName) { this.clubName = clubName; }
            public String getLeaderName() { return leaderName; }
            public void setLeaderName(String leaderName) { this.leaderName = leaderName; }
            public String getCapabilityTags() { return capabilityTags; }
            public void setCapabilityTags(String capabilityTags) { this.capabilityTags = capabilityTags; }
            public Integer getApplicationStatus() { return applicationStatus; }
            public void setApplicationStatus(Integer applicationStatus) { this.applicationStatus = applicationStatus; }
            public String getApplicationMessage() { return applicationMessage; }
            public void setApplicationMessage(String applicationMessage) { this.applicationMessage = applicationMessage; }
            public Date getApplicationTime() { return applicationTime; }
            public void setApplicationTime(Date applicationTime) { this.applicationTime = applicationTime; }
            public Long getInviteId() { return inviteId; }
            public void setInviteId(Long inviteId) { this.inviteId = inviteId; }
            public Integer getInviteStatus() { return inviteStatus; }
            public void setInviteStatus(Integer inviteStatus) { this.inviteStatus = inviteStatus; }
            public String getInviteMessage() { return inviteMessage; }
            public void setInviteMessage(String inviteMessage) { this.inviteMessage = inviteMessage; }
            public Date getInviteTime() { return inviteTime; }
            public void setInviteTime(Date inviteTime) { this.inviteTime = inviteTime; }
            public Long getCooperationId() { return cooperationId; }
            public void setCooperationId(Long cooperationId) { this.cooperationId = cooperationId; }
            public Date getCooperationTime() { return cooperationTime; }
            public void setCooperationTime(Date cooperationTime) { this.cooperationTime = cooperationTime; }
            public Integer getDepositStatus() { return depositStatus; }
            public void setDepositStatus(Integer depositStatus) { this.depositStatus = depositStatus; }
            public BigDecimal getDepositAmount() { return depositAmount; }
            public void setDepositAmount(BigDecimal depositAmount) { this.depositAmount = depositAmount; }
            public int getSessionCount() { return sessionCount; }
            public void setSessionCount(int sessionCount) { this.sessionCount = sessionCount; }
        }

        /** 带队场次的最小运营投影；不返回领队成员ID或到场人员明细。 */
        public static class Session {
            private Long activityId;
            private Long clubId;
            private String activityName;
            private Date assemblyTime;
            private String assemblyLocation;
            private String leaderDisplayName;
            private Long currentChapterId;
            private Integer status;
            private String broadcastText;
            private Date broadcastAt;

            public Long getActivityId() { return activityId; }
            public void setActivityId(Long activityId) { this.activityId = activityId; }
            public Long getClubId() { return clubId; }
            public void setClubId(Long clubId) { this.clubId = clubId; }
            public String getActivityName() { return activityName; }
            public void setActivityName(String activityName) { this.activityName = activityName; }
            public Date getAssemblyTime() { return assemblyTime; }
            public void setAssemblyTime(Date assemblyTime) { this.assemblyTime = assemblyTime; }
            public String getAssemblyLocation() { return assemblyLocation; }
            public void setAssemblyLocation(String assemblyLocation) { this.assemblyLocation = assemblyLocation; }
            public String getLeaderDisplayName() { return leaderDisplayName; }
            public void setLeaderDisplayName(String leaderDisplayName) { this.leaderDisplayName = leaderDisplayName; }
            public Long getCurrentChapterId() { return currentChapterId; }
            public void setCurrentChapterId(Long currentChapterId) { this.currentChapterId = currentChapterId; }
            public Integer getStatus() { return status; }
            public void setStatus(Integer status) { this.status = status; }
            public String getBroadcastText() { return broadcastText; }
            public void setBroadcastText(String broadcastText) { this.broadcastText = broadcastText; }
            public Date getBroadcastAt() { return broadcastAt; }
            public void setBroadcastAt(Date broadcastAt) { this.broadcastAt = broadcastAt; }
        }

        /** 到场仅用于聚合计数，不能成为工作台响应里的成员名册。 */
        public static class AttendanceMarker {
            private Long activityId;

            public Long getActivityId() { return activityId; }
            public void setActivityId(Long activityId) { this.activityId = activityId; }
        }
    }

    public static class Candidate {
        private Long id;
        private String merchantName;
        private Long nodeId;
        private String addressName;
        private Long status;
        private Long auditStatus;
        private Date createTime;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getMerchantName() { return merchantName; }
        public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
        public Long getNodeId() { return nodeId; }
        public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
        public String getAddressName() { return addressName; }
        public void setAddressName(String addressName) { this.addressName = addressName; }
        public Long getStatus() { return status; }
        public void setStatus(Long status) { this.status = status; }
        public Long getAuditStatus() { return auditStatus; }
        public void setAuditStatus(Long auditStatus) { this.auditStatus = auditStatus; }
        public Date getCreateTime() { return createTime; }
        public void setCreateTime(Date createTime) { this.createTime = createTime; }
    }

    public static class Cooperation {
        private Long id;
        private Long registrationId;
        private Long counterpartyId;
        private String counterpartyType;
        private Integer shareMode;
        private BigDecimal shareRate;
        private BigDecimal fixedFee;
        private Integer status;
        private Date createTime;
        private int verifiedGroupCount;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getRegistrationId() { return registrationId; }
        public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
        public Long getCounterpartyId() { return counterpartyId; }
        public void setCounterpartyId(Long counterpartyId) { this.counterpartyId = counterpartyId; }
        public String getCounterpartyType() { return counterpartyType; }
        public void setCounterpartyType(String counterpartyType) { this.counterpartyType = counterpartyType; }
        public Integer getShareMode() { return shareMode; }
        public void setShareMode(Integer shareMode) { this.shareMode = shareMode; }
        public BigDecimal getShareRate() { return shareRate; }
        public void setShareRate(BigDecimal shareRate) { this.shareRate = shareRate; }
        public BigDecimal getFixedFee() { return fixedFee; }
        public void setFixedFee(BigDecimal fixedFee) { this.fixedFee = fixedFee; }
        public Integer getStatus() { return status; }
        public void setStatus(Integer status) { this.status = status; }
        public Date getCreateTime() { return createTime; }
        public void setCreateTime(Date createTime) { this.createTime = createTime; }
        public int getVerifiedGroupCount() { return verifiedGroupCount; }
        public void setVerifiedGroupCount(int verifiedGroupCount) { this.verifiedGroupCount = verifiedGroupCount; }
    }

    /** 主题 360 的结算状态白名单；收款人和金额仅在财务结算视图读取。 */
    public static class Settlement {
        private Long id;
        private Long cooperationId;
        private String payeeType;
        private String lineType;
        private Integer status;
        private Date payableTime;
        private Date payoutTime;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getCooperationId() { return cooperationId; }
        public void setCooperationId(Long cooperationId) { this.cooperationId = cooperationId; }
        public String getPayeeType() { return payeeType; }
        public void setPayeeType(String payeeType) { this.payeeType = payeeType; }
        public String getLineType() { return lineType; }
        public void setLineType(String lineType) { this.lineType = lineType; }
        public Integer getStatus() { return status; }
        public void setStatus(Integer status) { this.status = status; }
        public Date getPayableTime() { return payableTime; }
        public void setPayableTime(Date payableTime) { this.payableTime = payableTime; }
        public Date getPayoutTime() { return payoutTime; }
        public void setPayoutTime(Date payoutTime) { this.payoutTime = payoutTime; }
    }

    public static class Fulfillment {
        private BigDecimal totalSales = BigDecimal.ZERO;
        private int verifiedHeads;
        private BigDecimal verifiedSales = BigDecimal.ZERO;
        private int openComplaintCount;

        public BigDecimal getTotalSales() { return totalSales; }
        public void setTotalSales(BigDecimal totalSales) { this.totalSales = totalSales; }
        public int getVerifiedHeads() { return verifiedHeads; }
        public void setVerifiedHeads(int verifiedHeads) { this.verifiedHeads = verifiedHeads; }
        public BigDecimal getVerifiedSales() { return verifiedSales; }
        public void setVerifiedSales(BigDecimal verifiedSales) { this.verifiedSales = verifiedSales; }
        public int getOpenComplaintCount() { return openComplaintCount; }
        public void setOpenComplaintCount(int openComplaintCount) { this.openComplaintCount = openComplaintCount; }
    }

    public enum CapabilityState {
        AVAILABLE,
        NOT_APPLICABLE,
        NOT_GRANTED,
        NOT_INTEGRATED,
        LOAD_FAILED
    }

    /**
     * 单一工作台数据轴的读取能力合同。
     *
     * <p>只有 AVAILABLE 可以携带并展示业务数据；其他状态必须由后端说明原因，
     * 前端不得通过字段为空、空数组或零值自行猜测。</p>
     */
    public static class AxisCapability {
        private CapabilityState state = CapabilityState.LOAD_FAILED;
        private String reasonCode = "CAPABILITY_CONTRACT_MISSING";
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date asOf;

        public static AxisCapability available(String reasonCode, Date asOf) {
            AxisCapability capability = new AxisCapability();
            capability.setState(CapabilityState.AVAILABLE);
            capability.setReasonCode(reasonCode);
            capability.setAsOf(asOf);
            return capability;
        }

        public static AxisCapability unavailable(CapabilityState state, String reasonCode) {
            AxisCapability capability = new AxisCapability();
            capability.setState(state);
            capability.setReasonCode(reasonCode);
            capability.setAsOf(null);
            return capability;
        }

        public CapabilityState getState() { return state; }
        public void setState(CapabilityState state) { this.state = state; }
        public String getReasonCode() { return reasonCode; }
        public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
        public Date getAsOf() { return asOf; }
        public void setAsOf(Date asOf) { this.asOf = asOf; }
    }

    /**
     * 数据轴可用性合同。默认全为 LOAD_FAILED，避免缺少新合同的旧响应被前端误读为可用。
     */
    public static class Capabilities {
        private AxisCapability structure = AxisCapability.unavailable(
                CapabilityState.LOAD_FAILED, "CAPABILITY_CONTRACT_MISSING");
        private AxisCapability audit = AxisCapability.unavailable(
                CapabilityState.LOAD_FAILED, "CAPABILITY_CONTRACT_MISSING");
        private AxisCapability candidates = AxisCapability.unavailable(
                CapabilityState.LOAD_FAILED, "CAPABILITY_CONTRACT_MISSING");
        private AxisCapability fulfillment = AxisCapability.unavailable(
                CapabilityState.LOAD_FAILED, "CAPABILITY_CONTRACT_MISSING");
        private AxisCapability clubFulfillment = AxisCapability.unavailable(
                CapabilityState.LOAD_FAILED, "CAPABILITY_CONTRACT_MISSING");
        private AxisCapability merchantSupply = AxisCapability.unavailable(
                CapabilityState.LOAD_FAILED, "CAPABILITY_CONTRACT_MISSING");
        private AxisCapability tickets = AxisCapability.unavailable(
                CapabilityState.LOAD_FAILED, "CAPABILITY_CONTRACT_MISSING");
        private AxisCapability cooperation = AxisCapability.unavailable(
                CapabilityState.LOAD_FAILED, "CAPABILITY_CONTRACT_MISSING");
        private AxisCapability settlement = AxisCapability.unavailable(
                CapabilityState.LOAD_FAILED, "CAPABILITY_CONTRACT_MISSING");
        private AxisCapability roam = AxisCapability.unavailable(
                CapabilityState.LOAD_FAILED, "CAPABILITY_CONTRACT_MISSING");

        public AxisCapability getStructure() { return structure; }
        public void setStructure(AxisCapability structure) { this.structure = structure; }
        public AxisCapability getAudit() { return audit; }
        public void setAudit(AxisCapability audit) { this.audit = audit; }
        public AxisCapability getCandidates() { return candidates; }
        public void setCandidates(AxisCapability candidates) { this.candidates = candidates; }
        public AxisCapability getFulfillment() { return fulfillment; }
        public void setFulfillment(AxisCapability fulfillment) { this.fulfillment = fulfillment; }
        public AxisCapability getClubFulfillment() { return clubFulfillment; }
        public void setClubFulfillment(AxisCapability clubFulfillment) { this.clubFulfillment = clubFulfillment; }
        public AxisCapability getMerchantSupply() { return merchantSupply; }
        public void setMerchantSupply(AxisCapability merchantSupply) { this.merchantSupply = merchantSupply; }
        public AxisCapability getTickets() { return tickets; }
        public void setTickets(AxisCapability tickets) { this.tickets = tickets; }
        public AxisCapability getCooperation() { return cooperation; }
        public void setCooperation(AxisCapability cooperation) { this.cooperation = cooperation; }
        public AxisCapability getSettlement() { return settlement; }
        public void setSettlement(AxisCapability settlement) { this.settlement = settlement; }
        public AxisCapability getRoam() { return roam; }
        public void setRoam(AxisCapability roam) { this.roam = roam; }
    }
}
