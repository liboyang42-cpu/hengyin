package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.UmsCreativeSquare;
import com.chengyinhub.business.domain.ViewCreativeSquare;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IUmsCreativeSquareService;
import com.chengyinhub.business.service.IViewCreativeSquareService;
import com.chengyinhub.business.service.ICommunityPostService;
import com.chengyinhub.business.domain.dto.CommunityPostRequests;
import com.chengyinhub.business.service.impl.CreativeSquareCommunityPolicyServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.service.ICmsTopicService;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 广场社区第一条纵向闭环：关注流、Block 可见性和真实举报原因。 */
class ApiCreativeSquareCommunityPolicyTest {

    @Test
    void legacyCommunityWritesStayEnabledUntilControlledCutover() throws Exception {
        assertEquals("${community.post.legacy-writes-enabled:true}",
                ApiCreativeSquareController.class.getDeclaredField("legacyCommunityWritesEnabled")
                        .getAnnotation(Value.class).value());
        assertEquals("${community.post.legacy-writes-enabled:true}",
                ApiCommentController.class.getDeclaredField("legacyCommunityWritesEnabled")
                        .getAnnotation(Value.class).value());
        assertEquals("${community.post.legacy-writes-enabled:true}",
                ApiTopicController.class.getDeclaredField("legacyCommunityWritesEnabled")
                        .getAnnotation(Value.class).value());
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void followingFeedScopesQueryToCurrentMemberAndAppliesBlockPolicy() {
        IViewCreativeSquareService viewService = mock(IViewCreativeSquareService.class);
        when(viewService.selectViewCreativeSquareList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Collections.emptyList());
        ApiCreativeSquareController controller = controller(8L, viewService, null, null, null);
        requestParameter("feed_mode", "following");

        AjaxResult result = controller.viewCreativeSquareList("0", null, null);

        assertEquals(200, result.get("code"));
        ArgumentCaptor<ViewCreativeSquare> query = ArgumentCaptor.forClass(ViewCreativeSquare.class);
        verify(viewService).selectViewCreativeSquareList(query.capture());
        assertEquals(Long.valueOf(8L), query.getValue().getFollowingMemberId());
        assertEquals(Long.valueOf(8L), query.getValue().getViewerMemberId());
    }

    @Test
    void followingFeedRejectsGuestInsteadOfPretendingToBePersonalized() {
        IViewCreativeSquareService viewService = mock(IViewCreativeSquareService.class);
        ApiCreativeSquareController controller = controller(null, viewService, null, null, null);
        requestParameter("feed_mode", "following");

        AjaxResult result = controller.viewCreativeSquareList("0", null, null);

        assertEquals(500, result.get("code"));
        verify(viewService, never()).selectViewCreativeSquareList(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void favoriteFeedScopesQueryToCurrentMember() {
        IViewCreativeSquareService viewService = mock(IViewCreativeSquareService.class);
        when(viewService.selectViewCreativeSquareList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Collections.emptyList());
        ApiCreativeSquareController controller = controller(8L, viewService, null, null, null);
        requestParameter("favorite_only", "1");

        AjaxResult result = controller.viewCreativeSquareList("0", null, null);

        assertEquals(200, result.get("code"));
        ArgumentCaptor<ViewCreativeSquare> query = ArgumentCaptor.forClass(ViewCreativeSquare.class);
        verify(viewService).selectViewCreativeSquareList(query.capture());
        assertEquals(Boolean.TRUE, query.getValue().getFavoriteOnly());
        assertEquals(Long.valueOf(8L), query.getValue().getViewerMemberId());
    }

    @Test
    void bookmarkEndpointDelegatesToCommunityPostSourceOfTruth() {
        ICommunityPostService postService = mock(ICommunityPostService.class);
        when(postService.act(eq(8L), eq(42L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Collections.emptyMap());
        when(postService.unact(8L, 42L, "BOOKMARK", "bookmark-42-off"))
                .thenReturn(Collections.emptyMap());
        ApiCreativeSquareController controller = controller(8L, null, null, null, null);
        ReflectionTestUtils.setField(controller, "communityPostService", postService);

        assertEquals(200, controller.creativeSquareBookmark("42", 1, "bookmark-42-on").get("code"));
        ArgumentCaptor<CommunityPostRequests.Action> action = ArgumentCaptor.forClass(CommunityPostRequests.Action.class);
        verify(postService).act(eq(8L), eq(42L), action.capture());
        assertEquals("BOOKMARK", action.getValue().getActionType());
        assertEquals("bookmark-42-on", action.getValue().getRequestId());

        assertEquals(200, controller.creativeSquareBookmark("42", 0, "bookmark-42-off").get("code"));
        verify(postService).unact(8L, 42L, "BOOKMARK", "bookmark-42-off");
    }

    @Test
    void blockedAuthorCannotBeOpenedThroughDetailDeepLink() {
        IViewCreativeSquareService viewService = mock(IViewCreativeSquareService.class);
        IImService imService = mock(IImService.class);
        ViewCreativeSquare post = new ViewCreativeSquare();
        post.setId(31L);
        post.setMemberId(9L);
        post.setStatus(1);
        post.setDelFlag(0);
        when(viewService.selectViewCreativeSquareById(31L)).thenReturn(post);
        when(imService.isBlockedBetween(8L, 9L)).thenReturn(true);
        ApiCreativeSquareController controller = controller(8L, viewService, null, null, imService);

        AjaxResult result = controller.viewCreativeSquareInfo("31");

        assertEquals(500, result.get("code"));
        assertEquals("内容不存在", result.get("msg"));
    }

    @ParameterizedTest
    @CsvSource({"false,2,1,0", "false,0,1,0", "false,1,0,0", "false,1,1,2", "true,1,1,0", "false,1,1,0"})
    void linkedTopicDetailUsesCurrentPublicState(boolean missing, int status, int userStatus, int delFlag) {
        IViewCreativeSquareService views = mock(IViewCreativeSquareService.class);
        ICmsTopicService topics = mock(ICmsTopicService.class);
        ViewCreativeSquare post = new ViewCreativeSquare();
        post.setId(31L);
        post.setMemberId(9L);
        post.setStatus(1);
        post.setDelFlag(0);
        post.setDataType(2L);
        post.setDataId(77L);
        when(views.selectViewCreativeSquareById(31L)).thenReturn(post);
        CmsTopic topic = new CmsTopic();
        topic.setId(77L);
        topic.setStatus(status);
        topic.setUserStatus(userStatus);
        topic.setDelFlag(delFlag);
        topic.setCheckStatus(0); // 专业主题先发后审,不得要求 checkStatus=1。
        when(topics.selectCmsTopicById(77L)).thenReturn(missing ? null : topic);
        ApiCreativeSquareController controller = controller(8L, views, null, null, null);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topics);

        AjaxResult result = controller.viewCreativeSquareInfo("31");

        assertEquals(!missing && status == 1 && userStatus == 1 && delFlag == 0 ? 200 : 500,
                result.get("code"));
        if (!Integer.valueOf(200).equals(result.get("code"))) {
            verify(views, never()).selectCreativeSquareLikesByMemberAndDataIds(
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyList());
        }
    }

    @Test
    void reportForwardsSelectedReasonToUnifiedAuditTask() {
        IUmsCreativeSquareService squareService = mock(IUmsCreativeSquareService.class);
        IAuditTaskService auditTaskService = mock(IAuditTaskService.class);
        UmsCreativeSquare post = new UmsCreativeSquare();
        post.setId(42L);
        post.setDelFlag(0);
        when(squareService.selectUmsCreativeSquareById(42L)).thenReturn(post);
        ApiCreativeSquareController controller = controller(8L, null, squareService, auditTaskService, null);
        ReflectionTestUtils.setField(controller, "legacyCommunityWritesEnabled", true);

        AjaxResult result = controller.ceativeSquareReport("42", "虚假信息或欺诈");

        assertEquals(200, result.get("code"));
        verify(auditTaskService).reportContent("SQUARE", 42L, 8L, "虚假信息或欺诈");
    }

    @Test
    void blankReportReasonIsRejectedWithoutCreatingAuditRecord() {
        IUmsCreativeSquareService squareService = mock(IUmsCreativeSquareService.class);
        IAuditTaskService auditTaskService = mock(IAuditTaskService.class);
        UmsCreativeSquare post = new UmsCreativeSquare();
        post.setId(42L);
        post.setDelFlag(0);
        when(squareService.selectUmsCreativeSquareById(42L)).thenReturn(post);
        ApiCreativeSquareController controller = controller(8L, null, squareService, auditTaskService, null);
        ReflectionTestUtils.setField(controller, "legacyCommunityWritesEnabled", true);

        AjaxResult result = controller.ceativeSquareReport("42", "  ");

        assertEquals(500, result.get("code"));
        verify(auditTaskService, never()).reportContent(eq("SQUARE"), eq(42L), eq(8L), org.mockito.ArgumentMatchers.anyString());
    }

    private ApiCreativeSquareController controller(Long memberId,
                                                   IViewCreativeSquareService viewService,
                                                   IUmsCreativeSquareService squareService,
                                                   IAuditTaskService auditTaskService,
                                                   IImService imService) {
        ApiCreativeSquareController controller = spy(new ApiCreativeSquareController());
        doReturn(memberId).when(controller).getAppUserId();
        if (viewService != null) ReflectionTestUtils.setField(controller, "viewCreativeSquareService", viewService);
        if (squareService != null) ReflectionTestUtils.setField(controller, "umsCreativeSquareService", squareService);
        if (auditTaskService != null) ReflectionTestUtils.setField(controller, "auditTaskService", auditTaskService);
        ReflectionTestUtils.setField(
                controller,
                "creativeSquareCommunityPolicyService",
                new CreativeSquareCommunityPolicyServiceImpl(imService == null ? mock(IImService.class) : imService));
        return controller;
    }

    private void requestParameter(String name, String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter(name, value);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
