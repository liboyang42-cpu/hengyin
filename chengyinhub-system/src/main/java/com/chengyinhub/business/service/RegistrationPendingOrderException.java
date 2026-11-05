package com.chengyinhub.business.service;

/**
 * j6 N1(第十二轮 k1 第 5 项):同一会员在同一场次上已有一笔还能被扣款的订单,或这一单已被之后付成功的另一笔取代。
 * 携带应把玩家带去的那笔订单 id,由结算应用服务映射成带 errorCode 与订单 id 的拒绝。
 */
public class RegistrationPendingOrderException extends RuntimeException {

    public static final String PENDING_ORDER_EXISTS = "PENDING_ORDER_EXISTS";
    public static final String ORDER_SUPERSEDED = "ORDER_SUPERSEDED";

    private final Long registrationId;
    private final String errorCode;

    public RegistrationPendingOrderException(String errorCode, Long registrationId, String message) {
        super(message);
        this.errorCode = errorCode;
        this.registrationId = registrationId;
    }

    public Long getRegistrationId() {
        return registrationId;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
