package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.UmsComment;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.AppMediaAssetMapper;
import com.chengyinhub.business.service.CommentPublicationService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.impl.AppMediaAssetService;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Round5B-1:评论图片不得直收客户端给的任意外链。 */
class ApiCommentMediaGuardTest {
    private static final String OWN_KEY = "upload/0123456789abcdef0123456789abcdef.jpg";

    private final CommentPublicationService publication = mock(CommentPublicationService.class);
    private final IWxContentSecurityService security = mock(IWxContentSecurityService.class);
    private final AppMediaAssetMapper assets = mock(AppMediaAssetMapper.class);

    private ApiCommentController controller() {
        ApiCommentController controller = spy(new ApiCommentController());
        doReturn(8L).when(controller).getAppUserId();
        IUmsMemberService members = mock(IUmsMemberService.class);
        UmsMember member = new UmsMember();
        member.setWxOpenId("openid-8");
        when(members.selectUmsMemberById(8L)).thenReturn(member);
        AppMediaReferenceGuard guard = new AppMediaReferenceGuard();
        ReflectionTestUtils.setField(guard, "mapper", assets);
        ReflectionTestUtils.setField(guard, "assets", mock(AppMediaAssetService.class));
        ReflectionTestUtils.setField(controller, "umsMemberService", members);
        ReflectionTestUtils.setField(controller, "commentPublicationService", publication);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", security);
        ReflectionTestUtils.setField(controller, "mediaReferenceGuard", guard);
        return controller;
    }

    @Test
    void externalImageUrlIsRefusedBeforePublication() {
        AjaxResult result = controller().addComment("1", "3", "5", "好玩", "0",
                "https://evil.example.com/ad.jpg");

        assertEquals(500, result.get("code"));
        verify(publication, never()).publish(any());
        verify(security, never()).submitMediaCheck(anyInt(), any(), anyString(), anyString());
    }

    @Test
    void ownUploadIsStoredAsReviewedAssetReference() {
        Map<String, Object> asset = new HashMap<>();
        asset.put("id", 41L);
        asset.put("ownerType", "APP");
        asset.put("memberId", 8L);
        asset.put("reviewState", "PENDING");
        when(assets.selectByObjectKey(OWN_KEY)).thenReturn(asset);

        AjaxResult result = controller().addComment("1", "3", "5", "好玩", "0",
                "https://oss.example.com/" + OWN_KEY);

        assertEquals(200, result.get("code"));
        ArgumentCaptor<UmsComment> saved = ArgumentCaptor.forClass(UmsComment.class);
        verify(publication).publish(saved.capture());
        assertEquals("media-asset:41", saved.getValue().getImgArr());
    }
}
