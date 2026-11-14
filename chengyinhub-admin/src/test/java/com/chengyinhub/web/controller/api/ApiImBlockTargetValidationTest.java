package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IImService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/** Pins the existing block/unblock endpoints; no new route, storage or friend prerequisite. */
class ApiImBlockTargetValidationTest
{
    private IImService service;
    private ApiImController controller;

    @BeforeEach void setUp()
    {
        service = mock(IImService.class);
        controller = spy(new ApiImController());
        doReturn(42L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "imService", service);
    }

    @Test void malformedBlockTargetNeverReachesPersistenceService()
    {
        for (String target : new String[]{null, "", " ", "not-a-member", "0", "-1", "9223372036854775808", "18446744073709551623", "7.9", "7e0"})
        {
            AjaxResult result = controller.block(target);
            assertEquals(400, result.get(AjaxResult.CODE_TAG));
            assertEquals("参数有误", result.get(AjaxResult.MSG_TAG));
        }
        verifyNoInteractions(service);
    }

    @Test void malformedUnblockTargetNeverReachesPersistenceService()
    {
        for (String target : new String[]{null, "", " ", "not-a-member", "0", "-1", "9223372036854775808", "18446744073709551623", "7.9", "7e0"})
        {
            AjaxResult result = controller.unblock(target);
            assertEquals(400, result.get(AjaxResult.CODE_TAG));
            assertEquals("参数有误", result.get(AjaxResult.MSG_TAG));
        }
        verifyNoInteractions(service);
    }

    @Test void validBlockStillUsesAuthenticatedActorAndExistingService()
    {
        assertEquals(200, controller.block("7").get(AjaxResult.CODE_TAG));
        verify(service).block(42L, 7L);
        verifyNoMoreInteractions(service);
    }

    @Test void validUnblockStillUsesAuthenticatedActorAndExistingService()
    {
        assertEquals(200, controller.unblock("7").get(AjaxResult.CODE_TAG));
        verify(service).unblock(42L, 7L);
        verifyNoMoreInteractions(service);
    }

    @Test void peerAliasStillUsesAuthenticatedActorAndExistingService()
    {
        assertEquals(200, controller.block(null, "7").get(AjaxResult.CODE_TAG));
        assertEquals(200, controller.unblock(null, "7").get(AjaxResult.CODE_TAG));
        verify(service).block(42L, 7L);
        verify(service).unblock(42L, 7L);
        verifyNoMoreInteractions(service);
    }

    @Test void identicalAliasesAreAcceptedOncePerCommand()
    {
        assertEquals(200, controller.block(" 7 ", "007").get(AjaxResult.CODE_TAG));
        assertEquals(200, controller.unblock("007", " 7 ").get(AjaxResult.CODE_TAG));
        verify(service).block(42L, 7L);
        verify(service).unblock(42L, 7L);
        verifyNoMoreInteractions(service);
    }

    @Test void conflictingAliasesAreRejectedBeforeService()
    {
        assertEquals(400, controller.block("7", "8").get(AjaxResult.CODE_TAG));
        assertEquals(400, controller.unblock("7", "8").get(AjaxResult.CODE_TAG));
        verifyNoInteractions(service);
    }

    @Test void malformedAliasCannotBeHiddenByValidOtherName()
    {
        for (String bad : new String[]{"", " ", "0", "-1", "7.9", "7e0", "9223372036854775808"})
        {
            assertEquals(400, controller.block("7", bad).get(AjaxResult.CODE_TAG));
            assertEquals(400, controller.block(bad, "7").get(AjaxResult.CODE_TAG));
            assertEquals(400, controller.unblock("7", bad).get(AjaxResult.CODE_TAG));
            assertEquals(400, controller.unblock(bad, "7").get(AjaxResult.CODE_TAG));
            assertEquals(400, controller.block(null, bad).get(AjaxResult.CODE_TAG));
            assertEquals(400, controller.unblock(null, bad).get(AjaxResult.CODE_TAG));
        }
        verifyNoInteractions(service);
    }

    @Test void httpPeerAliasBindsToExistingBlockAndUnblockRoutes() throws Exception
    {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        for (String route : new String[]{"block", "unblock"})
        {
            String body = mvc.perform(post("/api/im/" + route).param("peer_id", "7"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertTrue(body.contains("\"code\":200"), body);
        }
        verify(service).block(42L, 7L);
        verify(service).unblock(42L, 7L);
        verifyNoMoreInteractions(service);
    }

    @Test void httpLegacyNameRemainsCompatible() throws Exception
    {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        for (String route : new String[]{"block", "unblock"})
        {
            String body = mvc.perform(post("/api/im/" + route).param("target_member_id", "7"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertTrue(body.contains("\"code\":200"), body);
        }
        verify(service).block(42L, 7L);
        verify(service).unblock(42L, 7L);
        verifyNoMoreInteractions(service);
    }

    @Test void httpConflictingNamesAndMissingTargetsCannotWrite() throws Exception
    {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        for (String route : new String[]{"block", "unblock"})
        {
            String conflict = mvc.perform(post("/api/im/" + route)
                    .param("target_member_id", "7").param("peer_id", "8"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertTrue(conflict.contains("\"code\":400"), conflict);
            String absent = mvc.perform(post("/api/im/" + route))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertTrue(absent.contains("\"code\":400"), absent);
        }
        verifyNoInteractions(service);
    }
}
