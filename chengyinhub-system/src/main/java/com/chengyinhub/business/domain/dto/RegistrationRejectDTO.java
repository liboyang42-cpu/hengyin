package com.chengyinhub.business.domain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 商家报名调配驳回请求。
 *
 * <p>只允许提交可追溯的驳回原因，报名状态仍由调配状态机统一变更。</p>
 */
public class RegistrationRejectDTO {

    @NotBlank(message = "请填写驳回原因")
    @Size(max = 200, message = "驳回原因不能超过200字")
    private String reason;

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
