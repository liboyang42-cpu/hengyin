package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * A3-1(P0):用户余额明细转只读。
 *
 * <p>余额明细是财务台账(currentBalance/afterBalance/changeBalance),只应由真实交易系统写入。
 * 后台裸新增 = 凭空造余额、裸改 = 篡改金额、裸删 = 抹掉资金变动证据,三者都是资损面,一律 FORBIDDEN;
 * 只保留 list/query/export 读路径。
 */
class UmsMemberBalanceDetailControllerSafetyTest {

    private UmsMemberBalanceDetailController controllerWith(IUmsMemberBalanceDetailService service) {
        UmsMemberBalanceDetailController controller = new UmsMemberBalanceDetailController();
        ReflectionTestUtils.setField(controller, "umsMemberBalanceDetailService", service);
        return controller;
    }

    @Test
    void addRejectsFabricatingBalanceLedgerRow() {
        IUmsMemberBalanceDetailService service = mock(IUmsMemberBalanceDetailService.class);
        AjaxResult result = controllerWith(service).add(new UmsMemberBalanceDetail());

        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("不支持后台新增"),
                "应被余额台账封口拦下,实际文案:" + result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(service);
    }

    @Test
    void editRejectsTamperingBalanceAmounts() {
        IUmsMemberBalanceDetailService service = mock(IUmsMemberBalanceDetailService.class);
        AjaxResult result = controllerWith(service).edit(new UmsMemberBalanceDetail());

        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("不支持后台修改"),
                "应被余额台账封口拦下,实际文案:" + result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(service);
    }

    @Test
    void removeRejectsErasingLedgerEvidence() {
        IUmsMemberBalanceDetailService service = mock(IUmsMemberBalanceDetailService.class);
        AjaxResult result = controllerWith(service).remove(new Long[] { 1L });

        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("不支持删除"),
                "应被余额台账封口拦下,实际文案:" + result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(service);
    }
}
