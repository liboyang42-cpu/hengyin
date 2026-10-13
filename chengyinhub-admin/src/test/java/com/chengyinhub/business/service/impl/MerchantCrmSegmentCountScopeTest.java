package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.dto.MerchantCrmCustomerQuery;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.MerchantCrmAppMapper;
import com.chengyinhub.business.mapper.MerchantCustomerTagMapper;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.business.service.IPlayerRoleService;
import com.chengyinhub.common.config.ComplianceProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CU-M-102:客户页头「N 位 · 本月新增 M」是全量口径,底栏「当前筛选 · X 人」是筛选口径。
 *
 * <p>两条断言是一对,缺一条都判不严:
 *  · 计数不带筛选  —— 否则搜一个不存在的名字,页头跟着变「0 位 · 本月新增 0」,
 *    商家把筛选结果读成了客户总量;
 *  · 名单与 total 带筛选 —— 否则就是把筛选整个做掉了,底栏和名单会一起变成全量,
 *    那是另一个更贵的 bug。
 *
 * ⚠️ 负控:把 {@code unfilteredCustomerQuery} 退回直接传同一个 query ⇒ 第一条红;
 *  反过来把名单查询也换成全量副本 ⇒ 第二条红。
 */
class MerchantCrmSegmentCountScopeTest {

    private static final Long PRINCIPAL = 9002L;
    private static final Long MERCHANT = 11L;
    private static final Long OWNER = 9002L;

    private final IMerchantAccessService accessService = mock(IMerchantAccessService.class);
    private final MerchantCrmAppMapper mapper = mock(MerchantCrmAppMapper.class);
    private final MerchantCustomerTagMapper tagMapper = mock(MerchantCustomerTagMapper.class);
    private final MerchantCrmAppServiceImpl service = new MerchantCrmAppServiceImpl();

    MerchantCrmSegmentCountScopeTest() {
        ReflectionTestUtils.setField(service, "merchantAccessService", accessService);
        ReflectionTestUtils.setField(service, "merchantCrmAppMapper", mapper);
        ReflectionTestUtils.setField(service, "merchantCustomerTagMapper", tagMapper);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", mock(BusinessAuditEventMapper.class));
        ReflectionTestUtils.setField(service, "consentRecordService", mock(IConsentRecordService.class));
        ReflectionTestUtils.setField(service, "complianceProperties", mock(ComplianceProperties.class));
        ReflectionTestUtils.setField(service, "playerRoleService", mock(IPlayerRoleService.class));
    }

    private MerchantCrmCustomerQuery filteredQuery() {
        when(accessService.require(PRINCIPAL, MerchantPermission.CRM_READ)).thenReturn(
                new MerchantAccessContext(PRINCIPAL, MERCHANT, OWNER, MerchantRoleCode.MERCHANT_MANAGER));
        when(mapper.selectOwnedCustomerPage(eq(OWNER), any(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());
        when(mapper.selectOwnedCustomerCount(eq(OWNER), any())).thenReturn(0L);
        when(tagMapper.selectTagList(any())).thenReturn(Collections.emptyList());
        MerchantCrmCustomerQuery query = new MerchantCrmCustomerQuery();
        query.setKeyword("不存在的人");
        query.setSegment("repeat");
        query.setTagId(7L);
        query.setSourceType(2);
        query.setSourceStart("2026-09-01");
        query.setSourceEnd("2026-09-20");
        return query;
    }

    private MerchantCrmCustomerQuery capturedCountQuery() {
        ArgumentCaptor<MerchantCrmCustomerQuery> captor =
                ArgumentCaptor.forClass(MerchantCrmCustomerQuery.class);
        verify(mapper).selectOwnedSegmentCounts(eq(OWNER), captor.capture());
        return captor.getValue();
    }

    @Test
    void segmentCountsStayOnTheFullScopeWhileTheFilterIsApplied() {
        service.listCustomers(PRINCIPAL, filteredQuery());
        MerchantCrmCustomerQuery counts = capturedCountQuery();

        assertEquals(MERCHANT, counts.getMerchantId(), "全量口径也得锁在这一个商家名下,不能跨商家数人");
        assertNull(counts.getKeyword());
        assertNull(counts.getTagId());
        assertNull(counts.getSourceType());
        assertNull(counts.getSourceStartTime());
        assertNull(counts.getSourceEndExclusive());
        assertNull(counts.getSegment());
    }

    /** 另一半:名单与 total 照旧吃这次筛选,底栏「当前筛选 · X 人」才有意义。 */
    @Test
    void customerListAndTotalStillCarryTheAppliedFilters() {
        service.listCustomers(PRINCIPAL, filteredQuery());

        ArgumentCaptor<MerchantCrmCustomerQuery> captor =
                ArgumentCaptor.forClass(MerchantCrmCustomerQuery.class);
        verify(mapper).selectOwnedCustomerPage(eq(OWNER), captor.capture(), anyInt(), anyInt());
        MerchantCrmCustomerQuery listed = captor.getValue();
        assertEquals("不存在的人", listed.getKeyword());
        assertEquals(7L, listed.getTagId());
        assertEquals(2, listed.getSourceType());
        assertEquals("repeat", listed.getSegment());
        org.junit.jupiter.api.Assertions.assertNotNull(listed.getSourceStartTime());
        org.junit.jupiter.api.Assertions.assertNotNull(listed.getSourceEndExclusive());

        ArgumentCaptor<MerchantCrmCustomerQuery> total = ArgumentCaptor.forClass(MerchantCrmCustomerQuery.class);
        verify(mapper).selectOwnedCustomerCount(eq(OWNER), total.capture());
        assertEquals("不存在的人", total.getValue().getKeyword());
    }
}
