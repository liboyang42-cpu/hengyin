package com.chengyinhub.business.cast;

import com.chengyinhub.business.domain.JourneyStory;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.business.service.support.AdvancedGameConfigValidator;
import com.chengyinhub.business.service.support.CastRunRules;
import com.chengyinhub.business.service.support.NodeJourneySnapshot;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 站点选择类玩法的信任(cast-game-trust-1119):选项 trust 从本局冻结块读,每站只结算一次,
 * 角色没开 / 不认识的角色 / 越界 delta 都不放大;结算后 endingFor 结局可以胜出。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StationGameTrustTest
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long OWNER = 7L;
    private static final long RUN = 100L;
    private static final long TOPIC = 9L;
    private static final long NODE = 31L;
    private static final String CFG = "{\"enabled\":true,\"cast\":["
            + "{\"id\":\"c_abc123\",\"name\":\"林姐\",\"color\":\"#E8604C\",\"voice\":{\"mode\":\"none\"}},"
            + "{\"id\":\"c_xyz789\",\"name\":\"老周\",\"color\":\"#3D8BFD\",\"voice\":{\"mode\":\"none\"}}]}";
    /** 作者存进玩法模板的选择题:a 带信任(含越界 delta 与已删角色),b 带反向信任,c 不带。 */
    private static final String TEMPLATE = "{\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"judge\":false,\"title\":\"跟谁走\",\"options\":["
            + "{\"id\":\"a\",\"label\":\"林姐\",\"trust\":[{\"castId\":\"c_abc123\",\"delta\":9},{\"castId\":\"c_ghost1\",\"delta\":2}]},"
            + "{\"id\":\"b\",\"label\":\"老周\",\"trust\":[{\"castId\":\"c_xyz789\",\"delta\":1},{\"castId\":\"c_abc123\",\"delta\":-1}]},"
            + "{\"id\":\"c\",\"label\":\"谁也不跟\"}]}}";

    @Mock private TopicRouteSessionMapper sessionMapper;
    @Mock private CmsTopicMapper topicMapper;
    private TopicRouteRuntimeServiceImpl service;
    private TopicRouteSession run;

    @BeforeEach
    void setUp() throws Exception
    {
        service = new TopicRouteRuntimeServiceImpl(sessionMapper, topicMapper, null, null, null, null);
        ObjectNode block = JSON.createObjectNode();
        NodeJourneySnapshot.copyMechanicSegments(JSON.readTree(TEMPLATE), block);
        ObjectNode snapshot = JSON.createObjectNode();
        snapshot.with(NodeJourneySnapshot.NODE_JOURNEY_KEY).set(String.valueOf(NODE), block);
        run = new TopicRouteSession();
        run.setId(RUN);
        run.setMemberId(OWNER);
        run.setTopicId(TOPIC);
        run.setActivityId(0L);
        run.setStatus(TopicRouteSession.STATUS_RUNNING);
        run.setStateJson("{}");
        run.setConfigSnapshotJson(snapshot.toString());
        when(sessionMapper.selectByOwner(0L, TOPIC, OWNER, "default")).thenReturn(run);
        when(sessionMapper.selectAccessibleByIdForUpdate(RUN, OWNER)).thenReturn(run);
        when(sessionMapper.updateStateCas(any())).thenAnswer(call -> {
            run.setStateJson(((TopicRouteSession) call.getArgument(0)).getStateJson());
            return 1;
        });
        when(topicMapper.selectCastConfigById(TOPIC)).thenReturn(CFG);
    }

    private List<String> pick(String option)
    {
        return service.applyStationTrust(OWNER, TOPIC, 0L, NODE, Collections.singletonList(new String[]{"qa", option}));
    }

    private TopicRouteEngine.State state() throws Exception
    {
        return JSON.readValue(run.getStateJson(), TopicRouteEngine.State.class);
    }

    @Test
    void 冻结块带上选项信任_不带答案() throws Exception
    {
        ObjectNode block = JSON.createObjectNode();
        NodeJourneySnapshot.copyMechanicSegments(JSON.readTree(TEMPLATE), block);
        assertEquals("c_abc123", block.path("choiceEffects").path("qa").path("options").path(0).path("trust").path(0).path("castId").asText());
        assertTrue(block.path("choiceEffects").path("qa").path("options").path(2).path("trust").isMissingNode());
        assertTrue(NodeJourneySnapshot.needsJourneyState(TEMPLATE), "只配了信任的选择题也要本局状态");
    }

    @Test
    void 选中带信任的选项_结算一次_越界夹到正负2_不认识的角色忽略() throws Exception
    {
        assertEquals(Collections.singletonList("c_abc123"), pick("a"));
        TopicRouteEngine.State after = state();
        assertEquals(2, after.getTrust().get("c_abc123"));
        assertEquals(1, after.getTrust().size());
    }

    @Test
    void 同一站重放或改答_不重复结算() throws Exception
    {
        pick("a");
        assertEquals(Collections.emptyList(), pick("a"), "重放");
        assertEquals(Collections.emptyList(), pick("b"), "改答");
        TopicRouteEngine.State after = state();
        assertEquals(2, after.getTrust().get("c_abc123"));
        assertTrue(!after.getTrust().containsKey("c_xyz789"));
        verify(sessionMapper, times(1)).updateStateCas(any());
    }

    @Test
    void 选了不带信任的选项_也算这一站答过_之后不再结算() throws Exception
    {
        assertEquals(Collections.emptyList(), pick("c"));
        assertEquals(Collections.emptyList(), pick("a"));
        assertTrue(state().getTrust().isEmpty());
    }

    @Test
    void 角色没开_或配置为空_不碰本局() throws Exception
    {
        when(topicMapper.selectCastConfigById(TOPIC)).thenReturn(CFG.replace("\"enabled\":true", "\"enabled\":false"));
        assertEquals(Collections.emptyList(), pick("a"));
        when(topicMapper.selectCastConfigById(TOPIC)).thenReturn(null);
        assertEquals(Collections.emptyList(), pick("a"));
        verify(sessionMapper, never()).updateStateCas(any());
    }

    @Test
    void 本局已结束_没有本局_站点没配信任_都不写() throws Exception
    {
        run.setStatus(TopicRouteSession.STATUS_COMPLETED);
        assertEquals(Collections.emptyList(), pick("a"));
        run.setStatus(TopicRouteSession.STATUS_RUNNING);
        assertEquals(Collections.emptyList(),
                service.applyStationTrust(OWNER, TOPIC, 0L, 32L, Collections.singletonList(new String[]{"qa", "a"})));
        when(sessionMapper.selectByOwner(0L, TOPIC, OWNER, "default")).thenReturn(null);
        assertEquals(Collections.emptyList(), pick("a"));
        verify(sessionMapper, never()).updateStateCas(any());
    }

    @Test
    void 结算后_信任最高的角色结局胜出() throws Exception
    {
        pick("b");
        TopicRouteEngine.State after = state();
        assertEquals(1, after.getTrust().get("c_xyz789"));
        assertEquals(-1, after.getTrust().get("c_abc123"));
        JourneyStory.EndingCase lin = CastTrustEndingTest.ending("ending1", "c_abc123", false, null);
        JourneyStory.EndingCase zhou = CastTrustEndingTest.ending("ending2", "c_xyz789", false, null);
        JourneyStory.EndingCase fallback = CastTrustEndingTest.ending("ending3", null, true, null);
        assertSame(zhou, CastRunRules.pickByTrust(Arrays.asList(lin, zhou, fallback), after.getTrust()));
    }

    @Test
    void 模板校验_选项信任与分支同口径()
    {
        AdvancedGameConfigValidator validator = new AdvancedGameConfigValidator();
        String ok = "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"judge\":false,\"title\":\"题\",\"options\":["
                + "{\"id\":\"a\",\"label\":\"甲\",\"trust\":%s},{\"id\":\"b\",\"label\":\"乙\"}]}}";
        assertTrue(validator.validateAndNormalize(String.format(ok, "[{\"castId\":\"c_abc123\",\"delta\":-2}]")).contains("c_abc123"));
        for (String bad : Arrays.asList(
                "{\"castId\":\"c_abc123\",\"delta\":1}",
                "[{\"castId\":\"林姐\",\"delta\":1}]",
                "[{\"castId\":\"c_abc123\",\"delta\":3}]",
                "[{\"castId\":\"c_abc123\",\"delta\":0}]",
                "[{\"castId\":\"c_abc123\",\"delta\":1.5}]",
                "[{\"castId\":\"c_abc123\",\"delta\":1},{\"castId\":\"c_abc123\",\"delta\":-1}]"))
            assertThrows(IllegalArgumentException.class, () -> validator.validateAndNormalize(String.format(ok, bad)), bad);
    }
}
