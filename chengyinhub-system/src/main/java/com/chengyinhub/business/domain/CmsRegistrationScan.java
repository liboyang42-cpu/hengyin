package com.chengyinhub.business.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 商家扫码记录对象 cms_registration_scan
 * 
 * @author ruoyi
 * @date 2025-10-17
 */
public class CmsRegistrationScan extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** ID */
    private String id;

    /** 报名ID */
    @Excel(name = "报名ID")
    private Long registrationId;

    /** 商家用户ID */
    @Excel(name = "商家用户ID")
    private Long memberId;

    /** 验证方式:1、文字验证 2、图片验证  3、选项验证 */
    @Excel(name = "验证方式:1、文字验证 2、图片验证  3、选项验证")
    private Integer validationMethod;

    /** 问题答案 */
    @Excel(name = "问题答案")
    private String questionAnswer;

    /** 反馈文字 */
    @Excel(name = "反馈文字")
    private String feedbackText;


    /** 状态 0未验证 1已验证 */
    @Excel(name = "状态 0未验证 1已验证")
    private Long status;

    private CmsRegistrationMerchant registrationMerchant;


    private CmsMemberTemplate memberTemplate;

    private SmsCoupon smsCoupon ;

    private  MmsMerchant mmsMerchant;

    private  Integer registrationType;

    private String name;

    private String address;

    private String latitude;

    private String longitude;

    private  Long templateId;

    public Long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
    }

    public Integer getRegistrationType() {
        return registrationType;
    }

    public void setRegistrationType(Integer registrationType) {
        this.registrationType = registrationType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getLatitude() {
        return latitude;
    }

    public void setLatitude(String latitude) {
        this.latitude = latitude;
    }

    public String getLongitude() {
        return longitude;
    }

    public void setLongitude(String longitude) {
        this.longitude = longitude;
    }

    public MmsMerchant getMmsMerchant() {
        return mmsMerchant;
    }

    public void setMmsMerchant(MmsMerchant mmsMerchant) {
        this.mmsMerchant = mmsMerchant;
    }

    public SmsCoupon getSmsCoupon() {
        return smsCoupon;
    }

    public void setSmsCoupon(SmsCoupon smsCoupon) {
        this.smsCoupon = smsCoupon;
    }

    public CmsRegistrationMerchant getRegistrationMerchant() {
        return registrationMerchant;
    }

    public void setRegistrationMerchant(CmsRegistrationMerchant registrationMerchant) {
        this.registrationMerchant = registrationMerchant;
    }

    public CmsMemberTemplate getMemberTemplate() {
        return memberTemplate;
    }

    public void setMemberTemplate(CmsMemberTemplate memberTemplate) {
        this.memberTemplate = memberTemplate;
    }

    public void setId(String id)
    {
        this.id = id;
    }

    public String getId() 
    {
        return id;
    }

    public void setRegistrationId(Long registrationId) 
    {
        this.registrationId = registrationId;
    }

    public Long getRegistrationId() 
    {
        return registrationId;
    }

    public void setMemberId(Long memberId) 
    {
        this.memberId = memberId;
    }

    public Long getMemberId() 
    {
        return memberId;
    }

    public void setValidationMethod(Integer validationMethod) 
    {
        this.validationMethod = validationMethod;
    }

    public Integer getValidationMethod() 
    {
        return validationMethod;
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

    public void setStatus(Long status) 
    {
        this.status = status;
    }

    public Long getStatus() 
    {
        return status;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("registrationId", getRegistrationId())
            .append("memberId", getMemberId())
            .append("validationMethod", getValidationMethod())
            .append("questionAnswer", getQuestionAnswer())
            .append("feedbackText", getFeedbackText())
            .append("status", getStatus())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}
