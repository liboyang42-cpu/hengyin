package com.chengyinhub.business.service;

/** 同一商家对同一章节的承接申请已存在。 */
public class MerchantChapterApplicationExistsException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public static final String ERROR_CODE = "CHAPTER_APPLICATION_EXISTS";

    public MerchantChapterApplicationExistsException(String message) {
        super(message);
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }
}
