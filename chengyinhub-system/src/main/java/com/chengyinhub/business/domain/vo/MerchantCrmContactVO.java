package com.chengyinhub.business.domain.vo;

/**
 * F15「获取联系号码」响应。号码只在权限、归属、同意三道闸全过且未超日限时下发,
 * 明文不进列表、导出或审计正文;每次下发都由服务端留一条 business_audit_event。
 */
public class MerchantCrmContactVO {
    private String phone;

    public String getPhone() { return phone; }
    public void setPhone(String value) { phone = value; }
}
