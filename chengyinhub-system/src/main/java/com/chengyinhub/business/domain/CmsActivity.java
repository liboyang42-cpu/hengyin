package com.chengyinhub.business.domain;

import com.chengyinhub.system.domain.SysCategory;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 活动对象 cms_activity
 * 
 * @author webxh
 * @date 2025-08-31
 */
public class CmsActivity extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    private Long id;

    /** 用户ID */
    @Excel(name = "用户ID")
    private Long memberId;

    /**
     * 当前查看者报过名没有(只在活动详情查询里有值,列表查询恒为 null)。
     * ⚠️ 不入库,是 selectCmsActivityDetailById 现算的派生态;
     *    判据照抄 ClubEventOpsMapper.countActiveRegistration,两处口径必须一致。
     */
    private Boolean hasSignup;

    /** 活动名称 */
    @Excel(name = "活动名称")
    private String name;

    /** 活动描述 */
    @Excel(name = "活动描述")
    private String description;

    /** 活动封面图 */
    @Excel(name = "活动封面图")
    private String imgUrl;

    /** 活动轮播图（多张）分号隔开 */
    @Excel(name = "活动轮播图", readConverterExp = "多=张")
    private String imgArr;

    /** 活动类别IDS,逗号隔开 */
    @Excel(name = "活动类别IDS")
    private String categoryIds;

    /** 场馆名称 */
    @Excel(name = "场馆名称")
    private String addressName;

    /** 地址经度 */
    @Excel(name = "地址经度")
    private String longitude;

    /** 地址纬度 */
    @Excel(name = "地址纬度")
    private String latitude;

    /** 地址 */
    @Excel(name = "地址")
    private String address;

    /** 当日运营备注(承接方领队可改,报备发起人) */
    private String dayNote;

    /** 距离 */
    private String distance;



    /** 状态：0-&gt;待审核；1-&gt;审核通过；2-&gt;审核失败； */
    @Excel(name = "状态：0-&gt;待审核；1-&gt;审核通过；2-&gt;审核失败；")
    private Long status;

    /** 发布状态：0下架中  1已上架 */
    @Excel(name = "发布状态：0下架中  1已上架")
    private Long publishStatus;

    /** 取消时间；非空=活动已被主办方取消(已对未核销报名全额退款)。与 publish_status(下架)/del_flag(删除) 正交 */
    @Excel(name = "取消时间")
    private Date cancelTime;

    /** 取消原因(主办方填写，展示给已报名用户) */
    @Excel(name = "取消原因")
    private String cancelReason;

    /** 驳回原因 */
    @Excel(name = "驳回原因")
    private String reason;

    /** 删除标记 0 代表存在  2删除 */
    private Integer delFlag;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Excel(name = "开始日期", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    private Date startDate;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Excel(name = "结束日期", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    private Date endDate;

    @Excel(name = "我的模版ID")
    private Long templateId;

    /** 来源主题ID（由商家报名主题生成时回填，独立创建的活动为空） */
    private Long topicId;

    /** M1-3 社群活动所属俱乐部ID(有值=社群活动,公域列表不出,仅俱乐部成员可见) */
    private Long clubId;

    /** M1-3 查询开关(非入库):publicOnly=1 时公域列表排除社群活动(club_id 有值) */
    private Integer publicOnly;

    /** 查询开关(非入库):excludeCancelled=1 时排除已取消的活动(cancel_time 非空)。
     *  注:不能靠 publish_status 排除 —— 公域列表从不设该条件,「下架」实际不生效(既有缺陷)。 */
    private Integer excludeCancelled;

    /** C 端通用列表专用：隐藏直接或父主题归属已解散俱乐部的活动；后台审计查询不设置。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Boolean activeClubOnly;

    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }

    public Integer getPublicOnly() { return publicOnly; }
    public void setPublicOnly(Integer publicOnly) { this.publicOnly = publicOnly; }

    public Integer getExcludeCancelled() { return excludeCancelled; }
    public void setExcludeCancelled(Integer excludeCancelled) { this.excludeCancelled = excludeCancelled; }
    public Boolean getActiveClubOnly() { return activeClubOnly; }
    public void setActiveClubOnly(Boolean activeClubOnly) { this.activeClubOnly = activeClubOnly; }

    /** 排序方式0默认(时间倒序)  1距离排序 */
    @Excel(name = "排序方式")
    private Integer sortType;

    /** NF-03 查询输入：ONGOING / UPCOMING；仅调用方显式传入时生效。 */
    @TableField(exist = false)
    private String scheduleFilter;

    /** NF-03 查询输入：显式距离半径；null 表示只排序、不截断结果。 */
    @TableField(exist = false)
    private Integer distanceMeters;

    public String getScheduleFilter() { return scheduleFilter; }
    public void setScheduleFilter(String scheduleFilter) { this.scheduleFilter = scheduleFilter; }
    public Integer getDistanceMeters() { return distanceMeters; }
    public void setDistanceMeters(Integer distanceMeters) { this.distanceMeters = distanceMeters; }

    /** 自己是否点赞  0未操作  1已点赞  2已踩*/
    @Excel(name = "自己是否点赞  0未操作  1已点赞  2已踩")
    private Integer isLiked;


    private Long isLikedMemberId;

    /** 自己是否关注该用户  0未关注  1已关注*/
    @Excel(name = "自己是否关注该用户  0未关注  1已关注")
    private Integer isFollowTheUser;

    /**搜索用  搜索选择的开始时间**/
    @TableField(exist = false)
    private String startTime;

    /**搜索用  搜索选择的结束时间**/
    @TableField(exist = false)
    private String endTime;

    /**
     * 所属主题的产品类型:1 经典定向 / 2 自由探索(探店日)。
     *
     * ★ cms_activity **没有** product_type 列 —— 该字段在 cms_topic 上。
     *   这里是查询时按 topic_id 关联取回的瞬态值,不落库、不参与写入。
     *   为 null 表示活动没有关联主题(自建活动),调用方需自行兜底。
     */
    @TableField(exist = false)
    private Integer productType;

    /** 主题级组队配置的只读投影，cms_activity 不落列。 */
    @TableField(exist = false)
    private Integer teamMode;

    @TableField(exist = false)
    private Integer teamMaxMembers;



    /** 销售金额 */
    private BigDecimal salesAmout;


    /** 最低金额 */
    private BigDecimal minAmout;

    /** 浏览次数 */
    private Long viewCount;


    private List<ViewCollaborators> cmsCollaboratorsList;

    private List<CmsMemberTemplate> cmsMemberTemplateList;

    private List<OmsTicket> omsTicketList;

    @Schema(description = "主题分类列表")
    private List<SysCategory> sysCategoryList;


    private  Double rating;


    private  Integer ratingTotal;



    public BigDecimal getMinAmout() {
        return minAmout;
    }

    private  Integer checkStatus;

    private  Integer isSettlement;

    private Integer isNew;

    public Integer getIsNew() {
        return isNew;
    }

    public void setIsNew(Integer isNew) {
        this.isNew = isNew;
    }

    public Long getIsLikedMemberId() {
        return isLikedMemberId;
    }

    public void setIsLikedMemberId(Long isLikedMemberId) {
        this.isLikedMemberId = isLikedMemberId;
    }

    public Integer getIsSettlement() {
        return isSettlement;
    }

    public void setIsSettlement(Integer isSettlement) {
        this.isSettlement = isSettlement;
    }

    public Integer getCheckStatus() {
        return checkStatus;
    }

    public void setCheckStatus(Integer checkStatus) {
        this.checkStatus = checkStatus;
    }

    public void setMinAmout(BigDecimal minAmout) {
        this.minAmout = minAmout;
    }

    public List<SysCategory> getSysCategoryList() {
        return sysCategoryList;
    }

    public void setSysCategoryList(List<SysCategory> sysCategoryList) {
        this.sysCategoryList = sysCategoryList;
    }

    public Integer getRatingTotal() {
        return ratingTotal;
    }

    public void setRatingTotal(Integer ratingTotal) {
        this.ratingTotal = ratingTotal;
    }

    public Double getRating() {
        return rating;
    }

    public void setRating(Double rating) {
        this.rating = rating;
    }

    public List<ViewCollaborators> getCmsCollaboratorsList() {
        return cmsCollaboratorsList;
    }

    public void setCmsCollaboratorsList(List<ViewCollaborators> cmsCollaboratorsList) {
        this.cmsCollaboratorsList = cmsCollaboratorsList;
    }

    public List<CmsMemberTemplate> getCmsMemberTemplateList() {
        return cmsMemberTemplateList;
    }

    public void setCmsMemberTemplateList(List<CmsMemberTemplate> cmsMemberTemplateList) {
        this.cmsMemberTemplateList = cmsMemberTemplateList;
    }

    public List<OmsTicket> getOmsTicketList() {
        return omsTicketList;
    }

    public void setOmsTicketList(List<OmsTicket> omsTicketList) {
        this.omsTicketList = omsTicketList;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    public void setMemberId(Long memberId) 
    {
        this.memberId = memberId;
    }

    public Long getMemberId() 
    {
        return memberId;
    }

    public void setName(String name) 
    {
        this.name = name;
    }

    public String getName() 
    {
        return name;
    }

    public void setDescription(String description) 
    {
        this.description = description;
    }

    public String getDescription() 
    {
        return description;
    }

    public void setImgUrl(String imgUrl) 
    {
        this.imgUrl = imgUrl;
    }

    public String getImgUrl() 
    {
        return imgUrl;
    }

    public void setImgArr(String imgArr) 
    {
        this.imgArr = imgArr;
    }

    public String getImgArr() 
    {
        return imgArr;
    }

    public void setCategoryIds(String categoryIds)
    {
        this.categoryIds = categoryIds;
    }

    public String getCategoryIds()
    {
        return categoryIds;
    }

    public void setAddressName(String addressName) 
    {
        this.addressName = addressName;
    }

    public String getAddressName() 
    {
        return addressName;
    }

    public void setLongitude(String longitude) 
    {
        this.longitude = longitude;
    }

    public String getLongitude() 
    {
        return longitude;
    }

    public void setLatitude(String latitude) 
    {
        this.latitude = latitude;
    }

    public String getLatitude() 
    {
        return latitude;
    }

    public void setAddress(String address) 
    {
        this.address = address;
    }

    public String getAddress()
    {
        return address;
    }

    public void setDayNote(String dayNote)
    {
        this.dayNote = dayNote;
    }

    public String getDayNote()
    {
        return dayNote;
    }

    public String getDistance() {
        return distance;
    }

    public void setDistance(String distance) {
        this.distance = distance;
    }


    public void setStatus(Long status) 
    {
        this.status = status;
    }

    public Long getStatus() 
    {
        return status;
    }

    public void setPublishStatus(Long publishStatus)
    {
        this.publishStatus = publishStatus;
    }

    public Long getPublishStatus()
    {
        return publishStatus;
    }

    public void setCancelTime(Date cancelTime)
    {
        this.cancelTime = cancelTime;
    }

    public Date getCancelTime()
    {
        return cancelTime;
    }

    public void setCancelReason(String cancelReason)
    {
        this.cancelReason = cancelReason;
    }

    public String getCancelReason()
    {
        return cancelReason;
    }


    public void setReason(String reason) 
    {
        this.reason = reason;
    }

    public String getReason() 
    {
        return reason;
    }

    public void setDelFlag(Integer delFlag) 
    {
        this.delFlag = delFlag;
    }

    public Integer getDelFlag() 
    {
        return delFlag;
    }

    public void  setStartDate(Date startDate){
        this.startDate = startDate;
    }

    public Date getStartDate()
    {
        return startDate;
    }

    public void  setEndDate(Date endDate){
        this.endDate = endDate;
    }

    public Date getEndDate()
    {
        return endDate;
    }

    public void  setTemplateId(Long templateId){
        this.templateId = templateId;
    }

    public Long getTemplateId()
    {
        return templateId;
    }

    public void setTopicId(Long topicId)
    {
        this.topicId = topicId;
    }

    public Long getTopicId()
    {
        return topicId;
    }



    public void setSortType(Integer sortType)
    {
        this.sortType = sortType;
    }

    public Integer getSortType()
    {
        return sortType;
    }

    public void setIsLiked(Integer isLiked)
    {
        this.isLiked = isLiked;
    }

    public Integer getIsLiked()
    {
        return isLiked;
    }

    public void setIsFollowTheUser(Integer isFollowTheUser)
    {
        this.isFollowTheUser = isFollowTheUser;
    }

    public Integer getIsFollowTheUser()
    {
        return isFollowTheUser;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public Integer getProductType()
    {
        return productType;
    }

    public void setProductType(Integer productType)
    {
        this.productType = productType;
    }

    public Integer getTeamMode() { return teamMode; }
    public void setTeamMode(Integer teamMode) { this.teamMode = teamMode; }
    public Integer getTeamMaxMembers() { return teamMaxMembers; }
    public void setTeamMaxMembers(Integer teamMaxMembers) { this.teamMaxMembers = teamMaxMembers; }

    public BigDecimal getSalesAmout() {
        return salesAmout;
    }

    public void setSalesAmout(BigDecimal salesAmout) {
        this.salesAmout = salesAmout;
    }
    public Long getViewCount() {
        return viewCount;
    }

    public void setViewCount(Long viewCount) {
        this.viewCount = viewCount;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("memberId", getMemberId())
            .append("name", getName())
            .append("description", getDescription())
            .append("imgUrl", getImgUrl())
            .append("imgArr", getImgArr())
            .append("categoryIds", getCategoryIds())
            .append("addressName", getAddressName())
            .append("longitude", getLongitude())
            .append("latitude", getLatitude())
            .append("address", getAddress())
                .append("distance", getDistance())
            .append("status", getStatus())
                .append("publishStatus", getPublishStatus())
            .append("reason", getReason())
            .append("delFlag", getDelFlag())
                .append("startDate", getStartDate())
                .append("endDate", getEndDate())
                .append("templateId", getTemplateId())
                .append("sortType", getSortType())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }

    public Boolean getHasSignup() { return hasSignup; }

    public void setHasSignup(Boolean hasSignup) { this.hasSignup = hasSignup; }
}
