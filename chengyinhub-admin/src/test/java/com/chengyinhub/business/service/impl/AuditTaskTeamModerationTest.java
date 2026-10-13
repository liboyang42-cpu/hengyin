package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 地图组队 P 方案:公开队伍进通用审核工作台(TEAM)。审核期间地图不出由 nearby SQL 保证,见 PlayTeamNearbyMybatisIntegrationTest。 */
class AuditTaskTeamModerationTest {
    private AuditTaskMapper taskMapper;
    private AuditRecordMapper recordMapper;
    private PlayTeamMapper teamMapper;
    private AuditTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(AuditTaskMapper.class);
        recordMapper = mock(AuditRecordMapper.class);
        teamMapper = mock(PlayTeamMapper.class);
        BusinessAuditEventMapper auditMapper = mock(BusinessAuditEventMapper.class);
        service = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(service, "auditTaskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "auditRecordMapper", recordMapper);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", auditMapper);
        ReflectionTestUtils.setField(service, "playTeamMapper", teamMapper);
        when(recordMapper.insertAuditRecord(any())).thenReturn(1);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);
        when(taskMapper.updateAuditTaskReviewCas(any(), any(), any(), any(), any(), any())).thenReturn(1);
    }

    @Test
    void upheldReportTakesTeamOffThePublicMapAndExpiresPendingApplications() {
        when(taskMapper.selectAuditTaskById(81L)).thenReturn(task(81L));
        assertEquals(1, service.review(81L, 900L, decision(AuditTask.STATUS_REJECTED, "队名违规")));
        verify(teamMapper).hideFromPublic(31L);
        verify(teamMapper).expirePendingApplications(31L);
    }

    @Test
    void dismissedReportChangesNothingOnTheTeam() {
        when(taskMapper.selectAuditTaskById(82L)).thenReturn(task(82L));
        assertEquals(1, service.review(82L, 900L, decision(AuditTask.STATUS_APPROVED, "举报无效")));
        verifyNoInteractions(teamMapper);
    }

    @Test
    void moderationFailureRollsBackTheDecision() {
        when(taskMapper.selectAuditTaskById(83L)).thenReturn(task(83L));
        when(teamMapper.hideFromPublic(31L)).thenThrow(new RuntimeException("db down"));
        assertThrows(ServiceException.class,
                () -> service.review(83L, 900L, decision(AuditTask.STATUS_REJECTED, "队名违规")));
    }

    @Test
    void reportSnapshotCapturesTeamEvidenceButNeverTheInviteCode() {
        when(taskMapper.selectAuditTaskList(any())).thenReturn(Collections.emptyList());
        when(taskMapper.insertAuditTask(any())).thenAnswer(inv -> {
            ((AuditTask) inv.getArgument(0)).setId(12L);
            return 1;
        });
        PlayTeam team = new PlayTeam();
        team.setId(31L); team.setTitle("外滩夜行搭子"); team.setLeaderMemberId(7L); team.setOwnerType(2);
        team.setOwnerId(10L); team.setJoinMode(2); team.setStatus(0); team.setJoinedCount(3); team.setMaxMembers(4);
        team.setInviteCode("SECRET_INVITE_CODE");
        when(teamMapper.selectPlayTeamById(31L)).thenReturn(team);
        AuditTask open = new AuditTask(); open.setStatus(AuditTask.STATUS_PENDING);
        when(taskMapper.selectAuditTaskByIdForUpdate(12L)).thenReturn(open);

        service.reportContent(AuditTask.BIZ_TEAM, 31L, 100L, "队名骚扰");

        ArgumentCaptor<AuditTask> cap = ArgumentCaptor.forClass(AuditTask.class);
        verify(taskMapper).insertAuditTask(cap.capture());
        String snapshot = cap.getValue().getSnapshotJson();
        assertTrue(snapshot.contains("外滩夜行搭子"), snapshot);
        assertTrue(snapshot.contains("\"leaderMemberId\":7"), snapshot);
        assertTrue(snapshot.contains("\"evidenceStatus\":\"CAPTURED\""), snapshot);
        assertFalse(snapshot.contains("SECRET_INVITE_CODE"), snapshot);
        assertFalse(snapshot.toLowerCase().contains("invite"), snapshot);
    }

    @Test
    void reportLandingOnARoundJustClosedAsksTheReporterToResubmit() {
        AuditTask closedMeanwhile = new AuditTask(); closedMeanwhile.setId(13L); closedMeanwhile.setStatus(AuditTask.STATUS_PENDING);
        when(taskMapper.selectAuditTaskList(any())).thenReturn(Collections.singletonList(closedMeanwhile));
        AuditTask locked = new AuditTask(); locked.setStatus(AuditTask.STATUS_APPROVED);
        when(taskMapper.selectAuditTaskByIdForUpdate(13L)).thenReturn(locked);
        assertThrows(ServiceException.class, () -> service.reportContent(AuditTask.BIZ_TEAM, 31L, 100L, "骚扰"));
    }

    private AuditTask task(Long id) {
        AuditTask task = new AuditTask();
        task.setId(id);
        task.setBizType(AuditTask.BIZ_TEAM);
        task.setBizId(31L);
        task.setSourceType("report");
        task.setStatus(AuditTask.STATUS_PENDING);
        task.setVersion(0);
        task.setSnapshotJson("{\"bizType\":\"TEAM\"}");
        return task;
    }

    private AuditReviewDTO decision(Integer status, String reason) {
        AuditReviewDTO request = new AuditReviewDTO();
        request.setStatus(status);
        request.setReasonText(reason);
        return request;
    }
}
