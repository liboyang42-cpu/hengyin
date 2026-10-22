package com.chengyinhub.business.exception;

/**
 * F3 套票业务拒绝(带稳定 businessCode)。
 *
 * <p>继承 IllegalStateException:核销链路(ApiRegistrationController.doRedeemExploreChapter)把
 * IllegalStateException 当「业务拒绝」原话透出、不记 error;套票余额不足/已过期/已约他店正属此类。
 */
public class FreeExploreBundleException extends IllegalStateException {
    private final String businessCode;

    public FreeExploreBundleException(String businessCode, String message) {
        super(message);
        this.businessCode = businessCode;
    }

    public String getBusinessCode() { return businessCode; }
}
