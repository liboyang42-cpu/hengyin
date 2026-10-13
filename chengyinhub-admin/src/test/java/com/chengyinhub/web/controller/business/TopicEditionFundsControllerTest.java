package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.service.ITopicEditionFundsService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class TopicEditionFundsControllerTest {
    private ITopicEditionFundsService fundsService;
    private ITopicOperationsScopeAuthorizer scopeAuthorizer;
    private TopicEditionFundsController controller;

    @BeforeEach
    void setUp() {
        fundsService = mock(ITopicEditionFundsService.class);
        scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        controller = new TopicEditionFundsController(fundsService, scopeAuthorizer);
    }

    @Test
    void currentWithoutTopicReadScopeFailsClosed() {
        AjaxResult result = controller.current(23L);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verifyNoInteractions(fundsService);
    }

    @Test
    void repairRequiresIndependentTopicWriteScope() {
        when(scopeAuthorizer.canViewTopic(23L)).thenReturn(true);
        when(scopeAuthorizer.canEditTopic(23L)).thenReturn(false);

        AjaxResult result = controller.repairPurchaseKind(23L);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verifyNoInteractions(fundsService);
    }

    @Test
    void authorizedRepairUsesExplicitAdminRepairSeam() {
        when(scopeAuthorizer.canEditTopic(23L)).thenReturn(true);
        when(fundsService.repairUnknownExplorePurchases(23L)).thenReturn(2);

        AjaxResult result = controller.repairPurchaseKind(23L);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(2, result.get(AjaxResult.DATA_TAG));
        verify(fundsService).repairUnknownExplorePurchases(23L);
    }

    @Test
    void financeOverviewRequiresObjectReadScope() {
        when(scopeAuthorizer.canViewTopic(23L)).thenReturn(false);

        AjaxResult result = controller.overview(23L);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(fundsService, never()).currentFinanceOverview(23L);
    }

    @Test
    void costWriteRequiresIndependentEditScope() {
        when(scopeAuthorizer.canViewTopic(23L)).thenReturn(true);
        when(scopeAuthorizer.canEditTopic(23L)).thenReturn(false);

        assertEquals(500, controller.saveCost(23L,
                new com.chengyinhub.business.domain.TopicEditionCost()).get(AjaxResult.CODE_TAG));
        verify(fundsService, never()).saveCost(any(), any(), any());
    }

    /**
     * 工时写入口已从本 Controller 删除（旧 PUT /{topicId}/hours 能一把改掉冻结计划与确认状态）。
     * 结构断言由 LegacyClubEditionHoursBackdoorClosedTest 负责；这里只钉住本类不再有写工时的方法。
     */
    @Test
    void thisControllerNoLongerExposesAnyHoursWriteEndpoint() {
        for (java.lang.reflect.Method method : TopicEditionFundsController.class.getDeclaredMethods()) {
            assertEquals(false, method.getName().toLowerCase().contains("hours"),
                    "工时写入口不得回到本 Controller: " + method.getName());
        }
    }
}
