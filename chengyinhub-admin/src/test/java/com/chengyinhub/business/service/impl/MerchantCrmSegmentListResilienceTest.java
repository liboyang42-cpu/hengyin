package com.chengyinhub.business.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.MerchantCrmSegment;
import com.chengyinhub.business.domain.vo.MerchantCrmSegmentVO;
import com.chengyinhub.business.mapper.MerchantCrmSegmentMapper;
import com.chengyinhub.business.service.IMerchantAccessService;

/** R9 BE-05: a damaged saved filter must not make the merchant's whole segment list fail. */
class MerchantCrmSegmentListResilienceTest
{
    @Test
    void damagedRowIsLeftOutAndTheRestIsListed()
    {
        MerchantCrmSegmentServiceImpl service = new MerchantCrmSegmentServiceImpl();
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        MerchantCrmSegmentMapper mapper = mock(MerchantCrmSegmentMapper.class);
        ReflectionTestUtils.setField(service, "merchantAccessService", access);
        ReflectionTestUtils.setField(service, "merchantCrmSegmentMapper", mapper);
        when(access.require(eq(9L), any())).thenReturn(new MerchantAccessContext(9L, 8001L, 9L, MerchantRoleCode.MERCHANT_MANAGER));
        when(mapper.selectActiveSegments(eq(8001L), anyInt())).thenReturn(Arrays.asList(
                segment(1L, "{\"segment\":\"repeat\"}"), segment(2L, "{not json"), segment(3L, "{\"tagId\":5}")));

        List<MerchantCrmSegmentVO> listed = service.list(9L);

        assertEquals(2, listed.size());
        assertEquals(Long.valueOf(1L), listed.get(0).getId());
        assertEquals(Long.valueOf(3L), listed.get(1).getId());
    }

    private static MerchantCrmSegment segment(Long id, String filterJson)
    {
        MerchantCrmSegment row = new MerchantCrmSegment();
        row.setId(id);
        row.setName("s" + id);
        row.setFilterJson(filterJson);
        return row;
    }
}
