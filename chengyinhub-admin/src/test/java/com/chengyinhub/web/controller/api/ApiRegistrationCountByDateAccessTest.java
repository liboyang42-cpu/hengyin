package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiRegistrationCountByDateAccessTest {
    private static final Long PRINCIPAL = 7002L;
    private static final Long OWNER = 9001L;
    private static final Long TOPIC = 301L;

    private ICmsRegistrationService registrationService;
    private ICmsRegistrationMerchantService merchantRegistrationService;
    private ICmsTopicService topicService;
    private ICmsActivityService activityService;
    private IClubMemberService clubMemberService;
    private IMerchantAccessService accessService;
    private ApiRegistrationController controller;

    @BeforeEach
    void setUp() {
        registrationService = mock(ICmsRegistrationService.class);
        merchantRegistrationService = mock(ICmsRegistrationMerchantService.class);
        topicService = mock(ICmsTopicService.class);
        activityService = mock(ICmsActivityService.class);
        clubMemberService = mock(IClubMemberService.class);
        accessService = mock(IMerchantAccessService.class);
        controller = new ApiRegistrationController() {
            @Override
            public Long getAppUserId() {
                return PRINCIPAL;
            }
        };
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "registrationService", registrationService);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "registrationMerchantService", merchantRegistrationService);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "topicService", topicService);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "activityService", activityService);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "clubMemberService", clubMemberService);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "merchantAccessService", accessService);
        when(registrationService.countByParticipateDate(any(), any()))
                .thenReturn(Collections.emptyList());
    }

    @Test
    void topicPublisherCanReadOwnTrendWithoutMerchantGuard() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(PRINCIPAL);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);

        AjaxResult result = RegistrationQueryTestFixtures.wire(controller).countByDate("1", String.valueOf(TOPIC));

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(accessService, never()).require(any(), any());
    }

    @Test
    void checkinOperatorUsesOwnerAndOnlyAwardedTopicCanBeRead() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(88L);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(accessService.require(PRINCIPAL, MerchantPermission.VERIFY_RECORD_READ))
                .thenReturn(new MerchantAccessContext(
                        PRINCIPAL, 11L, OWNER, MerchantRoleCode.MERCHANT_CHECKIN));
        when(merchantRegistrationService.selectCmsRegistrationMerchantList(any()))
                .thenReturn(Collections.singletonList(new CmsRegistrationMerchant()));

        AjaxResult result = RegistrationQueryTestFixtures.wire(controller).countByDate("1", String.valueOf(TOPIC));

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        ArgumentCaptor<CmsRegistrationMerchant> query =
                ArgumentCaptor.forClass(CmsRegistrationMerchant.class);
        verify(merchantRegistrationService).selectCmsRegistrationMerchantList(query.capture());
        assertEquals(OWNER, query.getValue().getMemberId());
        assertEquals(TOPIC, query.getValue().getTopicId());
        assertEquals(Long.valueOf(1L), query.getValue().getAuditStatus());
        assertEquals(Integer.valueOf(0), query.getValue().getDelFlag());
    }

    @Test
    void nonMerchantCannotEnumerateAnotherTopicsTrend() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(88L);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(accessService.require(PRINCIPAL, MerchantPermission.VERIFY_RECORD_READ))
                .thenThrow(new ServiceException("商家身份无效或权限不足", 403));

        AjaxResult result = RegistrationQueryTestFixtures.wire(controller).countByDate("1", String.valueOf(TOPIC));

        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        verify(registrationService, never()).countByParticipateDate(any(), any());
    }

    @Test
    void operatorCannotReadActivityOwnedByAnotherMerchant() {
        CmsActivity activity = new CmsActivity();
        activity.setId(401L);
        activity.setMemberId(88L);
        when(activityService.selectCmsActivityById(401L)).thenReturn(activity);
        when(accessService.require(PRINCIPAL, MerchantPermission.VERIFY_RECORD_READ))
                .thenReturn(new MerchantAccessContext(
                        PRINCIPAL, 11L, OWNER, MerchantRoleCode.MERCHANT_CHECKIN));

        AjaxResult result = RegistrationQueryTestFixtures.wire(controller).countByDate("2", "401");

        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        verify(registrationService, never()).countByParticipateDate(any(), any());
    }
}
