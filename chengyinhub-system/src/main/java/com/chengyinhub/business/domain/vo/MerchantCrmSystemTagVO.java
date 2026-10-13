package com.chengyinhub.business.domain.vo;

/** 服务端依据真实报名事实派生的只读客户标签。 */
public class MerchantCrmSystemTagVO {
    private String code;
    private String label;

    public MerchantCrmSystemTagVO() { }

    public MerchantCrmSystemTagVO(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() { return code; }
    public void setCode(String value) { code = value; }
    public String getLabel() { return label; }
    public void setLabel(String value) { label = value; }
}
