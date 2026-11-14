package com.chengyinhub.business.platformpass;

/** 自玩票虚拟支付业务异常：code 供客户端机器识别，message 可直接展示。 */
public class SelfPlayXpayException extends RuntimeException {
    private final String code;

    public SelfPlayXpayException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() { return code; }
}
