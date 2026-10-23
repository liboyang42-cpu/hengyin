package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * DOC2 F7 用户发票申请单：一张申请可合并同一会员的多笔已支付订单。
 * 抬头字段是申请时的快照（同样落密文），之后改/删抬头不影响已提交的申请。
 */
public class UserInvoiceApplication {
    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_ISSUED = "ISSUED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private Long id;
    private String applicationNo;
    private Long memberId;
    private String requestId;
    private Long titleId;
    private String titleType;
    private String titleName;
    private String taxNo;
    private BigDecimal totalAmount;
    private Integer orderCount;
    private String status;
    private String provider;
    private String invoiceNo;
    private String rejectReason;
    private Date issuedAt;
    private Date createTime;
    private Date updateTime;
    private List<UserInvoiceOrderLine> orders = new ArrayList<>();

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public String getApplicationNo() { return applicationNo; } public void setApplicationNo(String v) { applicationNo = v; }
    public Long getMemberId() { return memberId; } public void setMemberId(Long v) { memberId = v; }
    public String getRequestId() { return requestId; } public void setRequestId(String v) { requestId = v; }
    public Long getTitleId() { return titleId; } public void setTitleId(Long v) { titleId = v; }
    public String getTitleType() { return titleType; } public void setTitleType(String v) { titleType = v; }
    public String getTitleName() { return titleName; } public void setTitleName(String v) { titleName = v; }
    public String getTaxNo() { return taxNo; } public void setTaxNo(String v) { taxNo = v; }
    public BigDecimal getTotalAmount() { return totalAmount; } public void setTotalAmount(BigDecimal v) { totalAmount = v; }
    public Integer getOrderCount() { return orderCount; } public void setOrderCount(Integer v) { orderCount = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public String getProvider() { return provider; } public void setProvider(String v) { provider = v; }
    public String getInvoiceNo() { return invoiceNo; } public void setInvoiceNo(String v) { invoiceNo = v; }
    public String getRejectReason() { return rejectReason; } public void setRejectReason(String v) { rejectReason = v; }
    public Date getIssuedAt() { return issuedAt; } public void setIssuedAt(Date v) { issuedAt = v; }
    public Date getCreateTime() { return createTime; } public void setCreateTime(Date v) { createTime = v; }
    public Date getUpdateTime() { return updateTime; } public void setUpdateTime(Date v) { updateTime = v; }
    public List<UserInvoiceOrderLine> getOrders() { return orders; }
    public void setOrders(List<UserInvoiceOrderLine> v) { orders = v == null ? new ArrayList<>() : v; }
}
