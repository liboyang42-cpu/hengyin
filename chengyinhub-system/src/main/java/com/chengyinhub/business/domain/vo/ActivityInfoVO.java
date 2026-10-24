package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.ViewComment;
import com.chengyinhub.system.domain.SysCategory;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Date;
import java.util.List;

/**
 * 公开活动详情白名单。独立于 CmsActivity，实体新增字段不会自动进入公开响应。
 */
public class ActivityInfoVO {
    private Long id;
    private Long memberId;
    private String name;
    private String description;
    private String imgUrl;
    private String imgArr;
    private String categoryIds;
    private String addressName;
    private String longitude;
    private String latitude;
    private String address;
    private Long status;
    private Long publishStatus;
    /** CU-M-106:这一行在活动状态徽章上显示的生命周期(pending/online/offline 的时间分桶)。
     *  status 是**审核码**(0 待审 / 1 通过 / 2 未过),不是 official_event 那套 0..9 生命周期码;
     *  过去详情页拿它去查生命周期表,于是 1 被念成「即将开始」,而列表同时说「进行中」。
     *  两个视图现在都读这一个由 ActivityLifecycle 算出的口径。 */
    private String state;
    private String stateText;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date cancelTime;
    private String cancelReason;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startDate;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endDate;
    private Long templateId;
    private Long topicId;
    /** 关联主题的有效节点数；独立活动按自身玩法模板计数。 */
    private Integer playNodeCount;

    public Integer getPlayNodeCount() { return playNodeCount; }
    public void setPlayNodeCount(Integer playNodeCount) { this.playNodeCount = playNodeCount; }
    /** 所属主题的产品类型:1 经典定向 / 2 自由探索(探店日)。
     *  瞬态取自 cms_topic.product_type(cms_activity 无此列);无关联主题时为 null。
     *  由 BeanUtils.copyBeanProp 按属性名自动从 CmsActivity 拷入,无需额外接线。 */
    private Integer productType;
    /** 主题级组队入口：0关闭 1报名时组队 2报名后邀请。 */
    private Integer teamMode;
    /** 玩家可选队伍人数上限。 */
    private Integer teamMaxMembers;
    private Long clubId;

