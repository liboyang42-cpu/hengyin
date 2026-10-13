package com.chengyinhub.business.domain.vo;

import com.chengyinhub.common.annotation.Excel;
import java.util.Date;

/** 专用导出投影；手机号始终脱敏，禁止复用数据库实体。 */
public class MerchantCrmExportRowVO {
    @Excel(name = "客户ID")
    private String customerId;
    @Excel(name = "客户昵称")
    private String customerName;
    @Excel(name = "手机号（脱敏）")
    private String maskedPhone;
    @Excel(name = "来源")
    private String source;
    @Excel(name = "已核销")
    private Integer arrivedCount;
    @Excel(name = "待核销")
    private Integer pendingCount;
    @Excel(name = "退款")
    private Integer refundedCount;
    @Excel(name = "实付金额")
    private String paidAmount;
    @Excel(name = "最近互动", dateFormat = "yyyy-MM-dd HH:mm:ss")
    private Date lastTime;
    @Excel(name = "分群")
    private String segment;

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String value) { customerId = value; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String value) { customerName = value; }
    public String getMaskedPhone() { return maskedPhone; }
    public void setMaskedPhone(String value) { maskedPhone = value; }
    public String getSource() { return source; }
    public void setSource(String value) { source = value; }
    public Integer getArrivedCount() { return arrivedCount; }
    public void setArrivedCount(Integer value) { arrivedCount = value; }
    public Integer getPendingCount() { return pendingCount; }
    public void setPendingCount(Integer value) { pendingCount = value; }
    public Integer getRefundedCount() { return refundedCount; }
    public void setRefundedCount(Integer value) { refundedCount = value; }
    public String getPaidAmount() { return paidAmount; }
    public void setPaidAmount(String value) { paidAmount = value; }
    public Date getLastTime() { return lastTime; }
    public void setLastTime(Date value) { lastTime = value; }
    public String getSegment() { return segment; }
    public void setSegment(String value) { segment = value; }
}
