package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsSetting;
import com.chengyinhub.business.mapper.CmsSettingMapper;
import com.chengyinhub.business.service.impl.CmsSettingServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 旧 settings URI 可以兼容，但不得再成为活动平台分润账户的写入旁路。 */
class CmsSettingServicePlatformAccountGuardTest {

    private CmsSettingMapper mapper;
    private CmsSettingServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(CmsSettingMapper.class);
        service = new CmsSettingServiceImpl();
        ReflectionTestUtils.setField(service, "cmsSettingMapper", mapper);
    }

    @Test
    void genericInsertRejectsPlatformSettlementAccount() {
        CmsSetting setting = new CmsSetting();
        setting.setPlatformMemberId(42L);

        assertThrows(ServiceException.class, () -> service.insertCmsSetting(setting));

        verify(mapper, never()).insertCmsSetting(any());
    }

    @Test
    void genericUpdateRejectsChangedPlatformSettlementAccountEvenForAUserWithLegacyEditPermission() {
        CmsSetting current = new CmsSetting();
        current.setId(1L);
        current.setPlatformMemberId(7L);
        when(mapper.selectCmsSettingById(1L)).thenReturn(current);

        CmsSetting setting = new CmsSetting();
        setting.setId(1L);
        setting.setPlatformMemberId(42L);

        assertThrows(ServiceException.class, () -> service.updateCmsSetting(setting));

        verify(mapper, never()).updateCmsSetting(any());
    }

    @Test
    void genericFullObjectUpdateKeepsTheExistingPlatformAccountOutOfTheWrite() {
        CmsSetting current = new CmsSetting();
        current.setId(1L);
        current.setPlatformMemberId(42L);
        when(mapper.selectCmsSettingById(1L)).thenReturn(current);
        when(mapper.updateCmsSetting(any(CmsSetting.class))).thenReturn(1);

        CmsSetting setting = new CmsSetting();
        setting.setId(1L);
        setting.setPlatformMemberId(42L);
        setting.setActivityPlatformCommission(java.math.BigDecimal.ONE);

        assertEquals(1, service.updateCmsSetting(setting));
        assertNull(setting.getPlatformMemberId(), "同值回传必须在写入前剥离，不能覆盖专用结算入口的收款人");
        verify(mapper).updateCmsSetting(setting);
    }

    @Test
    void genericInsertCannotCreateTheActiveSettlementConfigRow() {
        CmsSetting setting = new CmsSetting();
        setting.setId(1L);

        assertThrows(ServiceException.class, () -> service.insertCmsSetting(setting));

        verify(mapper, never()).insertCmsSetting(any());
    }

    @Test
    void genericUpdateWithoutPlatformAccountStaysBackwardCompatible() {
        CmsSetting setting = new CmsSetting();
        setting.setId(1L);
        setting.setActivityPlatformCommission(java.math.BigDecimal.ONE);
        when(mapper.updateCmsSetting(setting)).thenReturn(1);

        assertEquals(1, service.updateCmsSetting(setting));
        verify(mapper).updateCmsSetting(setting);
    }

    @Test
    void genericDeleteCannotRemoveTheActiveSettlementConfigRow() {
        assertThrows(ServiceException.class, () -> service.deleteCmsSettingByIds(new Long[] { 1L }));

        verify(mapper, never()).deleteCmsSettingByIds(any());
    }
}
