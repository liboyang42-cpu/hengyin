package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 商家主题报名对象 cms_registration_merchant
 *
 * @author webxh
 * @date 2025-09-14
 */
public class CmsRegistrationMerchant extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /**
     * 报名ID
     */
    private Long id;

    /**
     * 商家用户ID
     */
    @Excel(name = "商家用户ID")
    private Long memberId;

    /**
     * 主题ID
     */
    @Excel(name = "主题ID")
    private Long topicId;

    private String topicName;

    /**
     * 活动模式 1=经典定向 2=自由定向(口径以 OmsTicket 为准)
     */
    @Excel(name = "活动模式 1=经典定向 2=自由定向")
    private Integer mode;

    /**
     * 节点ID
     */
    @Excel(name = "节点ID")
    private Long nodeId;

    private String nodeName;

    /**
     * 场馆名称
     */
    @Excel(name = "场馆名称")
    private String addressName;

    /**
     * 地址经度
     */
    @Excel(name = "地址经度")
    private String longitude;

    /**
     * 地址纬度
     */
    @Excel(name = "地址纬度")
    private String latitude;

    /**
     * 地址
     */
    @Excel(name = "活动地点")
    private String address;

    /**
     * 模版ID
     */
    @Excel(name = "模版ID")
    private Long templateId;

    private String templateName;


    @Excel(name = "商家报名图片")
    private String picUrl;


    /**
     * 邮箱
     */
    @Excel(name = "邮箱")
    private String email;

    /**
     * 参与开始时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "参与开始时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date startDate;

    /**
     * 参与结束时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "参与结束时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date endDate;

    /**
     * 可配合时间08-00-10:00
     */
    @Excel(name = "可配合时间")
    private String cooperateDate;


    /**
     * 活动说明
     */
    @Excel(name = "活动说明")
    private String activityDesc;

    /**
     * 限定人数
     */
    @Excel(name = "限定人数")
    private Long limitNum;

    /**
     * 规则说明
     */
    @Excel(name = "规则说明")
    private String ruleInstructions;

    /**
     * 验证方式:1、文字验证 2、图片验证  3、选项验证
     */
    @Excel(name = "验证方式:1、文字验证 2、图片验证  3、选项验证")
    private Integer validationMethod;

    /**
     * 问题名称
     */
    @Excel(name = "问题名称")
    private String questionName;

    /**
     * 问题答案
     */
    @Excel(name = "问题答案")
    private String questionAnswer;

    /**
     * 反馈文字
     */
    @Excel(name = "反馈文字")
    private String feedbackText;

    /**
     * 反馈优惠券ID
     */
    @Excel(name = "反馈优惠券ID")
    private Long couponId;

    /**
     * 问题选项A
     */
    @Excel(name = "问题选项A")
    private String questionA;

    /**
     * 问题选项B
     */
    @Excel(name = "问题选项B")
    private String questionB;

    /**
     * 问题选项C
     */
    @Excel(name = "问题选项C")
    private String questionC;

    /**
     * 问题选项D
     */
    @Excel(name = "问题选项D")
    private String questionD;

    /**
     * 正确答案(A/B/C/D)
     */
    @Excel(name = "正确答案(A/B/C/D)")
    private String correctAnswer;


    /**
     * 后台审核状态：0-&gt;待审核；1-&gt;审核通过；2-&gt;审核失败；
     */
    @Excel(name = "后台审核状态：0-&gt;待审核；1-&gt;审核通过；2-&gt;审核失败；")
    private Long status;

    /** 调配状态：0待调配 1中标 2落选 */
    private Long auditStatus;

    /** 审核决定发生的时刻(通过 / 驳回都写;商家改资料退回待定时清空)。
     *  稿 88:4368 的进度行「审核通过 8.2 14:05」要的就是它。
     *  ⚠️ 不是 update_time —— 后者任何一次改动都会动,不是审核发生的那一刻。 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date auditTime;


    /**
     * @deprecated ⚠️【已弃用,别拿它当报价】。两个理由:
     *   ① <b>量纲</b>:它在 legacy 里是【权重】(商家池 × 自己rate ÷ 所有中标商家rate之和),
     *      与 {@link #shareRate}(销售额的绝对百分比)不是一回事 —— 同一个「30」,
     *      一个算出 600、一个算出 3000。<b>勿互相映射。</b>
     *   ② <b>值已被污染</b>:结算的平均分配兜底把 {@code averageRate = 100/家数} 整对象写回了库
     *      (生产主题 2/21 的 100.00 就是这么来的,不是任何人填的报价)。
     * <p><b>条款不在报名上</b>:报名只表达意向,条款随 type0 邀约走、商家接受即冻结成 coop_order
     * (施工图 §3.6)。想知道某商家的分账条款,读 {@code coop_order.share_mode/share_rate/fixed_fee}。
     */
    @Deprecated
    private BigDecimal sharingRate;


    /**
     * @deprecated 死账:全仓零消费者。Task 6 已拆掉写它的 distributeMerchantCommissions,
     * 保留仅为历史痕迹。商家真实进账走 coop_settlement。
     */
    @Deprecated
    private BigDecimal sharingAmount;

    private Long searchStatus;


    /**
     * 驳回原因
     */
    @Excel(name = "驳回原因")
    private String reason;


    /**
     * 删除标记 0 代表存在  2删除
     */
    private Integer delFlag;


    private CmsMemberTemplate cmsTemplateLibrary;


    private CmsTopic cmsTopic;

    private MmsMerchant mmsMerchant;


    private Integer totalOrderNum;

    private Integer verifiedNum;


    public Long getSearchStatus() {
        return searchStatus;
    }

    public void setSearchStatus(Long searchStatus) {
        this.searchStatus = searchStatus;
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

    public String getTopicName() {
        return topicName;
    }

    public void setTopicName(String topicName) {
        this.topicName = topicName;
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

    public String getPicUrl() {
        return picUrl;
    }

    public void setPicUrl(String picUrl) {
        this.picUrl = picUrl;
    }

    public MmsMerchant getMmsMerchant() {
        return mmsMerchant;
    }

    public void setMmsMerchant(MmsMerchant mmsMerchant) {
        this.mmsMerchant = mmsMerchant;
    }

    public String getAddressName() {
        return addressName;
    }

    public void setAddressName(String addressName) {
        this.addressName = addressName;
    }

    public String getLongitude() {
        return longitude;
    }

    public void setLongitude(String longitude) {
        this.longitude = longitude;
    }

    public String getLatitude() {
        return latitude;
    }

    public void setLatitude(String latitude) {
        this.latitude = latitude;
    }

    public BigDecimal getSharingAmount() {
        return sharingAmount;
    }

    public void setSharingAmount(BigDecimal sharingAmount) {
        this.sharingAmount = sharingAmount;
    }

    public CmsTopic getCmsTopic() {
        return cmsTopic;
    }

    public void setCmsTopic(CmsTopic cmsTopic) {
        this.cmsTopic = cmsTopic;
    }

    @Deprecated
    public BigDecimal getSharingRate() {
        return sharingRate;
    }

    @Deprecated
    public void setSharingRate(BigDecimal sharingRate) {
        this.sharingRate = sharingRate;
    }

    public CmsMemberTemplate getCmsTemplateLibrary() {
        return cmsTemplateLibrary;
    }

    public void setCmsTemplateLibrary(CmsMemberTemplate cmsTemplateLibrary) {
        this.cmsTemplateLibrary = cmsTemplateLibrary;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public void setMemberId(Long memberId) {
        this.memberId = memberId;
    }

    public Long getMemberId() {
        return memberId;
    }

    public void setTopicId(Long topicId) {
        this.topicId = topicId;
    }

    public Long getTopicId() {
        return topicId;
    }

    public void setMode(Integer mode) {
        this.mode = mode;
    }

    public Integer getMode() {
        return mode;
    }

    public void setNodeId(Long nodeId) {
        this.nodeId = nodeId;
    }

    public Long getNodeId() {
        return nodeId;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getAddress() {
        return address;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getEmail() {
        return email;
    }

    public void setStartDate(Date startDate) {
        this.startDate = startDate;
    }

    public Date getStartDate() {
        return startDate;
    }

    public void setEndDate(Date endDate) {
        this.endDate = endDate;
    }

    public Date getEndDate() {
        return endDate;
    }

    public void setCooperateDate(String cooperateDate) {
        this.cooperateDate = cooperateDate;
    }

    public String getCooperateDate() {
        return cooperateDate;
    }

    public String getActivityDesc() {
        return activityDesc;
    }

    public void setActivityDesc(String activityDesc) {
        this.activityDesc = activityDesc;
    }

    public void setLimitNum(Long limitNum) {
        this.limitNum = limitNum;
    }

    public Long getLimitNum() {
        return limitNum;
    }

    public void setRuleInstructions(String ruleInstructions) {
        this.ruleInstructions = ruleInstructions;
    }

    public String getRuleInstructions() {
        return ruleInstructions;
    }

    public void setValidationMethod(Integer validationMethod) {
        this.validationMethod = validationMethod;
    }

    public Integer getValidationMethod() {
        return validationMethod;
    }

    public void setQuestionName(String questionName) {
        this.questionName = questionName;
    }

    public String getQuestionName() {
        return questionName;
    }

    public void setQuestionAnswer(String questionAnswer) {
        this.questionAnswer = questionAnswer;
    }

    public String getQuestionAnswer() {
        return questionAnswer;
    }

    public void setFeedbackText(String feedbackText) {
        this.feedbackText = feedbackText;
    }

    public String getFeedbackText() {
        return feedbackText;
    }

    public void setCouponId(Long couponId) {
        this.couponId = couponId;
    }

    public Long getCouponId() {
        return couponId;
    }

    public void setQuestionA(String questionA) {
        this.questionA = questionA;
    }

    public String getQuestionA() {
        return questionA;
    }

    public void setQuestionB(String questionB) {
        this.questionB = questionB;
    }

    public String getQuestionB() {
        return questionB;
    }

    public void setQuestionC(String questionC) {
        this.questionC = questionC;
    }

    public String getQuestionC() {
        return questionC;
    }

    public void setQuestionD(String questionD) {
        this.questionD = questionD;
    }

    public String getQuestionD() {
        return questionD;
    }

    public void setCorrectAnswer(String correctAnswer) {
        this.correctAnswer = correctAnswer;
    }

    public String getCorrectAnswer() {
        return correctAnswer;
    }


    public Long getStatus() {
        return status;
    }

    public void setStatus(Long status) {
        this.status = status;
    }

    public Long getAuditStatus() {
        return auditStatus;
    }

    public void setAuditStatus(Long auditStatus) {
        this.auditStatus = auditStatus;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Integer getDelFlag() {
        return delFlag;
    }

    public void setDelFlag(Integer delFlag) {
        this.delFlag = delFlag;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
                .append("id", getId())
                .append("memberId", getMemberId())
                .append("topicId", getTopicId())
                .append("mode", getMode())
                .append("nodeId", getNodeId())
                .append("address", getAddress())
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
                .append("createBy", getCreateBy())
                .append("createTime", getCreateTime())
                .append("updateBy", getUpdateBy())
                .append("updateTime", getUpdateTime())
                .toString();
    }

    public Date getAuditTime() {
        return auditTime;
    }

    public void setAuditTime(Date auditTime) {
        this.auditTime = auditTime;
    }
}
