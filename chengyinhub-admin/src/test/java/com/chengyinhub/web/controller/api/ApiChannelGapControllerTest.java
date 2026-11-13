package com.chengyinhub.web.controller.api;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.chengyinhub.business.service.channel.platform.ChannelAuditLogService;
import com.chengyinhub.business.service.channel.platform.ChannelInteractionService;
import com.chengyinhub.business.service.channel.platform.ChannelPlatformService;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.business.service.channel.platform.ChannelWebhookService;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 缺口包每条路由：动词、路径、查询/请求体字段到服务的映射，以及 Webhook 族拒绝体里的 err。 */
class ApiChannelGapControllerTest
{
    static final String BODY = "{\"request_id\":\"r1\",\"revision\":1}";
    ChannelPlatformService channels;
    ChannelAuditLogService audit;
    ChannelWebhookService webhooks;
    ChannelInteractionService interaction;
    MockMvc mvc;

    @BeforeEach void setUp()
    {
        channels = mock(ChannelPlatformService.class);
        audit = mock(ChannelAuditLogService.class);
        webhooks = mock(ChannelWebhookService.class);
        interaction = mock(ChannelInteractionService.class);
        ApiChannelGapController controller = spy(new ApiChannelGapController());
        doReturn(7L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "channels", channels);
        ReflectionTestUtils.setField(controller, "audit", audit);
        ReflectionTestUtils.setField(controller, "webhooks", webhooks);
        ApiChannelInteractionController chain = spy(new ApiChannelInteractionController());
        doReturn(7L).when(chain).getAppUserId();
        ReflectionTestUtils.setField(chain, "interaction", interaction);
        mvc = MockMvcBuilders.standaloneSetup(controller, chain).build();
    }

