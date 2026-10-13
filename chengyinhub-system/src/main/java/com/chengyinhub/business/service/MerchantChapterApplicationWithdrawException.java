package com.chengyinhub.business.service;

/** 商家撤回章节承接申请失败，供 API 返回稳定错误码。 */
public class MerchantChapterApplicationWithdrawException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public static final String ERROR_CODE_NOT_FOUND = "CHAPTER_APPLICATION_NOT_FOUND";
    public static final String ERROR_CODE_NOT_OWNER = "CHAPTER_APPLICATION_NOT_OWNER";
    public static final String ERROR_CODE_FINALIZED = "CHAPTER_APPLICATION_FINALIZED";
    public static final String ERROR_CODE_STATE_CHANGED = "CHAPTER_APPLICATION_STATE_CHANGED";

    private final String errorCode;

    public MerchantChapterApplicationWithdrawException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
