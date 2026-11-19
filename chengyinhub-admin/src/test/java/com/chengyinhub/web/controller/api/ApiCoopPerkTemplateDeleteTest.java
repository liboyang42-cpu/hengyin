package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** MC-022:常备权益模板删除 0 行(不存在或非本店)不能回「已删除」。 */
class ApiCoopPerkTemplateDeleteTest {
    private static final Long PRINCIPAL = 7002L;
    private static final Long OWNER = 9001L;

    private ApiCoopController controller(ICoopInviteService invites) {
        ApiCoopController c = new ApiCoopController() {
            @Override public Long getAppUserId() { return PRINCIPAL; }
        };
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(PRINCIPAL, MerchantPermission.COOP_MANAGE))
                .thenReturn(new MerchantAccessContext(PRINCIPAL, 11L, OWNER, MerchantRoleCode.MERCHANT_MANAGER));
        ReflectionTestUtils.setField(c, "merchantAccessService", access);
        ReflectionTestUtils.setField(c, "coopInviteService", invites);
        return c;
    }

    private Map<String, Object> body(long id) {
        Map<String, Object> b = new HashMap<>();
        b.put("id", id);
        return b;
    }

    @Test
    void zeroRowsReportsNotFoundOrForbidden() {
        ICoopInviteService invites = mock(ICoopInviteService.class);
        when(invites.deletePerkTemplate(5L, OWNER)).thenReturn(0);
        AjaxResult r = controller(invites).perkTemplateDelete(body(5L));
        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals("模板不存在或无权删除", r.get(AjaxResult.MSG_TAG));
    }

    @Test
    void deletedRowReportsSuccess() {
        ICoopInviteService invites = mock(ICoopInviteService.class);
        when(invites.deletePerkTemplate(5L, OWNER)).thenReturn(1);
        assertEquals("已删除", controller(invites).perkTemplateDelete(body(5L)).get(AjaxResult.MSG_TAG));
    }
}
