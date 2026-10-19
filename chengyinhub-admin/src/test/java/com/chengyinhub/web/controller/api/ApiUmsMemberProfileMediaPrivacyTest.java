package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.UmsMemberUpdateDTO;
import com.chengyinhub.business.service.INicknameSuggestionService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** F0435/F0436:作品图与联系二维码各自送审、新图核上传归属,旧图不重核。 */
class ApiUmsMemberProfileMediaPrivacyTest {

    private final IUmsMemberService members = mock(IUmsMemberService.class);
    private final IWxContentSecurityService security = mock(IWxContentSecurityService.class);
    private final List<String> verified = new ArrayList<>();
    private final ApiUmsMemberController controller = new ApiUmsMemberController() {
        @Override public Long getAppUserId() { return 42L; }
    };

    ApiUmsMemberProfileMediaPrivacyTest() {
        ReflectionTestUtils.setField(controller, "umsMemberService", members);
        ReflectionTestUtils.setField(controller, "nicknameSuggestionService", mock(INicknameSuggestionService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", security);
        when(members.updateUmsMember(any())).thenReturn(1);
        UmsMember current = new UmsMember();
        current.setId(42L);
        current.setWxOpenId("openid-42");
        current.setCasePics("https://o/upload/old-work.png");
        current.setWechat("https://o/upload/old-qr.png");
        when(members.selectUmsMemberById(42L)).thenReturn(current);
        controller.profileMediaOwnerVerifier = (url, memberId) -> {
            verified.add(url);
            if (url.contains("someone-else")) throw new IllegalArgumentException("not owner");
            if (url.contains("storage-down")) throw new IllegalStateException("oss down");
        };
    }

    @Test
    void contactQrCodeIsSubmittedToMediaReviewTogetherWithWorks() {
        UmsMemberUpdateDTO req = new UmsMemberUpdateDTO();
        req.setCasePics("https://o/upload/old-work.png");
        req.setWechat("https://o/upload/new-qr.png");

        AjaxResult res = controller.updateUserInfo(req);

        assertEquals(200, res.get(AjaxResult.CODE_TAG), String.valueOf(res.get(AjaxResult.MSG_TAG)));
        ArgumentCaptor<String> imgs = ArgumentCaptor.forClass(String.class);
        verify(security).submitMediaCheck(eq(IWxContentSecurityService.BIZ_TYPE_MEMBER_PROFILE), eq(42L),
                imgs.capture(), eq("openid-42"));
        assertTrue(imgs.getValue().contains("https://o/upload/new-qr.png"), "联系二维码必须进送审组合");
        assertTrue(imgs.getValue().contains("https://o/upload/old-work.png"));
    }

    @Test
    void onlyNewlyAddedWorksAndQrAreOwnershipChecked() {
        UmsMemberUpdateDTO req = new UmsMemberUpdateDTO();
        req.setCasePics("https://o/upload/old-work.png;https://o/upload/new-work.png");
        req.setWechat("https://o/upload/old-qr.png");

        assertEquals(200, controller.updateUserInfo(req).get(AjaxResult.CODE_TAG));
        assertEquals(java.util.Collections.singletonList("https://o/upload/new-work.png"), verified,
                "资料里原有的作品/二维码不重核,只核这次新出现的地址");
    }

    @Test
    void qrUploadedBySomeoneElseIsRejectedBeforeAnyWrite() {
        UmsMemberUpdateDTO req = new UmsMemberUpdateDTO();
        req.setWechat("https://o/upload/someone-else-qr.png");

        AjaxResult res = controller.updateUserInfo(req);

        assertEquals(500, res.get(AjaxResult.CODE_TAG));
        verify(members, never()).updateUmsMember(any());
        verify(security, never()).submitMediaCheck(anyInt(), anyLong(), anyString(), any());
    }

    @Test
    void storageOutageIsRetryableAndDoesNotSave() {
        UmsMemberUpdateDTO req = new UmsMemberUpdateDTO();
        req.setCasePics("https://o/upload/storage-down.png");

        AjaxResult res = controller.updateUserInfo(req);

        assertEquals("图片存储暂时不可用，请稍后重试", res.get(AjaxResult.MSG_TAG));
        verify(members, never()).updateUmsMember(any());
    }

    @Test
    void withdrawingQrSavesEmptyValueWithoutOwnershipCheck() {
        UmsMemberUpdateDTO req = new UmsMemberUpdateDTO();
        req.setWechat("");

        assertEquals(200, controller.updateUserInfo(req).get(AjaxResult.CODE_TAG));
        ArgumentCaptor<UmsMember> saved = ArgumentCaptor.forClass(UmsMember.class);
        verify(members).updateUmsMember(saved.capture());
        assertEquals("", saved.getValue().getWechat(), "撤回 = 写空串,站内本人资料重读即不再显示");
        assertTrue(verified.isEmpty());
    }
}