    void ok(MockHttpServletRequestBuilder request) throws Exception
    {
        mvc.perform(request.contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
    }

    @Test void channelsListsTheGuildDirectory() throws Exception
    {
        when(channels.directory(7L, 1, 10L, false)).thenReturn(Map.of("groups", java.util.List.of()));
        ok(get("/api/channels").param("guild_kind", "1").param("guild_id", "10"));
        verify(channels).directory(7L, 1, 10L, false);
    }

    @Test void auditLogPassesVerifyAndCursor() throws Exception
    {
        when(audit.read(anyLong(), anyInt(), anyLong(), any(), anyInt(), anyBoolean())).thenReturn(Map.of("list", java.util.List.of()));
        ok(get("/api/im/audit-log").param("guild_kind", "1").param("guild_id", "10").param("limit", "50").param("verify", "1"));
        verify(audit).read(7L, 1, 10L, null, 50, true);
        ok(get("/api/im/audit-log").param("guild_kind", "1").param("guild_id", "10").param("limit", "50").param("verify", "0").param("before", "9"));
        verify(audit).read(7L, 1, 10L, "9", 50, false);
        mvc.perform(get("/api/im/audit-log").param("guild_kind", "1").param("guild_id", "10").param("verify", "yes"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_FIELD"));
    }

    @Test void chainInfoAcceptsAConversationWithoutChainId() throws Exception
    {
        when(interaction.chainInfo(anyLong(), anyLong(), any(), anyLong(), anyInt())).thenReturn(new java.util.HashMap<>(Map.of("list", java.util.List.of())));
        ok(get("/api/im/chain/info").param("conversation_id", "100").param("limit", "50"));
        verify(interaction).chainInfo(7L, 100L, null, 0L, 50);
        ok(get("/api/im/chain/info").param("conversation_id", "100").param("chain_id", "5"));
        verify(interaction).chainInfo(7L, 100L, 5L, 0L, 50);
    }

    @Test void webhookContextAndRejectionCarryErr() throws Exception
    {
        when(webhooks.context(7L, 10L, 100L)).thenReturn(Map.of("owner_id", "10"));
        ok(get("/api/im/webhook/context").param("owner_id", "10").param("channel_id", "100"));
        when(webhooks.context(7L, 10L, 101L)).thenThrow(new ChannelRejection(403, "WEBHOOK_FORBIDDEN", "x"));
        mvc.perform(get("/api/im/webhook/context").param("owner_id", "10").param("channel_id", "101"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.err").value("WEBHOOK_FORBIDDEN"))
                .andExpect(jsonPath("$.errorCode").value("WEBHOOK_FORBIDDEN"));
    }

    @Test void subscriptionRoutes() throws Exception
    {
        when(webhooks.listSubscriptions(anyLong(), anyString(), anyLong(), anyLong(), any(), anyInt())).thenReturn(Map.of());
        when(webhooks.createSubscription(anyLong(), anyMap())).thenReturn(Map.of());
        when(webhooks.subscription(anyLong(), anyLong())).thenReturn(Map.of());
        when(webhooks.updateSubscription(anyLong(), anyLong(), anyMap())).thenReturn(Map.of());
        when(webhooks.deleteSubscription(anyLong(), anyLong(), anyMap())).thenReturn(Map.of());
        when(webhooks.rotateSecret(anyLong(), anyLong(), anyMap())).thenReturn(Map.of());
        when(webhooks.deliveries(anyLong(), anyLong(), any(), any(), anyInt(), anyBoolean())).thenReturn(Map.of());
        when(webhooks.retry(anyLong(), anyLong(), anyLong(), anyMap())).thenReturn(Map.of());
        when(webhooks.test(anyLong(), anyLong(), anyMap())).thenReturn(Map.of());
        ok(get("/api/im/webhook/subscriptions").param("owner_type", "CLUB").param("owner_id", "10").param("channel_id", "100").param("limit", "20"));
        verify(webhooks).listSubscriptions(7L, "CLUB", 10L, 100L, null, 20);
        ok(post("/api/im/webhook/subscriptions").content("{\"request_id\":\"r1\"}"));
        verify(webhooks).createSubscription(eq(7L), eq(Map.of("request_id", "r1")));
        ok(get("/api/im/webhook/subscriptions/3"));
        verify(webhooks).subscription(7L, 3L);
        ok(put("/api/im/webhook/subscriptions/3").content(BODY));
        verify(webhooks).updateSubscription(eq(7L), eq(3L), anyMap());
        ok(delete("/api/im/webhook/subscriptions/3").content(BODY));
        verify(webhooks).deleteSubscription(eq(7L), eq(3L), anyMap());
        ok(post("/api/im/webhook/subscriptions/3/rotate-secret").content(BODY));
        verify(webhooks).rotateSecret(eq(7L), eq(3L), anyMap());
        ok(get("/api/im/webhook/subscriptions/3/deliveries").param("limit", "20").param("include_payload", "1").param("state", "FAILED").param("cursor", "8"));
        verify(webhooks).deliveries(7L, 3L, "FAILED", "8", 20, true);
        ok(post("/api/im/webhook/subscriptions/3/deliveries/8/retry").content(BODY));
        verify(webhooks).retry(eq(7L), eq(3L), eq(8L), anyMap());
        ok(post("/api/im/webhook/subscriptions/3/test").content("{\"request_id\":\"r1\",\"revision\":1,\"event_type\":\"message.created\"}"));
        verify(webhooks).test(eq(7L), eq(3L), anyMap());
        mvc.perform(get("/api/im/webhook/subscriptions/abc")).andExpect(status().isNotFound()).andExpect(jsonPath("$.err").value("WEBHOOK_NOT_FOUND"));
    }

    @Test void endpointRoutes() throws Exception
    {
        when(webhooks.listEndpoints(anyLong(), anyString(), anyLong(), anyLong(), any(), anyInt())).thenReturn(Map.of());
        when(webhooks.endpoint(anyLong(), anyLong())).thenReturn(Map.of());
        when(webhooks.endpointLogs(anyLong(), anyLong(), any(), any(), anyInt())).thenReturn(Map.of());
        when(webhooks.createEndpoint(anyLong(), anyMap())).thenThrow(new ChannelRejection(409, "WEBHOOK_INBOUND_UNAVAILABLE", "x"));
        when(webhooks.mutateEndpoint(anyLong(), anyLong(), anyMap())).thenThrow(new ChannelRejection(409, "WEBHOOK_INBOUND_UNAVAILABLE", "x"));
        ok(get("/api/im/webhook/endpoints").param("owner_type", "CLUB").param("owner_id", "10").param("channel_id", "100"));
        verify(webhooks).listEndpoints(7L, "CLUB", 10L, 100L, null, 20);
        ok(get("/api/im/webhook/endpoints/4"));
        verify(webhooks).endpoint(7L, 4L);
        ok(get("/api/im/webhook/endpoints/4/logs").param("result", "REJECTED"));
        verify(webhooks).endpointLogs(7L, 4L, "REJECTED", null, 20);
        for (MockHttpServletRequestBuilder r : new MockHttpServletRequestBuilder[] { post("/api/im/webhook/endpoints"),
                put("/api/im/webhook/endpoints/4"), delete("/api/im/webhook/endpoints/4"), post("/api/im/webhook/endpoints/4/rotate-token") })
            mvc.perform(r.contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.err").value("WEBHOOK_INBOUND_UNAVAILABLE"));
        verify(webhooks, times(3)).mutateEndpoint(eq(7L), eq(4L), anyMap());
    }
}
