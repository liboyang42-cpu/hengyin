package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.Club;

import java.math.BigDecimal;

/** 俱乐部公开列表/详情白名单；不得加入申请资料、联系方式或审核内部字段。 */
public class PublicClubVO {
    private Long id;
    private Long memberId;
    private String name;
    private String logo;
    private String cover;
    private String description;
    private String clubTypes;
    private String activityPrefs;
    private String clubType;
    private String city;
    private String keywords;
    private String style;
    private String leaderName;
    private Integer level;
    private String tags;
    private Integer memberCount;
    private Integer topicCount;
    private Integer prioritySignupEnabled;
    private BigDecimal memberDiscountPrice;
    private Integer memberReservedQuota;
    private Boolean isJoined;
    private Boolean isOwner;
    private Integer joinPolicy;
    /** 俱乐部公开可见 0/1。真闸在 /api/club/list 与 /api/club/detail 上,这里只供主理人渲染开关。 */
    private Integer publicVisible;
    /** 允许成员发帖 0/1。前端据此决定发帖框出不出;真闸在 /api/club/post/create 上。 */
    private Integer memberPostAllowed;
    /** 入会欢迎语(主理人自定)。空串/null = 没写,前端只显示默认的「你可以做什么」。 */
    private String welcomeNote;
    /** 开放商家承接 0/1。真闸在商家池那条查询上,这里只供主理人渲染开关。 */
    private Integer merchantUndertakeOpen;
    private Boolean joinPolicySupported;
    private Integer myJoinStatus;
    /** Filled only from the authenticated viewer's own membership in detail(). */
    private String myJoinReviewReason = "";
    public String getMyJoinReviewReason() { return myJoinReviewReason; }
    private Boolean viewerIsAdmin;
    private Boolean viewerIdentityUnavailable;
    private Integer pendingJoinRequestCount;
    private Integer nonOwnerMemberCount;

    public static PublicClubVO from(Club club) {
        PublicClubVO vo = new PublicClubVO();
        vo.id = club.getId();
        vo.memberId = club.getMemberId();
        vo.name = club.getName();
        vo.logo = club.getLogo();
        vo.cover = club.getCover();
        vo.description = club.getDescription();
        vo.clubTypes = club.getClubTypes();
        vo.activityPrefs = club.getActivityPrefs();
        vo.clubType = club.getClubType();
        vo.city = club.getCity();
        vo.keywords = club.getKeywords();
        vo.style = club.getStyle();
        vo.leaderName = club.getLeaderName();
        vo.level = club.getLevel();
        vo.tags = club.getTags();
        vo.memberCount = club.getMemberCount();
        vo.topicCount = club.getTopicCount();
        vo.prioritySignupEnabled = club.getPrioritySignupEnabled();
        vo.memberDiscountPrice = club.getMemberDiscountPrice();
        vo.memberReservedQuota = club.getMemberReservedQuota();
        vo.isJoined = club.getIsJoined();
        vo.isOwner = club.getIsOwner();
        vo.joinPolicy = club.getJoinPolicy();
        vo.joinPolicySupported = club.getJoinPolicySupported();
        // 缺列/旧行读到 null 时按 1 —— 与加这列之前的行为一致,不会突然把发帖框藏掉
        vo.memberPostAllowed = club.getMemberPostAllowed() == null ? 1 : club.getMemberPostAllowed();
        vo.merchantUndertakeOpen = club.getMerchantUndertakeOpen() == null ? 1 : club.getMerchantUndertakeOpen();
        vo.welcomeNote = club.getWelcomeNote() == null ? "" : club.getWelcomeNote();
        vo.myJoinStatus = club.getMyJoinStatus();
        vo.myJoinReviewReason = club.getMyJoinReviewReason();
        vo.viewerIsAdmin = club.getViewerIsAdmin();
        vo.viewerIdentityUnavailable = club.getViewerIdentityUnavailable();
        vo.pendingJoinRequestCount = club.getPendingJoinRequestCount();
        vo.nonOwnerMemberCount = club.getNonOwnerMemberCount();
        return vo;
    }

    public Long getId() { return id; }
    public Long getMemberId() { return memberId; }
    public String getName() { return name; }
    public String getLogo() { return logo; }
    public String getCover() { return cover; }
    public String getDescription() { return description; }
    public String getClubTypes() { return clubTypes; }
    public String getActivityPrefs() { return activityPrefs; }
    public String getClubType() { return clubType; }
    public String getCity() { return city; }
    public String getKeywords() { return keywords; }
    public String getStyle() { return style; }
    public String getLeaderName() { return leaderName; }
    public Integer getLevel() { return level; }
    public String getTags() { return tags; }
    public Integer getMemberCount() { return memberCount; }
    public Integer getTopicCount() { return topicCount; }
    public Integer getPrioritySignupEnabled() { return prioritySignupEnabled; }
    public BigDecimal getMemberDiscountPrice() { return memberDiscountPrice; }
    public Integer getMemberReservedQuota() { return memberReservedQuota; }
    public Boolean getIsJoined() { return isJoined; }
    public Boolean getIsOwner() { return isOwner; }
    public Integer getJoinPolicy() { return joinPolicy; }
    public Integer getMemberPostAllowed() { return memberPostAllowed; }
    public String getWelcomeNote() { return welcomeNote; }
    public Integer getMerchantUndertakeOpen() { return merchantUndertakeOpen; }
    public Boolean getJoinPolicySupported() { return joinPolicySupported; }
    public Integer getMyJoinStatus() { return myJoinStatus; }
    public Boolean getViewerIsAdmin() { return viewerIsAdmin; }
    public Boolean getViewerIdentityUnavailable() { return viewerIdentityUnavailable; }
    public Integer getPendingJoinRequestCount() { return pendingJoinRequestCount; }
    public Integer getNonOwnerMemberCount() { return nonOwnerMemberCount; }
}
