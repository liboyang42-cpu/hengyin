package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.framework.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R9-48 补充:超限文案必须跟随**生效配置**,不是写死 10MB/20MB。
 *
 * <p>换一组与 application.yml 不同的限额(7MB/13MB)注入同一个 {@link GlobalExceptionHandler},
 * 直接调用其处理入口断言文案里出现的是这组生效值 —— 证明 {@code @Value} 读的是运行时配置。
 *
 * <p>★ 刻意不走 HTTP:真实 Tomcat 未设 max-swallow-size 时默认 2MB,超限连接会被容器断连、
 * 客户端拿不到业务体(实测 10MiB+1 即 Broken pipe)。用嵌入式容器断言这段文案,只能靠把
 * max-swallow-size 放大到 50MB 伪造 —— 那正是上一版被判 No-Go 的做法,这里不再重复。
 */
@SpringBootTest(classes = AudioUploadConfiguredLimitContractTest.ConfiguredLimitApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.servlet.multipart.max-file-size=7MB",
                "spring.servlet.multipart.max-request-size=13MB"
        })
class AudioUploadConfiguredLimitContractTest {

    @SpringBootConfiguration
    @Import(GlobalExceptionHandler.class)
    static class ConfiguredLimitApp {
    }

    @Autowired
    private GlobalExceptionHandler handler;

    @Test
    void actionableMessageReportsEffectiveConfiguredLimits() {
        MaxUploadSizeExceededException e = new MaxUploadSizeExceededException(
                7 * 1024 * 1024 + 1, new IllegalStateException("size limit"));
        AjaxResult res = handler.handleMaxUploadSizeExceeded(
                e, new MockHttpServletRequest("POST", "/api/common/uploadOSS"));
        String msg = String.valueOf(res.get("msg"));

        assertEquals(Integer.valueOf(400), res.get("code"));
        assertTrue(msg.contains("7MB"), "文案要报生效的单文件限额: " + msg);
        assertTrue(msg.contains("13MB"), "文案要报生效的总请求限额: " + msg);
        assertFalse(msg.contains("10MB"), "不得残留 application.yml 之外的写死限额: " + msg);
        assertFalse(msg.contains("系统繁忙"), msg);
    }
}
