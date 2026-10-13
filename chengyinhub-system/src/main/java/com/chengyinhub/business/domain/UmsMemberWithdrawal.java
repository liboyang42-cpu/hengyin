package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 用户提现申请对象 ums_member_withdrawal
 * 
 * @author webxh
 * @date 2025-10-20
 */
public class UmsMemberWithdrawal extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    private Long id;

    /** 用户ID */
    @Excel(name = "用户ID")
    private Long memberId;

    /** 客户端业务幂等键；同一会员内唯一且强制非空。 */
    private String requestId;

    /** 规范化请求 SHA-256；同 key 异参数必须拒绝。 */
    private String requestFingerprint;

    /** 姓名 */
    private String realname;

    /** 银行名称 */
    private String bankName;

    /** 银行账号 */
    private String bankAccount;

    /** 电话 */
    private String mobilephone;

    /** 敏感资料密文，仅服务端回填和受控出款流程使用 */
    private String realnameEncrypted;
    private String bankNameEncrypted;
    private String bankAccountEncrypted;
    private String mobilephoneEncrypted;

    /** 对外展示掩码 */
    private String realnameMask;
    private String bankNameMask;
    private String bankAccountMask;
    private String mobilephoneMask;

    /** 敏感资料密文版本 */
    private Integer piiCipherVersion;

    /** 历史明文清除完成时间 */
    private Date piiMigratedAt;

    /** 提现金额 */
    @Excel(name = "提现金额")
    private BigDecimal withdrawalAmount;

    /** 提现手续费 */
    @Excel(name = "提现手续费")
    private BigDecimal serviceFee;

    /** 实际到账金额 */
    @Excel(name = "实际到账金额")
    private BigDecimal receivedAmount;

    /** 状态：0待审核 1审核通过 2审核失败 3已打款 */
    @Excel(name = "状态：0待审核 1审核通过 2审核失败 3已打款")
    private Long status;

    /** 银行流水号/打款凭据 */
    @Excel(name = "银行流水号/打款凭据")
    private String payoutReference;

    /** 删除标记 0 代表存在  2删除 */
    private Long delFlag;

    /** 审核时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "审核时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date auditTime;

    /** 审核人 */
    @Excel(name = "审核人")
    private Long auditBy;

    /** 处理人 */
    @Excel(name = "处理人")
    private Long handleBy;

    /** 处理时间（状态 3 时为打款确认时间） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Excel(name = "处理时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    private Date handleTime;

    public void setId(Long id) 
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    public void setMemberId(Long memberId) 
    {
        this.memberId = memberId;
    }

    public Long getMemberId() 
    {
        return memberId;
    }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    @JsonIgnore
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }

    public void setRealname(String realname) 
    {
        this.realname = realname;
    }

    public String getRealname() 
    {
        return realname;
    }

    public void setBankName(String bankName) 
    {
        this.bankName = bankName;
    }

    public String getBankName() 
    {
        return bankName;
    }

    public void setBankAccount(String bankAccount) 
    {
        this.bankAccount = bankAccount;
    }

    public String getBankAccount() 
    {
        return bankAccount;
    }

    public void setMobilephone(String mobilephone) 
    {
        this.mobilephone = mobilephone;
    }

    public String getMobilephone() 
    {
        return mobilephone;
    }

    @JsonIgnore
    public String getRealnameEncrypted() { return realnameEncrypted; }
    public void setRealnameEncrypted(String realnameEncrypted) { this.realnameEncrypted = realnameEncrypted; }

    @JsonIgnore
    public String getBankNameEncrypted() { return bankNameEncrypted; }
    public void setBankNameEncrypted(String bankNameEncrypted) { this.bankNameEncrypted = bankNameEncrypted; }

    @JsonIgnore
    public String getBankAccountEncrypted() { return bankAccountEncrypted; }
    public void setBankAccountEncrypted(String bankAccountEncrypted) { this.bankAccountEncrypted = bankAccountEncrypted; }

    @JsonIgnore
    public String getMobilephoneEncrypted() { return mobilephoneEncrypted; }
    public void setMobilephoneEncrypted(String mobilephoneEncrypted) { this.mobilephoneEncrypted = mobilephoneEncrypted; }

    public String getRealnameMask() { return realnameMask; }
    public void setRealnameMask(String realnameMask) { this.realnameMask = realnameMask; }
    public String getBankNameMask() { return bankNameMask; }
    public void setBankNameMask(String bankNameMask) { this.bankNameMask = bankNameMask; }
    public String getBankAccountMask() { return bankAccountMask; }
    public void setBankAccountMask(String bankAccountMask) { this.bankAccountMask = bankAccountMask; }
    public String getMobilephoneMask() { return mobilephoneMask; }
    public void setMobilephoneMask(String mobilephoneMask) { this.mobilephoneMask = mobilephoneMask; }
    public Integer getPiiCipherVersion() { return piiCipherVersion; }
    public void setPiiCipherVersion(Integer piiCipherVersion) { this.piiCipherVersion = piiCipherVersion; }
    public Date getPiiMigratedAt() { return piiMigratedAt; }
    public void setPiiMigratedAt(Date piiMigratedAt) { this.piiMigratedAt = piiMigratedAt; }

    public void setWithdrawalAmount(BigDecimal withdrawalAmount) 
    {
        this.withdrawalAmount = withdrawalAmount;
    }

    public BigDecimal getWithdrawalAmount() 
    {
        return withdrawalAmount;
    }

    public void setServiceFee(BigDecimal serviceFee) 
    {
        this.serviceFee = serviceFee;
    }

    public BigDecimal getServiceFee() 
    {
        return serviceFee;
    }

    public void setReceivedAmount(BigDecimal receivedAmount) 
    {
        this.receivedAmount = receivedAmount;
    }

    public BigDecimal getReceivedAmount() 
    {
        return receivedAmount;
    }

    public void setStatus(Long status) 
    {
        this.status = status;
    }

    public Long getStatus() 
    {
        return status;
    }

    public String getPayoutReference()
    {
        return payoutReference;
    }

    public void setPayoutReference(String payoutReference)
    {
        this.payoutReference = payoutReference;
    }

    public void setDelFlag(Long delFlag) 
    {
        this.delFlag = delFlag;
    }

    public Long getDelFlag() 
    {
        return delFlag;
    }

    public void setAuditTime(Date auditTime) 
    {
        this.auditTime = auditTime;
    }

    public Date getAuditTime() 
    {
        return auditTime;
    }

    public void setAuditBy(Long auditBy) 
    {
        this.auditBy = auditBy;
    }

    public Long getAuditBy() 
    {
        return auditBy;
    }

    public void setHandleBy(Long handleBy) 
    {
        this.handleBy = handleBy;
    }

    public Long getHandleBy() 
    {
        return handleBy;
    }

    public void setHandleTime(Date handleTime) 
    {
        this.handleTime = handleTime;
    }

    public Date getHandleTime() 
    {
        return handleTime;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("memberId", getMemberId())
            .append("withdrawalAmount", getWithdrawalAmount())
            .append("serviceFee", getServiceFee())
            .append("receivedAmount", getReceivedAmount())
            .append("status", getStatus())
            .append("payoutReference", getPayoutReference())
            .append("delFlag", getDelFlag())
            .append("remark", getRemark())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("auditTime", getAuditTime())
            .append("auditBy", getAuditBy())
            .append("handleBy", getHandleBy())
            .append("handleTime", getHandleTime())
            .toString();
    }
}
