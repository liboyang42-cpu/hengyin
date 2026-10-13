package com.chengyinhub.business.service;

/**
 * 支付窗口已过的待支付报名单。支付口拒绝继续支付,并即时触发关单释放库存/积分/权益。
 */
public class RegistrationOrderExpiredException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RegistrationOrderExpiredException(String message) {
        super(message);
    }
}
