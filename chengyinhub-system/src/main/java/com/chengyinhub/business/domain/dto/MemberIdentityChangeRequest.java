package com.chengyinhub.business.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;

/**
 * 发布者实名改绑申请(新姓名 + 新身份证号 + 变更原因 + 单独同意)。
 *
 * <p>与 {@link MemberIdentityRequest} 同一条纪律:带明文身份证号,不许出现在 {@code @Log} 接口上,
 * 也不许 toString 进日志。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemberIdentityChangeRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private String realName;
    private String idCard;
    private String reason;
    private Boolean consent;

    public String getRealName() { return realName; }
    public void setRealName(String realName) { this.realName = realName; }
    public String getIdCard() { return idCard; }
    public void setIdCard(String idCard) { this.idCard = idCard; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Boolean getConsent() { return consent; }
    public void setConsent(Boolean consent) { this.consent = consent; }
}
