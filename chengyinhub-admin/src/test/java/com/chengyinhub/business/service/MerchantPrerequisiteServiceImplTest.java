package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.vo.MerchantOpsVO;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.impl.MerchantPrerequisiteServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MerchantPrerequisiteServiceImplTest {
    @Test
    void missingQualificationBlocksContinuationAndNeverInventsActionPage() {
        MmsMerchantMapper mapper = mock(MmsMerchantMapper.class);
        MmsMerchant merchant = completeMerchant();
        merchant.setBusinessLicense(null);
        when(mapper.selectMmsMerchantByMemberId(7L)).thenReturn(merchant);

        MerchantOpsVO.PrerequisiteChecklist result = new MerchantPrerequisiteServiceImpl(mapper).calculate(7L);

        assertFalse(result.isCanContinue());
        MerchantOpsVO.Prerequisite license = result.getItems().stream()
                .filter(item -> "BUSINESS_LICENSE".equals(item.getCode())).findFirst().orElseThrow(AssertionError::new);
        assertEquals("BLOCKED", license.getStatus());
        assertFalse(license.getActionTarget().getAvailable());
    }

    @Test
    void missingMerchantIsAVisibleBusinessError() {
        MmsMerchantMapper mapper = mock(MmsMerchantMapper.class);
        when(mapper.selectMmsMerchantByMemberId(7L)).thenReturn(null);
        ServiceException error = assertThrows(ServiceException.class,
                () -> new MerchantPrerequisiteServiceImpl(mapper).calculate(7L));
        assertEquals(Integer.valueOf(404), error.getCode());
    }

    private MmsMerchant completeMerchant() {
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(11L);
        merchant.setStatus(1L);
        merchant.setAccountStatus(1L);
        merchant.setDelFlag(0);
        merchant.setBusinessLicense("license");
        merchant.setName("店铺");
        merchant.setCategoryId(1);
        merchant.setLogo("logo");
        merchant.setCoverImage("cover");
        merchant.setLocationVerified(1);
        merchant.setSlogan("slogan");
        merchant.setDescription("description");
        return merchant;
    }
}
