package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.CmsTopicContentRevision;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.CmsTopicContentRevisionMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayHelpAuditMapper;
import com.chengyinhub.business.service.support.PlayHelpReceiptReadModel;
import com.chengyinhub.business.service.support.PlayHelpUsagePolicy;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.service.play.PlayHelpApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * qx-c1 #4 (UG-12): admin read-only hint-ladder summary and per-run help lines.
 * Uses the real PlayHelpApplication frozen-ladder derivation and the real receipt read model.
 */
class PlayHelpAuditControllerTest {
    private static final long RUN = 41L, MEMBER = 9004L, TOPIC = 990014L, NODE = 301L;
    private static final String SECRET_HINT = "提示正文：看门牌背面";
    private static final String SECRET_ANSWER = "答案是一九三七";
    private static final String SNAPSHOT = "{\"hintFreeUsesPerRun\":1,"
            + "\"nodeConfigs\":{\"301\":{\"hintLadder\":["
            + "{\"level\":1,\"type\":\"TEXT\",\"text\":\"" + SECRET_HINT + "\",\"cost\":{\"points\":0}},"
            + "{\"level\":2,\"type\":\"TEXT\",\"text\":\"再看看\",\"cost\":{\"points\":5}},"
            + "{\"level\":3,\"type\":\"SKIP\",\"cost\":{\"points\":10}}]}},"
            + "\"nodeAnswers\":{\"301\":{\"answer\":\"" + SECRET_ANSWER + "\",\"aliases\":[\"1937\"]}}}";

    private PlayHelpAuditController controller;
    private PlayAttemptLogMapper attempts;

    @BeforeEach
    void setUp() {
        attempts = mock(PlayAttemptLogMapper.class);
        PlayHelpApplication help = new PlayHelpApplication();
        ReflectionTestUtils.setField(help, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(help, "playAttemptLogMapper", attempts);
        ReflectionTestUtils.setField(help, "playHelpReceiptReadModel", new PlayHelpReceiptReadModel(attempts));
        ReflectionTestUtils.setField(help, "playNodeContext", mock(com.chengyinhub.web.service.play.PlayNodeContext.class));
        ReflectionTestUtils.setField(help, "playerNodeProgressService", mock(com.chengyinhub.business.service.IPlayerNodeProgressService.class));
        PlayHelpAuditMapper runs = mock(PlayHelpAuditMapper.class);
        CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        CmsTopicContentRevisionMapper revisions = mock(CmsTopicContentRevisionMapper.class);
        TopicRouteSession run = new TopicRouteSession();
        run.setId(RUN); run.setMemberId(MEMBER); run.setTopicId(TOPIC); run.setActivityId(13L);
        run.setPlayInstanceId("pi-audit-1"); run.setConfigVersion(3L); run.setConfigSnapshotJson(SNAPSHOT);
        when(runs.selectRunById(RUN)).thenReturn(run);
        CmsTopicNode node = new CmsTopicNode(); node.setId(NODE); node.setTopicId(TOPIC);
        when(nodes.selectCmsTopicNodeById(NODE)).thenReturn(node);
        CmsTopicContentRevision revision = new CmsTopicContentRevision();
        revision.setId(88L); revision.setRevisionNo(2L); revision.setSnapshotJson(SNAPSHOT);
        when(revisions.selectLatestByTopicId(TOPIC)).thenReturn(revision);
        controller = new PlayHelpAuditController(help, runs, nodes, revisions);

        // level 2 was bought for 5 points in this run: one fact row, receipt id 7001.
        String scopeKey = PlayHelpUsagePolicy.scopeKey(new PlayHelpUsagePolicy.Scope(TOPIC, 13L, NODE, "pi-audit-1"));
        PlayAttemptLog fact = new PlayAttemptLog();
        fact.setId(7001L); fact.setMemberId(MEMBER); fact.setAttemptType(PlayAttemptLog.ATTEMPT_TYPE_PUZZLE_HINT);
        fact.setResultJson("{\"free\":false,\"chargedPoints\":5}");
        when(attempts.selectByIdempotencyKey(anyString())).thenReturn(null);
        when(attempts.selectByIdempotencyKey(PlayHelpUsagePolicy.levelFactKey(MEMBER, scopeKey, 2))).thenReturn(fact);
    }

    @Test
    void bothEndpointsRequireTheDedicatedQueryPermission() throws Exception {
        for (String name : List.of("topicConfig", "run")) {
            Method method = PlayHelpAuditController.class.getMethod(name, Long.class);
            PreAuthorize gate = method.getAnnotation(PreAuthorize.class);
            assertNotNull(gate, name);
            assertEquals("@ss.hasPermi('business:playhelp:query')", gate.value());
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void topicConfigSummarisesLayersCostsAndRunFreeUsesWithoutBodies() {
        AjaxResult result = controller.topicConfig(TOPIC);
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        assertEquals(88L, data.get("contentRevisionId"));
        assertEquals(1, data.get("freeUsesPerRun"));
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) data.get("nodes");
        assertEquals(1, nodes.size());
        assertEquals(3, nodes.get(0).get("layerCount"));
        List<Map<String, Object>> levels = (List<Map<String, Object>>) nodes.get(0).get("levels");
        assertEquals(5, levels.get(1).get("costPoints"));
        assertEquals("SKIP", levels.get(2).get("type"));
        assertNoSecrets(result);
    }

    @SuppressWarnings("unchecked")
    @Test
    void runLinesCarryReceiptIdsAndTheSameNetScoreComputationAsThePlayer() {
        AjaxResult result = controller.run(RUN);
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        assertEquals(MEMBER, data.get("memberId"));
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) data.get("nodes");
        Map<String, Object> line = nodes.get(0);
        assertEquals("OK", line.get("state"));
        List<Map<String, Object>> help = (List<Map<String, Object>>) line.get("helpLines");
        assertEquals(1, help.size());
        assertEquals(7001L, help.get(0).get("receiptId"));
        assertEquals(2, help.get(0).get("level"));
        assertEquals(false, help.get(0).get("free"));
        assertEquals(5, help.get(0).get("pointsCharged"));
        Map<String, Object> receipt = (Map<String, Object>) line.get("receipt");
        assertEquals(5, receipt.get("pointsCharged"));
        assertEquals(false, receipt.get("skipped"));
        assertNoSecrets(result);
    }

    @Test
    void unknownRunIs404() {
        assertEquals(404, controller.run(999L).get(AjaxResult.CODE_TAG));
    }

    private static void assertNoSecrets(AjaxResult result) {
        String dumped = String.valueOf(result);
        assertFalse(dumped.contains(SECRET_HINT), dumped);
        assertFalse(dumped.contains("再看看"), dumped);
        assertFalse(dumped.contains(SECRET_ANSWER), dumped);
        assertFalse(dumped.contains("1937"), dumped);
        assertFalse(dumped.contains("aliases"), dumped);
    }
}
