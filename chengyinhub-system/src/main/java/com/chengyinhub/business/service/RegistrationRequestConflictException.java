package com.chengyinhub.business.service;

/** 同一 requestId 已绑定到不同资金语义，必须拒绝而不能返回旧订单。 */
public class RegistrationRequestConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RegistrationRequestConflictException(String message) {
        super(message);
    }
}
