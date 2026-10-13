package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * VIEW对象 view_registration_merchant
 * 
 * @author ruoyi
 * @date 2026-01-31
 */
public class ViewRegistrationMerchant extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 报名ID */
    private Long id;

    /** 商家用户ID */
    @Excel(name = "商家用户ID")
    private Long memberId;

    /** 主题ID */
    @Excel(name = "主题ID")
    private Long topicId;

    /** 活动模式 1=经典定向 2=自由定向(口径以 OmsTicket 为准) */
    @Excel(name = "活动模式 1=经典定向 2=自由定向")
    private Integer mode;

    /** 节点ID */
    @Excel(name = "节点ID")
    private Long nodeId;

    /** 活动地点 */
    @Excel(name = "活动地点")
    private String address;

    /** 场馆名称 */
    @Excel(name = "场馆名称")
    private String addressName;

    /** 地址经度 */
    @Excel(name = "地址经度")
    private String longitude;

    /** 地址纬度 */
    @Excel(name = "地址纬度")
    private String latitude;

    /** 模版ID */
    @Excel(name = "模版ID")
    private Long templateId;

    /** 商家报名图片 */
    @Excel(name = "商家报名图片")
    private String picUrl;

    /** 邮箱 */
    @Excel(name = "邮箱")
    private String email;

    /** 参与开始时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "参与开始时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date startDate;

    /** 参与结束时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "参与结束时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date endDate;

    /** 可配合时间 */
    @Excel(name = "可配合时间")
    private String cooperateDate;

    /** 活动说明 */
    @Excel(name = "活动说明")
    private String activityDesc;

    /** 限定人数 */
    @Excel(name = "限定人数")
    private Long limitNum;

    /** 规则说明 */
    @Excel(name = "规则说明")
    private String ruleInstructions;

    /** 验证方式:1、文字验证 2、图片验证  3、选项验证 */
    @Excel(name = "验证方式:1、文字验证 2、图片验证  3、选项验证")
    private Integer validationMethod;

    /** 问题名称 */
    @Excel(name = "问题名称")
    private String questionName;

    /** 问题答案 */
    @Excel(name = "问题答案")
    private String questionAnswer;

    /** 反馈文字 */
    @Excel(name = "反馈文字")
    private String feedbackText;

    /** 反馈优惠券ID */
    @Excel(name = "反馈优惠券ID")
    private Long couponId;

    /** 问题选项A */
    @Excel(name = "问题选项A")
    private String questionA;

    /** 问题选项B */
    @Excel(name = "问题选项B")
    private String questionB;

    /** 问题选项C */
    @Excel(name = "问题选项C")
    private String questionC;

    /** 问题选项D */
    @Excel(name = "问题选项D")
    private String questionD;

    /** 正确答案(A/B/C/D) */
    @Excel(name = "正确答案(A/B/C/D)")
    private String correctAnswer;

    /** 状态：0-&gt;待审核  1已审核 2审核失败 */
    @Excel(name = "状态：0-&gt;待审核  1已审核 2审核失败")
    private Long status;

    /** 分润比例 */
    @Excel(name = "分润比例")
    private BigDecimal sharingRate;

    /** 分润金额 */
    @Excel(name = "分润金额")
    private BigDecimal sharingAmount;

    /** 驳回原因 */
    @Excel(name = "驳回原因")
    private String reason;

    /** 删除标记 0 代表存在  2删除 */
    private Long delFlag;

    /** 用户ID-用户和后台发布（商家不能发布） */
    @Excel(name = "用户ID-用户和后台发布", readConverterExp = "商=家不能发布")
    private Long topicMemberId;

    /** 主题类型：1平台主题  2自定义主题 */
    @Excel(name = "主题类型：1平台主题  2自定义主题")
    private Long topicType;

    /** 选择主题的ID */
    @Excel(name = "选择主题的ID")
    private Long relatedTopicId;

    /** 主题名称 */
    @Excel(name = "主题名称")
    private String topicName;

    /** 副标题 */
    @Excel(name = "副标题")
    private String topicSubtitle;

    /** 主题描述 */
    @Excel(name = "主题描述")
    private String topicDescription;

    /** 地址 */
    @Excel(name = "地址")
    private String topicAddressName;

    /** 总时长，单位秒 */
    @Excel(name = "总时长，单位秒")
    private Long totalTime;

    /** 总里程KM */
    @Excel(name = "总里程KM")
    private BigDecimal totalMileage;

    /** 地点数 */
    @Excel(name = "地点数")
    private Long locationCount;

    /** 模版数 */
    @Excel(name = "模版数")
    private Long templateCount;

    /** 主题开始日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "主题开始日期", width = 30, dateFormat = "yyyy-MM-dd")
    private Date topicStartDate;

    /** 主题结束日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "主题结束日期", width = 30, dateFormat = "yyyy-MM-dd")
    private Date topicEndDate;

    /** 主题长图（单张） */
    @Excel(name = "主题长图", readConverterExp = "单=张")
    private String topicImgUrl;

    /** 主题轮播图（多张）分号隔开 */
    @Excel(name = "主题轮播图", readConverterExp = "多=张")
    private String topicImgArr;

    /** 主题类别IDS，逗号隔开 */
    @Excel(name = "主题类别IDS，逗号隔开")
    private String categoryIds;


    /** 审核决定发生的时刻(通过 / 驳回都写;商家改资料退回待定时清空)。
     *  稿 88:4368 的进度行「审核通过 8.2 14:05」要的就是它。
     *  ⚠️ 不是 update_time —— 后者任何一次改动都会动,不是审核发生的那一刻。 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date auditTime;

    /** 后台审核状态：0-&gt;待审核；1-&gt;审核通过；2-&gt;审核失败； */
    @Excel(name = "后台审核状态：0-&gt;待审核；1-&gt;审核通过；2-&gt;审核失败；")
    private Integer topicStatus;

    /** 驳回原因 */
    @Excel(name = "驳回原因")
    private String topicReason;

    /** 用户端状态：0未上架  1已上架 */
    @Excel(name = "用户端状态：0未上架  1已上架")
    private Integer userStatus;

    /** 商家端状态：0未上架  1已上架  */
    @Excel(name = "商家端状态：0未上架  1已上架 ")
    private Integer merchantStatus;

    /** 商家报名开始时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "商家报名开始时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date merchantSignUpStartDate;

    /** 商家报名结束时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "商家报名结束时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date merchantSignUpEndDate;

    /** 删除标记 0 代表存在  2删除 */
    @Excel(name = "删除标记 0 代表存在  2删除")
    private Integer topicDelFlag;

    /** 是否同步到创意广场1 同步 */
    @Excel(name = "是否同步到创意广场1 同步")
    private Integer isSync;

    /** 是否推荐 1 是 0 否 */
    @Excel(name = "是否推荐 1 是 0 否")
    private Long isRecommend;

    /** 后台查阅状态：0-&gt;待审核；1-&gt;审核通过；2-&gt;审核失败； */
    @Excel(name = "后台查阅状态：0-&gt;待审核；1-&gt;审核通过；2-&gt;审核失败；")
    private Long topicCheckStatus;

    /** 是否结算 1是 0否 */
    @Excel(name = "是否结算 1是 0否")
    private Long isSettlement;

    /** 创建人 */
    @Excel(name = "创建人")
    private String topicCreateBy;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "创建时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date topicCreateTime;

    /** 修改人 */
    @Excel(name = "修改人")
    private String topicUpdateBy;

    /** 修改时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "修改时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date topicUpdateTime;

    /** $column.columnComment */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private String displayStatus;

    /** $column.columnComment */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private Long isInProgress;

    /** $column.columnComment */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private Long isEnded;

    /** $column.columnComment */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private Long isUnderReview;

    /** $column.columnComment */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private Long isRejected;


    private Long searchStatus;



    private Integer totalOrderNum;

    /** 本商家在本主题核销过的票数(不是全主题已核销数,口径见 ApiRegistrationMerchantController) */
    private Integer verifiedNum;
    private String nodeName;
    private String nodeImgUrl;
    private String templateName;

    /** 承接章节(从 node 反查富化,视图本身不存) */
    private Long chapterId;
    private String chapterName;

    /** 中标态:1=已中标。视图不含此列,由 controller 从报名主表富化 */
    private Long auditStatus;

    /**
     * 承接节点绑定的玩法模板(从 node.templateId 反查富化,视图本身不存)。
     *
     * 原来只富化一个 templateName,商家点进承接详情只看到一行模板名,不知道玩家
     * 到店会跟他做什么、要准备什么、玩家能拿到什么 —— 而这些字段库里全都有。
     *
     * 该字段只在当前用户通过承接记录归属校验后挂载;只有已中标的核销执行商家
     * 使用 {@link com.chengyinhub.business.util.TemplateSecrets#merchantView},待调配/落选仍剝离答案与提示。
     */
    private CmsMemberTemplate templateDetail;

    public CmsMemberTemplate getTemplateDetail() {
        return templateDetail;
    }

    public void setTemplateDetail(CmsMemberTemplate templateDetail) {
        this.templateDetail = templateDetail;
    }

    public Long getChapterId() {
        return chapterId;
    }

    public void setChapterId(Long chapterId) {
        this.chapterId = chapterId;
    }

    public String getChapterName() {
        return chapterName;
    }

    public void setChapterName(String chapterName) {
        this.chapterName = chapterName;
    }

    public Long getAuditStatus() {
        return auditStatus;
    }

    public void setAuditStatus(Long auditStatus) {
        this.auditStatus = auditStatus;
    }

    public String getNodeImgUrl() {
        return nodeImgUrl;
    }

    public void setNodeImgUrl(String nodeImgUrl) {
        this.nodeImgUrl = nodeImgUrl;
    }

    public Integer getTotalOrderNum() {
        return totalOrderNum;
    }

    public void setTotalOrderNum(Integer totalOrderNum) {
        this.totalOrderNum = totalOrderNum;
    }

    public Integer getVerifiedNum() {
        return verifiedNum;
    }

    public void setVerifiedNum(Integer verifiedNum) {
        this.verifiedNum = verifiedNum;
    }

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public String getTemplateName() {
        return templateName;
    }

    public void setTemplateName(String templateName) {
        this.templateName = templateName;
    }

    public Long getSearchStatus() {
        return searchStatus;
    }

    public void setSearchStatus(Long searchStatus) {
        this.searchStatus = searchStatus;
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

    public void setTopicId(Long topicId) 
    {
        this.topicId = topicId;
    }

    public Long getTopicId() 
    {
        return topicId;
    }

    public void setMode(Integer mode) 
    {
        this.mode = mode;
    }

    public Integer getMode() 
    {
        return mode;
    }

    public void setNodeId(Long nodeId) 
    {
        this.nodeId = nodeId;
    }

    public Long getNodeId() 
    {
        return nodeId;
    }

    public void setAddress(String address) 
    {
        this.address = address;
    }

    public String getAddress() 
    {
        return address;
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

    public void setTemplateId(Long templateId) 
    {
        this.templateId = templateId;
    }

    public Long getTemplateId() 
    {
        return templateId;
    }

    public void setPicUrl(String picUrl) 
    {
        this.picUrl = picUrl;
    }

    public String getPicUrl() 
    {
        return picUrl;
    }

    public void setEmail(String email) 
    {
        this.email = email;
    }

    public String getEmail() 
    {
        return email;
    }

    public void setStartDate(Date startDate) 
    {
        this.startDate = startDate;
    }

    public Date getStartDate() 
    {
        return startDate;
    }

    public void setEndDate(Date endDate) 
    {
        this.endDate = endDate;
    }

    public Date getEndDate() 
    {
        return endDate;
    }

    public void setCooperateDate(String cooperateDate) 
    {
        this.cooperateDate = cooperateDate;
    }

    public String getCooperateDate() 
    {
        return cooperateDate;
    }

    public void setActivityDesc(String activityDesc) 
    {
        this.activityDesc = activityDesc;
    }

    public String getActivityDesc() 
    {
        return activityDesc;
    }

    public void setLimitNum(Long limitNum) 
    {
        this.limitNum = limitNum;
    }

    public Long getLimitNum() 
    {
        return limitNum;
    }

    public void setRuleInstructions(String ruleInstructions) 
    {
        this.ruleInstructions = ruleInstructions;
    }

    public String getRuleInstructions() 
    {
        return ruleInstructions;
    }

    public void setValidationMethod(Integer validationMethod) 
    {
        this.validationMethod = validationMethod;
    }

    public Integer getValidationMethod() 
    {
        return validationMethod;
    }

    public void setQuestionName(String questionName) 
    {
        this.questionName = questionName;
    }

    public String getQuestionName() 
    {
        return questionName;
    }

    public void setQuestionAnswer(String questionAnswer) 
    {
        this.questionAnswer = questionAnswer;
    }

    public String getQuestionAnswer() 
    {
        return questionAnswer;
    }

    public void setFeedbackText(String feedbackText) 
    {
        this.feedbackText = feedbackText;
    }

    public String getFeedbackText() 
    {
        return feedbackText;
    }

    public void setCouponId(Long couponId) 
    {
        this.couponId = couponId;
    }

    public Long getCouponId() 
    {
        return couponId;
    }

    public void setQuestionA(String questionA) 
    {
        this.questionA = questionA;
    }

    public String getQuestionA() 
    {
        return questionA;
    }

    public void setQuestionB(String questionB) 
    {
        this.questionB = questionB;
    }

    public String getQuestionB() 
    {
        return questionB;
    }

    public void setQuestionC(String questionC) 
    {
        this.questionC = questionC;
    }

    public String getQuestionC() 
    {
        return questionC;
    }

    public void setQuestionD(String questionD) 
    {
        this.questionD = questionD;
    }

    public String getQuestionD() 
    {
        return questionD;
    }

    public void setCorrectAnswer(String correctAnswer) 
    {
        this.correctAnswer = correctAnswer;
    }

    public String getCorrectAnswer() 
    {
        return correctAnswer;
    }

    public void setStatus(Long status) 
    {
        this.status = status;
    }

    public Long getStatus() 
    {
        return status;
    }

    public void setSharingRate(BigDecimal sharingRate) 
    {
        this.sharingRate = sharingRate;
    }

    public BigDecimal getSharingRate() 
    {
        return sharingRate;
    }

    public void setSharingAmount(BigDecimal sharingAmount) 
    {
        this.sharingAmount = sharingAmount;
    }

    public BigDecimal getSharingAmount() 
    {
        return sharingAmount;
    }

    public void setReason(String reason) 
    {
        this.reason = reason;
    }

    public String getReason() 
    {
        return reason;
    }

    public void setDelFlag(Long delFlag) 
    {
        this.delFlag = delFlag;
    }

    public Long getDelFlag() 
    {
        return delFlag;
    }

    public void setTopicMemberId(Long topicMemberId) 
    {
        this.topicMemberId = topicMemberId;
    }

    public Long getTopicMemberId() 
    {
        return topicMemberId;
    }

    public void setTopicType(Long topicType) 
    {
        this.topicType = topicType;
    }

    public Long getTopicType() 
    {
        return topicType;
    }

    public void setRelatedTopicId(Long relatedTopicId) 
    {
        this.relatedTopicId = relatedTopicId;
    }

    public Long getRelatedTopicId() 
    {
        return relatedTopicId;
    }

    public void setTopicName(String topicName) 
    {
        this.topicName = topicName;
    }

    public String getTopicName() 
    {
        return topicName;
    }

    public void setTopicSubtitle(String topicSubtitle) 
    {
        this.topicSubtitle = topicSubtitle;
    }

    public String getTopicSubtitle() 
    {
        return topicSubtitle;
    }

    public void setTopicDescription(String topicDescription) 
    {
        this.topicDescription = topicDescription;
    }

    public String getTopicDescription() 
    {
        return topicDescription;
    }

    public void setTopicAddressName(String topicAddressName) 
    {
        this.topicAddressName = topicAddressName;
    }

    public String getTopicAddressName() 
    {
        return topicAddressName;
    }

    public void setTotalTime(Long totalTime) 
    {
        this.totalTime = totalTime;
    }

    public Long getTotalTime() 
    {
        return totalTime;
    }

    public void setTotalMileage(BigDecimal totalMileage) 
    {
        this.totalMileage = totalMileage;
    }

    public BigDecimal getTotalMileage() 
    {
        return totalMileage;
    }

    public void setLocationCount(Long locationCount) 
    {
        this.locationCount = locationCount;
    }

    public Long getLocationCount() 
    {
        return locationCount;
    }

    public void setTemplateCount(Long templateCount) 
    {
        this.templateCount = templateCount;
    }

    public Long getTemplateCount() 
    {
        return templateCount;
    }

    public void setTopicStartDate(Date topicStartDate) 
    {
        this.topicStartDate = topicStartDate;
    }

    public Date getTopicStartDate() 
    {
        return topicStartDate;
    }

    public void setTopicEndDate(Date topicEndDate) 
    {
        this.topicEndDate = topicEndDate;
    }

    public Date getTopicEndDate() 
    {
        return topicEndDate;
    }

    public void setTopicImgUrl(String topicImgUrl) 
    {
        this.topicImgUrl = topicImgUrl;
    }

    public String getTopicImgUrl() 
    {
        return topicImgUrl;
    }

    public void setTopicImgArr(String topicImgArr) 
    {
        this.topicImgArr = topicImgArr;
    }

    public String getTopicImgArr() 
    {
        return topicImgArr;
    }

    public void setCategoryIds(String categoryIds) 
    {
        this.categoryIds = categoryIds;
    }

    public String getCategoryIds() 
    {
        return categoryIds;
    }

    public void setTopicStatus(Integer topicStatus) 
    {
        this.topicStatus = topicStatus;
    }

    public Integer getTopicStatus() 
    {
        return topicStatus;
    }

    public void setTopicReason(String topicReason) 
    {
        this.topicReason = topicReason;
    }

    public String getTopicReason() 
    {
        return topicReason;
    }

    public void setUserStatus(Integer userStatus) 
    {
        this.userStatus = userStatus;
    }

    public Integer getUserStatus() 
    {
        return userStatus;
    }

    public void setMerchantStatus(Integer merchantStatus) 
    {
        this.merchantStatus = merchantStatus;
    }

    public Integer getMerchantStatus() 
    {
        return merchantStatus;
    }

    public void setMerchantSignUpStartDate(Date merchantSignUpStartDate) 
    {
        this.merchantSignUpStartDate = merchantSignUpStartDate;
    }

    public Date getMerchantSignUpStartDate() 
    {
        return merchantSignUpStartDate;
    }

    public void setMerchantSignUpEndDate(Date merchantSignUpEndDate) 
    {
        this.merchantSignUpEndDate = merchantSignUpEndDate;
    }

    public Date getMerchantSignUpEndDate() 
    {
        return merchantSignUpEndDate;
    }

    public void setTopicDelFlag(Integer topicDelFlag) 
    {
        this.topicDelFlag = topicDelFlag;
    }

    public Integer getTopicDelFlag() 
    {
        return topicDelFlag;
    }

    public void setIsSync(Integer isSync) 
    {
        this.isSync = isSync;
    }

    public Integer getIsSync() 
    {
        return isSync;
    }

    public void setIsRecommend(Long isRecommend) 
    {
        this.isRecommend = isRecommend;
    }

    public Long getIsRecommend() 
    {
        return isRecommend;
    }

    public void setTopicCheckStatus(Long topicCheckStatus) 
    {
        this.topicCheckStatus = topicCheckStatus;
    }

    public Long getTopicCheckStatus() 
    {
        return topicCheckStatus;
    }

    public void setIsSettlement(Long isSettlement) 
    {
        this.isSettlement = isSettlement;
    }

    public Long getIsSettlement() 
    {
        return isSettlement;
    }

    public void setTopicCreateBy(String topicCreateBy) 
    {
        this.topicCreateBy = topicCreateBy;
    }

    public String getTopicCreateBy() 
    {
        return topicCreateBy;
    }

    public void setTopicCreateTime(Date topicCreateTime) 
    {
        this.topicCreateTime = topicCreateTime;
    }

    public Date getTopicCreateTime() 
    {
        return topicCreateTime;
    }

    public void setTopicUpdateBy(String topicUpdateBy) 
    {
        this.topicUpdateBy = topicUpdateBy;
    }

    public String getTopicUpdateBy() 
    {
        return topicUpdateBy;
    }

    public void setTopicUpdateTime(Date topicUpdateTime) 
    {
        this.topicUpdateTime = topicUpdateTime;
    }

    public Date getTopicUpdateTime() 
    {
        return topicUpdateTime;
    }

    public void setDisplayStatus(String displayStatus) 
    {
        this.displayStatus = displayStatus;
    }

    public String getDisplayStatus() 
    {
        return displayStatus;
    }

    public void setIsInProgress(Long isInProgress) 
    {
        this.isInProgress = isInProgress;
    }

    public Long getIsInProgress() 
    {
        return isInProgress;
    }

    public void setIsEnded(Long isEnded) 
    {
        this.isEnded = isEnded;
    }

    public Long getIsEnded() 
    {
        return isEnded;
    }

    public void setIsUnderReview(Long isUnderReview) 
    {
        this.isUnderReview = isUnderReview;
    }

    public Long getIsUnderReview() 
    {
        return isUnderReview;
    }

    public void setIsRejected(Long isRejected) 
    {
        this.isRejected = isRejected;
    }

    public Long getIsRejected() 
    {
        return isRejected;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("memberId", getMemberId())
            .append("topicId", getTopicId())
            .append("mode", getMode())
            .append("nodeId", getNodeId())
            .append("address", getAddress())
            .append("addressName", getAddressName())
            .append("longitude", getLongitude())
            .append("latitude", getLatitude())
            .append("templateId", getTemplateId())
            .append("picUrl", getPicUrl())
            .append("email", getEmail())
            .append("startDate", getStartDate())
            .append("endDate", getEndDate())
            .append("cooperateDate", getCooperateDate())
            .append("activityDesc", getActivityDesc())
            .append("limitNum", getLimitNum())
            .append("ruleInstructions", getRuleInstructions())
            .append("validationMethod", getValidationMethod())
            .append("questionName", getQuestionName())
            .append("questionAnswer", getQuestionAnswer())
            .append("feedbackText", getFeedbackText())
            .append("couponId", getCouponId())
            .append("questionA", getQuestionA())
            .append("questionB", getQuestionB())
            .append("questionC", getQuestionC())
            .append("questionD", getQuestionD())
            .append("correctAnswer", getCorrectAnswer())
            .append("status", getStatus())
            .append("sharingRate", getSharingRate())
            .append("sharingAmount", getSharingAmount())
            .append("reason", getReason())
            .append("delFlag", getDelFlag())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("topicMemberId", getTopicMemberId())
            .append("topicType", getTopicType())
            .append("relatedTopicId", getRelatedTopicId())
            .append("topicName", getTopicName())
            .append("topicSubtitle", getTopicSubtitle())
            .append("topicDescription", getTopicDescription())
            .append("topicAddressName", getTopicAddressName())
            .append("totalTime", getTotalTime())
            .append("totalMileage", getTotalMileage())
            .append("locationCount", getLocationCount())
            .append("templateCount", getTemplateCount())
            .append("topicStartDate", getTopicStartDate())
            .append("topicEndDate", getTopicEndDate())
            .append("topicImgUrl", getTopicImgUrl())
            .append("topicImgArr", getTopicImgArr())
            .append("categoryIds", getCategoryIds())
            .append("topicStatus", getTopicStatus())
            .append("topicReason", getTopicReason())
            .append("userStatus", getUserStatus())
            .append("merchantStatus", getMerchantStatus())
            .append("merchantSignUpStartDate", getMerchantSignUpStartDate())
            .append("merchantSignUpEndDate", getMerchantSignUpEndDate())
            .append("topicDelFlag", getTopicDelFlag())
            .append("isSync", getIsSync())
            .append("isRecommend", getIsRecommend())
            .append("topicCheckStatus", getTopicCheckStatus())
            .append("isSettlement", getIsSettlement())
            .append("topicCreateBy", getTopicCreateBy())
            .append("topicCreateTime", getTopicCreateTime())
            .append("topicUpdateBy", getTopicUpdateBy())
            .append("topicUpdateTime", getTopicUpdateTime())
            .append("displayStatus", getDisplayStatus())
            .append("isInProgress", getIsInProgress())
            .append("isEnded", getIsEnded())
            .append("isUnderReview", getIsUnderReview())
            .append("isRejected", getIsRejected())
            .toString();
    }

    public Date getAuditTime() {
        return auditTime;
    }

    public void setAuditTime(Date auditTime) {
        this.auditTime = auditTime;
    }
}
