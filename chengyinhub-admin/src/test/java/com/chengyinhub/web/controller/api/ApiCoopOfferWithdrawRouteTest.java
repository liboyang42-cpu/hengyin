package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.service.IMerchantOfferService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiCoopOfferWithdrawRouteTest {
    private IMerchantAccessService access;
    private IMerchantOfferService offers;
    private MockMvc mvc;
    private Long principal;

    @BeforeEach
    void setUp() {
        principal = 7002L;
        access = mock(IMerchantAccessService.class);
        offers = mock(IMerchantOfferService.class);
        ApiCoopController controller = new ApiCoopController() {
            @Override public Long getAppUserId() { return principal; }
        };
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        ReflectionTestUtils.setField(controller, "merchantOfferService", offers);
        when(access.require(7002L, MerchantPermission.COOP_MANAGE)).thenReturn(
                new MerchantAccessContext(7002L, 11L, 9001L, MerchantRoleCode.MERCHANT_MANAGER));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void requiredSupplyUsesAuthenticatedOwnerAndReturnsPausedState() throws Exception {
        when(offers.withdrawOwnChapterOffer(77L, 9001L, "暂停营业")).thenReturn(true);
        mvc.perform(post("/api/coop/offer/withdraw").contentType(MediaType.APPLICATION_JSON)
                .content("{\"offerId\":77,\"merchantId\":9999,\"reason\":\"  暂停营业  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.enrollmentPaused").value(true));
        verify(offers).withdrawOwnChapterOffer(77L, 9001L, "暂停营业");
    }

    @Test
    void optionalSupplyDoesNotClaimEnrollmentPaused() throws Exception {
        when(offers.withdrawOwnChapterOffer(77L, 9001L, "暂停营业")).thenReturn(false);
        mvc.perform(post("/api/coop/offer/withdraw").contentType(MediaType.APPLICATION_JSON)
                .content("{\"offerId\":77,\"reason\":\"暂停营业\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.enrollmentPaused").value(false));
    }

    @Test
    void inactiveMerchantCannotWithdraw() throws Exception {
        when(access.require(7002L, MerchantPermission.COOP_MANAGE))
                .thenThrow(new ServiceException("商家身份无效或权限不足", 403));
        mvc.perform(post("/api/coop/offer/withdraw").contentType(MediaType.APPLICATION_JSON)
                .content("{\"offerId\":77,\"reason\":\"暂停营业\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(offers);
    }

    @Test
    void unauthenticatedRequestDoesNotReachMerchantGuard() throws Exception {
        principal = null;
        mvc.perform(post("/api/coop/offer/withdraw").contentType(MediaType.APPLICATION_JSON)
                .content("{\"offerId\":77,\"reason\":\"暂停营业\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(500));
        verifyNoInteractions(access, offers);
    }

    @Test
    void malformedIdsAreRejectedBeforeBusinessWrite() throws Exception {
        for (String body : new String[]{"{}", "{\"offerId\":0}", "{\"offerId\":-1}",
                "{\"offerId\":\"bad\"}"}) {
            mvc.perform(post("/api/coop/offer/withdraw").contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(500));
        }
        verifyNoInteractions(offers);
    }

    @Test
    void emptyRequestBodyIsRejected() throws Exception {
        mvc.perform(post("/api/coop/offer/withdraw")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
        verifyNoInteractions(offers);
    }

    @Test
    void anotherMerchantsSupplyIsDeniedByService() throws Exception {
        when(offers.withdrawOwnChapterOffer(77L, 9001L, "暂停营业"))
                .thenThrow(new IllegalStateException("只能退出自己的章节供给"));
        mvc.perform(post("/api/coop/offer/withdraw").contentType(MediaType.APPLICATION_JSON)
                .content("{\"offerId\":77,\"reason\":\"暂停营业\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("只能退出自己的章节供给"));
    }

    @Test
    void missingReasonReachesExistingValidationWithoutInventingReason() throws Exception {
        when(offers.withdrawOwnChapterOffer(77L, 9001L, null))
                .thenThrow(new IllegalStateException("请填写1至500字的退出原因"));
        mvc.perform(post("/api/coop/offer/withdraw").contentType(MediaType.APPLICATION_JSON)
                .content("{\"offerId\":77}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("请填写1至500字的退出原因"));
    }
}