    @Schema(description = "评论数")
    private Integer commentCount = 0;
    @Schema(description = "平均评分")
    private float averageRating = 0;
    @Schema(description = "评论列表")
    private List<ViewComment> commentList;
    @Schema(description = "模版列表（答案、提示已剥离）")
    private List<CmsMemberTemplate> memberTemplateList;
    @Schema(description = "阵容-合作者列表")
    private List<PublicCollaboratorVO> collaboratorsList;
    @Schema(description = "活动票务列表")
    private List<PublicTicketVO> omsTicketList;
    @Schema(description = "主题分类列表")
    private List<SysCategory> sysCategoryList;
    @Schema(description = "参与者列表")
    private List<PublicRegistrationSummaryVO> registrationList;
    @Schema(description = "报名数")
    private Integer registrationCount = 0;
    @Schema(description = "是否报名0未报 1已报")
    private Integer isSignUp = 0;
    /**
     * 已售出(有已支付报名)后时间与地点被锁 —— CR-63(2026-09-15 裁决 15)。
     *
     * <p>前端据此把时间/地点置灰并说明原因;真正的闸在服务端
     * ({@code ApiActivityController#update} 与 {@code ApiClubLeadController#editOps})。
     * 只回布尔不回报名数:编辑页只需要知道能不能改。
     */
    @Schema(description = "已售出活动时间与地点是否锁定")
    private Boolean timeLocationLocked = Boolean.FALSE;
    /** 拍板 2026-09-16 #10:场次归属商家已打烊时为 true;下单入口据此提示「商家暂停营业」。 */
    private Boolean merchantClosed = Boolean.FALSE;
    /** U-168:本次读的服务端时刻(epoch ms,瞬态);报名页据此算时钟偏移来置灰,不入库。 */
    private Long serverNow;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getImgUrl() { return imgUrl; }
    public void setImgUrl(String imgUrl) { this.imgUrl = imgUrl; }
    public String getImgArr() { return imgArr; }
    public void setImgArr(String imgArr) { this.imgArr = imgArr; }
    public String getCategoryIds() { return categoryIds; }
    public void setCategoryIds(String categoryIds) { this.categoryIds = categoryIds; }
    public String getAddressName() { return addressName; }
    public void setAddressName(String addressName) { this.addressName = addressName; }
    public String getLongitude() { return longitude; }
    public void setLongitude(String longitude) { this.longitude = longitude; }
    public String getLatitude() { return latitude; }
    public void setLatitude(String latitude) { this.latitude = latitude; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public Long getStatus() { return status; }
    public void setStatus(Long status) { this.status = status; }
    public Long getPublishStatus() { return publishStatus; }
    public void setPublishStatus(Long publishStatus) { this.publishStatus = publishStatus; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getStateText() { return stateText; }
    public void setStateText(String stateText) { this.stateText = stateText; }
    public Date getCancelTime() { return cancelTime; }
    public void setCancelTime(Date cancelTime) { this.cancelTime = cancelTime; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }
    public Date getStartDate() { return startDate; }
    public void setStartDate(Date startDate) { this.startDate = startDate; }
    public Date getEndDate() { return endDate; }
    public void setEndDate(Date endDate) { this.endDate = endDate; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Integer getProductType() { return productType; }
    public void setProductType(Integer productType) { this.productType = productType; }
    public Integer getTeamMode() { return teamMode; }
    public void setTeamMode(Integer teamMode) { this.teamMode = teamMode; }
    public Integer getTeamMaxMembers() { return teamMaxMembers; }
    public void setTeamMaxMembers(Integer teamMaxMembers) { this.teamMaxMembers = teamMaxMembers; }
    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Integer getCommentCount() { return commentCount; }
    public void setCommentCount(Integer commentCount) { this.commentCount = commentCount; }
    public float getAverageRating() { return averageRating; }
    public void setAverageRating(float averageRating) { this.averageRating = averageRating; }
    public List<ViewComment> getCommentList() { return commentList; }
    public void setCommentList(List<ViewComment> commentList) { this.commentList = commentList; }
    public List<CmsMemberTemplate> getMemberTemplateList() { return memberTemplateList; }
    public void setMemberTemplateList(List<CmsMemberTemplate> memberTemplateList) { this.memberTemplateList = memberTemplateList; }
    public List<PublicCollaboratorVO> getCollaboratorsList() { return collaboratorsList; }
    public void setCollaboratorsList(List<PublicCollaboratorVO> collaboratorsList) { this.collaboratorsList = collaboratorsList; }
    public List<PublicTicketVO> getOmsTicketList() { return omsTicketList; }
    public void setOmsTicketList(List<PublicTicketVO> omsTicketList) { this.omsTicketList = omsTicketList; }
    public List<SysCategory> getSysCategoryList() { return sysCategoryList; }
    public void setSysCategoryList(List<SysCategory> sysCategoryList) { this.sysCategoryList = sysCategoryList; }
    public List<PublicRegistrationSummaryVO> getRegistrationList() { return registrationList; }
    public void setRegistrationList(List<PublicRegistrationSummaryVO> registrationList) { this.registrationList = registrationList; }
    public Integer getRegistrationCount() { return registrationCount; }
    public void setRegistrationCount(Integer registrationCount) { this.registrationCount = registrationCount; }
    public Integer getIsSignUp() { return isSignUp; }
    public void setIsSignUp(Integer isSignUp) { this.isSignUp = isSignUp; }
    public Boolean getTimeLocationLocked() { return timeLocationLocked; }
    public void setTimeLocationLocked(Boolean timeLocationLocked) { this.timeLocationLocked = timeLocationLocked; }
    public Boolean getMerchantClosed() { return merchantClosed; }
    public void setMerchantClosed(Boolean merchantClosed) { this.merchantClosed = merchantClosed; }
    public Long getServerNow() { return serverNow; }
    public void setServerNow(Long serverNow) { this.serverNow = serverNow; }
}
