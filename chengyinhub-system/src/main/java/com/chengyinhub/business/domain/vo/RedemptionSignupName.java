package com.chengyinhub.business.domain.vo;

/**
 * M-27(2026-11-03 业主):商家核销台账行显示顾客报名时填写的姓名。
 * 只读投影:核销 id + 报名姓名密文(cms_registration.real_name),解密只在 Service 做,不进日志。
 */
public class RedemptionSignupName {
    private Long redemptionId;
    private String realName;

    public Long getRedemptionId() { return redemptionId; }
    public void setRedemptionId(Long value) { redemptionId = value; }
    public String getRealName() { return realName; }
    public void setRealName(String value) { realName = value; }

    @Override
    public String toString() {
        // 姓名不进 toString,避免被日志或异常带出。
        return "RedemptionSignupName{redemptionId=" + redemptionId + "}";
    }
}
