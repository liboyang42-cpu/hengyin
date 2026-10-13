package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsSetting;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.vo.PlatformSettlementAccountVO;
import com.chengyinhub.business.mapper.CmsSettingMapper;
import com.chengyinhub.business.service.impl.SettlementRuleServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 平台活动分润收款账户只能指向一个真实、启用且未删除的会员账户。 */
class SettlementPlatformAccountServiceTest {

    private CmsSettingMapper cmsSettingMapper;
    private IUmsMemberService umsMemberService;
    private SettlementRuleServiceImpl service;

    @BeforeEach
    void setUp() {
        cmsSettingMapper = mock(CmsSettingMapper.class);
        umsMemberService = mock(IUmsMemberService.class);
        service = new SettlementRuleServiceImpl();
        ReflectionTestUtils.setField(service, "cmsSettingMapper", cmsSettingMapper);
        ReflectionTestUtils.setField(service, "umsMemberService", umsMemberService);
    }

    @Test
    void rejectsMissingMemberWithoutTouchingLegacySettings() {
        when(umsMemberService.selectUmsMemberById(42L)).thenReturn(null);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.updatePlatformSettlementAccount(42L));

        assertEquals("平台收款账户不存在或不可用", exception.getMessage());
        verify(cmsSettingMapper, never()).selectCmsSettingById(any());
        verify(cmsSettingMapper, never()).updateCmsSetting(any());
    }

    @Test
    void rejectsDisabledOrDeletedMemberWithoutWriting() {
        UmsMember disabled = activeMember(42L);
        disabled.setStatus(2L);
        when(umsMemberService.selectUmsMemberById(42L)).thenReturn(disabled);

        assertThrows(ServiceException.class, () -> service.updatePlatformSettlementAccount(42L));

        verify(cmsSettingMapper, never()).updateCmsSetting(any());
    }

    @Test
    void rejectsDeletedMemberWithoutWriting() {
        UmsMember deleted = activeMember(42L);
        deleted.setDelFlag(2);
        when(umsMemberService.selectUmsMemberById(42L)).thenReturn(deleted);

        assertThrows(ServiceException.class, () -> service.updatePlatformSettlementAccount(42L));

        verify(cmsSettingMapper, never()).updateCmsSetting(any());
    }

    @Test
    void updatesOnlyPlatformMemberIdOnTheExistingSettlementConfigRow() {
        CmsSetting existing = new CmsSetting();
        existing.setId(1L);
        existing.setActivityPlatformCommission(java.math.BigDecimal.valueOf(99));
        when(umsMemberService.selectUmsMemberById(42L)).thenReturn(activeMember(42L));
        when(cmsSettingMapper.selectCmsSettingById(1L)).thenReturn(existing);
        when(cmsSettingMapper.updateCmsSetting(any(CmsSetting.class))).thenReturn(1);

        int affected = service.updatePlatformSettlementAccount(42L);

        assertEquals(1, affected);
        ArgumentCaptor<CmsSetting> patch = ArgumentCaptor.forClass(CmsSetting.class);
        verify(cmsSettingMapper).updateCmsSetting(patch.capture());
        assertEquals(Long.valueOf(1L), patch.getValue().getId());
        assertEquals(Long.valueOf(42L), patch.getValue().getPlatformMemberId());
        assertNull(patch.getValue().getActivityPlatformCommission());
        assertNull(patch.getValue().getActivityMerchantCommission());
        assertNull(patch.getValue().getActivityDesignerCommission());
        assertNull(patch.getValue().getTopicPlatformCommission());
        assertNull(patch.getValue().getTopicMerchantCommission());
        assertNull(patch.getValue().getTopicPublisherCommission());
        assertNull(patch.getValue().getTopicInviteCommission());
        assertNull(patch.getValue().getUser1TopicNum());
        assertNull(patch.getValue().getMerchantTopicNum());
    }

    @Test
    void readsOnlyTheConfiguredAccountIdAndItsUsability() {
        CmsSetting setting = new CmsSetting();
        setting.setId(1L);
        setting.setPlatformMemberId(42L);
        when(cmsSettingMapper.selectCmsSettingById(1L)).thenReturn(setting);
        when(umsMemberService.selectUmsMemberById(42L)).thenReturn(activeMember(42L));

        PlatformSettlementAccountVO account = service.getPlatformSettlementAccount();

        assertEquals(Long.valueOf(42L), account.getMemberId());
        assertTrue(account.isConfigured());
        assertTrue(account.isUsable());
    }

    @Test
    void reportsAConfiguredButUnavailableLegacyAccountWithoutChangingIt() {
        CmsSetting setting = new CmsSetting();
        setting.setId(1L);
        setting.setPlatformMemberId(42L);
        UmsMember deleted = activeMember(42L);
        deleted.setDelFlag(2);
        when(cmsSettingMapper.selectCmsSettingById(1L)).thenReturn(setting);
        when(umsMemberService.selectUmsMemberById(42L)).thenReturn(deleted);

        PlatformSettlementAccountVO account = service.getPlatformSettlementAccount();

        assertTrue(account.isConfigured());
        assertFalse(account.isUsable());
        verify(cmsSettingMapper, never()).updateCmsSetting(any());
    }

    private UmsMember activeMember(Long id) {
        UmsMember member = new UmsMember();
        member.setId(id);
        member.setStatus(1L);
        member.setDelFlag(0);
        return member;
    }
}
