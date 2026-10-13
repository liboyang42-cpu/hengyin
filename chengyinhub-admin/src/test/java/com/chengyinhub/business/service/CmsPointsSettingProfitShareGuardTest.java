package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsPointsSetting;
import com.chengyinhub.business.mapper.CmsPointsSettingMapper;
import com.chengyinhub.business.service.impl.CmsPointsSettingServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 历史分润行只能由结算规则承接，不能继续借积分配置写入口修改。 */
class CmsPointsSettingProfitShareGuardTest {

    private CmsPointsSettingMapper mapper;
    private ICmsPointsSettingService service;

    @BeforeEach
    void setUp() {
        mapper = mock(CmsPointsSettingMapper.class);
        CmsPointsSettingServiceImpl serviceImpl = new CmsPointsSettingServiceImpl();
        ReflectionTestUtils.setField(serviceImpl, "cmsPointsSettingMapper", mapper);
        service = serviceImpl;
    }

    @Test
    void legacyProfitShareRowCannotBeUpdatedEvenWhenPayloadOmitsType() {
        CmsPointsSetting stored = setting(8L, 2L);
        when(mapper.selectCmsPointsSettingById(8L)).thenReturn(stored);

        CmsPointsSetting request = new CmsPointsSetting();
        request.setId(8L);

        assertThrows(ServiceException.class, () -> service.updateCmsPointsSetting(request));

        verify(mapper, never()).updateCmsPointsSetting(any(CmsPointsSetting.class));
    }

    @Test
    void legacyProfitShareRuleCannotBeCreatedThroughPointsSettingService() {
        CmsPointsSetting request = setting(null, 2L);

        assertThrows(ServiceException.class, () -> service.insertCmsPointsSetting(request));

        verify(mapper, never()).insertCmsPointsSetting(any(CmsPointsSetting.class));
    }

    @Test
    void newRuleMustExplicitlyDeclareTypeOne() {
        assertThrows(ServiceException.class, () -> service.insertCmsPointsSetting(setting(null, null)));
        assertThrows(ServiceException.class, () -> service.insertCmsPointsSetting(setting(null, 3L)));

        verify(mapper, never()).insertCmsPointsSetting(any(CmsPointsSetting.class));
    }

    @Test
    void legacyProfitShareRowCannotBeDeletedThroughPointsSettingService() {
        when(mapper.selectCmsPointsSettingById(8L)).thenReturn(setting(8L, 2L));

        assertThrows(ServiceException.class, () -> service.deleteCmsPointsSettingByIds(new Long[] { 8L }));

        verify(mapper, never()).deleteCmsPointsSettingByIds(any(Long[].class));
    }

    @Test
    void mixedBatchCannotDeleteAnyRowWhenItContainsAProfitShareRow() {
        when(mapper.selectCmsPointsSettingById(1L)).thenReturn(setting(1L, 1L));
        when(mapper.selectCmsPointsSettingById(8L)).thenReturn(setting(8L, 2L));

        assertThrows(ServiceException.class, () -> service.deleteCmsPointsSettingByIds(new Long[] { 1L, 8L }));

        verify(mapper, never()).deleteCmsPointsSettingByIds(any(Long[].class));
    }

    @Test
    void legacyProfitShareRowCannotBeDeletedThroughSingleRowServicePath() {
        when(mapper.selectCmsPointsSettingById(8L)).thenReturn(setting(8L, 2L));

        assertThrows(ServiceException.class, () -> service.deleteCmsPointsSettingById(8L));

        verify(mapper, never()).deleteCmsPointsSettingById(8L);
    }

    @Test
    void pointRuleCanStillBeUpdated() {
        when(mapper.selectCmsPointsSettingById(1L)).thenReturn(setting(1L, 1L));
        when(mapper.updateCmsPointsSetting(any(CmsPointsSetting.class))).thenReturn(1);
        CmsPointsSetting request = setting(1L, 1L);

        assertEquals(1, service.updateCmsPointsSetting(request));

        verify(mapper).updateCmsPointsSetting(request);
    }

