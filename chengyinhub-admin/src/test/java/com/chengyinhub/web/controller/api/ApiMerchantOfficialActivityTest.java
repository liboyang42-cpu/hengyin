package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiMerchantOfficialActivityTest {
    @Test
    @SuppressWarnings("unchecked")
    void publicDetail_exposesOnlyServiceVerifiedAwardedOfficialActivities() {
        IMmsMerchantService merchantService = mock(IMmsMerchantService.class);
        ICoopInviteService inviteService = mock(ICoopInviteService.class);
        ApiMerchantController controller = new ApiMerchantController();
        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchantService);
        ReflectionTestUtils.setField(controller, "coopInviteService", inviteService);
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(8L);
        merchant.setMemberId(201L);
        merchant.setStatus(1L);
        merchant.setAccountStatus(1L);
        merchant.setDelFlag(0);
        when(merchantService.selectMmsMerchantById(8L)).thenReturn(merchant);
        Map<String, Object> internal = new LinkedHashMap<>();
        internal.put("topicId", 7L);
        internal.put("title", "周末城市漫游");
        internal.put("cover", "cover.png");
        internal.put("inviteId", 11L);
        internal.put("shareRate", 35);
        internal.put("fixedFee", 80);
        internal.put("handleReason", "内部协商原因");
        internal.put("registrationId", 71L);
        List<Map<String, Object>> awarded = Collections.singletonList(internal);
        when(inviteService.listAwardedOfficialActivities(201L)).thenReturn(awarded);
        MmsMerchant request = new MmsMerchant();
        request.setId(8L);

        AjaxResult result = controller.publicDetail(request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        List<Map<String, Object>> publicRows =
                (List<Map<String, Object>>) result.get("officialActivities");
        assertEquals(Collections.singletonMap("topicId", 7L).get("topicId"),
                publicRows.get(0).get("topicId"));
        assertEquals("周末城市漫游", publicRows.get(0).get("title"));
        assertEquals("cover.png", publicRows.get(0).get("cover"));
        assertEquals(3, publicRows.get(0).size(), "匿名视图只允许主题公开字段");
        verify(inviteService).listAwardedOfficialActivities(201L);
    }
}
