package com.chengyinhub.web.controller.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class LogAttemptFailureTest {

    private static final Long MEMBER = 88L;
    private static final Long ACTIVITY = 500L;
    private static final Long TOPIC = 300L;
    private static final Long NODE = 401L;

    private ApiPlayProgressController controller;
    private PlayAttemptLogMapper attemptLogMapper;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        attemptLogMapper = mock(PlayAttemptLogMapper.class);
        controller = new ApiPlayProgressController();
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playAttemptLogMapper", attemptLogMapper);

        logger = (Logger) LoggerFactory.getLogger(com.chengyinhub.web.service.play.PlayNodeCompletionApplication.class);
        originalLevel = logger.getLevel();
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(originalLevel);
        appender.stop();
    }

    private CmsTopicNode node() {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(NODE);
        n.setTopicId(TOPIC);
        return n;
    }

    private void invokeLogAttempt(Long memberId, CmsTopicNode node, Long actId,
                                   int attemptType, int result, String wrongAnswer) {
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.invokeMethod(controller, "logAttempt",
                memberId, node, actId, attemptType, result, wrongAnswer);
    }

    private List<ILoggingEvent> events() {
        return appender.list;
    }

    @Test
    void mapperThrows_logsWarningWithStructuredFields() {
        doThrow(new RuntimeException("DB连接失败")).when(attemptLogMapper)
                .insertPlayAttemptLog(any());

        invokeLogAttempt(MEMBER, node(), ACTIVITY, 1, 0, null);

        assertEquals(1, events().size(), "mapper异常时应产生一条WARN日志");
        ILoggingEvent ev = events().get(0);
        assertEquals(Level.WARN, ev.getLevel());
        String msg = ev.getFormattedMessage();
        assertTrue(msg.contains("member=88"), "日志应包含memberId");
        assertTrue(msg.contains("activity=500"), "日志应包含activityId");
        assertTrue(msg.contains("topic=300"), "日志应包含topicId");
        assertTrue(msg.contains("node=401"), "日志应包含nodeId");
        assertTrue(msg.contains("attemptType=1"), "日志应包含attemptType");
        assertTrue(msg.contains("result=0"), "日志应包含result");
    }

    @Test
    void mapperSucceeds_noLogGenerated() {
        invokeLogAttempt(MEMBER, node(), ACTIVITY, 2, 1, null);

        assertTrue(events().isEmpty(), "mapper成功时不应产生任何日志");
    }

    @Test
    void mapperThrows_errorTypeIncludedInLog() {
        doThrow(new IllegalArgumentException("节点不存在")).when(attemptLogMapper)
                .insertPlayAttemptLog(any());

        invokeLogAttempt(MEMBER, node(), ACTIVITY, 3, 1, null);

        assertEquals(1, events().size());
        String msg = events().get(0).getFormattedMessage();
        assertTrue(msg.contains("errorType=IllegalArgumentException"),
                "日志应包含异常类型 short name");
    }

    @Test
    void mapperThrows_wrongAnswerNotLeakedInLog() {
        String secretWrongAnswer = "习近平的秘密答案";
        doThrow(new RuntimeException("DB异常")).when(attemptLogMapper)
                .insertPlayAttemptLog(any());

        invokeLogAttempt(MEMBER, node(), ACTIVITY, 1, 0, secretWrongAnswer);

        assertEquals(1, events().size());
        String msg = events().get(0).getFormattedMessage();
        assertFalse(msg.contains(secretWrongAnswer),
                "错误答案严禁出现在日志中");
        assertFalse(msg.contains("wrongAnswer"),
                "日志消息不应包含wrongAnswer字段名");
    }

    @Test
    void mapperThrows_throwableArgumentProvidesStackTrace() {
        doThrow(new RuntimeException("连接超时")).when(attemptLogMapper)
                .insertPlayAttemptLog(any());

        invokeLogAttempt(MEMBER, node(), ACTIVITY, 4, 1, null);

        assertEquals(1, events().size());
        ILoggingEvent ev = events().get(0);
        assertTrue(ev.getThrowableProxy() != null,
                "日志应携带Throwable(由SLF4J最后一个参数 e 提供)");
        assertTrue(ev.getThrowableProxy().getClassName()
                        .contains("RuntimeException"),
                "Throwable类型应可追溯");
    }

    @Test
    void mapperThrows_nodeIsNull_logStillProduced() {
        doThrow(new RuntimeException("DB异常")).when(attemptLogMapper)
                .insertPlayAttemptLog(any());

        invokeLogAttempt(MEMBER, null, ACTIVITY, 2, 1, null);

        assertEquals(1, events().size(), "node为null时仍应产生日志(不做NPE)");
        String msg = events().get(0).getFormattedMessage();
        assertTrue(msg.contains("node=null"), "node为null时日志字段应为null");
    }
}
