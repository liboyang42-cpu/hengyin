package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.DispatchResult;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IDispatchService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 小程序确认候选只保留发布者归属校验，调配规则统一委托 DispatchService。 */
class ApiCoopControllerConfirmTest {

    private static final Long PUBLISHER = 500L;
    private static final Long TOPIC = 200L;
    private static final Long REGISTRATION = 10L;

    @Test
    void publisherConfirmationDelegatesOnceAndNeverCreatesOrderDirectly() {
        Fixture fixture = fixture();
        when(fixture.dispatchService.confirmRegistration(REGISTRATION))
                .thenReturn(DispatchResult.selected(REGISTRATION));

        AjaxResult result = fixture.controller.confirmCandidate(requestBody());

        assertEquals(200, result.get("code"));
        verify(fixture.dispatchService).confirmRegistration(REGISTRATION);
        verify(fixture.coopInviteService, never()).createCoopOrder(any());
    }

    @Test
    void dispatchRejectionUsesCentralMessage() {
        Fixture fixture = fixture();
        when(fixture.dispatchService.confirmRegistration(REGISTRATION)).thenReturn(
                DispatchResult.rejected(DispatchResult.Code.SLOT_OCCUPIED, "该竞争位已有中标商家"));

        AjaxResult result = fixture.controller.confirmCandidate(requestBody());

        assertEquals(500, result.get("code"));
        assertEquals("该竞争位已有中标商家", result.get("msg"));
    }

    @Test
    void merchantScopedConfirmationUsesProjectOwnerInsteadOfOperatorPrincipal() {
        Fixture fixture = fixture();
        Long owner = 501L;
        when(fixture.merchantAccess.require(PUBLISHER, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(new MerchantAccessContext(PUBLISHER, 77L, owner,
                        MerchantRoleCode.MERCHANT_MANAGER));
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(owner);
        when(fixture.topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(fixture.dispatchService.confirmRegistration(REGISTRATION))
                .thenReturn(DispatchResult.selected(REGISTRATION));
        Map<String, Object> body = requestBody();
        body.put("scope", "MERCHANT");

        AjaxResult result = fixture.controller.confirmCandidate(body);

        assertEquals(200, result.get("code"));
        verify(fixture.merchantAccess).require(PUBLISHER, MerchantPermission.PROJECT_MANAGE);
    }

    private Fixture fixture() {
        Fixture fixture = new Fixture();
        fixture.controller = spy(new ApiCoopController());
        doReturn(PUBLISHER).when(fixture.controller).getAppUserId();
        fixture.registrationService = mock(ICmsRegistrationMerchantService.class);
        fixture.topicService = mock(ICmsTopicService.class);
        fixture.coopInviteService = mock(ICoopInviteService.class);
        fixture.dispatchService = mock(IDispatchService.class);
        fixture.merchantAccess = mock(IMerchantAccessService.class);

        CmsRegistrationMerchant registration = new CmsRegistrationMerchant();
        registration.setId(REGISTRATION);
        registration.setTopicId(TOPIC);
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(PUBLISHER);
        when(fixture.registrationService.selectCmsRegistrationMerchantById(REGISTRATION))
                .thenReturn(registration);
        when(fixture.topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);

        org.springframework.test.util.ReflectionTestUtils.setField(fixture.controller, "cmsRegistrationMerchantService", fixture.registrationService);
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.controller, "cmsTopicService", fixture.topicService);
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.controller, "coopInviteService", fixture.coopInviteService);
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.controller, "dispatchService", fixture.dispatchService);
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.controller, "merchantAccessService", fixture.merchantAccess);
        return fixture;
    }

    private Map<String, Object> requestBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("registrationId", REGISTRATION);
        return body;
    }

    private static class Fixture {
        private ApiCoopController controller;
        private ICmsRegistrationMerchantService registrationService;
        private ICmsTopicService topicService;
        private ICoopInviteService coopInviteService;
        private IDispatchService dispatchService;
        private IMerchantAccessService merchantAccess;
    }
}
