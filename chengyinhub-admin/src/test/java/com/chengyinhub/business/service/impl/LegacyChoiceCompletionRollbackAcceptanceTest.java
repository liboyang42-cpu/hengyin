package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.AdvancedGameRandom;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SC-TPL-OLD-SAVE-ROLLBACK 验收(Mockito 层):旧模板分支选择由 act 落库后,完成闸
 * {@link AdvancedGameRuntimeServiceImpl#completeGate} 的 CAS 抢不到时必须抛异常
 * (外层 checkin/answer 的 @Transactional 据此整体回滚进度/发奖),且不改写已保存的选择;
 * 之后重试能正常完成,结果码与第一次选择一致。
 *
 * <p>真事务回滚的忠实证明在 MySQL IT
 * {@code AdvancedGameRuntimeMySqlIT#legacyFrozenBranchCommitsOnceAndInvalidOutcomeRollsBack}
 * (ci/backend-test.sh 点名跑,本机无 MySQL 时不执行)。
 */
class LegacyChoiceCompletionRollbackAcceptanceTest {
    private static final long NOW = 1_775_000_000_000L;
    private static final long MEMBER = 7L, ACTIVITY = 9L, TOPIC = 19L, NODE = 29L, TEMPLATE = 39L;
    private static final String CONFIG = "{\"schemaVersion\":1,\"branch\":{\"enabled\":true,\"startStepId\":\"start\",\"steps\":["
            + "{\"id\":\"start\",\"terminal\":false,\"options\":[{\"id\":\"go\",\"label\":\"继续\",\"nextStepId\":\"end\"}]},"
            + "{\"id\":\"end\",\"terminal\":true,\"outcomeCode\":\"DONE\",\"options\":[]}]}}";

    private final ObjectMapper json = new ObjectMapper();
    private GameRuntimeSessionMapper sessionMapper;
    private AdvancedGameRuntimeServiceImpl service;
    /** 「库里」那一行:只有 CAS 成功才会被改写。 */
    private final AtomicReference<GameRuntimeSession> row = new AtomicReference<>();
    private final AtomicBoolean casWins = new AtomicBoolean(true);

    @BeforeEach
    void setUp() {
        sessionMapper = mock(GameRuntimeSessionMapper.class);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        PlayTeamMapper teamMapper = mock(PlayTeamMapper.class);
        service = new AdvancedGameRuntimeServiceImpl();
        com.chengyinhub.business.service.impl.AdvancedActionTestFixtures.transactions(service);
        ReflectionTestUtils.setField(service, "verifiedWeRunService", com.chengyinhub.business.it.R5WeRunFixture.service());
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "nodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "templateMapper", mock(CmsMemberTemplateMapper.class));
        ReflectionTestUtils.setField(service, "activityMapper", activityMapper);
        // 移植分支 A包当前资格按 activity→registration 锁读当前行(CurrentPlayRegistration)，夹具同 PersonalGameplayRuntimeServiceTest。
        CmsRegistrationMapper currentRegistrations = mock(CmsRegistrationMapper.class);
        CurrentRegistrationStubs.currentFromProbe(currentRegistrations);
        ReflectionTestUtils.setField(service, "registrationMapper", currentRegistrations);
        ReflectionTestUtils.setField(service, "teamMapper", teamMapper);
        ReflectionTestUtils.setField(service, "attemptMapper", mock(PlayAttemptLogMapper.class));
        ReflectionTestUtils.setField(service, "clock", (AdvancedGameClock) () -> new Date(NOW));
        ReflectionTestUtils.setField(service, "random", (AdvancedGameRandom) bound -> 0);

        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE); node.setTopicId(TOPIC); node.setTemplateId(TEMPLATE);
        when(nodeMapper.selectCmsTopicNodeById(NODE)).thenReturn(node);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY); activity.setTopicId(TOPIC); activity.setProductType(1);
        activity.setStartDate(new Date(NOW - 60_000)); activity.setEndDate(new Date(NOW + 3_600_000));
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(activityMapper.selectCmsActivityByIdForUpdate(ACTIVITY)).thenReturn(activity);
        when(teamMapper.hasPaidRegistration(MEMBER, 2, ACTIVITY)).thenReturn(1);

        GameRuntimeSession s = new GameRuntimeSession();
        s.setId(51L); s.setActivityId(ACTIVITY); s.setTopicId(TOPIC); s.setNodeId(NODE); s.setTemplateId(TEMPLATE);
        s.setOwnerType(GameRuntimeSession.OWNER_PLAYER); s.setOwnerId(MEMBER);
        s.setConfigSnapshotJson(CONFIG); s.setStateJson("{\"branch\":{\"currentStepId\":\"start\"}}");
        s.setStatus(GameRuntimeSession.STATUS_RUNNING); s.setVersion(0); s.setStartedAt(new Date(NOW));
        row.set(s);

        // 每次读都是库里那一行的副本(内存里改了不等于落库)
        when(sessionMapper.selectPlayerByOwner(ACTIVITY, TOPIC, NODE, MEMBER)).thenAnswer(i -> copy(row.get()));
        when(sessionMapper.selectAccessibleById(51L, MEMBER)).thenAnswer(i -> copy(row.get()));
        when(sessionMapper.updateStateCas(any())).thenAnswer(i -> {
            GameRuntimeSession w = i.getArgument(0);
            if (!casWins.get() || !w.getVersion().equals(row.get().getVersion())) return 0;
            GameRuntimeSession next = copy(w);
            next.setVersion(w.getVersion() + 1);
            row.set(next);
            return 1;
        });
    }

    private static GameRuntimeSession copy(GameRuntimeSession s) {
        GameRuntimeSession c = new GameRuntimeSession();
        c.setId(s.getId()); c.setActivityId(s.getActivityId()); c.setTopicId(s.getTopicId()); c.setNodeId(s.getNodeId());
        c.setTemplateId(s.getTemplateId()); c.setOwnerType(s.getOwnerType()); c.setOwnerId(s.getOwnerId());
        c.setConfigSnapshotJson(s.getConfigSnapshotJson()); c.setStateJson(s.getStateJson());
        c.setStatus(s.getStatus()); c.setVersion(s.getVersion()); c.setStartedAt(s.getStartedAt());
        c.setCompletedAt(s.getCompletedAt()); c.setDeadlineAt(s.getDeadlineAt());
        c.setPlayInstanceId(s.getPlayInstanceId()); c.setScore(s.getScore()); c.setCompletedUnits(s.getCompletedUnits());
        return c;
    }

    private String savedStep() throws Exception {
        return json.readTree(row.get().getStateJson()).path("branch").path("currentStepId").asText();
    }

    @Test
    void completionCasLossThrowsSoOuterTransactionRollsBackAndSavedChoiceIsUntouched() throws Exception {
        service.act(MEMBER, 51L, 0, "legacy-choice-1", "CHOOSE", Collections.singletonMap("optionId", "go"));
        assertEquals("end", savedStep(), "选择已由 act 落库");
        String savedState = row.get().getStateJson();
        int savedVersion = row.get().getVersion();

        casWins.set(false);   // 并发:别的请求先改了这一行
        ServiceException lost = assertThrows(ServiceException.class,
                () -> service.completeGate(MEMBER, ACTIVITY, TOPIC, NODE));
        assertEquals("高级玩法状态已更新，请重试", lost.getMessage());
        assertEquals(GameRuntimeSession.STATUS_RUNNING, row.get().getStatus(), "CAS 输了不得标记完成");
        assertNull(row.get().getCompletedAt());
        assertEquals(savedVersion, row.get().getVersion());
        assertEquals(savedState, row.get().getStateJson(), "已保存的选择不被改写");

        // 重试:同一选择完成一次,结果码来自那次选择
        casWins.set(true);
        assertEquals("DONE", service.completeGate(MEMBER, ACTIVITY, TOPIC, NODE));
        assertEquals(GameRuntimeSession.STATUS_COMPLETED, row.get().getStatus());
        assertEquals(savedVersion + 1, row.get().getVersion());
        assertEquals("end", savedStep());
        // 完成后再读:码不变、不再写
        casWins.set(false);
        assertEquals("DONE", service.completeGate(MEMBER, ACTIVITY, TOPIC, NODE));
        assertEquals(savedVersion + 1, row.get().getVersion());
    }

    @Test
    void completionBeforeAnyChoiceIsRefusedWithoutWriting() {
        assertThrows(ServiceException.class, () -> service.completeGate(MEMBER, ACTIVITY, TOPIC, NODE));
        verify(sessionMapper, never()).updateStateCas(any());
        assertEquals(GameRuntimeSession.STATUS_RUNNING, row.get().getStatus());
    }
}
