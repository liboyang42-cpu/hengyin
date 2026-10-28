package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.AdvancedGameRandom;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * 阶段 4 自适应难度的**运行期**那一半。
 *
 * <p>发布期两条(单向只放宽 / 只动容错面)在 AdvancedGameVariantsValidatorTest;
 * 这里证明另两条:
 *   ③ 开局求值一次,放宽后的配置写进本局快照 —— 局内不可变,判题读的就是它;
 *   ④ 求值在下发白名单之前 —— 本测试直接断快照,下发那一层仍是唯一出口。
 *
 * <p>外加 fail-closed:条件判不了(没有本局)时不放宽,而不是默认放宽。
 */
class AdvancedGameRelaxTest {

    private static final long NOW = 1_775_000_000_000L;
    private static final long MEMBER_ID = 7L, ACTIVITY_ID = 9L, TOPIC_ID = 19L;
    private static final long NODE_ID = 29L, TEMPLATE_ID = 39L;

    /** 基准:60 秒时限、排序最多 2 次。低血时放宽成 90 秒 / 不限次数。 */
    private static final String CONFIG = "{\"schemaVersion\":1,"
            + "\"sort\":{\"enabled\":true,\"prompt\":\"按年代排\",\"items\":["
            + "{\"id\":\"a\",\"label\":\"甲\"},{\"id\":\"b\",\"label\":\"乙\"},"
            + "{\"id\":\"c\",\"label\":\"丙\"}],"
            + "\"answerOrder\":[\"a\",\"b\",\"c\"],\"maxAttempts\":2,\"xp\":5},"
            + "\"timer\":{\"enabled\":true,\"durationSeconds\":60,\"timeoutResult\":\"FAILED\"},"
            + "\"variants\":[{\"when\":{\"op\":\"LTE\",\"var\":\"sys.hp\",\"value\":3},"
            + "\"relax\":{\"timer.durationSeconds\":\"+30\",\"sort.maxAttempts\":\"=0\"}}]}";