    @Test
    void pointRuleCanStillBeUpdatedWhenUiSendsOnlyIdAndValue() {
        when(mapper.selectCmsPointsSettingById(1L)).thenReturn(setting(1L, 1L));
        when(mapper.updateCmsPointsSetting(any(CmsPointsSetting.class))).thenReturn(1);
        CmsPointsSetting request = new CmsPointsSetting();
        request.setId(1L);
        request.setValue(new BigDecimal("50"));

        assertEquals(1, service.updateCmsPointsSetting(request));

        verify(mapper).updateCmsPointsSetting(request);
    }

    @Test
    void pointRuleCannotBeReclassifiedToAnUnknownTypeThroughPayload() {
        when(mapper.selectCmsPointsSettingById(1L)).thenReturn(setting(1L, 1L));
        CmsPointsSetting request = setting(1L, 3L);

        assertThrows(ServiceException.class, () -> service.updateCmsPointsSetting(request));

        verify(mapper, never()).updateCmsPointsSetting(any(CmsPointsSetting.class));
    }

    @Test
    void pointRuleCannotBeReclassifiedToProfitShareThroughPayload() {
        when(mapper.selectCmsPointsSettingById(1L)).thenReturn(setting(1L, 1L));
        CmsPointsSetting request = setting(1L, 2L);

        assertThrows(ServiceException.class, () -> service.updateCmsPointsSetting(request));

        verify(mapper, never()).updateCmsPointsSetting(any(CmsPointsSetting.class));
    }

    @Test
    void publisherSettlementRuleCannotBeChangedThroughGenericCrud() {
        CmsPointsSetting protectedRule = setting(15L, 1L);
        protectedRule.setEventType(15L);
        when(mapper.selectCmsPointsSettingById(15L)).thenReturn(protectedRule);

        CmsPointsSetting update = new CmsPointsSetting();
        update.setId(15L);
        update.setValue(new BigDecimal("1"));

        assertThrows(ServiceException.class, () -> service.updateCmsPointsSetting(update));
        assertThrows(ServiceException.class, () -> service.deleteCmsPointsSettingById(15L));
        assertThrows(ServiceException.class, () -> service.deleteCmsPointsSettingByIds(new Long[] { 15L }));

        verify(mapper, never()).updateCmsPointsSetting(any(CmsPointsSetting.class));
        verify(mapper, never()).deleteCmsPointsSettingById(15L);
        verify(mapper, never()).deleteCmsPointsSettingByIds(any(Long[].class));
    }

    @Test
    void publisherSettlementRuleCannotBeCreatedThroughGenericCrud() {
        CmsPointsSetting request = setting(null, 1L);
        request.setEventType(15L);

        assertThrows(ServiceException.class, () -> service.insertCmsPointsSetting(request));

        verify(mapper, never()).insertCmsPointsSetting(any(CmsPointsSetting.class));
    }

    @Test
    void ordinaryRuleCannotBeReclassifiedToPublisherSettlementWhenPayloadOmitsType() {
        when(mapper.selectCmsPointsSettingById(1L)).thenReturn(setting(1L, 1L));
        CmsPointsSetting request = new CmsPointsSetting();
        request.setId(1L);
        request.setEventType(15L);

        assertThrows(ServiceException.class, () -> service.updateCmsPointsSetting(request));

        verify(mapper, never()).updateCmsPointsSetting(any(CmsPointsSetting.class));
    }

    @Test
    void unknownStoredRuleCannotBeUpdatedWhenPayloadOmitsType() {
        when(mapper.selectCmsPointsSettingById(9L)).thenReturn(setting(9L, 3L));
        CmsPointsSetting request = new CmsPointsSetting();
        request.setId(9L);

        assertThrows(ServiceException.class, () -> service.updateCmsPointsSetting(request));

        verify(mapper, never()).updateCmsPointsSetting(any(CmsPointsSetting.class));
    }

    private static CmsPointsSetting setting(Long id, Long type) {
        CmsPointsSetting setting = new CmsPointsSetting();
        setting.setId(id);
        setting.setType(type);
        return setting;
    }
}
