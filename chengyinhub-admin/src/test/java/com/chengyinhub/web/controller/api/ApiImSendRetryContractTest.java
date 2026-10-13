package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiImSendRetryContractTest {
    private IImService service;
    private MockMvc mvc;
    private static final String KEY = "im_http_retry_00001";

    @BeforeEach
    void setUp() {
        service = mock(IImService.class);
        ApiImController controller = spy(new ApiImController());
        doReturn(42L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "imService", service);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void formBindingPreservesRetryKeyAndUsesAuthenticatedSender() throws Exception {
        ImMessage message = new ImMessage();
        message.setId(61L);
        message.setSenderId(42L);
        message.setClientMessageId(KEY);
        message.setRequestHash("internal-digest");
        when(service.send(42L, 26L, null, 1, "hello", null, KEY)).thenReturn(message);
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/im/send").param("conversation_id", "26")
                    .param("msg_type", "1").param("content", "hello")
                    .param("client_message_id", KEY).param("sender_id", "999"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.id").value(61))
                    .andExpect(jsonPath("$.data.senderId").value(42))
                    .andExpect(jsonPath("$.data.clientMessageId").value(KEY))
                    .andExpect(jsonPath("$.data.requestHash").doesNotExist());
        }
        verify(service, times(2)).send(42L, 26L, null, 1, "hello", null, KEY);
        verifyNoMoreInteractions(service);
    }

    @Test
    void conflictRemainsBusinessFailureAndLegacyRequestKeepsOriginalContract() throws Exception {
        when(service.send(42L, 26L, null, 1, "changed", null, KEY))
                .thenThrow(new ServiceException("message key conflict", 409));
        mvc.perform(post("/api/im/send").param("conversation_id", "26")
                .param("msg_type", "1").param("content", "changed").param("client_message_id", KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.data").doesNotExist());
        ImMessage legacy = new ImMessage();
        legacy.setId(62L);
        when(service.send(42L, 26L, null, 1, "hello", null)).thenReturn(legacy);
        mvc.perform(post("/api/im/send").param("conversation_id", "26").param("content", "hello"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(62));
        verify(service).send(42L, 26L, null, 1, "hello", null);
    }
}
