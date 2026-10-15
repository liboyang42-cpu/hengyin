package com.chengyinhub.business.domain.dto;

import com.chengyinhub.common.annotation.Excel;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.Date;

/**
 * 商家主题报名-请求参数
 */
public class RegistrationMerchantDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 报名ID。仅 /update 用;/create 不传(创建时还没有 id) */
    @Schema(description = "报名ID(仅修改时传)", example = "900")
    private Long id;

    /** 主题ID */
    @Schema(description = "主题ID",  example = "123")
    @NotNull(message = "主题ID不能为空")
    private Long topicId;

    /** 活动模式 1=经典定向 2=自由定向(口径以 OmsTicket 为准) */
    @Schema(description = "活动模式 1=经典定向 2=自由定向",  example = "1")
    // 放开:前端默认带 mode,服务端不强制(原 @NotBlank 误用于 Integer 会崩)
    private Integer mode;

    /** 节点ID */
    @Schema(description = "节点ID",  example = "12")
    //@NotBlank(message = "请选择节点ID")
    private Long nodeId;

    @Schema(description = "场馆名称", example = "国际会议中心")
    private String addressName;

    @Schema(description = "图片", example = "")
    private String picUrl;

    @Schema(description = "地址经度", example = "116.391275")
    private String longitude;

    @Schema(description = "地址纬度", example = "39.992357")
    private String latitude;

    @Schema(description = "活动地点", example = "北京市朝阳区国家体育场南路1号")
    private String address;


    /** 模版ID */
    @Schema(description = "模版ID",  example = "12")
    private Long templateId;


    /** 参与开始时间 */
    @Schema(description = "参与开始时间")
    private Date startDate;

    /** 参与结束时间 */
    @Schema(description = "参与结束时间")
    private Date endDate;

    /** 可配合时间08:00-10:00 */
    @Schema(description = "可配合时间08:00-10:00")
    private String cooperateDate;



    /** 活动说明 */
    @Schema(description = "活动说明")
    private String activityDesc;

    /** 限定人数 */
    @Schema(description = "限定人数",  example = "12")
    //@NotBlank(message = "请输入限定人数")
    private Long limitNum;

    /** 规则说明 */
    @Schema(description = "规则说明",  example = "")
    private String ruleInstructions;

    /** 验证方式:1、文字验证 2、图片验证  3、选项验证 */
    @Schema(description = "验证方式:1、文字验证 2、图片验证  3、选项验证",  example = "")
    // 放开:method2图片/3选项验证不一定有问题名,服务端不强制(原 @NotBlank 误用于 Integer)
    private Integer validationMethod;

    /** 问题名称 */
    @Schema(description = "问题名称",  example = "")
    // 放开:仅 method1 文字验证才需问题名,由前端按 validationMethod 条件校验
    private String questionName;

    /** 问题答案 */
    @Schema(description = "问题答案",  example = "")
    private String questionAnswer;

    /** 反馈文字 */
    @Schema(description = "反馈文字",  example = "")
    private String feedbackText;

    /** 反馈优惠券ID */
    @Schema(description = "反馈优惠券ID",  example = "1")
    private Long couponId;

    /** 问题选项A */
    @Schema(description = "问题选项A",  example = "")
    private String questionA;

    /** 问题选项B */
    @Schema(description = "问题选项B",  example = "")
    private String questionB;

    /** 问题选项C */
    @Schema(description = "问题选项C",  example = "")
    private String questionC;

    /** 问题选项D */
    @Schema(description = "问题选项D",  example = "")
    private String questionD;

    /** 正确答案(A/B/C/D) */
    @Schema(description = "正确答案(A/B/C/D)",  example = "A")
    private String correctAnswer;

    public String getPicUrl() {
        return picUrl;
    }

    public void setPicUrl(String picUrl) {
        this.picUrl = picUrl;
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

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTopicId() {
        return topicId;
    }

    public void setTopicId(Long topicId) {
        this.topicId = topicId;
    }

    public Integer getMode() {
        return mode;
    }

    public void setMode(Integer mode) {
        this.mode = mode;
    }

    public Long getNodeId() {
        return nodeId;
    }

    public void setNodeId(Long nodeId) {
        this.nodeId = nodeId;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
    }

    public Date getStartDate() {
        return startDate;
    }

    public void setStartDate(Date startDate) {
        this.startDate = startDate;
    }

    public Date getEndDate() {
        return endDate;
    }

    public void setEndDate(Date endDate) {
        this.endDate = endDate;
    }

    public String getCooperateDate() {
        return cooperateDate;
    }

    public void setCooperateDate(String cooperateDate) {
        this.cooperateDate = cooperateDate;
    }

    public String getActivityDesc() {
        return activityDesc;
    }

    public void setActivityDesc(String activityDesc) {
        this.activityDesc = activityDesc;
    }

    public Long getLimitNum() {
        return limitNum;
    }

    public void setLimitNum(Long limitNum) {
        this.limitNum = limitNum;
    }

    public String getRuleInstructions() {
        return ruleInstructions;
    }

    public void setRuleInstructions(String ruleInstructions) {
        this.ruleInstructions = ruleInstructions;
    }

    public Integer getValidationMethod() {
        return validationMethod;
    }

    public void setValidationMethod(Integer validationMethod) {
        this.validationMethod = validationMethod;
    }

    public String getQuestionName() {
        return questionName;
    }

    public void setQuestionName(String questionName) {
        this.questionName = questionName;
    }

    public String getQuestionAnswer() {
        return questionAnswer;
    }

    public void setQuestionAnswer(String questionAnswer) {
        this.questionAnswer = questionAnswer;
    }

    public String getFeedbackText() {
        return feedbackText;
    }

    public void setFeedbackText(String feedbackText) {
        this.feedbackText = feedbackText;
    }

    public Long getCouponId() {
        return couponId;
    }

    public void setCouponId(Long couponId) {
        this.couponId = couponId;
    }

    public String getQuestionA() {
        return questionA;
    }

    public void setQuestionA(String questionA) {
        this.questionA = questionA;
    }

    public String getQuestionB() {
        return questionB;
    }

    public void setQuestionB(String questionB) {
        this.questionB = questionB;
    }

    public String getQuestionC() {
        return questionC;
    }

    public void setQuestionC(String questionC) {
        this.questionC = questionC;
    }

    public String getQuestionD() {
        return questionD;
    }

    public void setQuestionD(String questionD) {
        this.questionD = questionD;
    }

    public String getCorrectAnswer() {
        return correctAnswer;
    }

    public void setCorrectAnswer(String correctAnswer) {
        this.correctAnswer = correctAnswer;
    }

}
