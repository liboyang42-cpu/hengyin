package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A2-3(P0)中标旁路:后台裸 CRUD add/edit 不得直接置/改 audit_status=1 ——
 * 那会绕过 select→confirmWinnerIfPending 的 A-13 CAS + 逐节点唯一守卫,凭空造中标/改中标。
 * 定稿:add 强制待调配(0)、edit 剔除 audit_status(不改中标态);中标只能走 select/reject。
 */
class CmsRegistrationMerchantControllerAwardBypassTest {

    private CmsRegistrationMerchantController controller(ICmsRegistrationMerchantService svc) {
        com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer scopeAuthorizer =
                mock(com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer.class);
        org.mockito.Mockito.when(scopeAuthorizer.canViewTopic(org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        CmsRegistrationMerchantController c = new CmsRegistrationMerchantController();
        ReflectionTestUtils.setField(c, "cmsRegistrationMerchantService", svc);
        ReflectionTestUtils.setField(c, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        return c;
    }

    @Test
    void add_forcesPendingNotAwarded() {
        ICmsRegistrationMerchantService svc = mock(ICmsRegistrationMerchantService.class);
        CmsRegistrationMerchant m = new CmsRegistrationMerchant();
        m.setTopicId(1L);
        m.setAuditStatus(1L); // 攻击者试图凭空造一行中标
        m.setStatus(1L);
        m.setReason("伪造驳回原因");
        m.setSharingRate(new java.math.BigDecimal("99.99"));
        m.setSharingAmount(new java.math.BigDecimal("999.99"));

        controller(svc).add(m);

        ArgumentCaptor<CmsRegistrationMerchant> cap = ArgumentCaptor.forClass(CmsRegistrationMerchant.class);
        verify(svc).insertCmsRegistrationMerchant(cap.capture());
        assertEquals(Long.valueOf(0L), cap.getValue().getAuditStatus(),
                "裸建单必须被强制为待调配(0),不得直接中标");
        assertEquals(Long.valueOf(0L), cap.getValue().getStatus(),
                "裸建单不得伪造 status=1 绕过调配决策");
        assertNull(cap.getValue().getReason(), "待调配报名不能预写驳回原因");
        assertNull(cap.getValue().getSharingRate(), "报名阶段不能预写历史分润比例");
        assertNull(cap.getValue().getSharingAmount(), "报名阶段不能预写历史分润金额");
    }

    @Test
    void edit_isRetiredAndCannotMutateDispatchOrSettlementFields() {
        ICmsRegistrationMerchantService svc = mock(ICmsRegistrationMerchantService.class);
        CmsRegistrationMerchant stored = new CmsRegistrationMerchant();
        stored.setId(1L);
        stored.setTopicId(1L);
        org.mockito.Mockito.when(svc.selectCmsRegistrationMerchantById(1L)).thenReturn(stored);
        CmsRegistrationMerchant m = new CmsRegistrationMerchant();
        m.setId(1L);
        m.setAuditStatus(1L); // 试图经通用编辑直改为中标
        m.setStatus(1L);
        m.setReason("伪造驳回原因");
        m.setSharingRate(new java.math.BigDecimal("99.99"));
        m.setSharingAmount(new java.math.BigDecimal("999.99"));

        com.chengyinhub.common.core.domain.AjaxResult result = controller(svc).edit(m);

        assertNotEquals(200, result.get(com.chengyinhub.common.core.domain.AjaxResult.CODE_TAG),
                "通用编辑已退役，不得返回成功");
        verify(svc, never()).updateCmsRegistrationMerchant(org.mockito.Mockito.any());
    }
}