    private final ObjectMapper json = new ObjectMapper();
    private GameRuntimeSessionMapper sessionMapper;
    private ITopicRouteRuntimeService topicRouteRuntimeService;
    private AdvancedGameRuntimeServiceImpl service;
    private GameRuntimeSession saved;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(GameRuntimeSessionMapper.class);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        CmsMemberTemplateMapper templateMapper = mock(CmsMemberTemplateMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        PlayTeamMapper teamMapper = mock(PlayTeamMapper.class);
        PlayAttemptLogMapper attemptMapper = mock(PlayAttemptLogMapper.class);
        topicRouteRuntimeService = mock(ITopicRouteRuntimeService.class);

        service = new AdvancedGameRuntimeServiceImpl();
        com.chengyinhub.business.service.impl.AdvancedActionTestFixtures.transactions(service);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "nodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "templateMapper", templateMapper);
        ReflectionTestUtils.setField(service, "activityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "registrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "teamMapper", teamMapper);
        ReflectionTestUtils.setField(service, "attemptMapper", attemptMapper);
        ReflectionTestUtils.setField(service, "topicRouteRuntimeService", topicRouteRuntimeService);
        ReflectionTestUtils.setField(service, "clock", (AdvancedGameClock) () -> new Date(NOW));
        ReflectionTestUtils.setField(service, "random", (AdvancedGameRandom) bound -> 0);

        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE_ID); node.setTopicId(TOPIC_ID); node.setTemplateId(TEMPLATE_ID);
        when(nodeMapper.selectCmsTopicNodeById(NODE_ID)).thenReturn(node);
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(TEMPLATE_ID); template.setAdvancedConfigJson(CONFIG);
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID); activity.setTopicId(TOPIC_ID);
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);
        when(teamMapper.hasPaidRegistration(anyLong(), any(), anyLong())).thenReturn(1);
        when(sessionMapper.selectByOwner(anyLong(), anyLong(), anyLong(), any(), anyLong()))
                .thenReturn(null);
        when(sessionMapper.insert(any(GameRuntimeSession.class))).thenAnswer(inv -> {
            saved = inv.getArgument(0);
            saved.setId(51L);
            return 1;
        });
        com.chengyinhub.business.service.support.CurrentPlayTestFixtures.installOn(service);
    }

    private JsonNode startAndReadSnapshot() throws Exception {
        service.start(MEMBER_ID, ACTIVITY_ID, TOPIC_ID, NODE_ID, null);
        return json.readTree(saved.getConfigSnapshotJson());
    }

    @Test
    void lowHpRelaxesToleranceIntoTheFrozenSnapshot() throws Exception {
        when(topicRouteRuntimeService.conditionPasses(anyLong(), anyLong(), anyLong(), any())).thenReturn(true);

        JsonNode snapshot = startAndReadSnapshot();

        assertEquals(90, snapshot.path("timer").path("durationSeconds").asInt(), "60 + 30");
        assertEquals(0, snapshot.path("sort").path("maxAttempts").asInt(), "=0 改成不限");
    }

    /** 配对:条件不成立时一个字都不动 —— 防止上一条是「反正都放宽」蒙过去的。 */
    @Test
    void fullHpKeepsTheConfigUntouched() throws Exception {
        when(topicRouteRuntimeService.conditionPasses(anyLong(), anyLong(), anyLong(), any())).thenReturn(false);

        JsonNode snapshot = startAndReadSnapshot();

        assertEquals(60, snapshot.path("timer").path("durationSeconds").asInt());
        assertEquals(2, snapshot.path("sort").path("maxAttempts").asInt());
    }

    /** 题面一个字都不许动 —— 白名单之外的段落在任何分支下都原样。 */
    @Test
    void questionSurfaceIsUntouchedEvenWhenRelaxed() throws Exception {
        when(topicRouteRuntimeService.conditionPasses(anyLong(), anyLong(), anyLong(), any())).thenReturn(true);

        JsonNode snapshot = startAndReadSnapshot();

        assertEquals(3, snapshot.path("sort").path("items").size(), "题目数量不许变");
        assertEquals(5, snapshot.path("sort").path("xp").asInt(), "奖励分不许变");
        assertEquals("[\"a\",\"b\",\"c\"]", snapshot.path("sort").path("answerOrder").toString(), "答案不许变");
    }

    /** 非旅程主题:没有本局,conditionPasses 恒 false ⇒ 原样,零行为变化。 */
    @Test
    void withoutJourneyRunNothingIsRelaxed() throws Exception {
        when(topicRouteRuntimeService.conditionPasses(anyLong(), anyLong(), anyLong(), any())).thenReturn(false);

        JsonNode snapshot = startAndReadSnapshot();

        assertEquals(60, snapshot.path("timer").path("durationSeconds").asInt());
        verify(topicRouteRuntimeService, never()).recordMistake(anyLong(), anyLong(), anyLong(), anyLong());
    }

    /** 小数误差放宽:2.5 + 2 = 4.5,不许被截成整数(截了就是 2 + 2 = 4,比作者看到的少)。 */
    @Test
    void decimalToleranceKeepsFraction() throws Exception {
        CmsMemberTemplate t = new CmsMemberTemplate();
        t.setId(TEMPLATE_ID);
        t.setAdvancedConfigJson("{\"schemaVersion\":1,\"estimate\":{\"enabled\":true,\"title\":\"猜价\",\"min\":0,"
                + "\"max\":100,\"answer\":40,\"tolerance\":2.5,\"xp\":5},"
                + "\"variants\":[{\"when\":{\"op\":\"LTE\",\"var\":\"sys.hp\",\"value\":3},"
                + "\"relax\":{\"estimate.tolerance\":\"+2\"}}]}");
        CmsMemberTemplateMapper m = (CmsMemberTemplateMapper) ReflectionTestUtils.getField(service, "templateMapper");
        when(m.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(t);
        when(topicRouteRuntimeService.conditionPasses(anyLong(), anyLong(), anyLong(), any())).thenReturn(true);

        JsonNode snapshot = startAndReadSnapshot();

        assertEquals(4.5, snapshot.path("estimate").path("tolerance").asDouble(), 1e-9);
    }

    /** LOWER:安静挑战 10 秒,血量低时 -4 → 开局快照里是 6 秒。 */
    @Test
    void lowerRelaxShortensHoldInSnapshot() throws Exception {
        CmsMemberTemplate t = new CmsMemberTemplate();
        t.setId(TEMPLATE_ID);
        t.setAdvancedConfigJson("{\"schemaVersion\":1,\"quietHold\":{\"enabled\":true,\"seconds\":10},"
                + "\"variants\":[{\"when\":{\"op\":\"LTE\",\"var\":\"sys.hp\",\"value\":3},"
                + "\"relax\":{\"quietHold.seconds\":\"-4\"}}]}");
        CmsMemberTemplateMapper m = (CmsMemberTemplateMapper) ReflectionTestUtils.getField(service, "templateMapper");
        when(m.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(t);
        when(topicRouteRuntimeService.conditionPasses(anyLong(), anyLong(), anyLong(), any())).thenReturn(true);

        assertEquals(6, startAndReadSnapshot().path("quietHold").path("seconds").asInt());
    }
}
