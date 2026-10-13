package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 使用模板也属于项目创建，生成草稿必须归商家主体。 */
class ApiTopicTemplateMerchantOperatorScopeTest {
    private static final long PRINCIPAL = 901L;
    private static final long OWNER = 900L;

    private ApiTopicTemplateController controller;
    private ICmsTopicService topicService;
    private IMerchantAccessService merchantAccessService;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiTopicTemplateController());
        topicService = mock(ICmsTopicService.class);
        merchantAccessService = mock(IMerchantAccessService.class);
        doReturn(PRINCIPAL).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "merchantAccessService", merchantAccessService);
        when(topicService.selectTopicIsTemplate(42L)).thenReturn(1);
        when(topicService.copyCmsTopic(eq(42L), any())).thenReturn(7002L);
    }

    private MerchantAccessContext access(String roleCode) {
        return new MerchantAccessContext(PRINCIPAL, 77L, OWNER, roleCode);
    }

    @SuppressWarnings("unchecked")
    @Test
    void managerTemplateDraftBelongsToMerchantOwner() {
        MerchantAccessContext context = access(MerchantRoleCode.MERCHANT_MANAGER);
        when(merchantAccessService.resolve(PRINCIPAL)).thenReturn(context);
        when(merchantAccessService.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE)).thenReturn(context);

        AjaxResult result = controller.use("42", "MERCHANT");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        org.mockito.ArgumentCaptor<Map<String, Object>> params = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(topicService).copyCmsTopic(eq(42L), params.capture());
        assertEquals(OWNER, params.getValue().get("memberId"));
    }

    @Test
    void checkinOperatorCannotUseTemplate() {
        MerchantAccessContext context = access(MerchantRoleCode.MERCHANT_CHECKIN);
        when(merchantAccessService.resolve(PRINCIPAL)).thenReturn(context);
        when(merchantAccessService.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenThrow(new ServiceException("商家身份无效或权限不足", HttpStatus.FORBIDDEN));

        AjaxResult result = controller.use("42", "MERCHANT");

        assertEquals(HttpStatus.FORBIDDEN, result.get(AjaxResult.CODE_TAG));
        verify(topicService, never()).copyCmsTopic(anyLong(), any());
    }

    @Test
    void ordinaryPublisherTemplateDraftStillBelongsToThem() {
        MerchantAccessContext context = access(MerchantRoleCode.MERCHANT_MANAGER);
        when(merchantAccessService.resolve(PRINCIPAL)).thenReturn(context);

        AjaxResult result = controller.use("42", null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(merchantAccessService, never()).require(anyLong(), anyString());
        org.mockito.ArgumentCaptor<Map<String, Object>> params = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(topicService).copyCmsTopic(eq(42L), params.capture());
        assertEquals(PRINCIPAL, params.getValue().get("memberId"));
    }

    @Test
    void managerCanSaveMerchantOwnedPublishedTopicAsTemplate() {
        MerchantAccessContext context = access(MerchantRoleCode.MERCHANT_MANAGER);
        when(merchantAccessService.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE)).thenReturn(context);
        CmsTopic topic = new CmsTopic();
        topic.setId(88L);
        topic.setMemberId(OWNER);
        topic.setStatus(1);
        when(topicService.selectCmsTopicById(88L)).thenReturn(topic);
        when(topicService.saveAsTemplate(88L, OWNER, 1)).thenReturn(1);

        AjaxResult result = controller.saveAs("88", "MERCHANT");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(topicService).saveAsTemplate(88L, OWNER, 1);
    }

    @Test
    void blankScopeSaveAsStillChecksPrincipalOwnership() {
        CmsTopic topic = new CmsTopic();
        topic.setId(89L);
        topic.setMemberId(PRINCIPAL);
        topic.setStatus(1);
        when(topicService.selectCmsTopicById(89L)).thenReturn(topic);
        when(topicService.saveAsTemplate(89L, PRINCIPAL, 1)).thenReturn(1);

        AjaxResult result = controller.saveAs("89", null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(merchantAccessService, never()).require(anyLong(), anyString());
        verify(topicService).saveAsTemplate(89L, PRINCIPAL, 1);
    }
}
