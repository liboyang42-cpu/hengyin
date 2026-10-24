package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.GrowthBadge;
import com.chengyinhub.business.mapper.AppMediaAssetMapper;
import com.chengyinhub.business.mapper.GrowthBadgeMapper;
import com.chengyinhub.business.service.ICapabilityGuard;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApiClubBadgeMediaWriteTest {
    private ApiClubController controller;
    private AppMediaAssetMapper assets;
    private GrowthBadgeMapper badges;
    private IClubService clubs;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiClubController());
        doReturn(42L).when(controller).getAppUserId();
        assets = mock(AppMediaAssetMapper.class);
        badges = mock(GrowthBadgeMapper.class);
        clubs = mock(IClubService.class);
        Club club = new Club();
        club.setId(7L);
        club.setMemberId(42L);
        when(clubs.selectClubById(7L)).thenReturn(club);
        AppMediaReferenceGuard guard = new AppMediaReferenceGuard();
        ReflectionTestUtils.setField(guard, "mapper", assets);
        ReflectionTestUtils.setField(controller, "mediaReferences", guard);
        ReflectionTestUtils.setField(controller, "clubService", clubs);
        ReflectionTestUtils.setField(controller, "growthBadgeMapper", badges);
        ReflectionTestUtils.setField(controller, "capabilityGuard", mock(ICapabilityGuard.class));
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", mock(IWxContentSecurityService.class));
    }

    @Test
    void approvedUploadedIconPersistsItsCanonicalAssetInsteadOfTheSubmittedUrl() {
        String key = "upload/0123456789abcdef0123456789abcdef.png";
        when(assets.selectByObjectKey(key)).thenReturn(asset("APPROVED"));
        when(badges.insertGrowthBadge(any())).thenAnswer(call -> {
            GrowthBadge row = call.getArgument(0);
            assertEquals("media-asset:9", row.getIconUrl());
            row.setId(10L);
            return 1;
        });
        assertEquals(200, controller.badgeCreate(body("https://uploads.example/" + key + "?signature=alias")).get("code"));
        verify(badges).insertGrowthBadge(any());
    }

    @Test
    void ownHeldIconCannotBePubliclyCreated() {
        when(assets.select(9L)).thenReturn(asset("HOLD"));
        ServiceException failure = assertThrows(ServiceException.class,
                () -> controller.badgeCreate(body("media-asset:9")));
        assertEquals(422, failure.getCode());
        verifyNoInteractions(badges);
    }

    @Test
    void unregisteredLegacyOrExternalIconNeverWritesABadge() {
        assertThrows(ServiceException.class,
                () -> controller.badgeCreate(body("https://external.example/unreviewed.png")));
        verifyNoInteractions(badges);
    }

    @Test
    void foreignClubIsRejectedBeforeAnyAssetLookup() {
        Club foreign = new Club();
        foreign.setId(7L);
        foreign.setMemberId(43L);
        when(clubs.selectClubById(7L)).thenReturn(foreign);
        assertEquals(500, controller.badgeCreate(body("media-asset:9")).get("code"));
        verifyNoInteractions(assets, badges);
    }

    @Test
    void existingOptionalEmptyIconStillAllowsATextBadge() {
        assertEquals(200, controller.badgeCreate(body("")).get("code"));
        verifyNoInteractions(assets);
        verify(badges).insertGrowthBadge(any());
    }

    private Map<String, Object> body(String icon) {
        Map<String, Object> body = new HashMap<>();
        body.put("clubId", 7L);
        body.put("badgeName", "到场勋章");
        body.put("iconUrl", icon);
        return body;
    }

    private Map<String, Object> asset(String state) {
        Map<String, Object> asset = new HashMap<>();
        asset.put("id", 9L);
        asset.put("memberId", 42L);
        asset.put("ownerType", "APP");
        asset.put("reviewState", state);
        return asset;
    }
}
