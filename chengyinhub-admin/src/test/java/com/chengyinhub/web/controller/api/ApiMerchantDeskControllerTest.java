package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.service.IMerchantDeskService;
import com.chengyinhub.business.service.support.MerchantDeskSwitch;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 开关默认关:任何端点都只回 F4_DISABLED,不解析商家身份、不碰服务。 */
class ApiMerchantDeskControllerTest {

    private ApiMerchantDeskController controller;
    private ISysConfigService config;
    private IMerchantAccessService accessService;
    private IMerchantDeskService deskService;

    @BeforeEach
    void setUp() {
        config = mock(ISysConfigService.class);
        MerchantDeskSwitch deskSwitch = new MerchantDeskSwitch();
        ReflectionTestUtils.setField(deskSwitch, "configService", config);
        accessService = mock(IMerchantAccessService.class);
        deskService = mock(IMerchantDeskService.class);
        controller = spy(new ApiMerchantDeskController());
        ReflectionTestUtils.setField(controller, "deskSwitch", deskSwitch);
        ReflectionTestUtils.setField(controller, "merchantAccessService", accessService);
        ReflectionTestUtils.setField(controller, "merchantDeskService", deskService);
        doReturn(501L).when(controller).getAppUserId();
    }

    @Test
    void switchMissing_returnsDisabled_withoutTouchingAccessOrService() {
        AjaxResult result = controller.saveProduct(new HashMap<>());
        assertEquals(404, result.get(AjaxResult.CODE_TAG));
        assertEquals("F4_DISABLED", result.get("errorCode"));
        verifyNoInteractions(accessService, deskService);
    }

    @Test
    void switchNotStrictTrue_staysDisabled() {
        when(config.selectConfigByKey(MerchantDeskSwitch.CONFIG_KEY)).thenReturn("yes");
        assertEquals("F4_DISABLED", controller.stores().get("errorCode"));
        when(config.selectConfigByKey(MerchantDeskSwitch.CONFIG_KEY)).thenThrow(new RuntimeException("db down"));
        assertEquals("F4_DISABLED", controller.reconciliation().get("errorCode"));
        verifyNoInteractions(deskService);
    }

    @Test
    void enabled_requiresMerchantPermission_beforeService() {
        when(config.selectConfigByKey(MerchantDeskSwitch.CONFIG_KEY)).thenReturn("true");
        when(accessService.require(anyLong(), anyString())).thenThrow(new ServiceException("商家身份无效或权限不足", 403));
        AjaxResult result = controller.reconciliation();
        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        verify(accessService).require(501L, MerchantPermission.FINANCE_READ);
        verifyNoInteractions(deskService);
    }

