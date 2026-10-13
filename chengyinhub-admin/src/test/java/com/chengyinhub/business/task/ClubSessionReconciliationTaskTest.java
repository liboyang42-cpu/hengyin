package com.chengyinhub.business.task;

import com.chengyinhub.business.domain.vo.ClubSessionGapVO;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.service.impl.AlarmService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ClubSessionReconciliationTaskTest {

    @Test
    void runDoesNotAlertWhenEveryClubOrderHasSession() {
        CmsActivityMapper mapper = mock(CmsActivityMapper.class);
        AlarmService alarmService = mock(AlarmService.class);
        when(mapper.selectClubOrderTopicsWithoutClubSession()).thenReturn(Collections.emptyList());
        ClubSessionReconciliationTask task = task(mapper, alarmService);

        task.run();

        verifyNoInteractions(alarmService);
    }

    @Test
    void runAlertsWhenEffectiveClubOrderHasNoClubSession() {
        CmsActivityMapper mapper = mock(CmsActivityMapper.class);
        AlarmService alarmService = mock(AlarmService.class);
        ClubSessionGapVO gap = new ClubSessionGapVO();
        gap.setTopicId(31L);
        gap.setClubId(21L);
        when(mapper.selectClubOrderTopicsWithoutClubSession()).thenReturn(Collections.singletonList(gap));
        ClubSessionReconciliationTask task = task(mapper, alarmService);

        task.run();

        verify(alarmService).alarm(eq("俱乐部合作主题缺少带队场次"), contains("topicId=31"));
        verify(alarmService).alarm(eq("俱乐部合作主题缺少带队场次"), contains("clubId=21"));
    }

    private ClubSessionReconciliationTask task(CmsActivityMapper mapper, AlarmService alarmService) {
        ClubSessionReconciliationTask task = new ClubSessionReconciliationTask();
        ReflectionTestUtils.setField(task, "cmsActivityMapper", mapper);
        ReflectionTestUtils.setField(task, "alarmService", alarmService);
        return task;
    }
}
