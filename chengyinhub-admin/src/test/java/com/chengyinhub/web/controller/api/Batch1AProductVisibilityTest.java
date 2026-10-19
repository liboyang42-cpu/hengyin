package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.PmsProduct;
import com.chengyinhub.business.domain.PmsSku;
import com.chengyinhub.business.service.IPmsProductService;
import com.chengyinhub.business.service.IPmsSkuService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Batch1AProductVisibilityTest {

    @Test
    void publicInfoReturnsPublishedProductWithItsSku() {
        IPmsProductService productService = mock(IPmsProductService.class);
        IPmsSkuService skuService = mock(IPmsSkuService.class);
        ApiProductController controller = new ApiProductController();
        ReflectionTestUtils.setField(controller, "pmsProductService", productService);
        ReflectionTestUtils.setField(controller, "pmsSkuService", skuService);
        PmsProduct product = new PmsProduct();
        product.setId(6L);
        product.setPublishStatus(1);
        product.setDelFlag(0);
        PmsSku sku = new PmsSku();
        sku.setId(61L);
        when(productService.selectPmsProductById(6L)).thenReturn(product);
        when(skuService.selectPmsSkuList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Collections.singletonList(sku));

        AjaxResult result = controller.producInfo("6");

        assertEquals(com.chengyinhub.common.constant.HttpStatus.SUCCESS, result.get(AjaxResult.CODE_TAG));
        assertSame(product, result.get(AjaxResult.DATA_TAG));
        assertEquals(Collections.singletonList(sku), product.getSkuList());
        ArgumentCaptor<PmsSku> query = ArgumentCaptor.forClass(PmsSku.class);
        verify(skuService).selectPmsSkuList(query.capture());
        assertEquals(Long.valueOf(6L), query.getValue().getProductId());
    }

    @Test
    void publicInfoReturnsNotFoundWhenProductServiceReturnsNull() {
        IPmsProductService productService = mock(IPmsProductService.class);
        IPmsSkuService skuService = mock(IPmsSkuService.class);
        ApiProductController controller = new ApiProductController();
        ReflectionTestUtils.setField(controller, "pmsProductService", productService);
        ReflectionTestUtils.setField(controller, "pmsSkuService", skuService);
        when(productService.selectPmsProductById(9L)).thenReturn(null);

        AjaxResult result = controller.producInfo("9");

        assertFalse(result.isSuccess());
        assertEquals("商品不存在", result.get(AjaxResult.MSG_TAG));
        verify(skuService, never()).selectPmsSkuList(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void publicInfoHidesUnpublishedProductBeforeReadingSku() {
        IPmsProductService productService = mock(IPmsProductService.class);
        IPmsSkuService skuService = mock(IPmsSkuService.class);
        ApiProductController controller = new ApiProductController();
        ReflectionTestUtils.setField(controller, "pmsProductService", productService);
        ReflectionTestUtils.setField(controller, "pmsSkuService", skuService);
        PmsProduct product = new PmsProduct();
        product.setId(7L);
        product.setPublishStatus(0);
        product.setDelFlag(0);
        when(productService.selectPmsProductById(7L)).thenReturn(product);

        AjaxResult result = controller.producInfo("7");

        assertFalse(result.isSuccess());
        verify(skuService, never()).selectPmsSkuList(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void publicInfoHidesDeletedProductBeforeReadingSku() {
        IPmsProductService productService = mock(IPmsProductService.class);
        IPmsSkuService skuService = mock(IPmsSkuService.class);
        ApiProductController controller = new ApiProductController();
        ReflectionTestUtils.setField(controller, "pmsProductService", productService);
        ReflectionTestUtils.setField(controller, "pmsSkuService", skuService);
        PmsProduct product = new PmsProduct();
        product.setId(8L);
        product.setPublishStatus(1);
        product.setDelFlag(2);
        when(productService.selectPmsProductById(8L)).thenReturn(product);

        AjaxResult result = controller.producInfo("8");

        assertFalse(result.isSuccess());
        verify(skuService, never()).selectPmsSkuList(org.mockito.ArgumentMatchers.any());
    }
}
