package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IWechatPayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WechatCallbackHttpAckTest {
    private MockMvc mvc;
    private IWechatPayService gateway;

    @BeforeEach
    void setUp() {
        ApiRegistrationController controller = new ApiRegistrationController();
        gateway = mock(IWechatPayService.class);
        ReflectionTestUtils.setField(controller, "wechatPayService", gateway);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void committedPaymentAndRefundReturn204WithoutBody() throws Exception {
        when(gateway.handlePayCallback(anyString(), anyMap())).thenReturn("{\"code\":\"SUCCESS\"}");
        when(gateway.handleRefundCallback(anyString(), anyMap())).thenReturn("{\"code\":\"SUCCESS\"}");
        for (String path : new String[]{"callback", "refund-callback"}) {
            mvc.perform(post("/api/registration/" + path).contentType("application/json").content("{}"))
                    .andExpect(status().isNoContent()).andExpect(content().string(""));
        }
    }

    @Test
    void failedMissingMalformedAndNonStringAcksNeverReturn2xx() throws Exception {
        for (String ack : new String[]{"{\"code\":\"FAIL\"}", null, "", "not-json", "[]",
                "{\"message\":\"SUCCESS\"}", "{\"code\":200}", "{\"code\":\"success\"}"}) {
            when(gateway.handlePayCallback(anyString(), anyMap())).thenReturn(ack);
            when(gateway.handleRefundCallback(anyString(), anyMap())).thenReturn(ack);
            for (String path : new String[]{"callback", "refund-callback"}) {
                mvc.perform(post("/api/registration/" + path).contentType("application/json").content("{}"))
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.code").value("FAIL"));
            }
        }
    }

    @Test
    void unexpectedGatewayFailureReturnsRetryableStatusWithoutEchoingItsMessage() throws Exception {
        when(gateway.handlePayCallback(anyString(), anyMap())).thenThrow(new IllegalStateException("private-detail"));
        when(gateway.handleRefundCallback(anyString(), anyMap())).thenThrow(new IllegalStateException("private-detail"));
        for (String path : new String[]{"callback", "refund-callback"}) {
            mvc.perform(post("/api/registration/" + path).contentType("application/json").content("{}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value("通知处理尚未确认"));
        }
    }
}
