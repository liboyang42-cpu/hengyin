package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.dto.DispatchResult;
import com.chengyinhub.business.domain.dto.RegistrationRejectDTO;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.IDispatchService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsTemplateLibraryService;
import com.chengyinhub.business.service.ITemplatePublishService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TemplateAndMerchantMutationGuardTest {
    @Test
    void memberTemplateMutationsDelegateToTemplatePublishService() {
        ITemplatePublishService service = mock(ITemplatePublishService.class);
        CmsMemberTemplateController c = spy(new CmsMemberTemplateController());
        doReturn("operator").when(c).getUsername();
        ReflectionTestUtils.setField(c, "templatePublishService", service);
        CmsMemberTemplate t = new CmsMemberTemplate();
        t.setId(8L); t.setMemberId(9L); t.setTitle("待审模板"); t.setStatus(1L);
        t.setCheckStatus(1); t.setPublishStatus(1L); t.setReason("伪造"); t.setDelFlag(2);
        when(service.submitTemplateForReview(8L)).thenReturn(81L);

        c.add(t);
        c.edit(t);
        AjaxResult result = c.submitReview(8L);
        assertEquals(81L, result.get(AjaxResult.DATA_TAG));
        verify(service).createAdminTemplate(eq(t), any());
        verify(service).updateAdminTemplate(eq(t), any());
        verify(service).submitTemplateForReview(8L);
    }

    @Test
    void templateLibraryMutationsUseCanonicalTemplateService() {
        ITemplatePublishService service = mock(ITemplatePublishService.class);
        CmsTemplateLibraryController c = spy(new CmsTemplateLibraryController());
        doReturn("operator").when(c).getUsername();
        ReflectionTestUtils.setField(c, "templatePublishService", service);
        CmsTemplateLibrary t = new CmsTemplateLibrary();
        t.setId(3L);
        t.setStatus(1L); t.setPublishStatus(1L); t.setDelFlag(2);
        AjaxResult addResult = c.add(t);
        assertNotEquals(200, addResult.get(AjaxResult.CODE_TAG));
        c.edit(t);
        c.remove(new Long[] { 3L });
        verify(service).updateTemplateFromLibraryProjection(eq(t), any());
        verify(service).deleteTemplateLibraryProjections(new Long[] { 3L }, "operator");
    }

    @Test
    void removeWinnerRegistrationRefused() {
        ICmsRegistrationMerchantService service = mock(ICmsRegistrationMerchantService.class);
        CmsRegistrationMerchant winner = new CmsRegistrationMerchant();
        winner.setTopicId(1L);
        winner.setAuditStatus(1L);
        when(service.selectCmsRegistrationMerchantById(1L)).thenReturn(winner);
        com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer scopeAuthorizer =
                mock(com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer.class);
        when(scopeAuthorizer.canViewTopic(1L)).thenReturn(true);
        CmsRegistrationMerchantController c = new CmsRegistrationMerchantController();
        ReflectionTestUtils.setField(c, "cmsRegistrationMerchantService", service);
        ReflectionTestUtils.setField(c, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        AjaxResult result = c.remove(new Long[]{1L});
        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(service, never()).deleteCmsRegistrationMerchantByIds(any());
    }

    @Test
    void registrationRejectDelegatesToDispatchHub() {
        IDispatchService dispatchService = mock(IDispatchService.class);
        ICmsRegistrationMerchantService registrationService = mock(ICmsRegistrationMerchantService.class);
        CmsRegistrationMerchant registration = new CmsRegistrationMerchant();
        registration.setId(7L);
        registration.setTopicId(1L);
        when(registrationService.selectCmsRegistrationMerchantById(7L)).thenReturn(registration);
        com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer scopeAuthorizer =
                mock(com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer.class);
        when(scopeAuthorizer.canViewTopic(1L)).thenReturn(true);
        CmsRegistrationMerchantController c = new CmsRegistrationMerchantController();
        ReflectionTestUtils.setField(c, "dispatchService", dispatchService);
        ReflectionTestUtils.setField(c, "cmsRegistrationMerchantService", registrationService);
        ReflectionTestUtils.setField(c, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        RegistrationRejectDTO request = new RegistrationRejectDTO();
        request.setReason("材料不完整");
        when(dispatchService.rejectRegistration(7L, "材料不完整"))
                .thenReturn(DispatchResult.registrationRejected(7L));

        AjaxResult result = c.reject(7L, request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(dispatchService).rejectRegistration(7L, "材料不完整");
    }
}
