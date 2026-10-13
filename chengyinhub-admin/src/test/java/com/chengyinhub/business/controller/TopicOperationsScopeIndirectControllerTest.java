package com.chengyinhub.business.controller;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.dto.RegistrationRejectDTO;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.CoopGroupVerifyMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsRegistrationScanService;
import com.chengyinhub.business.service.IDispatchService;
import com.chengyinhub.business.service.IMerchantOfferService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.business.ClubRunController;
import com.chengyinhub.web.controller.business.CmsRegistrationMerchantController;
import com.chengyinhub.web.controller.business.MerchantChapterOfferController;
import com.chengyinhub.web.controller.business.VerifyEvidenceController;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 主题运营的间接入口也必须继承主题范围，拒绝时不得读取或改写跨主题数据。 */
class TopicOperationsScopeIndirectControllerTest {

    @Test
    void clubRunRejectsHiddenTopicBeforeReadingRunRowsOrManifest() {
        ITopicOperationsScopeAuthorizer scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        ClubLeadMapper clubLeadMapper = mock(ClubLeadMapper.class);
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        when(scopeAuthorizer.canViewTopic(81L)).thenReturn(false);
        CmsActivity hiddenActivity = new CmsActivity();
        hiddenActivity.setId(9L);
        hiddenActivity.setTopicId(81L);
        when(activityService.selectCmsActivityById(9L)).thenReturn(hiddenActivity);
        ClubRunController controller = new ClubRunController();
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        ReflectionTestUtils.setField(controller, "clubLeadMapper", clubLeadMapper);
        ReflectionTestUtils.setField(controller, "cmsActivityService", activityService);

        AjaxResult list = controller.list(81L, null, null);
        AjaxResult manifest = controller.manifest(9L);

        assertEquals(500, list.get(AjaxResult.CODE_TAG));
        assertEquals(500, manifest.get(AjaxResult.CODE_TAG));
        verifyNoInteractions(clubLeadMapper);
    }

    @Test
    void verificationEvidenceRejectsGlobalAndTopicScopedQueriesBeforeDataLookup() {
        ITopicOperationsScopeAuthorizer scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        ICmsRegistrationScanService scanService = mock(ICmsRegistrationScanService.class);
        CoopGroupVerifyMapper groupVerifyMapper = mock(CoopGroupVerifyMapper.class);
        when(scopeAuthorizer.hasGlobalTopicScope()).thenReturn(false);
        when(scopeAuthorizer.canViewTopic(82L)).thenReturn(false);
        VerifyEvidenceController controller = new VerifyEvidenceController();
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        ReflectionTestUtils.setField(controller, "cmsRegistrationScanService", scanService);
        ReflectionTestUtils.setField(controller, "coopGroupVerifyMapper", groupVerifyMapper);

        AjaxResult scans = controller.scans(null, null, null);
        AjaxResult groups = controller.groupVerifies(82L, null, null);

        assertEquals(500, scans.get(AjaxResult.CODE_TAG));
        assertEquals(500, groups.get(AjaxResult.CODE_TAG));
        verifyNoInteractions(scanService, groupVerifyMapper);
    }

    @Test
    void merchantOfferQueueRejectsHiddenTopicBeforeCallingService() {
        ITopicOperationsScopeAuthorizer scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        IMerchantOfferService offerService = mock(IMerchantOfferService.class);
        when(scopeAuthorizer.canViewTopic(83L)).thenReturn(false);
        MerchantChapterOfferController controller = new MerchantChapterOfferController();
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        ReflectionTestUtils.setField(controller, "merchantOfferService", offerService);

        AjaxResult result = controller.queue(83L, null);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verifyNoInteractions(offerService);
    }

    @Test
    void registrationListRejectsUnscopedGlobalQueryBeforeLookup() {
        ITopicOperationsScopeAuthorizer scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        ICmsRegistrationMerchantService registrationService = mock(ICmsRegistrationMerchantService.class);
        when(scopeAuthorizer.hasGlobalTopicScope()).thenReturn(false);
        CmsRegistrationMerchantController controller = new CmsRegistrationMerchantController();
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantService", registrationService);

        controller.list(new CmsRegistrationMerchant());

        verifyNoInteractions(registrationService);
    }

    @Test
    void registrationBatchDeleteRejectsStoredHiddenTopicBeforeDelete() {
        ITopicOperationsScopeAuthorizer scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        ICmsRegistrationMerchantService registrationService = mock(ICmsRegistrationMerchantService.class);
        CmsRegistrationMerchant hidden = new CmsRegistrationMerchant();
        hidden.setId(51L);
        hidden.setTopicId(84L);
        when(registrationService.selectCmsRegistrationMerchantById(51L)).thenReturn(hidden);
        when(scopeAuthorizer.canViewTopic(84L)).thenReturn(false);
        CmsRegistrationMerchantController controller = new CmsRegistrationMerchantController();
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantService", registrationService);

        AjaxResult result = controller.remove(new Long[]{51L});

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(registrationService, never()).deleteCmsRegistrationMerchantByIds(any(Long[].class));
    }

    @Test
    void registrationSelectAndRejectRejectStoredHiddenTopicBeforeDispatch() {
        ITopicOperationsScopeAuthorizer scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        ICmsRegistrationMerchantService registrationService = mock(ICmsRegistrationMerchantService.class);
        IDispatchService dispatchService = mock(IDispatchService.class);
        CmsRegistrationMerchant hidden = new CmsRegistrationMerchant();
        hidden.setId(52L);
        hidden.setTopicId(84L);
        when(registrationService.selectCmsRegistrationMerchantById(52L)).thenReturn(hidden);
        when(scopeAuthorizer.canViewTopic(84L)).thenReturn(false);
        CmsRegistrationMerchantController controller = new CmsRegistrationMerchantController();
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantService", registrationService);
        ReflectionTestUtils.setField(controller, "dispatchService", dispatchService);

        AjaxResult selected = controller.select(52L);
        AjaxResult rejected = controller.reject(52L, new RegistrationRejectDTO());

        assertEquals(500, selected.get(AjaxResult.CODE_TAG));
        assertEquals(500, rejected.get(AjaxResult.CODE_TAG));
        verifyNoInteractions(dispatchService);
    }

}
