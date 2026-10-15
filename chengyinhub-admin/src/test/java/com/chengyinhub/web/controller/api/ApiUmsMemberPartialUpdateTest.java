package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.UmsMemberUpdateDTO;
import com.chengyinhub.business.domain.vo.NicknameCheckVO;
import com.chengyinhub.business.service.INicknameSuggestionService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T8:/api/user/update 是部分更新 —— 没传的字段不校验、不修改(首页城市回写只带 workAddress)。 */
class ApiUmsMemberPartialUpdateTest {

    private final IUmsMemberService members = mock(IUmsMemberService.class);
    private final AppMediaReferenceGuard media = mock(AppMediaReferenceGuard.class);
    private final INicknameSuggestionService nicknames = mock(INicknameSuggestionService.class);
    private final ApiUmsMemberController controller = new ApiUmsMemberController() {
        @Override public Long getAppUserId() { return 42L; }
    };

    ApiUmsMemberPartialUpdateTest() {
        ReflectionTestUtils.setField(controller, "umsMemberService", members);
        ReflectionTestUtils.setField(controller, "mediaReferences", media);
        ReflectionTestUtils.setField(controller, "nicknameSuggestionService", nicknames);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", mock(IWxContentSecurityService.class));
        when(members.updateUmsMember(any())).thenReturn(1);
        NicknameCheckVO invalid = new NicknameCheckVO();
        invalid.setAvailable(false);
        invalid.setErrorCode("NICKNAME_INVALID");
        when(nicknames.check(any(), anyLong())).thenReturn(invalid);
    }

    @Test
    void cityOnlyUpdateSkipsNicknameCheckAndLeavesNameUntouched() {
        UmsMemberUpdateDTO req = new UmsMemberUpdateDTO();
        req.setWorkAddress("上海市");

        AjaxResult res = controller.updateUserInfo(req);

        assertEquals(200, res.get(AjaxResult.CODE_TAG), String.valueOf(res.get(AjaxResult.MSG_TAG)));
        verify(nicknames, never()).check(any(), anyLong());
        ArgumentCaptor<UmsMember> saved = ArgumentCaptor.forClass(UmsMember.class);
        verify(members).updateUmsMember(saved.capture());
        assertEquals("上海市", saved.getValue().getWorkAddress());
        assertNull(saved.getValue().getName());
        assertNull(saved.getValue().getNickname());
    }

    @Test
    void sentNameIsStillValidatedAsBefore() {
        UmsMemberUpdateDTO req = new UmsMemberUpdateDTO();
        req.setName("x");

        AjaxResult res = controller.updateUserInfo(req);

        assertEquals(400, res.get(AjaxResult.CODE_TAG), "App 带 name 的调用行为不变:非法昵称仍拒");
        verify(members, never()).updateUmsMember(any());
    }

    /** 一个可更新字段都没有(如设置页误传 nickname/email)不能 200 装成功 —— 与改前一样拒。 */
    @Test
    void emptyPatchStillRejected() {
        AjaxResult res = controller.updateUserInfo(new UmsMemberUpdateDTO());

        assertEquals(400, res.get(AjaxResult.CODE_TAG));
        verify(members, never()).updateUmsMember(any());
    }
    @Test void profileCannotPublishUnapprovedAvatarOrWriteAnyOtherFieldsAfterRejection() {
        UmsMemberUpdateDTO req = new UmsMemberUpdateDTO();
        req.setAvatar("media-asset:5"); req.setWorkAddress("上海市");
        when(media.requireAttachment(42L, "media-asset:5", true)).thenThrow(new ServiceException("媒体尚未审核通过", 422));
        ServiceException error = org.junit.jupiter.api.Assertions.assertThrows(ServiceException.class,
                () -> controller.updateUserInfo(req));
        assertEquals(422, error.getCode());
        verify(members, never()).updateUmsMember(any());
    }

    @Test void approvedProfileMediaPersistsCanonicalReferencesThroughTheActualEntry() {
        UmsMemberUpdateDTO req = new UmsMemberUpdateDTO();
        req.setAvatar("https://reviewed/avatar.png"); req.setCasePics("https://reviewed/case.png");
        when(media.requireAttachment(42L, req.getAvatar(), true)).thenReturn("media-asset:5");
        when(media.requireAttachments(42L, req.getCasePics(), true)).thenReturn("media-asset:6");
        assertEquals(200, controller.updateUserInfo(req).get(AjaxResult.CODE_TAG));
        ArgumentCaptor<UmsMember> saved = ArgumentCaptor.forClass(UmsMember.class);
        verify(members).updateUmsMember(saved.capture());
        assertEquals("media-asset:5", saved.getValue().getAvatar());
        assertEquals("media-asset:6", saved.getValue().getCasePics());
    }
}
