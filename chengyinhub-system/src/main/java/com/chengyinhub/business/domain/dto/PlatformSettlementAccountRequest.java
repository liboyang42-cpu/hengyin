package com.chengyinhub.business.domain.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 更新活动平台分润的收款会员账户。 */
public class PlatformSettlementAccountRequest {

    @NotNull(message = "请选择平台收款会员账户")
    @Min(value = 1, message = "平台收款会员ID必须为正整数")
    private Long memberId;

    public Long getMemberId() {
        return memberId;
    }

    public void setMemberId(Long memberId) {
        this.memberId = memberId;
    }
}
