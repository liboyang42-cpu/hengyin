package com.chengyinhub.business.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public class BankWithdrawalPreflightRequest {
    @NotBlank(message = "提现幂等键不能为空")
    @Size(max = 64, message = "提现幂等键长度不能超过64个字符")
    private String requestId;
    @NotNull(message = "提现金额不能为空")
    @DecimalMin(value = "0", inclusive = false, message = "提现金额必须大于0")
    @Digits(integer = 12, fraction = 2, message = "提现金额最多12位整数和2位小数")
    private BigDecimal amount;
    @NotBlank(message = "姓名不能为空") @Size(max = 64, message = "姓名长度不能超过64个字符")
    private String realname;
    @NotBlank(message = "银行名称不能为空") @Size(max = 128, message = "银行名称长度不能超过128个字符")
    private String bankName;
    @NotBlank(message = "银行账号不能为空") @Size(max = 64, message = "银行账号长度不能超过64个字符")
    private String bankAccount;
    @NotBlank(message = "电话不能为空") @Size(max = 32, message = "电话长度不能超过32个字符")
    private String mobilephone;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = trimToNull(requestId); }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getRealname() { return realname; }
    public void setRealname(String realname) { this.realname = realname; }
    public String getBankName() { return bankName; }
    public void setBankName(String bankName) { this.bankName = bankName; }
    public String getBankAccount() { return bankAccount; }
    public void setBankAccount(String bankAccount) { this.bankAccount = bankAccount; }
    public String getMobilephone() { return mobilephone; }
    public void setMobilephone(String mobilephone) { this.mobilephone = mobilephone; }
    private String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
