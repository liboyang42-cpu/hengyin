package com.chengyinhub.business.domain.dto;

/**
 * 平台后台撤销券核销的请求体。
 *
 * <p>撤销原因是留痕本体:没有原因就不许撤(服务端会连同操作者账号落不可变审计)。
 */
public class CouponReverseRequest {
    /** 撤销原因,必填,≤255。 */
    private String reason;

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