    @Test
    void enabled_resolveCode_rejectsNodeTaskCode() {
        when(config.selectConfigByKey(MerchantDeskSwitch.CONFIG_KEY)).thenReturn("true");
        when(accessService.require(501L, MerchantPermission.VERIFY))
                .thenReturn(new MerchantAccessContext(501L, 11L, 501L, MerchantRoleCode.MERCHANT_OWNER));
        Map<String, Object> body = new HashMap<>();
        body.put("code", "poi1.55.abcdefghijklmnop");
        body.put("mode", "MANUAL");
        AjaxResult result = controller.resolveCode(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        assertEquals(false, data.get("accepted"));
        assertEquals("F4_NODE_TASK_CODE_NOT_VOUCHER", data.get("errorCode"));
        verify(deskService, never()).receipt(any(), any());
    }

    /** D2:工作台开着、商品与履约子开关缺省关闭时,三个写入口按标准 F4_DISABLED 拒绝且不进服务;打开后放行。 */
    @Test
    void productWrites_refuseUntilProductsSwitchOn_evenWithDeskEnabled() {
        when(config.selectConfigByKey(MerchantDeskSwitch.CONFIG_KEY)).thenReturn("true");
        MerchantAccessContext access = new MerchantAccessContext(501L, 11L, 501L, MerchantRoleCode.MERCHANT_OWNER);
        when(accessService.require(501L, MerchantPermission.PROJECT_MANAGE)).thenReturn(access);
        for (AjaxResult result : java.util.List.of(controller.saveProduct(new HashMap<>()),
                controller.saveSku(new HashMap<>()), controller.delistProduct(new HashMap<>()))) {
            assertEquals(404, result.get(AjaxResult.CODE_TAG));
            assertEquals("F4_DISABLED", result.get("errorCode"));
        }
        verifyNoInteractions(deskService);
        when(config.selectConfigByKey(MerchantDeskSwitch.PRODUCTS_KEY)).thenReturn("true");
        when(deskService.saveSku(access, java.util.Collections.emptyMap())).thenReturn(Map.of("state", "APPLIED"));
        assertEquals(200, controller.saveSku(new HashMap<>()).get(AjaxResult.CODE_TAG));
        verify(deskService).saveSku(access, java.util.Collections.emptyMap());
    }

    @Test
    void notLoggedIn_isRejectedFirst() {
        doReturn(null).when(controller).getAppUserId();
        assertEquals(500, controller.stores().get(AjaxResult.CODE_TAG));
        verifyNoInteractions(config, accessService, deskService);
    }
    @Test
    void productDetail_preservesNestedIds_inSerializedResponse_withoutMutatingServiceFacts() throws Exception {
        when(config.selectConfigByKey(MerchantDeskSwitch.CONFIG_KEY)).thenReturn("true");
        MerchantAccessContext access = new MerchantAccessContext(501L, 11L, 501L, MerchantRoleCode.MERCHANT_OWNER);
        when(accessService.require(501L, MerchantPermission.PROJECT_MANAGE)).thenReturn(access);
        long id = 9007199254740993L;
        Map<String, Object> sku = Map.of("skuId", id + 2, "stock", 9, "ruleVersion", 2);
        Map<String, Object> source = Map.of("productId", id, "skus", java.util.List.of(sku), "price", 125);
        when(deskService.productDetail(access, id)).thenReturn(source);
        AjaxResult response = controller.productDetail(Map.of("productId", Long.toString(id)));
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        var data = json.readTree(json.writeValueAsString(response)).get("data");
        assertEquals(Long.toString(id), data.get("productId").textValue());
        assertEquals(Long.toString(id + 2), data.get("skus").get(0).get("skuId").textValue());
        assertEquals(9, data.get("skus").get(0).get("stock").intValue());
        assertEquals(2, data.get("skus").get(0).get("ruleVersion").intValue());
        assertEquals(125, data.get("price").intValue());
        assertEquals(id, source.get("productId"));
        verify(deskService).productDetail(access, id);
    }

    @Test
    void receipt_preservesTargetId_andOriginalRequestId() {
        when(config.selectConfigByKey(MerchantDeskSwitch.CONFIG_KEY)).thenReturn("true");
        MerchantAccessContext access = new MerchantAccessContext(501L, 11L, 501L, MerchantRoleCode.MERCHANT_OWNER);
        when(accessService.require(501L, MerchantPermission.BASIC_READ)).thenReturn(access);
        when(deskService.receipt(access, "sku-save-original"))
                .thenReturn(Map.of("targetId", 9007199254740993L, "requestId", "sku-save-original", "state", "APPLIED"));
        Map<?, ?> data = (Map<?, ?>) controller.receipt(Map.of("requestId", "sku-save-original")).get("data");
        assertEquals("9007199254740993", data.get("targetId"));
        assertEquals("sku-save-original", data.get("requestId"));
        assertEquals("APPLIED", data.get("state"));
    }

    @Test
    void wire_keepsNullsDecimalsAndCounts_andHandlesListsOfClaimsAndBatches() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", null);
        row.put("claimId", 9007199254740993L);
        row.put("batchId", 9007199254740995L);
        row.put("payableNetWithoutClaim", new java.math.BigDecimal("-12.34"));
        row.put("claimCount", 2L);
        Map<?, ?> wire = (Map<?, ?>) ((java.util.List<?>) MerchantDeskWire.response(java.util.List.of(row))).get(0);
        assertEquals(null, wire.get("id"));
        assertEquals("9007199254740993", wire.get("claimId"));
        assertEquals("9007199254740995", wire.get("batchId"));
        assertEquals(new java.math.BigDecimal("-12.34"), wire.get("payableNetWithoutClaim"));
        assertEquals(2L, wire.get("claimCount"));
    }

