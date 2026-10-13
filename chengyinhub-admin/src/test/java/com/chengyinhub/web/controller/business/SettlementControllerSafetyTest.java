package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.dto.SettlementGenerateDTO;
import com.chengyinhub.business.service.ISettlementService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * A3-1(P0):结算「手工生成批次」端点封口。
 *
 * <p>generateBatch 全仓仅被本端点调用、UI 零调用;真实结算由系统1(settleActivity/settleTopic)
 * 自建批次。保留这条手工旁路 = 与系统1并行的重复生成/双花隐患,故一律 FORBIDDEN。
 */
class SettlementControllerSafetyTest {

    @Test
    void generateRejectsManualSettlementBatchCreation() {
        ISettlementService service = mock(ISettlementService.class);
        SettlementController controller = new SettlementController();
        ReflectionTestUtils.setField(controller, "settlementService", service);

        AjaxResult result = controller.generate(new SettlementGenerateDTO());

        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("手工生成结算批次已停用"),
                "应被结算生成封口拦下,实际文案:" + result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(service);
    }
}
