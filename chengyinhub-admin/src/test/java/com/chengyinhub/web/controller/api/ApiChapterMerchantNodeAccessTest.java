package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.service.IChapterMerchantNodeService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiChapterMerchantNodeAccessTest {
    private static final long PRINCIPAL = 901L;
    private static final long OWNER = 900L;

    @Test
    void submitUsesResolvedMerchantOwnerInsteadOfLoggedInOperator() {
        IChapterMerchantNodeService nodes = mock(IChapterMerchantNodeService.class);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(new MerchantAccessContext(PRINCIPAL, 11L, OWNER,
                        MerchantRoleCode.MERCHANT_MANAGER));
        when(nodes.submit(any(), any(), any())).thenReturn(new CmsTopicNode());
        ApiChapterMerchantNodeController controller = controller(nodes, access);
        ApiChapterMerchantNodeController.ChapterNodeRequest request =
                new ApiChapterMerchantNodeController.ChapterNodeRequest();
        request.setChapterId(33L);

        AjaxResult result = controller.submit(request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(nodes).submit(org.mockito.ArgumentMatchers.eq(OWNER),
                org.mockito.ArgumentMatchers.eq(33L), any(CmsTopicNode.class));
        verify(nodes, never()).submit(org.mockito.ArgumentMatchers.eq(PRINCIPAL),
                any(), any());
    }

    @Test
    void flaggedPointTextIsRejectedBeforeSubmitService() {
        IChapterMerchantNodeService nodes = mock(IChapterMerchantNodeService.class);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(new MerchantAccessContext(PRINCIPAL, 11L, OWNER,
                        MerchantRoleCode.MERCHANT_MANAGER));
        ApiChapterMerchantNodeController controller = controller(nodes, access);
        com.chengyinhub.business.service.IWxContentSecurityService sec =
                mock(com.chengyinhub.business.service.IWxContentSecurityService.class);
        when(sec.checkText(org.mockito.ArgumentMatchers.contains("违规店名"), any())).thenReturn("内容含违规信息");
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", sec);
        ApiChapterMerchantNodeController.ChapterNodeRequest request =
                new ApiChapterMerchantNodeController.ChapterNodeRequest();
        request.setChapterId(33L);
        request.setName("违规店名");

        AjaxResult result = controller.submit(request);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(nodes, never()).submit(any(), any(), any());
    }

    @Test
    void inactiveMerchantIsRejectedBeforeSubmitService() {
        IChapterMerchantNodeService nodes = mock(IChapterMerchantNodeService.class);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenThrow(new ServiceException("商家身份无效或权限不足", HttpStatus.FORBIDDEN));
        ApiChapterMerchantNodeController controller = controller(nodes, access);
        ApiChapterMerchantNodeController.ChapterNodeRequest request =
                new ApiChapterMerchantNodeController.ChapterNodeRequest();
        request.setChapterId(33L);

        AjaxResult result = controller.submit(request);

        assertEquals(HttpStatus.FORBIDDEN, result.get(AjaxResult.CODE_TAG));
        verify(nodes, never()).submit(any(), any(), any());
    }

    @Test
    void mineReadsResolvedOwnerScope() {
        IChapterMerchantNodeService nodes = mock(IChapterMerchantNodeService.class);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(PRINCIPAL, MerchantPermission.BASIC_READ))
                .thenReturn(new MerchantAccessContext(PRINCIPAL, 11L, OWNER,
                        MerchantRoleCode.MERCHANT_MANAGER));
        when(nodes.listMine(OWNER, 88L)).thenReturn(Collections.emptyList());
        ApiChapterMerchantNodeController controller = controller(nodes, access);

        AjaxResult result = controller.mine(88L);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(nodes).listMine(OWNER, 88L);
    }

    @Test
    void merchantScopedPendingUsesProjectOwnerInsteadOfLoggedInOperator() {
        IChapterMerchantNodeService nodes = mock(IChapterMerchantNodeService.class);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        ICmsTopicNodeService topicNodes = mock(ICmsTopicNodeService.class);
        ICmsTopicService topics = mock(ICmsTopicService.class);
        when(access.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(new MerchantAccessContext(PRINCIPAL, 11L, OWNER,
                        MerchantRoleCode.MERCHANT_MANAGER));
        CmsTopic topic = new CmsTopic();
        topic.setMemberId(OWNER);
        when(topics.selectCmsTopicById(88L)).thenReturn(topic);
        when(topicNodes.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.emptyList());
        ApiChapterMerchantNodeController controller = controller(
                nodes, access, topicNodes, topics);

        AjaxResult result = controller.pending(88L, "MERCHANT");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(access).require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE);
        verify(topicNodes).selectCmsTopicNodeList(any(CmsTopicNode.class));
    }

    @Test
    void merchantScopedAuditUsesProjectOwnerInsteadOfLoggedInOperator() {
        IChapterMerchantNodeService nodes = mock(IChapterMerchantNodeService.class);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(new MerchantAccessContext(PRINCIPAL, 11L, OWNER,
                        MerchantRoleCode.MERCHANT_MANAGER));
        CmsTopicNode approved = new CmsTopicNode();
        approved.setId(77L);
        when(nodes.audit(OWNER, 77L, true, "通过")).thenReturn(approved);
        ApiChapterMerchantNodeController controller = controller(nodes, access);

        AjaxResult result = controller.audit(77L, true, "通过", "MERCHANT");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(nodes).audit(OWNER, 77L, true, "通过");
        verify(nodes, never()).audit(PRINCIPAL, 77L, true, "通过");
    }

    private ApiChapterMerchantNodeController controller(IChapterMerchantNodeService nodes,
                                                        IMerchantAccessService access) {
        return controller(nodes, access, null, null);
    }

    private ApiChapterMerchantNodeController controller(IChapterMerchantNodeService nodes,
                                                        IMerchantAccessService access,
                                                        ICmsTopicNodeService topicNodes,
                                                        ICmsTopicService topics) {
        ApiChapterMerchantNodeController controller = new ApiChapterMerchantNodeController() {
            @Override
            public Long getAppUserId() {
                return PRINCIPAL;
            }
        };
        ReflectionTestUtils.setField(controller, "chapterMerchantNodeService", nodes);
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(com.chengyinhub.business.service.IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService",
                mock(com.chengyinhub.business.service.IWxContentSecurityService.class));
        if (topicNodes != null) {
            ReflectionTestUtils.setField(controller, "nodeService", topicNodes);
        }
        if (topics != null) {
            ReflectionTestUtils.setField(controller, "topicService", topics);
        }
        return controller;
    }
}
