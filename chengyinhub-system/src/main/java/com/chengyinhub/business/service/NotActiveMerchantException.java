package com.chengyinhub.business.service;

/**
 * 当前主体不是审核通过且启用的商家。
 *
 * <p>继承 IllegalStateException:既有按 ISE 透出文案的调用点行为不变;需要让前端**稳定区分**
 * 「本来就不是商家」与「服务故障」的端点(如 /api/official/merchant-invites)单独捕获它,
 * 下发 {@link #ERROR_CODE},前端只认这个码,不再按文案正则猜身份。
 */
public class NotActiveMerchantException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public static final String ERROR_CODE = "NOT_ACTIVE_MERCHANT";

    public NotActiveMerchantException(String message) {
        super(message);
    }
}
