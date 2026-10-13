package com.chengyinhub.business.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.mapper.RoamMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoamRecallServiceTest {
    private static final long HOUR_MS = 60L * 60L * 1000L;

    @Mock private RoamMapper roamMapper;
    @Mock private INotifyService notifyService;

    private RoamRecallService service;

    @BeforeEach
    void setUp() {
        service = new RoamRecallService();
        ReflectionTestUtils.setField(service, "roamMapper", roamMapper);
        ReflectionTestUtils.setField(service, "notifyService", notifyService);
        lenient().when(notifyService.isRoamRecallReady()).thenReturn(true);
    }

    @Test
    void disabledSwitchDoesNotReadOrSendCandidates() {
        ReflectionTestUtils.setField(service, "enabled", false);

        assertEquals(0, service.runDue(new Date(1000L)));

        verify(roamMapper, never()).selectRecallCandidates(any(), any(), any(), anyInt());
        verify(notifyService, never()).notifyRoamRecall(any(), anyInt(), anyInt());
    }

    @Test
    void missingTemplateDoesNotClaimAttemptsEvenWhenFeatureWasMisconfiguredOn() {
        ReflectionTestUtils.setField(service, "enabled", true);
        when(notifyService.isRoamRecallReady()).thenReturn(false);

        assertEquals(0, service.runDue(new Date(1000L)));

        verify(roamMapper, never()).selectRecallCandidates(any(), any(), any(), anyInt());
        verify(roamMapper, never()).claimRecallAttempt(any(), any());
    }

    /** 静默空跑是可观测性缺陷:未扫描/未发送必须留明确日志,不能只返回 0 冒充「扫描完成」。 */
    @Test
    void disabledSwitchLogsExplicitSkipInsteadOfSilentZero() {
        ReflectionTestUtils.setField(service, "enabled", false);
        // logback-test.xml 把 com.chengyinhub 压到 WARN,开关关闭是正常的 INFO 状态事件,测试里临时放开。
        Logger logger = (Logger) LoggerFactory.getLogger(RoamRecallService.class);
        Level previous = logger.getLevel();
        logger.setLevel(Level.INFO);
        ListAppender<ILoggingEvent> logs = attachAppender();
        try {
            assertEquals(0, service.runDue(new Date(1000L)));

            assertTrue(logs.list.stream().anyMatch(event ->
                            event.getFormattedMessage().contains("feature.roamRecall.enabled=false")
                                    && event.getFormattedMessage().contains("未扫描、未发送")),
                    "开关关闭时必须显式声明未扫描未发送,否则日志只剩「受理 0 条」冒充成功");
        } finally {
            detachAppender(logs);
            logger.setLevel(previous);
        }
    }

    @Test
    void missingTemplateLogsWarnSkipInsteadOfSilentZero() {
        ReflectionTestUtils.setField(service, "enabled", true);
        when(notifyService.isRoamRecallReady()).thenReturn(false);
        ListAppender<ILoggingEvent> logs = attachAppender();
        try {
            assertEquals(0, service.runDue(new Date(1000L)));

            assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                            && event.getFormattedMessage().contains("tplRoamRecall")
                            && event.getFormattedMessage().contains("未扫描、未发送")),
                    "开关已开而模板缺失 = 配置错误,必须 WARN 并声明未扫描未发送");
        } finally {
            detachAppender(logs);
        }
    }

    /** 负控:正常扫描路径不得出现「跳过」日志,否则上面的断言会被泛化日志蒙混通过。 */
    @Test
    void normalRunDoesNotEmitSkipLog() {
        ReflectionTestUtils.setField(service, "enabled", true);
        when(roamMapper.selectRecallCandidates(any(), any(), any(), eq(50)))
                .thenReturn(java.util.Collections.emptyList());
        ListAppender<ILoggingEvent> logs = attachAppender();
        try {
            assertEquals(0, service.runDue(new Date(2000L * HOUR_MS)));

            assertTrue(logs.list.stream()
                            .noneMatch(event -> event.getFormattedMessage().contains("跳过")),
                    "正常扫描路径不得输出跳过日志");
        } finally {
            detachAppender(logs);
        }
    }

    @Test
    void sendsOnlyClaimedCandidatesAndMarksOnlyWechatAcceptedMessages() {
        ReflectionTestUtils.setField(service, "enabled", true);
        Date now = new Date(1000L * HOUR_MS);
        RoamSession accepted = session(11L, 21L, 1200, 4);
        RoamSession rejected = session(12L, 22L, 800, 2);
        when(roamMapper.selectRecallCandidates(any(), any(), any(), eq(50))).thenReturn(Arrays.asList(accepted, rejected));
        when(roamMapper.claimRecallAttempt(eq(11L), any())).thenReturn(1);
        when(roamMapper.claimRecallAttempt(eq(12L), any())).thenReturn(1);
        when(notifyService.notifyRoamRecall(21L, 1200, 4)).thenReturn(true);
        when(notifyService.notifyRoamRecall(22L, 800, 2)).thenReturn(false);

        assertEquals(1, service.runDue(now));

        verify(roamMapper).markRecallSent(11L, now);
        verify(roamMapper, never()).markRecallSent(eq(12L), any());
    }

    @Test
    void queryWindowIsExactlyTwentyFourToSeventyTwoHoursAfterFinish() {
        ReflectionTestUtils.setField(service, "enabled", true);
        Date now = new Date(2000L * HOUR_MS);
        when(roamMapper.selectRecallCandidates(any(), any(), any(), eq(50))).thenReturn(java.util.Collections.emptyList());

        service.runDue(now);

        ArgumentCaptor<Date> from = ArgumentCaptor.forClass(Date.class);
        ArgumentCaptor<Date> to = ArgumentCaptor.forClass(Date.class);
        verify(roamMapper).selectRecallCandidates(from.capture(), to.capture(), any(), eq(50));
        assertEquals(now.getTime() - 72L * HOUR_MS, from.getValue().getTime());
        assertEquals(now.getTime() - 24L * HOUR_MS, to.getValue().getTime());
    }

    private static ListAppender<ILoggingEvent> attachAppender() {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(RoamRecallService.class)).addAppender(logs);
        return logs;
    }

    private static void detachAppender(ListAppender<ILoggingEvent> logs) {
        ((Logger) LoggerFactory.getLogger(RoamRecallService.class)).detachAppender(logs);
    }

    private static RoamSession session(Long id, Long memberId, int distanceM, int poisFound) {
        RoamSession session = new RoamSession();
        session.setId(id);
        session.setMemberId(memberId);
        session.setDistanceM(distanceM);
        session.setPoisFound(poisFound);
        return session;
    }
}
