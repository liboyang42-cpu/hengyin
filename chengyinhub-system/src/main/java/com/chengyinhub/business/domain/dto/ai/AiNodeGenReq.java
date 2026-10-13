package com.chengyinhub.business.domain.dto.ai;

/**
 * M2 节点/谜题助手请求。
 */
public class AiNodeGenReq
{
    private String nodeName;
    private String address;
    /** 可选:当前主题上下文(标题/简介摘要) */
    private String themeContext;
    /** 可选:验证方式 1文字 2拍照 3选项 4扫码 5GPS 0/null=让AI选 */
    private Integer validationMethod;
    /** 可选:节点目的(拍照打卡/答题/谜题) */
    private String purpose;

    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getThemeContext() { return themeContext; }
    public void setThemeContext(String themeContext) { this.themeContext = themeContext; }
    public Integer getValidationMethod() { return validationMethod; }
    public void setValidationMethod(Integer validationMethod) { this.validationMethod = validationMethod; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
}
