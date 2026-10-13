package com.chengyinhub.business.task;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chengyinhub.business.domain.dto.BeforeStartNotifyResult;
import com.chengyinhub.business.service.ITeamGroupService;
import com.chengyinhub.business.service.impl.AlarmService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 出发前提醒任务(102)的失败信号契约(审查D §8 P1-1)。
 *
 * 病灶:任务层只在送达数 n&gt;0 时打一行 info;errcode 非 0 的失败既不上浮也不告警。
 * 启用 102 后若全量被微信拒(date5 格式即一例),sys_job_log 照样记成功、日志一条没有 ⇒
 * 「每小时重试、每小时静默失败」。本类钉住三件事:
 *   ① 有失败 → ERROR 汇总(scanned/failed 计数)+ alarmService 告警;
 *   ② 无失败 → 不告警,且 0 条也要留 INFO 汇总(n==0 不许同时表示「没人可发」和「全失败」);
 *   ③ 任务抛异常 → 除了 error 日志还要告警(原实现自己吞掉异常,Quartz 看到的是成功)。
 */
class BeforeStartNotifyTaskFailureSignalTest {

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger logger;
    private Level originalLevel;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(BeforeStartNotifyTask.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(logs);
        logger.setLevel(originalLevel);
    }

    private boolean logged(Level level, String needle) {
        return logs.list.stream()
                .anyMatch(e -> e.getLevel() == level && e.getFormattedMessage().contains(needle));
    }

    @Test
    void 任务异常必须告警_不能只吞异常打日志() {
        ITeamGroupService service = mock(ITeamGroupService.class);
        AlarmService alarmService = mock(AlarmService.class);
        when(service.notifyBeforeStartBatch())
                .thenThrow(new IllegalStateException("oms_ticket query failed"));

        BeforeStartNotifyTask task = new BeforeStartNotifyTask();
        ReflectionTestUtils.setField(task, "teamGroupService", service);
        ReflectionTestUtils.setField(task, "alarmService", alarmService);

        task.run();

        verify(alarmService).alarm(contains("出发前提醒"), anyString());
        assertTrue(logged(Level.ERROR, "出发前提醒任务异常"), "异常必须留 ERROR 日志");
    }

    /** 🔴 全量失败的形状:errcode 非 0 时任务层必须 ERROR 汇总(scanned/failed)+ 告警,不能只有 error 日志。 */
    @Test
    void 有发送失败必须ERROR汇总并告警() {
        BeforeStartNotifyResult result = new BeforeStartNotifyResult(5, 1, 1, 3);
        AlarmService alarmService = mock(AlarmService.class);

        BeforeStartNotifyTask task = taskReturning(result, alarmService);
        task.run();

        assertTrue(logged(Level.ERROR, "failed=3"), "失败数必须进 ERROR 汇总,实际日志=" + logs.list);
        assertTrue(logged(Level.ERROR, "scanned=5"), "扫描数必须同框出现,才能区分「没人可发」和「全失败」");
        verify(alarmService).alarm(contains("出发前提醒发送失败"), contains("failed=3"));
        assertFalse(logged(Level.INFO, "出发前提醒任务完成"),
                "有失败就不能同时打「任务完成」,那正是原实现把失败记成成功的方式");
    }

    /** 负控的另一半:全部成功不告警,但汇总 INFO 必须在。 */
    @Test
    void 全部成功不告警_但必须有INFO汇总() {
        AlarmService alarmService = mock(AlarmService.class);
        BeforeStartNotifyTask task = taskReturning(new BeforeStartNotifyResult(2, 0, 2, 0), alarmService);

        task.run();

        assertTrue(logged(Level.INFO, "delivered=2"), "成功路径要留下可当送达证据的汇总");
        assertFalse(logged(Level.ERROR, "出发前提醒"), "成功不得打 ERROR");
        verifyNoInteractions(alarmService);
    }

    /**
     * 零候选是「扫描跑了但没人需要发」,不是失败 —— 必须 INFO 留痕且不告警。
     * 负控锚点:一旦有人把任务层退回「只在 n&gt;0 时打日志」,这条和上一条都会红;
     * 一旦有人把 0 条当失败告警,这条会红。
     */
    @Test
    void 零候选也要留痕_不能与全失败长得一样() {
        AlarmService alarmService = mock(AlarmService.class);
        BeforeStartNotifyTask task = taskReturning(new BeforeStartNotifyResult(0, 0, 0, 0), alarmService);

        task.run();

        assertTrue(logged(Level.INFO, "scanned=0"), "0 条也要打扫描汇总,否则空跑和失败在日志上不可分");
        assertTrue(logged(Level.INFO, "failed=0"), "0 条也要显式写失败数=0");
        assertFalse(logged(Level.ERROR, "出发前提醒"), "零候选不是失败");
        verifyNoInteractions(alarmService);
    }

    private BeforeStartNotifyTask taskReturning(BeforeStartNotifyResult result, AlarmService alarmService) {
        ITeamGroupService service = mock(ITeamGroupService.class);
        when(service.notifyBeforeStartBatch()).thenReturn(result);
        BeforeStartNotifyTask task = new BeforeStartNotifyTask();
        ReflectionTestUtils.setField(task, "teamGroupService", service);
        ReflectionTestUtils.setField(task, "alarmService", alarmService);
        return task;
    }
}
