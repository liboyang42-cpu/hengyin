package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.Club;

/** 商家发起合作时使用的俱乐部候选白名单；只暴露匹配合作所需的公开能力。 */
public final class ClubInviteCandidateVO {
    private Long id;
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
    private Integer maxEventSize;
    private Integer avgEventSize;
    private Integer canDesignRoute;
    private Integer canDesignTask;
    private Integer canNpc;
    private Integer canMerchantCoop;
    private Integer hasGuideCert;
    private Integer level;
    private String tags;
    private Integer memberCount;
    private Integer topicCount;
    private Integer leaderLevel;
    private String leaderTags;

    public static ClubInviteCandidateVO from(Club club) {
        ClubInviteCandidateVO vo = new ClubInviteCandidateVO();
        vo.id = club.getId();
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
        vo.maxEventSize = club.getMaxEventSize();
        vo.avgEventSize = club.getAvgEventSize();
        vo.canDesignRoute = club.getCanDesignRoute();
        vo.canDesignTask = club.getCanDesignTask();
        vo.canNpc = club.getCanNpc();
        vo.canMerchantCoop = club.getCanMerchantCoop();
        vo.hasGuideCert = club.getHasGuideCert();
        vo.level = club.getLevel();
        vo.tags = club.getTags();
        vo.memberCount = club.getMemberCount();
        vo.topicCount = club.getTopicCount();
        vo.leaderLevel = club.getLeaderLevel();
        vo.leaderTags = club.getLeaderTags();
        return vo;
    }

    public Long getId() { return id; }
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
    public Integer getMaxEventSize() { return maxEventSize; }
    public Integer getAvgEventSize() { return avgEventSize; }
    public Integer getCanDesignRoute() { return canDesignRoute; }
    public Integer getCanDesignTask() { return canDesignTask; }
    public Integer getCanNpc() { return canNpc; }
    public Integer getCanMerchantCoop() { return canMerchantCoop; }
    public Integer getHasGuideCert() { return hasGuideCert; }
    public Integer getLevel() { return level; }
    public String getTags() { return tags; }
    public Integer getMemberCount() { return memberCount; }
    public Integer getTopicCount() { return topicCount; }
    public Integer getLeaderLevel() { return leaderLevel; }
    public String getLeaderTags() { return leaderTags; }
}
