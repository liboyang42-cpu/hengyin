package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.mapper.MerchantDeskMapper;
import com.chengyinhub.business.service.IMerchantDeskService;
import com.chengyinhub.business.service.MerchantDeskException;
import com.chengyinhub.business.service.impl.MerchantDeskServiceImpl;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import com.chengyinhub.business.service.support.MerchantDeskSwitch;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

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

    @Test
    void receiptKeepsNotFoundAndDropsAppliedRowWithoutAction() {
        when(config.selectConfigByKey(MerchantDeskSwitch.CONFIG_KEY)).thenReturn("true");
        MerchantAccessContext access = new MerchantAccessContext(501L, 11L, 501L, MerchantRoleCode.MERCHANT_OWNER);
        when(accessService.require(501L, MerchantPermission.BASIC_READ)).thenReturn(access);
        Map<String, Object> missing = new HashMap<>();
        missing.put("requestId", "desk-req-01");
        missing.put("state", "NOT_FOUND");
        when(deskService.receipt(access, "desk-req-01")).thenReturn(missing);
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", "desk-req-01");
        @SuppressWarnings("unchecked")
        Map<String, Object> notFound = (Map<String, Object>) controller.receipt(body).get(AjaxResult.DATA_TAG);
        assertEquals("NOT_FOUND", notFound.get("state"));

        Map<String, Object> bare = new HashMap<>();
        bare.put("requestId", "desk-req-02");
        bare.put("state", "APPLIED");
        when(deskService.receipt(access, "desk-req-02")).thenReturn(bare);
        body.put("requestId", "desk-req-02");
        assertEquals(null, controller.receipt(body).get(AjaxResult.DATA_TAG));

        Map<String, Object> applied = new HashMap<>();
        applied.put("requestId", "desk-req-03");
        applied.put("state", "APPLIED");
        applied.put("action", "SKU_SAVE");
        applied.put("targetType", "pms_sku");
        applied.put("targetId", 44L);
        when(deskService.receipt(access, "desk-req-03")).thenReturn(applied);
        body.put("requestId", "desk-req-03");
        @SuppressWarnings("unchecked")
        Map<String, Object> kept = (Map<String, Object>) controller.receipt(body).get(AjaxResult.DATA_TAG);
        assertEquals("SKU_SAVE", kept.get("action"));
        assertEquals("pms_sku", kept.get("targetType"));
        assertEquals(44L, kept.get("targetId"));
    }

    @Test
    void notLoggedIn_isRejectedFirst() {
        doReturn(null).when(controller).getAppUserId();
        assertEquals(500, controller.stores().get(AjaxResult.CODE_TAG));
        verifyNoInteractions(config, accessService, deskService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveSkuEntryCallsOriginalWriterThenReceiptStaysApplied() {
        when(config.selectConfigByKey(MerchantDeskSwitch.CONFIG_KEY)).thenReturn("true");
        doReturn(777L).when(controller).getAppUserId();
        MerchantAccessContext access = new MerchantAccessContext(777L, 11L, 501L, MerchantRoleCode.MERCHANT_MANAGER);
        when(accessService.require(777L, MerchantPermission.PROJECT_MANAGE)).thenReturn(access);
        when(accessService.require(777L, MerchantPermission.BASIC_READ)).thenReturn(access);
        MerchantDeskMapper mapper = mock(MerchantDeskMapper.class, withSettings().defaultAnswer(invocation ->
                Map.class.equals(invocation.getMethod().getReturnType()) ? null
                        : RETURNS_DEFAULTS.answer(invocation)));
        MerchantDeskServiceImpl service = new MerchantDeskServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "mediaReferences", mock(AppMediaReferenceGuard.class));
        ReflectionTestUtils.setField(controller, "merchantDeskService", service);

        AjaxResult bad = controller.saveSku(skuBody("88.00\u2028"));
        assertEquals(400, bad.get(AjaxResult.CODE_TAG));
        assertEquals(MerchantDeskException.BAD_REQUEST, bad.get("errorCode"));
        verify(mapper, never()).updateSku(anyMap());
        verify(mapper, never()).insertReceipt(anyMap());

        Map<String, Object> product = new HashMap<>();
        product.put("publishStatus", 2);
        when(mapper.selectProductForUpdate(11L, 900L)).thenReturn(product);
        Map<String, Object> sku = new HashMap<>();
        sku.put("productId", 900L);
        when(mapper.selectSkuForUpdate(11L, 44L)).thenReturn(sku);
        when(mapper.updateSku(anyMap())).thenReturn(1);
        Map<String, Object> currentRule = new HashMap<>();
        currentRule.put("version", 3);
        when(mapper.selectBookingRule(11L, 44L)).thenReturn(currentRule);
        when(mapper.updateBookingRule(anyMap())).thenReturn(1);

        Map<String, Object> body = skuBody("88.00\n");
        body.put("skuId", 44L);
        body.put("ruleVersion", 3);
        body.put("bookable", true);
        body.put("slotMinutes", 30);
        body.put("capacityPerSlot", 2);
        AjaxResult saved = controller.saveSku(body);
        assertEquals(200, saved.get(AjaxResult.CODE_TAG));
        Map<String, Object> applied = (Map<String, Object>) saved.get(AjaxResult.DATA_TAG);
        assertEquals("APPLIED", applied.get("state"));
        assertEquals(false, applied.get("replayed"));
        assertEquals("SKU_SAVE", applied.get("action"));
        assertEquals("pms_sku", applied.get("targetType"));
        assertEquals(44L, applied.get("targetId"));

        ArgumentCaptor<Map<String, Object>> skuRow = ArgumentCaptor.forClass(Map.class);
        verify(mapper).updateSku(skuRow.capture());
        assertEquals(11L, skuRow.getValue().get("merchantId"));
        assertEquals("777", skuRow.getValue().get("actor"));
        assertEquals(new BigDecimal("88.00"), skuRow.getValue().get("price"));
        ArgumentCaptor<Map<String, Object>> rule = ArgumentCaptor.forClass(Map.class);
        verify(mapper).updateBookingRule(rule.capture());
        assertEquals(3, rule.getValue().get("expectedVersion"));
        assertEquals(1, rule.getValue().get("bookable"));
        assertEquals(30, rule.getValue().get("slotMinutes"));
        assertEquals(2, rule.getValue().get("capacityPerSlot"));
        ArgumentCaptor<Map<String, Object>> receipt = ArgumentCaptor.forClass(Map.class);
        verify(mapper).insertReceipt(receipt.capture());
        assertEquals(501L, receipt.getValue().get("ownerMemberId"));
        assertEquals(777L, receipt.getValue().get("actorMemberId"));
        assertEquals("SKU_SAVE", receipt.getValue().get("action"));
        assertEquals("pms_sku", receipt.getValue().get("targetType"));
        when(mapper.selectReceipt(501L, "req-sku-00007")).thenReturn(receipt.getValue());

        Map<String, Object> readBody = new HashMap<>();
        readBody.put("requestId", "req-sku-00007");
        Map<String, Object> kept = (Map<String, Object>) controller.receipt(readBody).get(AjaxResult.DATA_TAG);
        assertEquals("APPLIED", kept.get("state"));
        assertEquals("SKU_SAVE", kept.get("action"));
        assertEquals("pms_sku", kept.get("targetType"));
        assertEquals(44L, kept.get("targetId"));
        assertEquals("req-sku-00007", kept.get("requestId"));

        Map<String, Object> again = (Map<String, Object>) controller.saveSku(new HashMap<>(body)).get(AjaxResult.DATA_TAG);
        assertEquals(true, again.get("replayed"));
        assertEquals("SKU_SAVE", again.get("action"));
        verify(mapper, times(1)).updateSku(anyMap());
        verify(mapper, times(1)).updateBookingRule(anyMap());
        assertEquals(3, body.get("ruleVersion"));

        when(mapper.selectProduct(11L, 900L)).thenReturn(product);
        Map<String, Object> read = new HashMap<>();
        read.put("skuId", 44L);
        read.put("price", new BigDecimal("88.00"));
        read.put("ruleVersion", 4);
        when(mapper.selectSkus(11L, 900L)).thenReturn(List.of(read));
        Map<String, Object> detailBody = new HashMap<>();
        detailBody.put("productId", 900);
        Map<String, Object> detail = (Map<String, Object>) controller.productDetail(detailBody).get(AjaxResult.DATA_TAG);
        List<Map<String, Object>> skus = (List<Map<String, Object>>) detail.get("skus");
        assertEquals(4, skus.get(0).get("ruleVersion"));
        assertEquals(new BigDecimal("88.00"), skus.get(0).get("price"));

        read.put("ruleVersion", 5);
        Map<String, Object> later = (Map<String, Object>) controller.productDetail(detailBody).get(AjaxResult.DATA_TAG);
        List<Map<String, Object>> laterSkus = (List<Map<String, Object>>) later.get("skus");
        assertEquals(5, laterSkus.get(0).get("ruleVersion"));
        Map<String, Object> still = (Map<String, Object>) controller.receipt(readBody).get(AjaxResult.DATA_TAG);
        assertEquals("APPLIED", still.get("state"));
        assertEquals("SKU_SAVE", still.get("action"));
        assertEquals(44L, still.get("targetId"));

        body.put("price", "99.00");
        AjaxResult conflict = controller.saveSku(body);
        assertEquals(409, conflict.get(AjaxResult.CODE_TAG));
        assertEquals(MerchantDeskException.IDEMPOTENCY_CONFLICT, conflict.get("errorCode"));
        verify(mapper, times(1)).updateSku(anyMap());
    }

    private static Map<String, Object> skuBody(String price) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", "req-sku-00007");
        body.put("productId", 900L);
        body.put("skuName", "周末场");
        body.put("price", price);
        body.put("stock", 5);
        return body;
    }
}
