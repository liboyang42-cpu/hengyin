package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MerchantCustomerProfile;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.vo.MerchantCrmProfileReconcileVO;
import com.chengyinhub.business.domain.vo.MerchantCrmSummaryVO;
import com.chengyinhub.business.mapper.MerchantCrmAppMapper;
import com.chengyinhub.business.mapper.MerchantCustomerProfileMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MerchantCrmServiceImplTest {

    @Test
    void listRequiresMerchantId() {
        MerchantCrmServiceImpl service = new MerchantCrmServiceImpl();

        assertThrows(IllegalArgumentException.class,
                () -> service.selectCustomerList(new MerchantCustomerProfile()));
    }

    @Test
    void listDelegatesWithMerchantScopedQuery() {
        MerchantCustomerProfileMapper mapper = mock(MerchantCustomerProfileMapper.class);
        MerchantCrmServiceImpl service = new MerchantCrmServiceImpl();
        ReflectionTestUtils.setField(service, "merchantCustomerProfileMapper", mapper);
        MerchantCustomerProfile query = new MerchantCustomerProfile();
        query.setMerchantId(9L);
        MerchantCustomerProfile row = new MerchantCustomerProfile();
        row.setMemberId(100L);
        when(mapper.selectCustomerList(any(MerchantCustomerProfile.class))).thenReturn(Collections.singletonList(row));

        List<MerchantCustomerProfile> rows = service.selectCustomerList(query);

        assertEquals(1, rows.size());
        verify(mapper).selectCustomerList(query);
    }

    @Test
    void aggregateRequiresOwnerMemberIdAndIncrementallyReconcilesRegistrationTruth() {
        MerchantCustomerProfileMapper mapper = mock(MerchantCustomerProfileMapper.class);
        MerchantCrmAppMapper appMapper = mock(MerchantCrmAppMapper.class);
        MmsMerchantMapper merchantMapper = mock(MmsMerchantMapper.class);
        MerchantCrmServiceImpl service = new MerchantCrmServiceImpl();
        ReflectionTestUtils.setField(service, "merchantCustomerProfileMapper", mapper);
        ReflectionTestUtils.setField(service, "merchantCrmAppMapper", appMapper);
        ReflectionTestUtils.setField(service, "mmsMerchantMapper", merchantMapper);
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(77L);
        merchant.setMemberId(9L);
        when(merchantMapper.selectMmsMerchantList(any(MmsMerchant.class)))
                .thenReturn(Collections.singletonList(merchant));
        when(appMapper.upsertOwnedCustomerProfiles(77L, 9L)).thenReturn(5);
        when(appMapper.markStaleCustomerProfiles(77L, 9L)).thenReturn(3);
        when(appMapper.countOwnedRegistrationCustomers(9L)).thenReturn(7L);
        when(appMapper.countActiveCustomerProfiles(77L)).thenReturn(7L);

        MerchantCrmProfileReconcileVO result = service.aggregateMerchantCustomers(9L);

        assertEquals(5, result.getUpsertedRows());
        assertEquals(3, result.getStaleRows());
        assertEquals(7L, result.getSourceCustomerCount());
        assertEquals(7L, result.getActiveProfileCount());
        assertEquals(true, result.getConsistent());
        verify(appMapper).upsertOwnedCustomerProfiles(77L, 9L);
        verify(appMapper).markStaleCustomerProfiles(77L, 9L);
        verify(appMapper).countOwnedRegistrationCustomers(9L);
        verify(appMapper).countActiveCustomerProfiles(77L);
        verifyNoInteractions(mapper);
        assertThrows(IllegalArgumentException.class, () -> service.aggregateMerchantCustomers(null));
        assertThrows(IllegalArgumentException.class, () -> service.aggregateMerchantCustomers(0L));
    }

    @Test
    void aggregateResolvesOneActiveMerchantWhenSameOwnerAlsoHasInactiveRow() {
        MerchantCrmAppMapper appMapper = mock(MerchantCrmAppMapper.class);
        MmsMerchantMapper merchantMapper = mock(MmsMerchantMapper.class);
        MerchantCrmServiceImpl service = new MerchantCrmServiceImpl();
        ReflectionTestUtils.setField(service, "merchantCrmAppMapper", appMapper);
        ReflectionTestUtils.setField(service, "mmsMerchantMapper", merchantMapper);
        MmsMerchant active = merchant(77L, 9L, 1L, 1L, 0);
        MmsMerchant inactive = merchant(78L, 9L, 0L, 1L, 0);
        List<MmsMerchant> stored = java.util.Arrays.asList(active, inactive);
        when(merchantMapper.selectMmsMerchantList(any(MmsMerchant.class))).thenAnswer(invocation -> {
            MmsMerchant query = invocation.getArgument(0);
            return stored.stream()
                    .filter(row -> query.getMemberId() == null || query.getMemberId().equals(row.getMemberId()))
                    .filter(row -> query.getStatus() == null || query.getStatus().equals(row.getStatus()))
                    .filter(row -> query.getAccountStatus() == null
                            || query.getAccountStatus().equals(row.getAccountStatus()))
                    .filter(row -> query.getDelFlag() == null || query.getDelFlag().equals(row.getDelFlag()))
                    .collect(Collectors.toList());
        });
        when(appMapper.countOwnedRegistrationCustomers(9L)).thenReturn(0L);
        when(appMapper.countActiveCustomerProfiles(77L)).thenReturn(0L);

        MerchantCrmProfileReconcileVO result = service.aggregateMerchantCustomers(9L);

        assertEquals(true, result.getConsistent());
        verify(appMapper).upsertOwnedCustomerProfiles(77L, 9L);
        verify(appMapper).markStaleCustomerProfiles(77L, 9L);
    }

    @Test
    void summaryRequiresMerchantId() {
        MerchantCustomerProfileMapper mapper = mock(MerchantCustomerProfileMapper.class);
        MerchantCrmServiceImpl service = new MerchantCrmServiceImpl();
        ReflectionTestUtils.setField(service, "merchantCustomerProfileMapper", mapper);
        MerchantCrmSummaryVO summary = new MerchantCrmSummaryVO();
        summary.setMerchantId(9L);
        summary.setCustomerCount(11L);
        when(mapper.selectSummaryByMerchantId(9L)).thenReturn(summary);

        MerchantCrmSummaryVO result = service.getSummary(9L);

        assertEquals(11L, result.getCustomerCount());
        assertThrows(IllegalArgumentException.class, () -> service.getSummary(null));
    }

    private MmsMerchant merchant(Long id, Long memberId, Long status, Long accountStatus, Integer delFlag) {
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(id);
        merchant.setMemberId(memberId);
        merchant.setStatus(status);
        merchant.setAccountStatus(accountStatus);
        merchant.setDelFlag(delFlag);
        return merchant;
    }
}
