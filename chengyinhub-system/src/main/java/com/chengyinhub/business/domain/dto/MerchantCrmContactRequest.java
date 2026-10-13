package com.chengyinhub.business.domain.dto;

/** F15「获取联系号码」请求体。用途只允许 call / copy,写进访问审计并参与操作员日频控。 */
public class MerchantCrmContactRequest {
    private String purpose;

    public String getPurpose() { return purpose; }
    public void setPurpose(String value) { purpose = value; }
}