    @Test
    void slotPageBindsExactCursorAndUsesProjectPermission() {
        when(config.selectConfigByKey(MerchantDeskSwitch.CONFIG_KEY)).thenReturn("true");
        MerchantAccessContext access = new MerchantAccessContext(501L, 11L, 501L, MerchantRoleCode.MERCHANT_OWNER);
        when(accessService.require(501L, MerchantPermission.PROJECT_MANAGE)).thenReturn(access);
        long slotId = 9007199254740993L, startAt = 1792980000000L;
        when(deskService.capacitySlotPage(access, 8L, "2026-10-26", 14, startAt, slotId))
                .thenReturn(Map.of("skuId", 8L, "rows", java.util.List.of(), "hasMore", false,
                        "next", Map.of("afterStartAt", startAt, "afterSlotId", slotId)));
        Map<?, ?> data = (Map<?, ?>) controller.skuSlotPage(Map.of("skuId", "8", "fromDate", "2026-10-26",
                "days", 14, "afterStartAt", startAt, "afterSlotId", Long.toString(slotId))).get("data");
        assertEquals("8", data.get("skuId"));
        assertEquals(Long.toString(slotId), ((Map<?, ?>) data.get("next")).get("afterSlotId"));
        assertEquals(startAt, ((Map<?, ?>) data.get("next")).get("afterStartAt"));
        verify(accessService).require(501L, MerchantPermission.PROJECT_MANAGE);
        verify(deskService).capacitySlotPage(access, 8L, "2026-10-26", 14, startAt, slotId);
    }

    @Test
    void frozenPageRequiresFinanceScopeAndIgnoresClientMerchantIdentity() {
        when(config.selectConfigByKey(MerchantDeskSwitch.CONFIG_KEY)).thenReturn("true");
        MerchantAccessContext access = new MerchantAccessContext(501L, 11L, 501L, MerchantRoleCode.MERCHANT_OWNER);
        when(accessService.require(501L, MerchantPermission.FINANCE_READ)).thenReturn(access);
        when(deskService.frozenBatchPage(access, "2026-10", 9007199254740993L))
                .thenReturn(Map.of("rows", java.util.List.of(Map.of("batchId", 9007199254740991L)), "hasMore", false));
        Map<?, ?> data = (Map<?, ?>) controller.frozenPage(Map.of("merchantId", "999", "ownerMemberId", "999",
                "afterPeriodYm", "2026-10", "afterBatchId", "9007199254740993")).get("data");
        assertEquals("9007199254740991", ((Map<?, ?>) ((java.util.List<?>) data.get("rows")).get(0)).get("batchId"));
        verify(deskService).frozenBatchPage(access, "2026-10", 9007199254740993L);
    }

    @Test
    void frozenPageDeniesUnauthorizedStaffBeforeReading() {
        when(config.selectConfigByKey(MerchantDeskSwitch.CONFIG_KEY)).thenReturn("true");
        when(accessService.require(501L, MerchantPermission.FINANCE_READ)).thenThrow(new ServiceException("denied", 403));
        assertEquals(403, controller.frozenPage(Map.of()).get(AjaxResult.CODE_TAG));
        verifyNoInteractions(deskService);
    }

}
