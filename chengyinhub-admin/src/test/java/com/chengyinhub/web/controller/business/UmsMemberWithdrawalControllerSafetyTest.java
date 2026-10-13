package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.UmsMemberWithdrawal;
import com.chengyinhub.business.service.IUmsMemberWithdrawalService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class UmsMemberWithdrawalControllerSafetyTest {

    @Test
    void addRejectsManualBackendWithdrawalCreation() {
        IUmsMemberWithdrawalService service = mock(IUmsMemberWithdrawalService.class);
        UmsMemberWithdrawalController controller = new UmsMemberWithdrawalController();
        ReflectionTestUtils.setField(controller, "umsMemberWithdrawalService", service);

        AjaxResult result = controller.add(new UmsMemberWithdrawal());

        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        assertEquals("提现申请仅允许用户端提交", result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(service);
    }

    @Test
    void editRejectsBypassingWithdrawalReviewStateMachine() {
        IUmsMemberWithdrawalService service = mock(IUmsMemberWithdrawalService.class);
        UmsMemberWithdrawalController controller = new UmsMemberWithdrawalController();
        ReflectionTestUtils.setField(controller, "umsMemberWithdrawalService", service);

        AjaxResult result = controller.edit(new UmsMemberWithdrawal());

        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        assertEquals("提现资料不支持后台编辑，请使用审核接口", result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(service);
    }

    @Test
    void removeRejectsDeletingFinancialAuditRecords() {
        IUmsMemberWithdrawalService service = mock(IUmsMemberWithdrawalService.class);
        UmsMemberWithdrawalController controller = new UmsMemberWithdrawalController();
        ReflectionTestUtils.setField(controller, "umsMemberWithdrawalService", service);

        AjaxResult result = controller.remove(new Long[] { 1L });

        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        assertEquals("提现申请不支持删除，请通过审核流程处理", result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(service);
    }
}
