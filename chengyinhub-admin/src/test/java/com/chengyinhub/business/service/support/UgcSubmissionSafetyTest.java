package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.AppMediaAssetMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.impl.AppMediaAssetService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Round5B-6:零散写入口统一复用既有文本机审 + 媒体引用闸。 */
class UgcSubmissionSafetyTest {
    private final IWxContentSecurityService security = mock(IWxContentSecurityService.class);

    private UgcSubmissionSafety safety() {
        UgcSubmissionSafety safety = new UgcSubmissionSafety();
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        UmsMember member = new UmsMember();
        member.setWxOpenId("openid-7");
        when(members.selectUmsMemberById(7L)).thenReturn(member);
        AppMediaReferenceGuard guard = new AppMediaReferenceGuard();
        ReflectionTestUtils.setField(guard, "mapper", mock(AppMediaAssetMapper.class));
        ReflectionTestUtils.setField(guard, "assets", mock(AppMediaAssetService.class));
        ReflectionTestUtils.setField(safety, "security", security);
        ReflectionTestUtils.setField(safety, "members", members);
        ReflectionTestUtils.setField(safety, "media", guard);
        return safety;
    }

    @Test
    void externalImageUrlIsRefused() {
        assertThrows(ServiceException.class, () -> safety().requireOwnImages(7L, "https://evil.example.com/x.jpg"));
    }

    @Test
    void unavailableCheckRefusesTextFailClosed() {
        when(security.checkText("店名", "openid-7")).thenReturn(IWxContentSecurityService.MSG_BUSY);
        ServiceException e = assertThrows(ServiceException.class, () -> safety().requireText(7L, false, "店名"));
        assertEquals(IWxContentSecurityService.MSG_BUSY, e.getMessage());
    }

    @Test
    void reviewRequiredPassesOnlyWhenPlatformReviewFollows() {
        when(security.checkText("简介", "openid-7")).thenReturn(IWxContentSecurityService.REVIEW_REQUIRED);
        assertDoesNotThrow(() -> safety().requireText(7L, true, "简介"));
        assertThrows(ServiceException.class, () -> safety().requireText(7L, false, "简介"));
    }
}
