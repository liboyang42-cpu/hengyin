package com.chengyinhub.framework.web.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual DispatcherServlet route/resource resolution, with a real business 500 as a negative control. */
class UnknownRouteMvcContractTest {
    @Test
    void missingRouteAndMissingStaticResourceAre404WithoutSystemErrorStack() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        Level old = logger.getLevel();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start(); logger.setLevel(Level.DEBUG); logger.addAppender(logs);
        try (AnnotationConfigWebApplicationContext context = context()) {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/api/path-that-does-not-exist"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404))
                    .andExpect(jsonPath("$.msg").value("请求路径不存在"));
            mvc.perform(get("/assets/not-present.png"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
            assertTrue(logs.list.stream().noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.ERROR)));
            assertTrue(logs.list.stream().allMatch(event -> event.getThrowableProxy() == null));
            mvc.perform(get("/fixture/business-failure"))
                    .andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.msg").value("真实业务失败"));
            assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR
                    && event.getThrowableProxy() != null), "真实业务错误仍保留既有500和错误诊断");
        } finally {
            logger.detachAppender(logs); logger.setLevel(old); logs.stop();
        }
    }

    private AnnotationConfigWebApplicationContext context() {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(App.class); context.refresh();
        return context;
    }

    @Configuration @EnableWebMvc
    static class App implements WebMvcConfigurer {
        @Bean GlobalExceptionHandler errors() { return new GlobalExceptionHandler(); }
        @Bean BusinessFailureController business() { return new BusinessFailureController(); }
        @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/assets/**").addResourceLocations("classpath:/missing-fixture-resources/");
        }
    }
    @RestController
    static class BusinessFailureController {
        @GetMapping("/fixture/business-failure")
        public String fail() { throw new IllegalStateException("真实业务失败"); }
    }
}
