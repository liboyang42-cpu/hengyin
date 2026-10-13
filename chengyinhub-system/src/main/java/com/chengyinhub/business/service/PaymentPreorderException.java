package com.chengyinhub.business.service;

/**
 * 微信预下单失败分类。结果不确定表示请求可能已经被微信受理，本地必须保持 PAYING 等待回调恢复。
 */
public final class PaymentPreorderException extends RegistrationCheckoutException {
    private final boolean resultUncertain;

    private PaymentPreorderException(String message, Throwable cause, boolean resultUncertain) {
        super(500, message, cause);
        this.resultUncertain = resultUncertain;
    }

    public static PaymentPreorderException definiteFailure(String message, Throwable cause) {
        return new PaymentPreorderException(message, cause, false);
    }

    public static PaymentPreorderException resultUncertain(String message, Throwable cause) {
        return new PaymentPreorderException(message, cause, true);
    }

    public boolean isResultUncertain() {
        return resultUncertain;
    }
}
