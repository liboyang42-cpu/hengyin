package com.chengyinhub.business.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * F11 参考结算列表的响应合同(小程序与 iOS 共用,字段名不得改):
 * {@code {groups:[{activityId, activityTitle, payerName, openAmount, settledAmount, rows:[...]}],
 * totalOpenAmount, totalSettledAmount}}。没有数据时金额为 0.00(MC-011=B)。
 */
public class FeReferenceSettlementView {

    private List<Group> groups = new ArrayList<>();
    private BigDecimal totalOpenAmount = BigDecimal.ZERO.setScale(2);
    private BigDecimal totalSettledAmount = BigDecimal.ZERO.setScale(2);

    public List<Group> getGroups() { return groups; }
    public void setGroups(List<Group> groups) { this.groups = groups; }
    public BigDecimal getTotalOpenAmount() { return totalOpenAmount; }
    public void setTotalOpenAmount(BigDecimal totalOpenAmount) { this.totalOpenAmount = totalOpenAmount; }
    public BigDecimal getTotalSettledAmount() { return totalSettledAmount; }
    public void setTotalSettledAmount(BigDecimal totalSettledAmount) { this.totalSettledAmount = totalSettledAmount; }

    /** 一场(无场次的主题级购票 activityId 为 null)。 */
    public static class Group {
        private Long activityId;
        private String activityTitle;
        private String payerName;
        private BigDecimal openAmount = BigDecimal.ZERO.setScale(2);
        private BigDecimal settledAmount = BigDecimal.ZERO.setScale(2);
        private List<Row> rows = new ArrayList<>();

        public Long getActivityId() { return activityId; }
        public void setActivityId(Long activityId) { this.activityId = activityId; }
        public String getActivityTitle() { return activityTitle; }
        public void setActivityTitle(String activityTitle) { this.activityTitle = activityTitle; }
        public String getPayerName() { return payerName; }
        public void setPayerName(String payerName) { this.payerName = payerName; }
        public BigDecimal getOpenAmount() { return openAmount; }
        public void setOpenAmount(BigDecimal openAmount) { this.openAmount = openAmount; }
        public BigDecimal getSettledAmount() { return settledAmount; }
        public void setSettledAmount(BigDecimal settledAmount) { this.settledAmount = settledAmount; }
        public List<Row> getRows() { return rows; }
        public void setRows(List<Row> rows) { this.rows = rows; }
    }

    public static class Row {
        private Long id;
        private String chapterName;
        private String merchantName;
        private String supplierKind;
        private String basis;
        private Integer quantity;
        private BigDecimal unitAmount;
        private BigDecimal amount;
        private String status;
        private String statusText;
        private String voucherNo;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
        private Date settledAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getChapterName() { return chapterName; }
        public void setChapterName(String chapterName) { this.chapterName = chapterName; }
        public String getMerchantName() { return merchantName; }
        public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
        public String getSupplierKind() { return supplierKind; }
        public void setSupplierKind(String supplierKind) { this.supplierKind = supplierKind; }
        public String getBasis() { return basis; }
        public void setBasis(String basis) { this.basis = basis; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
        public BigDecimal getUnitAmount() { return unitAmount; }
        public void setUnitAmount(BigDecimal unitAmount) { this.unitAmount = unitAmount; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getStatusText() { return statusText; }
        public void setStatusText(String statusText) { this.statusText = statusText; }
        public String getVoucherNo() { return voucherNo; }
        public void setVoucherNo(String voucherNo) { this.voucherNo = voucherNo; }
        public Date getSettledAt() { return settledAt; }
        public void setSettledAt(Date settledAt) { this.settledAt = settledAt; }
    }
}
