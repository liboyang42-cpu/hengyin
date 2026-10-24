package com.chengyinhub.business.domain.dto;

import com.chengyinhub.common.annotation.Excel;
import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 提现申请-请求参数
 */
public class WithdrawalDTO  implements Serializable {
    private static final long serialVersionUID = 1L;


    /** 姓名 */
    @Schema(description = "姓名", example = "")
    @NotBlank(message = "姓名不能为空")
    private String realname;

    /** 银行名称 */
    @Schema(description = "银行名称", example = "")
    @NotBlank(message = "银行名称不能为空")
    private String bankName;

    /** 银行账号 */
    @Schema(description = "银行账号", example = "")
    @NotBlank(message = "银行账号不能为空")
    private String bankAccount;

    /** 电话 */
    @Schema(description = "电话", example = "18800000001")
    @NotBlank(message = "电话不能为空")
    private String mobilephone;

    /** 提现金额 */
    @Schema(description = "提现申请金额", example = "100")
    @NotNull(message = "提现申请金额不能为空")
    private BigDecimal withdrawalAmount;

    /** 每个银行卡提现意图的稳定幂等键；重试必须复用，服务端不生成。 */
    @Schema(description = "提现业务幂等键", example = "bank-withdraw-20260828-001")
    @NotBlank(message = "提现幂等键不能为空")
    @Size(max = 64, message = "提现幂等键长度不能超过64个字符")
    private String requestId;

    /** 已确认的资金预检 ID；与 requestId 和全部提现字段绑定。 */
    @Schema(description = "资金预检确认ID", example = "55")
    @NotNull(message = "资金确认不能为空")
    private Long challengeId;




    public String getRealname() {
        return realname;
    }

    public void setRealname(String realname) {
        this.realname = realname;
    }

    public String getBankName() {
        return bankName;
    }

    public void setBankName(String bankName) {
        this.bankName = bankName;
    }

    public String getBankAccount() {
        return bankAccount;
    }

    public void setBankAccount(String bankAccount) {
        this.bankAccount = bankAccount;
    }

    public String getMobilephone() {
        return mobilephone;
    }

    public void setMobilephone(String mobilephone) {
        this.mobilephone = mobilephone;
    }

    public BigDecimal getWithdrawalAmount() {
        return withdrawalAmount;
    }

    public void setWithdrawalAmount(BigDecimal withdrawalAmount) {
        this.withdrawalAmount = withdrawalAmount;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId == null || requestId.trim().isEmpty() ? null : requestId.trim();
    }

    public Long getChallengeId() {
        return challengeId;
    }

    public void setChallengeId(Long challengeId) {
        this.challengeId = challengeId;
    }
}
