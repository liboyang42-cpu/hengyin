package com.chengyinhub.business.service;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.MerchantCrmSegment;
import com.chengyinhub.business.domain.dto.MerchantCrmSegmentFilter;
import com.chengyinhub.business.domain.dto.MerchantCrmSegmentRequest;
import com.chengyinhub.business.domain.vo.MerchantCrmSegmentVO;
import com.chengyinhub.business.mapper.MerchantCrmSegmentMapper;
import com.chengyinhub.business.service.impl.MerchantCrmSegmentServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantCrmSegmentServiceImplTest {
    private static final long OWNER = 900L;
    private static final long MERCHANT = 77L;
    private static final long PRINCIPAL = OWNER;
    private static final long OPERATOR_PRINCIPAL = 901L;
    private IMerchantAccessService access;
    private MerchantCrmSegmentMapper mapper;
    private MerchantCrmSegmentServiceImpl service;

    @BeforeEach
    void setUp() {
        access = mock(IMerchantAccessService.class);
        mapper = mock(MerchantCrmSegmentMapper.class);
        service = new MerchantCrmSegmentServiceImpl();
        ReflectionTestUtils.setField(service, "merchantAccessService", access);
        ReflectionTestUtils.setField(service, "merchantCrmSegmentMapper", mapper);
        when(access.require(PRINCIPAL, MerchantPermission.CRM_SEGMENT)).thenReturn(context());
        when(access.require(PRINCIPAL, MerchantPermission.CRM_READ)).thenReturn(context());
    }

    @Test
    void savePersistsOnlyNormalizedWhitelistUnderResolvedOwner() {
        when(mapper.insertSegment(any())).thenAnswer(invocation -> {
            ((MerchantCrmSegment) invocation.getArgument(0)).setId(7L);
            return 1;
        });
        MerchantCrmSegmentRequest request = request("复购客", "refunded");
        request.getFilter().setTagId(3L);
        request.getFilter().setSourceType(2);
        request.getFilter().setSourceStart("2026-08-01");
        request.getFilter().setSourceEnd("2026-08-23");

        MerchantCrmSegmentVO out = service.save(PRINCIPAL, request);

        ArgumentCaptor<MerchantCrmSegment> saved = ArgumentCaptor.forClass(MerchantCrmSegment.class);
        verify(mapper).insertSegment(saved.capture());
        assertEquals(MERCHANT, saved.getValue().getMerchantId());
        assertEquals(PRINCIPAL, saved.getValue().getActorMemberId());
        assertEquals("abnormal", out.getFilter().getSegment());
        assertEquals(3L, out.getFilter().getTagId());
    }

    @Test
    void arbitrarySegmentExpressionFailsBeforePersistence() {
        MerchantCrmSegmentRequest request = request("危险条件", "repeat' OR 1=1 --");
        ServiceException error = assertThrows(ServiceException.class, () -> service.save(PRINCIPAL, request));
        assertEquals(400, error.getCode());
        verify(mapper, never()).insertSegment(any());
    }

    @Test
    void listAlwaysUsesResolvedOwnerScope() {
        MerchantCrmSegment row = new MerchantCrmSegment();
        row.setId(7L);
        row.setName("复购客");
        row.setFilterJson("{\"segment\":\"repeat\"}");
        when(mapper.selectActiveSegments(MERCHANT, 50)).thenReturn(Collections.singletonList(row));

        List<MerchantCrmSegmentVO> rows = service.list(PRINCIPAL);

        assertEquals(1, rows.size());
        verify(mapper).selectActiveSegments(MERCHANT, 50);
    }

    @Test
    void marketingOperatorCanSaveAndListSegmentsForExactStore() {
        when(access.require(OPERATOR_PRINCIPAL, MerchantPermission.CRM_READ)).thenReturn(
                new MerchantAccessContext(OPERATOR_PRINCIPAL, MERCHANT, OWNER,
                        MerchantRoleCode.MERCHANT_MARKETING));
        when(access.require(OPERATOR_PRINCIPAL, MerchantPermission.CRM_SEGMENT)).thenReturn(
                new MerchantAccessContext(OPERATOR_PRINCIPAL, MERCHANT, OWNER,
                        MerchantRoleCode.MERCHANT_MARKETING));
        when(mapper.insertSegment(any())).thenAnswer(invocation -> {
            ((MerchantCrmSegment) invocation.getArgument(0)).setId(8L);
            return 1;
        });
        when(mapper.selectActiveSegments(MERCHANT, 50)).thenReturn(Collections.emptyList());

        MerchantCrmSegmentVO saved = service.save(OPERATOR_PRINCIPAL, request("运营分群", "repeat"));
        List<MerchantCrmSegmentVO> listed = service.list(OPERATOR_PRINCIPAL);

        assertEquals(8L, saved.getId());
        assertEquals(0, listed.size());
        ArgumentCaptor<MerchantCrmSegment> row = ArgumentCaptor.forClass(MerchantCrmSegment.class);
        verify(mapper).insertSegment(row.capture());
        assertEquals(MERCHANT, row.getValue().getMerchantId());
        assertEquals(OPERATOR_PRINCIPAL, row.getValue().getActorMemberId());
        verify(mapper).selectActiveSegments(MERCHANT, 50);
    }

    @Test
    void sameOwnerOtherStoreCannotReplayOrReadSegment() {
        MerchantCrmSegment storeB = new MerchantCrmSegment();
        storeB.setId(99L);
        storeB.setMerchantId(88L);
        storeB.setActorMemberId(PRINCIPAL);
        storeB.setName("复购客");
        storeB.setFilterJson("{\"segment\":\"repeat\"}");
        storeB.setRequestId("segment-request-1");
        when(mapper.selectByRequestId(OWNER, "segment-request-1")).thenReturn(storeB);
        when(mapper.insertSegment(any())).thenAnswer(invocation -> {
            ((MerchantCrmSegment) invocation.getArgument(0)).setId(7L);
            return 1;
        });

        MerchantCrmSegmentVO out = service.save(PRINCIPAL, request("复购客", "repeat"));

        assertEquals(7L, out.getId());
        verify(mapper).selectByRequestId(MERCHANT, "segment-request-1");
        verify(mapper).insertSegment(any());
    }

    private MerchantCrmSegmentRequest request(String name, String segment) {
        MerchantCrmSegmentFilter filter = new MerchantCrmSegmentFilter();
        filter.setSegment(segment);
        MerchantCrmSegmentRequest out = new MerchantCrmSegmentRequest();
        out.setName(name);
        out.setFilter(filter);
        out.setRequestId("segment-request-1");
        return out;
    }

    private MerchantAccessContext context() {
        return new MerchantAccessContext(PRINCIPAL, MERCHANT, OWNER, MerchantRoleCode.MERCHANT_OWNER);
    }
}
