package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.dto.CircleExploreActionRequest;
import com.chengyinhub.business.domain.dto.CircleThemeInstanceConfigRequest;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiCircleThemeMerchantOperatorScopeTest {
    private static final long PRINCIPAL = 901L;
    private static final long OWNER = 900L;

    private ApiCircleThemeController controller;
    private ICmsTopicService topicService;
    private IMerchantAccessService accessService;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiCircleThemeController());
        topicService = mock(ICmsTopicService.class);
        accessService = mock(IMerchantAccessService.class);
        doReturn(PRINCIPAL).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "merchantAccessService", accessService);
        MerchantAccessContext access = new MerchantAccessContext(
                PRINCIPAL, 77L, OWNER, MerchantRoleCode.MERCHANT_MANAGER);
        when(accessService.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE)).thenReturn(access);
    }

    @Test
    void managerConfigureAndReviewUseMerchantOwner() {
        CircleThemeInstanceConfigRequest configure = new CircleThemeInstanceConfigRequest();
        configure.setTopicId(42L);
        configure.setCandidateCodes(Arrays.asList("COFFEE", "BOOK", "PHOTO"));
        configure.setScope("MERCHANT");
        when(topicService.configureCircleCandidates(42L, OWNER, configure.getCandidateCodes())).thenReturn(1);
        CircleExploreActionRequest review = new CircleExploreActionRequest();
        review.setTopicId(42L);
        review.setScope("MERCHANT");
        when(topicService.reviewCircleSupplies(42L, OWNER)).thenReturn(new Date());

        assertEquals(200, controller.configure(configure).get(AjaxResult.CODE_TAG));
        assertEquals(200, controller.review(review).get(AjaxResult.CODE_TAG));

        verify(topicService).configureCircleCandidates(42L, OWNER, configure.getCandidateCodes());
        verify(topicService).reviewCircleSupplies(42L, OWNER);
    }

    @Test
    void blankScopeKeepsPersonalTopicIdentity() {
        CircleThemeInstanceConfigRequest configure = new CircleThemeInstanceConfigRequest();
        configure.setTopicId(43L);
        configure.setCandidateCodes(Arrays.asList("COFFEE", "BOOK", "PHOTO"));
        when(topicService.configureCircleCandidates(43L, PRINCIPAL, configure.getCandidateCodes())).thenReturn(1);

        assertEquals(200, controller.configure(configure).get(AjaxResult.CODE_TAG));

        verify(accessService, never()).require(anyLong(), anyString());
        verify(topicService).configureCircleCandidates(43L, PRINCIPAL, configure.getCandidateCodes());
    }
}
