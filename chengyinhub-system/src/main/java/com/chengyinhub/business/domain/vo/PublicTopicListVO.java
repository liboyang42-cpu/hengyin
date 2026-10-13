package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.system.domain.SysCategory;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/** 公开主题列表白名单，不包含审核、分账与运营治理字段。 */
public class PublicTopicListVO {
    private Boolean inviteWindowOpen;
    public Boolean getInviteWindowOpen() { return inviteWindowOpen; }
    private Long id;
    private Long memberId;
    private Long clubId;
    private Long topicType;
    private Integer productType;
    private String name;
    private String subtitle;
    private String description;
    private Long totalTime;
    private BigDecimal totalMileage;
    private Long locationCount;
    private String addressName;
    private Long templateCount;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") private Date startDate;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") private Date endDate;
    private String imgUrl;
    private String imgArr;
    private String categoryIds;
    private Integer isLike;
    private Integer isFollowTheUser;
    private Long viewCount;
    private Double rating;
    private Integer ratingTotal;
    private BigDecimal minAmout;
    private Integer isRecommend;
    private String publishMode;
    /** 1=Beta试玩期(pro主题过审先进Beta,作者转正归0) */
    private Integer betaFlag;
    private String audioUrl;
    private Integer audioDuration;
    private Integer selfPlay;
    private BigDecimal selfPlayPrice;
    private Integer selfPlayQuota;
    private Integer lifecycle;
    private List<SysCategory> sysCategoryList;

    /**
     * 内容主体类型:PLATFORM / CLUB / MEMBER。
     * ⚠️ 无生效 CONTENT 槽时为 null(存量主题的正常状态),前端必须渲染中性态,<b>不得默认成官方</b>。
     * 只出类型与公开名,不出 entityId —— 主体识别不退化成裸 ID 展示。
     */
    private String contentPublisherType;

    /** 内容主体公开显示名;无槽时为 null,不回退到收款账号。 */
    private String contentPublisherName;

    public static PublicTopicListVO from(CmsTopic source) {
        PublicTopicListVO out = new PublicTopicListVO();
        if (source != null) {
            out.inviteWindowOpen = source.getInviteWindowOpen();
            out.id = source.getId(); out.memberId = source.getMemberId(); out.clubId = source.getClubId();
            out.topicType = source.getTopicType(); out.productType = source.getProductType(); out.name = source.getName();
            out.subtitle = source.getSubtitle(); out.description = source.getDescription(); out.totalTime = source.getTotalTime();
            out.totalMileage = source.getTotalMileage(); out.locationCount = source.getLocationCount(); out.addressName = source.getAddressName();
            out.templateCount = source.getTemplateCount(); out.startDate = source.getStartDate(); out.endDate = source.getEndDate();
            out.imgUrl = source.getImgUrl(); out.imgArr = source.getImgArr(); out.categoryIds = source.getCategoryIds();
            out.isLike = source.getIsLike(); out.isFollowTheUser = source.getIsFollowTheUser(); out.viewCount = source.getViewCount();
            out.rating = source.getRating(); out.ratingTotal = source.getRatingTotal(); out.minAmout = source.getMinAmout();
            out.isRecommend = source.getIsRecommend(); out.publishMode = source.getPublishMode(); out.audioUrl = source.getAudioUrl();
            out.betaFlag = source.getBetaFlag();
            out.audioDuration = source.getAudioDuration(); out.selfPlay = source.getSelfPlay(); out.selfPlayPrice = source.getSelfPlayPrice();
            out.selfPlayQuota = source.getSelfPlayQuota(); out.lifecycle = source.getLifecycle(); out.sysCategoryList = source.getSysCategoryList();
            out.contentPublisherType = source.getContentPublisherType();
            out.contentPublisherName = source.getContentPublisherName();
        }
        return out;
    }

    public Long getId() { return id; }
    public Long getMemberId() { return memberId; }
    public Long getClubId() { return clubId; }
    public Long getTopicType() { return topicType; }
    public Integer getProductType() { return productType; }
    public String getName() { return name; }
    public String getSubtitle() { return subtitle; }
    public String getDescription() { return description; }
    public Long getTotalTime() { return totalTime; }
    public BigDecimal getTotalMileage() { return totalMileage; }
    public Long getLocationCount() { return locationCount; }
    public String getAddressName() { return addressName; }
    public Long getTemplateCount() { return templateCount; }
    public Date getStartDate() { return startDate; }
    public Date getEndDate() { return endDate; }
    public String getImgUrl() { return imgUrl; }
    public String getImgArr() { return imgArr; }
    public String getCategoryIds() { return categoryIds; }
    public Integer getIsLike() { return isLike; }
    public Integer getIsFollowTheUser() { return isFollowTheUser; }
    public Long getViewCount() { return viewCount; }
    public Double getRating() { return rating; }
    public Integer getRatingTotal() { return ratingTotal; }
    public BigDecimal getMinAmout() { return minAmout; }
    public Integer getIsRecommend() { return isRecommend; }
    public String getPublishMode() { return publishMode; }
    public Integer getBetaFlag() { return betaFlag; }
    public String getAudioUrl() { return audioUrl; }
    public Integer getAudioDuration() { return audioDuration; }
    public Integer getSelfPlay() { return selfPlay; }
    public BigDecimal getSelfPlayPrice() { return selfPlayPrice; }
    public Integer getSelfPlayQuota() { return selfPlayQuota; }
    public Integer getLifecycle() { return lifecycle; }
    public List<SysCategory> getSysCategoryList() { return sysCategoryList; }
    public String getContentPublisherType() { return contentPublisherType; }
    public String getContentPublisherName() { return contentPublisherName; }
}
