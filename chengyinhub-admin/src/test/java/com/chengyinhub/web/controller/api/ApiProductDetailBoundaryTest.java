package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.PmsProduct;
import com.chengyinhub.business.domain.PmsSku;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IPmsProductService;
import com.chengyinhub.business.service.IPmsSkuService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApiProductDetailBoundaryTest {
    ApiProductController controller;
    IPmsProductService products;
    IMmsMerchantService merchants;
    IPmsSkuService skus;
    PmsProduct product;
    MmsMerchant merchant;

    @BeforeEach void setup() {
        controller = new ApiProductController();
        products = mock(IPmsProductService.class);
        merchants = mock(IMmsMerchantService.class);
        skus = mock(IPmsSkuService.class);
        ReflectionTestUtils.setField(controller, "pmsProductService", products);
        ReflectionTestUtils.setField(controller, "merchantService", merchants);
        ReflectionTestUtils.setField(controller, "pmsSkuService", skus);
        product = new PmsProduct();
        product.setId(7L); product.setMerchantId(3L); product.setPublishStatus(1); product.setDelFlag(0);
        merchant = new MmsMerchant();
        merchant.setId(3L); merchant.setStatus(1L); merchant.setAccountStatus(1L); merchant.setDelFlag(0);
        when(products.selectPmsProductById(7L)).thenReturn(product);
        when(merchants.selectMmsMerchantById(3L)).thenReturn(merchant);
    }

    @Test void hiddenDeletedOrUnknownProductsAreRejectedBeforeSkuRead() {
        product.setPublishStatus(0);
        assertFalse(controller.producInfo("7").isSuccess());
        product.setPublishStatus(1); product.setDelFlag(1);
        assertFalse(controller.producInfo("7").isSuccess());
        product.setDelFlag(null);
        assertFalse(controller.producInfo("7").isSuccess());
        verifyNoInteractions(skus, merchants);
    }

    @Test void closedOrUnreviewedMerchantCannotExposeProduct() {
        merchant.setAccountStatus(0L);
        assertFalse(controller.producInfo("7").isSuccess());
        merchant.setAccountStatus(1L); merchant.setStatus(0L);
        assertFalse(controller.producInfo("7").isSuccess());
        merchant.setStatus(1L); merchant.setDelFlag(1);
        assertFalse(controller.producInfo("7").isSuccess());
        verifyNoInteractions(skus);
    }

    @Test void publicProjectionExcludesAuditFieldsAndFiltersHtmlAndForeignSkus() {
        product.setProductName("公开商品"); product.setCreateBy("internal"); product.setRemark("private");
        product.setDetailHtml("<b>说明</b><script>alert(1)</script><img src=\"https://example.com/a.png\" onerror=\"alert(2)\">");
        PmsSku own = new PmsSku(); own.setId(8L); own.setProductId(7L); own.setOutSkuId("private");
        PmsSku foreign = new PmsSku(); foreign.setId(9L); foreign.setProductId(10L);
        when(skus.selectPmsSkuList(any())).thenReturn(Arrays.asList(own, foreign));
        AjaxResult result = controller.producInfo("7");
        assertTrue(result.isSuccess());
        Map<?, ?> detail = (Map<?, ?>) result.get("data");
        assertEquals("公开商品", detail.get("productName"));
        assertFalse(detail.containsKey("createBy")); assertFalse(detail.containsKey("remark"));
        String html = (String) detail.get("detailHtml");
        assertFalse(html.contains("<script")); assertFalse(html.contains("onerror"));
        List<?> rows = (List<?>) detail.get("skuList");
        assertEquals(1, rows.size()); assertFalse(((Map<?, ?>) rows.get(0)).containsKey("outSkuId"));
    }
}
