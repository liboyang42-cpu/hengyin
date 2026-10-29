package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.*;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApiCoopSupplyStationsTest {
    private final ICoopInviteService invites=mock(ICoopInviteService.class);
    private final ICmsTopicService topics=mock(ICmsTopicService.class);
    private final IMerchantAccessService access=mock(IMerchantAccessService.class);
    private long principal=11;
    private CmsTopic topic;
    private ApiCoopController controller;
    @BeforeEach void fixture() {
        controller=new ApiCoopController(){ @Override public Long getAppUserId(){return principal;} };
        ReflectionTestUtils.setField(controller,"coopInviteService",invites);
        ReflectionTestUtils.setField(controller,"cmsTopicService",topics);
        ReflectionTestUtils.setField(controller,"merchantAccessService",access);
        ReflectionTestUtils.setField(controller,"wxContentSecurityService",mock(IWxContentSecurityService.class));
        topic=new CmsTopic(); topic.setId(7L); topic.setMemberId(11L); topic.setProductType(1);
        when(topics.selectCmsTopicById(7L)).thenReturn(topic);
        when(invites.listSupplyStations(7L,22L,11L)).thenReturn(Collections.emptyList());
    }
    @Test void creatorGetsExplicitRequiredEmptyListNotFalseBypass() {
        AjaxResult response=controller.supplyStations(7L,22L);
        assertEquals(200,response.get(AjaxResult.CODE_TAG));
        Map<?,?> data=(Map<?,?>)response.get(AjaxResult.DATA_TAG);
        assertEquals(true,data.get("requiresSupplyStation")); assertEquals(List.of(),data.get("stations"));
        verify(invites,never()).insertCoopInvite(any());
    }
    @Test void responseUsesActualStationIdAndAddressNameWithoutNodeAlias() {
        CmsRegistrationMerchant station=new CmsRegistrationMerchant(); station.setId(101L); station.setAddressName("原核销据点");
        when(invites.listSupplyStations(7L,22L,11L)).thenReturn(List.of(station));
        Map<?,?> data=(Map<?,?>)controller.supplyStations(7L,22L).get(AjaxResult.DATA_TAG);
        assertEquals(List.of(Map.of("supplyStationId","101","stationName","原核销据点")),data.get("stations"));
    }
    @Test void merchantAcceptPageCannotReadCreatorsStationPicker() {
        principal=22;
        when(access.require(22L,MerchantPermission.PROJECT_MANAGE)).thenThrow(new ServiceException("无权",403));
        assertEquals(403,controller.supplyStations(7L,22L).get(AjaxResult.CODE_TAG));
        verify(invites,never()).listSupplyStations(any(),any(),any());
    }
    @Test void originalMerchantProjectPermissionResolvesContractOwnerWithoutClientScope() {
        principal=33;
        when(access.require(33L,MerchantPermission.PROJECT_MANAGE)).thenReturn(new MerchantAccessContext(33L,90L,11L,"owner"));
        assertEquals(200,controller.supplyStations(7L,22L).get(AjaxResult.CODE_TAG));
        verify(invites).listSupplyStations(7L,22L,11L);
    }
    @Test void delegatedProjectOwnerMustStillOwnTheExactTopicBeforeReadingStations() {
        principal=33;
        when(access.require(33L,MerchantPermission.PROJECT_MANAGE))
                .thenReturn(new MerchantAccessContext(33L,90L,44L,"manager"));
        AjaxResult response=controller.supplyStations(7L,22L);
        assertNotEquals(200,response.get(AjaxResult.CODE_TAG));
        assertEquals("只能为自己发布的主题选择合作据点",response.get(AjaxResult.MSG_TAG));
        verify(invites,never()).listSupplyStations(any(),any(),any());
        verify(invites,never()).insertCoopInvite(any());
    }
    @Test void invalidOrInapplicableScopeIsErrorNotRequiresFalse() {
        assertNotEquals(200,controller.supplyStations(-1L,22L).get(AjaxResult.CODE_TAG));
        when(invites.listSupplyStations(7L,22L,11L)).thenThrow(new IllegalStateException("不是经典主题"));
        AjaxResult response=controller.supplyStations(7L,22L);
        assertNotEquals(200,response.get(AjaxResult.CODE_TAG)); assertNull(response.get(AjaxResult.DATA_TAG));
    }
    @Test void acceptCallerPassesExactOriginalConfirmationToLockedService() {
        principal=22; CoopInvite original=new CoopInvite(); original.setId(41L); original.setInviteType(0);
        original.setStatus(0); original.setToType("merchant"); original.setToId(22L);
        original.setFromType("talent"); original.setFromId(11L); original.setTopicId(7L); original.setSupplyStationId(101L);
        when(invites.selectCoopInviteById(41L)).thenReturn(original);
        when(access.require(22L,MerchantPermission.COOP_MANAGE)).thenReturn(new MerchantAccessContext(22L,90L,22L,"owner"));
        CoopInvite request=new CoopInvite(); request.setId(41L); request.setStatus(1); request.setSupplyStationId(101L);
        assertEquals(200,controller.handle(request).get(AjaxResult.CODE_TAG));
        verify(invites).acceptInviteWithLock(41L,null,101L); verify(invites,never()).acceptInviteWithLock(any(),any());
    }
    @Test void existingInviteViewClassifiesRealClassicAndLegacyTypesWithoutManufacturingStation() {
        CoopInvite classic=new CoopInvite(); classic.setInviteType(0); classic.setToType("merchant"); classic.setTopicId(7L);
        CoopInvite club=new CoopInvite(); club.setInviteType(1); club.setToType("club"); club.setTopicId(7L);
        CoopInvite legacyNode=new CoopInvite(); legacyNode.setInviteType(2); legacyNode.setToType("merchant"); legacyNode.setTopicId(7L);
        ReflectionTestUtils.invokeMethod(controller,"fillTopicFacts",List.of(classic,club,legacyNode),List.of());
        assertEquals(Boolean.TRUE,classic.getRequiresSupplyStation()); assertNull(classic.getSupplyStationId());
        assertNull(classic.getSupplyStationName()); assertEquals(Boolean.FALSE,club.getRequiresSupplyStation());
        assertEquals(Boolean.FALSE,legacyNode.getRequiresSupplyStation());
        topic.setProductType(2); ReflectionTestUtils.invokeMethod(controller,"fillTopicFacts",List.of(classic),List.of());
        assertEquals(Boolean.FALSE,classic.getRequiresSupplyStation());
        when(topics.selectCmsTopicById(7L)).thenReturn(null);
        ReflectionTestUtils.invokeMethod(controller,"fillTopicFacts",List.of(classic),List.of());
        assertNull(classic.getRequiresSupplyStation(),"unverifiable original scope must not be a false bypass");
    }
}
