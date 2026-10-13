package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.PmsProduct;
import com.chengyinhub.business.domain.dto.ProductPublishStatusDTO;
import com.chengyinhub.business.service.IPmsProductService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** G5-10f 商品目录控制器的服务端身份与物理删除负控。 */
class PmsProductControllerSafetyTest {

    private IPmsProductService productService;
    private TestablePmsProductController controller;

    @BeforeEach
    void setUp() {
        productService = mock(IPmsProductService.class);
        controller = new TestablePmsProductController("catalog-admin");
        ReflectionTestUtils.setField(controller, "pmsProductService", productService);
    }

    @Test
    void physicalDeleteHttpEndpointRejectsWithoutCallingService() {
        ServiceException exception = assertThrows(ServiceException.class,
                () -> controller.remove(new Long[] { 7L, 8L }));

        assertEquals(HttpStatus.FORBIDDEN, exception.getCode());
        verify(productService, never()).deletePmsProductByIds(any(Long[].class));
    }

    @Test
    void catalogStatusEndpointUsesAuthenticatedOperator() {
        ProductPublishStatusDTO request = new ProductPublishStatusDTO();
        request.setId(7L);
        request.setPublishStatus(1);
        when(productService.updateProductPublishStatus(any(ProductPublishStatusDTO.class), eq("catalog-admin"))).thenReturn(1);

        AjaxResult result = controller.updatePublishStatus(request);

        assertEquals(HttpStatus.SUCCESS, result.get(AjaxResult.CODE_TAG));
        verify(productService).updateProductPublishStatus(request, "catalog-admin");
    }

    @Test
    void genericCreateAndEditCannotForgeOrChangeCatalogState() {
        PmsProduct created = new PmsProduct();
        created.setPublishStatus(1);
        created.setCreateBy("forged-creator");
        when(productService.insertPmsProduct(any(PmsProduct.class))).thenReturn(1);

        controller.add(created);

        verify(productService).insertPmsProduct(argThat(product ->
                Integer.valueOf(0).equals(product.getPublishStatus())
                        && "catalog-admin".equals(product.getCreateBy())));

        PmsProduct edited = new PmsProduct();
        edited.setId(7L);
        edited.setPublishStatus(1);
        edited.setUpdateBy("forged-editor");
        when(productService.updatePmsProduct(any(PmsProduct.class))).thenReturn(1);

        controller.edit(edited);

        verify(productService).updatePmsProduct(argThat(product ->
                product.getPublishStatus() == null
                        && "catalog-admin".equals(product.getUpdateBy())
                        && Long.valueOf(7L).equals(product.getId())));
    }

    private static class TestablePmsProductController extends PmsProductController {
        private final String username;

        private TestablePmsProductController(String username) {
            this.username = username;
        }

        @Override
        public String getUsername() {
            return username;
        }
    }
}
