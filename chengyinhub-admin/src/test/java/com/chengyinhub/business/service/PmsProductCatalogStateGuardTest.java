package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.PmsProduct;
import com.chengyinhub.business.domain.dto.ProductPublishStatusDTO;
import com.chengyinhub.business.mapper.PmsProductMapper;
import com.chengyinhub.business.service.impl.PmsProductServiceImpl;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * G5-10f: 商品目录的上下架只允许走不携带 SKU 的专用写口；商品实际被 C 端购物车/订单引用，
 * 因而物理删除在四重退役检查完成前必须 fail-closed。
 */
class PmsProductCatalogStateGuardTest {

    private PmsProductMapper productMapper;
    private PmsProductServiceImpl service;

    @BeforeEach
    void setUp() {
        productMapper = mock(PmsProductMapper.class);
        service = new PmsProductServiceImpl();
        ReflectionTestUtils.setField(service, "pmsProductMapper", productMapper);
    }

    @Test
    void invalidCatalogStateIsRejectedBeforeAnyReadOrWrite() {
        ProductPublishStatusDTO request = publishRequest(7L, 3);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.updateProductPublishStatus(request, "catalog-admin"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getCode());
        verifyNoInteractions(productMapper);
    }

    @Test
    void missingOrDeletedProductIsRejectedBeforeStatusWrite() {
        ProductPublishStatusDTO request = publishRequest(7L, 1);
        when(productMapper.selectPmsProductById(7L)).thenReturn(null);

        ServiceException missing = assertThrows(ServiceException.class,
                () -> service.updateProductPublishStatus(request, "catalog-admin"));

        assertEquals(HttpStatus.NOT_FOUND, missing.getCode());
        verify(productMapper, never()).updatePmsProductPublishStatus(any(), any(), any(), any(), any());

        PmsProduct deleted = new PmsProduct();
        deleted.setDelFlag(2);
        when(productMapper.selectPmsProductById(7L)).thenReturn(deleted);

        ServiceException deletedException = assertThrows(ServiceException.class,
                () -> service.updateProductPublishStatus(request, "catalog-admin"));

        assertEquals(HttpStatus.NOT_FOUND, deletedException.getCode());
        verify(productMapper, never()).updatePmsProductPublishStatus(any(), any(), any(), any(), any());
    }

    @Test
    void pendingStateRemainsReachableAndWriteUsesServerOperatorWithSnapshot() {
        ProductPublishStatusDTO request = publishRequest(7L, 0);
        PmsProduct stored = new PmsProduct();
        stored.setDelFlag(0);
        stored.setPublishStatus(2);
        stored.setCreateBy("catalog-owner");
        when(productMapper.selectPmsProductById(7L)).thenReturn(stored);
        when(productMapper.updatePmsProductPublishStatus(any(), any(), any(), any(), any())).thenReturn(1);

        int rows = service.updateProductPublishStatus(request, "catalog-admin");

        assertEquals(1, rows);
        verify(productMapper).updatePmsProductPublishStatus(
                eq(7L), eq(2), eq(0), eq("catalog-admin"), any(Date.class));
    }

    @Test
    void statusWritePreservesExistingProductEditPermissionScope() {
        ProductPublishStatusDTO request = publishRequest(7L, 1);
        PmsProduct stored = new PmsProduct();
        stored.setDelFlag(0);
        stored.setPublishStatus(0);
        stored.setCreateBy("catalog-owner");
        when(productMapper.selectPmsProductById(7L)).thenReturn(stored);
        when(productMapper.updatePmsProductPublishStatus(any(), any(), any(), any(), any())).thenReturn(1);

        int rows = service.updateProductPublishStatus(request, "catalog-operator");

        assertEquals(1, rows);
        verify(productMapper).updatePmsProductPublishStatus(
                eq(7L), eq(0), eq(1), eq("catalog-operator"), any(Date.class));
    }

    @Test
    void statusWriteThatLosesTheRowFailsClosed() {
        ProductPublishStatusDTO request = publishRequest(7L, 1);
        PmsProduct stored = new PmsProduct();
        stored.setDelFlag(0);
        stored.setPublishStatus(2);
        stored.setCreateBy("catalog-admin");
        when(productMapper.selectPmsProductById(7L)).thenReturn(stored);
        when(productMapper.updatePmsProductPublishStatus(any(), any(), any(), any(), any())).thenReturn(0);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.updateProductPublishStatus(request, "catalog-admin"));

        assertEquals(HttpStatus.CONFLICT, exception.getCode());
    }

    @Test
    void physicalDeleteIsDisabledUntilFourWayRetirementCheckCompletes() {
        ServiceException one = assertThrows(ServiceException.class,
                () -> service.deletePmsProductById(7L));
        ServiceException many = assertThrows(ServiceException.class,
                () -> service.deletePmsProductByIds(new Long[] { 7L, 8L }));

        assertEquals(HttpStatus.FORBIDDEN, one.getCode());
        assertEquals(HttpStatus.FORBIDDEN, many.getCode());
        verify(productMapper, never()).deletePmsProductById(any(Long.class));
        verify(productMapper, never()).deletePmsProductByIds(any(Long[].class));
    }

    private static ProductPublishStatusDTO publishRequest(Long id, Integer publishStatus) {
        ProductPublishStatusDTO request = new ProductPublishStatusDTO();
        request.setId(id);
        request.setPublishStatus(publishStatus);
        return request;
    }
}
