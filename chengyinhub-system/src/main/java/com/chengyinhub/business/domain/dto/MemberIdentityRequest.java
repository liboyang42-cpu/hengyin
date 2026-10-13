package com.chengyinhub.business.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;

/**
 * 发布者实名登记提交(姓名 + 身份证号 + 单独同意)。
 *
 * <p>这个对象带着明文身份证号,<b>不允许</b>出现在任何 {@code @Log} 标注的接口上、
 * 也不允许 toString 进日志 —— 登记接口刻意不带 @Log,原因就在这。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemberIdentityRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private String realName;
    private String idCard;
    private Boolean consent;
    /** 采集入口:club_apply / merchant_apply / topic_publish,服务端白名单校验。 */
    private String source;

    public String getRealName() { return realName; }
    public void setRealName(String realName) { this.realName = realName; }
    public String getIdCard() { return idCard; }
    public void setIdCard(String idCard) { this.idCard = idCard; }
    public Boolean getConsent() { return consent; }
    public void setConsent(Boolean consent) { this.consent = consent; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
