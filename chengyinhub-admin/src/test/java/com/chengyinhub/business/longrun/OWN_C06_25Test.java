package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.route.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.business.service.support.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Final settlement values must survive first response and action replay. */
class OWN_C06_25Test {
    private final ObjectMapper json=new ObjectMapper();
    private TopicRouteRuntimeServiceImpl service;
    private TopicRouteSessionMapper routes;
    private TopicRouteSession session;
    private TopicRouteEngine.State state;
    private TopicRouteAction saved;
    @BeforeEach void setup() throws Exception {
        service=new TopicRouteRuntimeServiceImpl();routes=mock(TopicRouteSessionMapper.class);
        ReflectionTestUtils.setField(service,"routeMapper",routes);
        ReflectionTestUtils.setField(service,"topicMapper",mock(CmsTopicMapper.class));
        JourneyRules rules=new JourneyRules();rules.setCheckEnabled(true);rules.getHp().setEnabled(true);
        ObjectNode theme=ThemeRuntimeConfig.parse("{\"schemaVersion\":1,\"capacity\":2,\"items\":[{\"id\":\"rescue\",\"kind\":\"ITEM\",\"name\":\"补给\",\"image\":\"https://example.test/rescue.png\",\"stat\":\"HP\",\"delta\":2,\"wear\":true,\"mode\":\"ONCE\"}],\"events\":[{\"id\":\"low\",\"content\":\"补给抵达\",\"when\":[{\"op\":\"LTE\",\"var\":\"sys.hp\",\"value\":1}],\"grants\":[{\"itemId\":\"rescue\",\"quantity\":1}]}]}");
        ObjectNode snapshot=json.createObjectNode();snapshot.set("journeyRules",json.valueToTree(rules));snapshot.set("themeConfig",theme);
        session=new TopicRouteSession();session.setId(30L);session.setMemberId(7L);session.setTopicId(20L);
        session.setActivityId(0L);session.setVersion(0);session.setStatus(0);session.setConfigSnapshotJson(snapshot.toString());
        state=new TopicRouteEngine.State();state.setStatus("ACTIVE");state.setCurrentNodeId(40L);
        state.getVariables().put("sys.hp",1);state.getVariables().put("sys.luck",2);
        new ThemeRuntimeEngine().initialize(theme,state);session.setStateJson(json.writeValueAsString(state));
        when(routes.insertAction(any())).thenAnswer(call->{saved=call.getArgument(0);saved.setId(90L);return 1;});
        when(routes.updateStateCas(any())).thenReturn(1);
        when(routes.updateActionResponse(anyLong(),anyString())).thenAnswer(call->{saved.setResponseJson(call.getArgument(1));return 1;});
        when(routes.selectActionByAction(30L,7L,"action-25")).thenAnswer(call->saved);
    }
    private PlayCheckReceipt check() {
        PlayCheckReceipt receipt=new PlayCheckReceipt();receipt.setHp(1);receipt.setLuck(2);
        receipt.setDice(Arrays.asList(7));receipt.setSuccess(false);receipt.setSettled(true);return receipt;
    }
    @Test void checkResponseAndReplayUsePostGrantVitalsWithoutChangingDice() throws Exception {
        PlayCheckReceipt result=ReflectionTestUtils.invokeMethod(service,"commitCheck",session,40L,"action-25","CHECK_SETTLE",check(),state);
        assertEquals(3,result.getHp());assertEquals(Arrays.asList(7),result.getDice());assertEquals(Boolean.FALSE,result.getSuccess());
        assertEquals(3,json.readTree(session.getStateJson()).path("variables").path("sys.hp").asInt());
        PlayCheckReceipt replay=ReflectionTestUtils.invokeMethod(service,"replayCheck",session,7L,"action-25");
        assertEquals(result.getHp(),replay.getHp());assertEquals(result.getDice(),replay.getDice());
        verify(routes,times(1)).updateActionResponse(eq(90L),anyString());
    }
    @Test void journeyResponseAndReplayUsePostGrantVitalsWithoutEncounter() {
        Map<String,Object> receipt=new LinkedHashMap<>();receipt.put("hp",1);receipt.put("luck",2);receipt.put("answer","kept");
        Map<String,Object> result=ReflectionTestUtils.invokeMethod(service,"commitJourneyAction",session,40L,"action-25","ASK",receipt,state);
        Map<String,Object> replay=ReflectionTestUtils.invokeMethod(service,"replayJourneyAction",session,7L,"action-25");
        assertEquals(3,result.get("hp"));assertEquals(3,replay.get("hp"));assertEquals("kept",replay.get("answer"));
        assertFalse(result.containsKey("encounter"));assertEquals(result.get("attributes"),replay.get("attributes"));
    }
    @Test void checkReceiptWriteFailureStillThrows() {
        when(routes.updateActionResponse(anyLong(),anyString())).thenReturn(0);
        IllegalStateException error=assertThrows(IllegalStateException.class,()->ReflectionTestUtils.invokeMethod(service,"commitCheck",session,40L,"action-25","CHECK_SETTLE",check(),state));
        assertEquals("检定回执保存失败",error.getMessage());
    }
    @Test void journeyReceiptWriteFailureStillThrows() {
        when(routes.updateActionResponse(anyLong(),anyString())).thenReturn(0);
        IllegalStateException error=assertThrows(IllegalStateException.class,()->ReflectionTestUtils.invokeMethod(service,"commitJourneyAction",session,40L,"action-25","ASK",new LinkedHashMap<String,Object>(),state));
        assertEquals("旅程动作回执保存失败",error.getMessage());
    }
    @Test void lostStateCasDoesNotPersistFinalResponse() {
        when(routes.updateStateCas(any())).thenReturn(0);
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,()->ReflectionTestUtils.invokeMethod(service,"commitCheck",session,40L,"action-25","CHECK_SETTLE",check(),state));
        verify(routes,never()).updateActionResponse(anyLong(),anyString());
    }
}
