package com.chengyinhub.business.service.invoice;

/** DOC2 F7 用户发票的可映射业务拒绝，errorCode 供前端稳定分支。 */
public class UserInvoiceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public static final String ARGUMENT_INVALID = "INVOICE_ARGUMENT_INVALID";
    public static final String TITLE_TYPE_INVALID = "INVOICE_TITLE_TYPE_INVALID";
    public static final String TITLE_NAME_INVALID = "INVOICE_TITLE_NAME_INVALID";
    public static final String TAX_NO_INVALID = "INVOICE_TAX_NO_INVALID";
    public static final String TITLE_NOT_FOUND = "INVOICE_TITLE_NOT_FOUND";
    public static final String TITLE_LIMIT = "INVOICE_TITLE_LIMIT";
    public static final String CRYPTO_UNAVAILABLE = "INVOICE_CRYPTO_UNAVAILABLE";
    public static final String ORDERS_REQUIRED = "INVOICE_ORDERS_REQUIRED";
    public static final String ORDERS_TOO_MANY = "INVOICE_ORDERS_TOO_MANY";
    public static final String ORDER_NOT_ELIGIBLE = "INVOICE_ORDER_NOT_ELIGIBLE";
    public static final String ORDER_ALREADY_INVOICED = "INVOICE_ORDER_ALREADY_INVOICED";
    public static final String REQUEST_REUSED = "INVOICE_REQUEST_REUSED";
    public static final String REQUEST_IN_PROGRESS = "INVOICE_REQUEST_IN_PROGRESS";
    public static final String NOT_FOUND = "INVOICE_NOT_FOUND";
    public static final String STATE_CONFLICT = "INVOICE_STATE_CONFLICT";

    private final String errorCode;

    public UserInvoiceException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() { return errorCode; }
}
