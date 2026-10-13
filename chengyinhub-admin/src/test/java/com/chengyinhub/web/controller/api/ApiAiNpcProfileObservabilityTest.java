package com.chengyinhub.web.controller.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.vo.ai.NpcProfileVO;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.impl.NpcProfileService;

import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiAiNpcProfileObservabilityTest
{
    private static final Long ACTIVITY_ID = 42L;

    private final Logger controllerLogger = (Logger) LoggerFactory.getLogger(ApiAiNpcController.class);
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    private Level originalLogLevel;
    private NpcProfileService npcProfileService;
    private ICmsTopicNodeService cmsTopicNodeService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp()
    {
        npcProfileService = mock(NpcProfileService.class);
        cmsTopicNodeService = mock(ICmsTopicNodeService.class);
        ApiAiNpcController controller = new ApiAiNpcController();
        ReflectionTestUtils.setField(controller, "npcProfileService", npcProfileService);
        ReflectionTestUtils.setField(controller, "cmsTopicNodeService", cmsTopicNodeService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        originalLogLevel = controllerLogger.getLevel();
        logAppender.start();
        controllerLogger.setLevel(Level.WARN);
        controllerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown()
    {
        controllerLogger.detachAppender(logAppender);
        controllerLogger.setLevel(originalLogLevel);
        logAppender.stop();
    }

    @Test
    void globalResolveFailureReturnsEmptyListAndStructuredWarnWithoutThrowable() throws Exception
    {
        RuntimeException failure = new RuntimeException("PII must not be logged");
        when(npcProfileService.resolveGlobal()).thenThrow(failure);

        mockMvc.perform(get("/api/ai/npc/profile").param("scope", "global"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.npcs", empty()));

        verify(npcProfileService).resolveGlobal();
        assertWarnContains("scope=global", "activityId=null", failure);
    }

    @Test
    void activityResolveFailureReturnsEmptyListAndStructuredWarnWithoutThrowable() throws Exception
    {
        RuntimeException failure = new RuntimeException("PII must not be logged");
        when(npcProfileService.resolveForActivity(ACTIVITY_ID)).thenThrow(failure);

        mockMvc.perform(get("/api/ai/npc/profile")
                .param("scope", "activity")
                .param("activityId", ACTIVITY_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.npcs", empty()));

        verify(npcProfileService).resolveForActivity(ACTIVITY_ID);
        assertWarnContains("scope=activity", "activityId=" + ACTIVITY_ID, failure);
    }

    /** 节点级 NPC(CR-927):节点还没过主办方准入时,一个字都不下发。 */
    @Test
    void nodeScopeRequiresApprovedMerchantNode() throws Exception
    {
        CmsTopicNode pending = new CmsTopicNode();
        pending.setId(31L);
        pending.setMerchantMemberId(501L);
        pending.setNodeAuditStatus(0);
        when(cmsTopicNodeService.selectCmsTopicNodeById(31L)).thenReturn(pending);

        mockMvc.perform(get("/api/ai/npc/profile")
                .param("scope", "node")
                .param("nodeId", "31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.npcs", empty()));

        verify(npcProfileService, org.mockito.Mockito.never()).resolveForNode(31L);
    }

    @Test
    void nodeScopeReturnsOnlyApprovedNpcForApprovedNode() throws Exception
    {
        CmsTopicNode approved = new CmsTopicNode();
        approved.setId(31L);
        approved.setMerchantMemberId(501L);
        approved.setNodeAuditStatus(1);
        when(cmsTopicNodeService.selectCmsTopicNodeById(31L)).thenReturn(approved);
        NpcProfileVO vo = new NpcProfileVO();
        vo.setProfileId(77L);
        vo.setName("阿福");
        when(npcProfileService.resolveForNode(31L)).thenReturn(vo);

        mockMvc.perform(get("/api/ai/npc/profile")
                .param("scope", "node")
                .param("nodeId", "31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.npcs[0].name").value("阿福"));
    }

    private void assertWarnContains(String scope, String activityId, RuntimeException failure)
    {
        assertEquals(1, logAppender.list.size(), "profile 异常必须只记录一条 WARN");
        ILoggingEvent event = logAppender.list.get(0);
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(event.getFormattedMessage().contains(scope));
        assertTrue(event.getFormattedMessage().contains(activityId));
        assertTrue(event.getFormattedMessage().contains("errorType=" + failure.getClass().getName()));
        assertFalse(event.getFormattedMessage().contains(failure.getMessage()),
                "异常消息可能包含 PII,不得拼入结构化上下文");
        assertNull(event.getThrowableProxy(), "异常不得作为 throwable 交给 Logback 自动渲染");
    }
}
