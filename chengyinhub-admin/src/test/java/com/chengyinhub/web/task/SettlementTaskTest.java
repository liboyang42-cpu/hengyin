package com.chengyinhub.web.task;

import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.impl.AlarmService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SettlementTaskTest {

    private SettlementTask newTask(ICmsActivityService activityService, ICmsTopicService topicService, AlarmService alarmService) {
        SettlementTask task = new SettlementTask();
        ReflectionTestUtils.setField(task, "cmsActivityService", activityService);
        ReflectionTestUtils.setField(task, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(task, "alarmService", alarmService);
        return task;
    }

    @Test
    void runDoesNotAlertWhenBothSettlementsSucceed() {
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        AlarmService alarmService = mock(AlarmService.class);
        when(activityService.executeActivitySettlement()).thenReturn(2);
        when(topicService.executeTopicSettlement()).thenReturn(1);

        newTask(activityService, topicService, alarmService).run();

        verifyNoInteractions(alarmService);
    }

    @Test
    void runAlertsWhenActivitySettlementThrowsButStillRunsTopicSettlement() {
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        AlarmService alarmService = mock(AlarmService.class);
        when(activityService.executeActivitySettlement()).thenThrow(new RuntimeException("activity boom"));
        when(topicService.executeTopicSettlement()).thenReturn(1);

        assertThrows(RuntimeException.class, newTask(activityService, topicService, alarmService)::run,
                "活动段失败必须让任务失败(Quartz 记 FAIL),但主题段仍要跑完");

        verify(alarmService).alarm(eq("活动结算失败"), contains("activity boom"));
        verify(topicService).executeTopicSettlement();
    }

    @Test
    void runAlertsWhenTopicSettlementThrows() {
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        AlarmService alarmService = mock(AlarmService.class);
        when(activityService.executeActivitySettlement()).thenReturn(2);
        when(topicService.executeTopicSettlement()).thenThrow(new RuntimeException("topic boom"));

        assertThrows(RuntimeException.class, newTask(activityService, topicService, alarmService)::run);

        verify(alarmService).alarm(eq("主题结算失败"), contains("topic boom"));
    }
}
