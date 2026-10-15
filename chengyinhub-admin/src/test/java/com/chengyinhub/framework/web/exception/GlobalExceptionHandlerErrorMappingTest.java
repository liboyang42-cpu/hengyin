package com.chengyinhub.framework.web.exception;

import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 终审 §12.3-3 P0「错误映射层」负控:未预期的系统异常不得把类名/包路径/堆栈/SQL/请求路径
 * 等内部细节原样吐给用户;业务方用 RuntimeException 抛出的中文短句必须保留。
 *
 * <p>直接调用 handler 方法(不依赖 Spring Security AOP),对消息收敛逻辑做确定性断言。
 */
class GlobalExceptionHandlerErrorMappingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/api/some/path");
    }

    private String msgOf(AjaxResult result) {
        return String.valueOf(result.get("msg"));
    }

    @Test
    void classCastLeak_isMaskedToGeneric() {
        RuntimeException e = new RuntimeException(
                "class java.math.BigDecimal cannot be cast to class java.lang.Double");
        AjaxResult res = handler.handleRuntimeException(e, request());
        assertEquals("系统繁忙，请稍后重试", msgOf(res));
    }

    @Test
    void sqlLeak_isMaskedToGeneric() {
        RuntimeException e = new RuntimeException(
                "Error querying database.  Cause: java.sql.SQLSyntaxErrorException: Unknown column 'x'");
        AjaxResult res = handler.handleRuntimeException(e, request());
        assertEquals("系统繁忙，请稍后重试", msgOf(res));
    }

    @Test
    void requestUriInMessage_isMaskedToGeneric() {
        RuntimeException e = new RuntimeException("请求访问：/api/coupon/qr-token，认证失败，无法访问系统资源");
        AjaxResult res = handler.handleRuntimeException(e, request());
        assertEquals("系统繁忙，请稍后重试", msgOf(res));
    }

    @Test
    void stackTraceSnippet_isMaskedToGeneric() {
        RuntimeException e = new RuntimeException(
                "nested exception is java.lang.NullPointerException at com.chengyinhub.business.service.impl.XxxServiceImpl");
        AjaxResult res = handler.handleRuntimeException(e, request());
        assertEquals("系统繁忙，请稍后重试", msgOf(res));
    }

    @Test
    void nullMessage_isMaskedToGeneric() {
        AjaxResult res = handler.handleRuntimeException(new RuntimeException(), request());
        assertEquals("系统繁忙，请稍后重试", msgOf(res));
    }

    @Test
    void overlongTechnicalMessage_isMaskedToGeneric() {
        StringBuilder sb = new StringBuilder("Internal failure: ");
        for (int i = 0; i < 40; i++) {
            sb.append("org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:230) ");
        }
        RuntimeException e = new RuntimeException(sb.toString());
        AjaxResult res = handler.handleRuntimeException(e, request());
        assertEquals("系统繁忙，请稍后重试", msgOf(res));
    }

    @Test
    void genericExceptionPath_alsoMasksLeak() {
        Exception e = new Exception("Cannot get a connection to the database: com.mysql.cj.jdbc.exceptions");
        AjaxResult res = handler.handleException(e, request());
        assertEquals("系统繁忙，请稍后重试", msgOf(res));
    }

    @Test
    void serviceExceptionTechnicalLeak_isMaskedToGeneric() {
        ServiceException e = new ServiceException("请求访问：/api/coupon/qr-token，认证失败，无法访问系统资源", 500);
        AjaxResult res = handler.handleServiceException(e, request());
        assertEquals("系统繁忙，请稍后重试", msgOf(res));
    }

    @Test
    void chineseBusinessMessage_isPreserved() {
        RuntimeException e = new RuntimeException("库存不足,仅剩 3 张");
        AjaxResult res = handler.handleRuntimeException(e, request());
        assertEquals("库存不足,仅剩 3 张", msgOf(res));
    }

    @Test
    void chineseFundsMessage_isPreserved() {
        RuntimeException e = new RuntimeException("有提现正在处理中,请等到账后再试");
        AjaxResult res = handler.handleRuntimeException(e, request());
        assertEquals("有提现正在处理中,请等到账后再试", msgOf(res));
    }

    @Test
    void chineseValidationMessage_isPreserved() {
        RuntimeException e = new RuntimeException("最低提现 ¥10");
        AjaxResult res = handler.handleRuntimeException(e, request());
        assertEquals("最低提现 ¥10", msgOf(res));
    }

    @Test
    void typeMismatch_doesNotLeakFullyQualifiedTypeName() {
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e =
                new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException(
                        "abc", java.lang.Long.class, "age", null, null);
        AjaxResult res = handler.handleMethodArgumentTypeMismatchException(e, request());
        String msg = msgOf(res);
        assertEquals(false, msg.contains("java.lang.Long"), "不得暴露全限定类型名,实际:" + msg);
        assertEquals(true, msg.contains("'Long'"), "应保留简单类型名,实际:" + msg);
    }

    @Test
    void methodNotSupported_leaksNothingTechnical() {
        org.springframework.web.HttpRequestMethodNotSupportedException e =
                new org.springframework.web.HttpRequestMethodNotSupportedException("GET", java.util.Collections.singletonList("POST"));
        AjaxResult res = handler.handleHttpRequestMethodNotSupported(e, request());
        String msg = msgOf(res);
        assertEquals(false, msg.contains("/api/"), "不得包含请求路径,实际:" + msg);
        assertEquals(false, msg.contains("Exception"), "不得暴露异常类名,实际:" + msg);
    }

    @Test
    void uploadSizeExceeded_returnsActionableLimitNotGenericSystemBusy() {
        org.springframework.web.multipart.MaxUploadSizeExceededException e =
                new org.springframework.web.multipart.MaxUploadSizeExceededException(10 * 1024 * 1024,
                        new IllegalStateException(
                                "org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException:"
                                        + " The field file exceeds its maximum permitted size of 10485760 bytes."));

        AjaxResult res = handler.handleMaxUploadSizeExceeded(e, request());
        String msg = msgOf(res);

        assertEquals(Integer.valueOf(HttpStatus.BAD_REQUEST), res.get("code"));
        assertFalse(msg.contains("系统繁忙"), "大小超限不得再收敛成系统繁忙:" + msg);
        assertTrue(msg.contains("文件过大"), "要点明文件过大:" + msg);
        assertTrue(msg.contains("压缩") || msg.contains("更换"), "要给压缩/换文件路径:" + msg);
        assertTrue(msg.contains("10MB"), "要写明单文件真正适用限额:" + msg);
        assertTrue(msg.contains("20MB"), "要写明总请求真正适用限额:" + msg);
        assertFalse(msg.contains("Exception"), "不得泄漏技术细节:" + msg);
    }

    @Test
    void negativeControl_withoutDedicatedBranchUploadSizeLosesActionableContract() {
        org.springframework.web.multipart.MaxUploadSizeExceededException e =
                new org.springframework.web.multipart.MaxUploadSizeExceededException(10 * 1024 * 1024,
                        new IllegalStateException(
                                "org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException:"
                                        + " The field file exceeds its maximum permitted size of 10485760 bytes."));

        // 负控:绕过专用分支仍然丢失 400/10MB/20MB 的可操作契约。
        // Spring 7 的异常 message 不再拼接 cause；不能用旧框架的偶然文案判断是否走对分支。
        AjaxResult res = handler.handleRuntimeException(e, request());

        assertEquals(Integer.valueOf(HttpStatus.ERROR), res.get("code"));
        assertFalse(msgOf(res).contains("10MB"));
        assertFalse(msgOf(res).contains("20MB"));
        assertFalse(msgOf(res).contains("Exception"));
    }

    @Test
    void serviceExceptionWithConflictCode_returnsConflictBusinessCode() {
        ServiceException e = new ServiceException("路线状态已更新，请刷新后重试", HttpStatus.CONFLICT);

        AjaxResult res = handler.handleServiceException(e, request());

        assertEquals(Integer.valueOf(HttpStatus.CONFLICT), res.get("code"));
        assertEquals("路线状态已更新，请刷新后重试", msgOf(res));
    }

    @Test
    void serviceExceptionWithoutCode_keepsGenericErrorCode() {
        ServiceException e = new ServiceException("答案不正确");

        AjaxResult res = handler.handleServiceException(e, request());

        assertEquals(Integer.valueOf(HttpStatus.ERROR), res.get("code"));
        assertEquals("答案不正确", msgOf(res));
    }
}
