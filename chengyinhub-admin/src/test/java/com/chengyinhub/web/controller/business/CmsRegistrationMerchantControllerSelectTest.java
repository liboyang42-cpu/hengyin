package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.dto.DispatchResult;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.IDispatchService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 后台调配端点只做权限/协议适配，竞争位规则全部由 DispatchService 承担。 */
class CmsRegistrationMerchantControllerSelectTest {

    private static final Long REGISTRATION_ID = 10L;

    @Test
    void selectDelegatesToDispatchServiceAndKeepsInviteNextStep() {
        IDispatchService dispatchService = mock(IDispatchService.class);
        when(dispatchService.confirmRegistration(REGISTRATION_ID))
                .thenReturn(DispatchResult.selected(REGISTRATION_ID));

        AjaxResult result = controller(dispatchService).select(REGISTRATION_ID);

        assertEquals(200, result.get("code"));
        verify(dispatchService).confirmRegistration(REGISTRATION_ID);
        String message = String.valueOf(result.get("msg"));
        org.junit.jupiter.api.Assertions.assertTrue(message.contains("合作邀约"));
    }

    @Test
    void dispatchFailureMessagePassesThroughWithoutSecondImplementation() {
        IDispatchService dispatchService = mock(IDispatchService.class);
        when(dispatchService.confirmRegistration(REGISTRATION_ID)).thenReturn(
                DispatchResult.rejected(DispatchResult.Code.SLOT_OCCUPIED, "该竞争位已有中标商家"));

        AjaxResult result = controller(dispatchService).select(REGISTRATION_ID);

        assertEquals(500, result.get("code"));
        assertEquals("该竞争位已有中标商家", result.get("msg"));
    }

    @Test
    void controllerNoLongerOwnsCompetitionSlotAlgorithms() {
        for (String removed : Arrays.asList("slotHasOtherWinner", "sweepLosers", "hasNodeSlot")) {
            boolean present = Arrays.stream(CmsRegistrationMerchantController.class.getDeclaredMethods())
                    .anyMatch(method -> method.getName().equals(removed));
            assertFalse(present, "竞争位算法不得回流 controller: " + removed);
        }
        for (String gone : Arrays.asList("cmsActivityService", "omsTicketService", "coopInviteService")) {
            boolean present = Arrays.stream(CmsRegistrationMerchantController.class.getDeclaredFields())
                    .anyMatch(field -> field.getName().equals(gone));
            assertFalse(present, "调配端点不得恢复复制层依赖: " + gone);
        }
    }

    private CmsRegistrationMerchantController controller(IDispatchService dispatchService) {
        ICmsRegistrationMerchantService registrationService = mock(ICmsRegistrationMerchantService.class);
        com.chengyinhub.business.domain.CmsRegistrationMerchant registration =
                new com.chengyinhub.business.domain.CmsRegistrationMerchant();
        registration.setId(REGISTRATION_ID);
        registration.setTopicId(1L);
        when(registrationService.selectCmsRegistrationMerchantById(REGISTRATION_ID)).thenReturn(registration);
        com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer scopeAuthorizer =
                mock(com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer.class);
        when(scopeAuthorizer.canViewTopic(1L)).thenReturn(true);
        CmsRegistrationMerchantController controller = new CmsRegistrationMerchantController();
        ReflectionTestUtils.setField(controller, "dispatchService", dispatchService);
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantService", registrationService);
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        return controller;
    }
}
