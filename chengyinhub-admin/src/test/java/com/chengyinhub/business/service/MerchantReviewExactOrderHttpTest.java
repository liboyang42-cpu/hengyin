package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.MerchantReviewEligibilityVO;
import com.chengyinhub.business.domain.vo.MerchantReviewPageVO;
import com.chengyinhub.web.controller.api.ApiMerchantReviewController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MerchantReviewExactOrderHttpTest {
    @Test
    void postBindsExactOrderQueryAndGetIsNotAnAlternateRoute() throws Exception {
        IMerchantReviewService service = mock(IMerchantReviewService.class);
        // Test only principal delegation and MVC binding, not the deployment security filter chain.
        ApiMerchantReviewController controller = new ApiMerchantReviewController() {
            @Override public Long getAppUserId() { return 101L; }
        };
        ReflectionTestUtils.setField(controller, "merchantReviewService", service);
        when(service.publicList(101L, 7L, 1, 20, Long.MAX_VALUE)).thenReturn(new MerchantReviewPageVO());
        when(service.publicList(eq(101L), eq(7L), eq(1), eq(20), isNull())).thenReturn(new MerchantReviewPageVO());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        String url = "/api/merchant/reviews/public";
        mvc.perform(post(url).param("merchantRowId", "7").param("registrationId", Long.toString(Long.MAX_VALUE))
                .param("pageNum", "1").param("pageSize", "20")).andExpect(status().isOk());
        verify(service).publicList(101L, 7L, 1, 20, Long.MAX_VALUE);
        mvc.perform(post(url).param("merchantRowId", "7").param("pageNum", "1").param("pageSize", "20"))
                .andExpect(status().isOk());
        verify(service).publicList(101L, 7L, 1, 20, null);
        mvc.perform(get(url).param("merchantRowId", "7").param("registrationId", "303"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(post(url).param("merchantRowId", "7").param("registrationId", "9223372036854775808"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void qualificationIdsSerializeAsExactStringsEvenAboveJavascriptSafeRange() throws Exception {
        MerchantReviewEligibilityVO value = new MerchantReviewEligibilityVO();
        value.setRegistrationId(Long.MAX_VALUE); value.setRequestedRegistrationId(Long.MAX_VALUE);
        var tree = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(value));
        assertEquals(Long.toString(Long.MAX_VALUE), tree.get("registrationId").textValue());
        assertEquals(Long.toString(Long.MAX_VALUE), tree.get("requestedRegistrationId").textValue());
    }
}
