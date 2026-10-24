package com.chengyinhub.web.controller.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IRefundService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [退款转人工-不再静默] 「算不出来」的单被玩家看见时必须留痕 + 文案指向下一步。
 *
 * <p>病象:{@code RefundPolicy.evaluate} 缺锚点返 {@code manualReview=true},但读侧只把一句
 * 「需人工处理」拼进展示,后端零日志 —— 用户卡在这一句上,平台也不知道有多少单卡着。
 * 本类钉两件事:①订单详情出参的文案含「联系客服」;②读侧打 {@code [退款转人工]} 可查日志。
 */
class RefundManualReviewAlertTest {

    private static final Long REG = 8001L;
    private static final Long ACTIVITY = 900L;
    private static final Long MEMBER = 99L;

    private final Logger controllerLogger = (Logger) LoggerFactory.getLogger(com.chengyinhub.web.service.registration.RegistrationQueryApplication.class);
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    private Level originalLogLevel;
    private ApiRegistrationController controller;
    private CmsRegistration row;

    @BeforeEach
    void setUp() {
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        ICmsActivityService activities = mock(ICmsActivityService.class);

        controller = new ApiRegistrationController() {
            @Override
            public Long getAppUserId() {
                return MEMBER;
            }
        };

        row = new CmsRegistration();
        row.setId(REG);
        row.setMemberId(MEMBER);
        row.setOwnerType(2L);
        row.setOwnerId(ACTIVITY);
        row.setRegistrationStatus(2);
        row.setPaymentStatus(2);
        row.setVerificationStatus(0);

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        // 集合时间缺失 = 「算不出来」那一档(RefundPolicy.evaluate 转人工)。
        activity.setStartDate(null);

        when(registrations.selectCmsRegistrationById(REG)).thenReturn(row);
        when(activities.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "registrationService", registrations);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "activityService", activities);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "refundService", mock(IRefundService.class));

        originalLogLevel = controllerLogger.getLevel();
        logAppender.start();
        controllerLogger.setLevel(Level.WARN);
        controllerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        controllerLogger.detachAppender(logAppender);
        controllerLogger.setLevel(originalLogLevel);
        logAppender.stop();
    }

    private List<String> warns() {
        return logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
    }

    @Test
    void manualReviewOrder_isLoggedAndTellsNextStep() {
        assertEquals(200, RegistrationQueryTestFixtures.wire(controller).getRegistrationDetail(String.valueOf(REG)).get("code"));

        assertTrue(row.getRefundInfo().manualReview, "缺锚点必须落 manualReview 档");
        assertTrue(String.valueOf(row.getRefundDeadlineDisplay()).contains("联系客服"),
                "玩家看到的文案必须给出下一步,实际:" + row.getRefundDeadlineDisplay());

        assertTrue(warns().stream().anyMatch(m -> m.contains("[退款转人工]") && m.contains(String.valueOf(REG))),
                "转人工的单被读侧丢弃前必须留可查日志,实际日志:" + warns());
    }
}
