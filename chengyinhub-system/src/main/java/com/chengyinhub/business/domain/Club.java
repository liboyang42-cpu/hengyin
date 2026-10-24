package com.chengyinhub.business.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 俱乐部对象 club
 *
 * RBAC R2：玩家申请 + 后台审核通过后成为俱乐部（一个会员一个俱乐部）。
 */
public class Club extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** 拥有者会员ID */
    @Excel(name = "会员ID")
    private Long memberId;

    /** 俱乐部名称 */
    @Excel(name = "俱乐部名称")
    private String name;

    /** LOGO/头像 */
    private String logo;

    /** 封面图 */
    private String cover;

    /** 简介 */
    @Excel(name = "简介")
    private String description;

    /** 俱乐部类型(逗号分隔) */
    private String clubTypes;

    /** 活动倾向(逗号分隔) */
    private String activityPrefs;

    /** 活动经验 */
    private String experience;

    /** 加入理由 */
    private String joinReason;

    /** 案例展示图(逗号分隔URL) */
    private String caseImages;

    /** 俱乐部类型大类(单选,C4) */
    private String clubType;

    /** 所在城市 */
    private String city;

    /** 核心关键词(逗号分隔) */
    private String keywords;

    /** 风格/调性 */
    private String style;

    /** 可承接最大活动人数 */
    private Integer maxEventSize;

    /** 常规活动人数 */
    private Integer avgEventSize;

    /** 能否设计路线 0否1是 */
    private Integer canDesignRoute;

    /** 能否设计任务 0否1是 */
    private Integer canDesignTask;

    /** 能否提供NPC 0否1是 */
    private Integer canNpc;

    /** 能否商家合作 0否1是 */
    private Integer canMerchantCoop;

    /** 是否有导游/旅游资质 0否1是 */
    private Integer hasGuideCert;

    /** 资质证书图(逗号分隔URL) */
    private String certImages;

    /** 俱乐部定级 0未定级 1-5 */
    private Integer level;

    /** 运营标签(逗号分隔) */
    private String tags;

    /** 定级审核人 */
    private String auditBy;

    /** 定级审核时间 */
    private Date auditTime;

    /** 分类ID */
    private Long categoryId;

    /** 地点 */
    @Excel(name = "地点")
    private String address;

    /** 负责人 */
    private String leaderName;

    /** 联系电话 */
    private String phone;

    /** 审核状态 0待审核 1通过 2驳回 */
    @Excel(name = "审核状态", readConverterExp = "0=待审核,1=通过,2=驳回")
    private Long status;

    /** 驳回原因 */
    private String rejectReason;

    /** 成员数(冗余) */
    private Integer memberCount;

    /** 是否开启成员优先报名 0否1是 */
    private Integer prioritySignupEnabled;

    /** 成员优惠价（元，NULL=不启用） */
    private BigDecimal memberDiscountPrice;

    /** 为成员保留的报名名额 */
    private Integer memberReservedQuota;

    /** del_flag */
    private Integer delFlag;

    /** 非持久：当前用户是否已加入 */
    private Boolean isJoined;

    /** 非持久：当前用户是否为群主 */
    private Boolean isOwner;

    /** 非持久：该俱乐部办的经典定向团数(按 club_id 统计) */
    private Integer topicCount;

    /** 非持久：主理人档案等级（club_leader 为定级单一真源） */
    private Integer leaderLevel;

    /** 非持久：主理人档案标签（club_leader 为定级单一真源） */
    private String leaderTags;

    /** 非持久：本次请求是否明确更新经营配置（支持将优惠价清空为 NULL） */
    private Boolean operationConfigUpdated;

    /** 允许成员发帖 0仅管理者可发 1所有成员可发（稿 M 268:223「开放设置」）。默认 1 = 加这列之前的行为。 */
    private Integer memberPostAllowed;

    /** 入会欢迎语(主理人自定,加入后的欢迎弹层最上面显示)。列未落库时为 null。 */
    private String welcomeNote;

    /** 俱乐部公开可见 0仅成员能搜到和打开 1所有人（稿 M 268:223）。默认 1 = 加这列之前的行为。 */
    private Integer publicVisible;

    /** 开放商家承接 0本俱乐部主题不进商家可承接列表 1进（稿 M 268:223）。默认 1 = 加这列之前的行为。 */
    private Integer merchantUndertakeOpen;

    /** 非持久：入会申请留言。只作为 /api/club/join 的请求字段用，落库落在 club_member.join_message 上。 */
    private String joinMessage;

    /** 非持久：加入策略 0公开直进 1需主理人审批（独立查询，兼容迁移未落库） */
    private Integer joinPolicy;

    /** 非持久：当前数据库是否已具备 join_policy 列 */
    private Boolean joinPolicySupported;

    /** 非持久：当前访问者的入会状态 0待审 1正常 2已拒绝 */
    private Integer myJoinStatus;
    private String myJoinReviewReason = "";
    public String getMyJoinReviewReason() { return myJoinReviewReason; }
    public void setMyJoinReviewReason(String value) { myJoinReviewReason = value == null ? "" : value; }

    /** 非持久：当前访问者是否为管理员；仅用于成员接口失败时给出可观察提示 */
    private Boolean viewerIsAdmin;

    /** 非持久：已登录访问者的成员身份查询失败；前端据此保持 fail-closed 并提示工具暂不可用 */
    private Boolean viewerIdentityUnavailable;

    /** 非持久：待主理人审批的入会申请数 */
    private Integer pendingJoinRequestCount;

    /** 非持久：不含主理人的正常成员数，供解散二次确认使用 */
    private Integer nonOwnerMemberCount;

    /** 非持久：解散动作的一次确认 */
    private Boolean dissolveConfirmed;

    /** 非持久：有成员时对成员后果的二次确认 */
    private Boolean memberConsequencesConfirmed;

    public Integer getTopicCount() { return topicCount; }
    public void setTopicCount(Integer topicCount) { this.topicCount = topicCount; }
    public Integer getLeaderLevel() { return leaderLevel; }
    public void setLeaderLevel(Integer leaderLevel) { this.leaderLevel = leaderLevel; }
    public String getLeaderTags() { return leaderTags; }
    public void setLeaderTags(String leaderTags) { this.leaderTags = leaderTags; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getLogo() { return logo; }
    public void setLogo(String logo) { this.logo = logo; }
    public String getCover() { return cover; }
    public void setCover(String cover) { this.cover = cover; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getClubTypes() { return clubTypes; }
    public void setClubTypes(String clubTypes) { this.clubTypes = clubTypes; }
    public String getActivityPrefs() { return activityPrefs; }
    public void setActivityPrefs(String activityPrefs) { this.activityPrefs = activityPrefs; }
    public String getExperience() { return experience; }
    public void setExperience(String experience) { this.experience = experience; }
    public String getJoinReason() { return joinReason; }
    public void setJoinReason(String joinReason) { this.joinReason = joinReason; }
    public String getCaseImages() { return caseImages; }
    public void setCaseImages(String caseImages) { this.caseImages = caseImages; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getLeaderName() { return leaderName; }
    public void setLeaderName(String leaderName) { this.leaderName = leaderName; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public Long getStatus() { return status; }
    public void setStatus(Long status) { this.status = status; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
    public Integer getMemberCount() { return memberCount; }
    public void setMemberCount(Integer memberCount) { this.memberCount = memberCount; }
    public Integer getPrioritySignupEnabled() { return prioritySignupEnabled; }
    public void setPrioritySignupEnabled(Integer prioritySignupEnabled) { this.prioritySignupEnabled = prioritySignupEnabled; }
    public BigDecimal getMemberDiscountPrice() { return memberDiscountPrice; }
    public void setMemberDiscountPrice(BigDecimal memberDiscountPrice) { this.memberDiscountPrice = memberDiscountPrice; }
    public Integer getMemberReservedQuota() { return memberReservedQuota; }
    public void setMemberReservedQuota(Integer memberReservedQuota) { this.memberReservedQuota = memberReservedQuota; }
    public Boolean getOperationConfigUpdated() { return operationConfigUpdated; }
    public void setOperationConfigUpdated(Boolean operationConfigUpdated) { this.operationConfigUpdated = operationConfigUpdated; }
    public String getJoinMessage() { return joinMessage; }
    public void setJoinMessage(String joinMessage) { this.joinMessage = joinMessage; }
    public Integer getPublicVisible() { return publicVisible; }
    public void setPublicVisible(Integer publicVisible) { this.publicVisible = publicVisible; }
    public Integer getMerchantUndertakeOpen() { return merchantUndertakeOpen; }
    public void setMerchantUndertakeOpen(Integer merchantUndertakeOpen) { this.merchantUndertakeOpen = merchantUndertakeOpen; }
    public Integer getMemberPostAllowed() { return memberPostAllowed; }
    public String getWelcomeNote() { return welcomeNote; }
    public void setWelcomeNote(String welcomeNote) { this.welcomeNote = welcomeNote; }
    public void setMemberPostAllowed(Integer memberPostAllowed) { this.memberPostAllowed = memberPostAllowed; }
    public Integer getJoinPolicy() { return joinPolicy; }
    public void setJoinPolicy(Integer joinPolicy) { this.joinPolicy = joinPolicy; }
    public Boolean getJoinPolicySupported() { return joinPolicySupported; }
    public void setJoinPolicySupported(Boolean joinPolicySupported) { this.joinPolicySupported = joinPolicySupported; }
    public Integer getMyJoinStatus() { return myJoinStatus; }
    public void setMyJoinStatus(Integer myJoinStatus) { this.myJoinStatus = myJoinStatus; }
    public Boolean getViewerIsAdmin() { return viewerIsAdmin; }
    public void setViewerIsAdmin(Boolean viewerIsAdmin) { this.viewerIsAdmin = viewerIsAdmin; }
    public Boolean getViewerIdentityUnavailable() { return viewerIdentityUnavailable; }
    public void setViewerIdentityUnavailable(Boolean viewerIdentityUnavailable) { this.viewerIdentityUnavailable = viewerIdentityUnavailable; }
    public Integer getPendingJoinRequestCount() { return pendingJoinRequestCount; }
    public void setPendingJoinRequestCount(Integer pendingJoinRequestCount) { this.pendingJoinRequestCount = pendingJoinRequestCount; }
    public Integer getNonOwnerMemberCount() { return nonOwnerMemberCount; }
    public void setNonOwnerMemberCount(Integer nonOwnerMemberCount) { this.nonOwnerMemberCount = nonOwnerMemberCount; }
    public Boolean getDissolveConfirmed() { return dissolveConfirmed; }
    public void setDissolveConfirmed(Boolean dissolveConfirmed) { this.dissolveConfirmed = dissolveConfirmed; }
    public Boolean getMemberConsequencesConfirmed() { return memberConsequencesConfirmed; }
    public void setMemberConsequencesConfirmed(Boolean memberConsequencesConfirmed) { this.memberConsequencesConfirmed = memberConsequencesConfirmed; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
    public Boolean getIsJoined() { return isJoined; }
    public void setIsJoined(Boolean isJoined) { this.isJoined = isJoined; }
    public Boolean getIsOwner() { return isOwner; }
    public void setIsOwner(Boolean isOwner) { this.isOwner = isOwner; }
    public String getClubType() { return clubType; }
    public void setClubType(String clubType) { this.clubType = clubType; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getKeywords() { return keywords; }
    public void setKeywords(String keywords) { this.keywords = keywords; }
    public String getStyle() { return style; }
    public void setStyle(String style) { this.style = style; }
    public Integer getMaxEventSize() { return maxEventSize; }
    public void setMaxEventSize(Integer maxEventSize) { this.maxEventSize = maxEventSize; }
    public Integer getAvgEventSize() { return avgEventSize; }
    public void setAvgEventSize(Integer avgEventSize) { this.avgEventSize = avgEventSize; }
    public Integer getCanDesignRoute() { return canDesignRoute; }
    public void setCanDesignRoute(Integer canDesignRoute) { this.canDesignRoute = canDesignRoute; }
    public Integer getCanDesignTask() { return canDesignTask; }
    public void setCanDesignTask(Integer canDesignTask) { this.canDesignTask = canDesignTask; }
    public Integer getCanNpc() { return canNpc; }
    public void setCanNpc(Integer canNpc) { this.canNpc = canNpc; }
    public Integer getCanMerchantCoop() { return canMerchantCoop; }
    public void setCanMerchantCoop(Integer canMerchantCoop) { this.canMerchantCoop = canMerchantCoop; }
    public Integer getHasGuideCert() { return hasGuideCert; }
    public void setHasGuideCert(Integer hasGuideCert) { this.hasGuideCert = hasGuideCert; }
    public String getCertImages() { return certImages; }
    public void setCertImages(String certImages) { this.certImages = certImages; }
    public Integer getLevel() { return level; }
    public void setLevel(Integer level) { this.level = level; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getAuditBy() { return auditBy; }
    public void setAuditBy(String auditBy) { this.auditBy = auditBy; }
    public Date getAuditTime() { return auditTime; }
    public void setAuditTime(Date auditTime) { this.auditTime = auditTime; }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("memberId", getMemberId())
            .append("name", getName())
            .append("status", getStatus())
            .append("memberCount", getMemberCount())
            .toString();
    }
}
