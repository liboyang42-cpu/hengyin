package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.GrowthBadge;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.domain.vo.CityNodeVO;
import com.chengyinhub.business.mapper.GrowthBadgeMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CityNodePhotoQuizTest {
    static class Controller extends ApiCityNodeController {
        @Override public Long getAppUserId() { return 7L; }
    }
    private final RoamMapper pois = mock(RoamMapper.class);
    private final PlayerBadgeMapper earned = mock(PlayerBadgeMapper.class);
    private final GrowthBadgeMapper definitions = mock(GrowthBadgeMapper.class);
    private final ICmsMemberTemplateService templates = mock(ICmsMemberTemplateService.class);

    private Controller controller() {
        CityNodeVO node = new CityNodeVO(); node.setPoiId(42L); node.setStatus(1);
        node.setLat(new BigDecimal("31.2")); node.setLng(new BigDecimal("121.4"));
        node.setRadiusM(80); node.setValidationMethod(3); node.setTemplateId(88L);
        node.setPhotoRequired(true); node.setBadgeCode("ROAM_POI_42");
        when(pois.selectPlayerMerchantNodeDetail(42L, 0L)).thenReturn(node);
        CmsMemberTemplate game = new CmsMemberTemplate(); game.setCorrectAnswer("B");
        when(templates.selectCmsMemberTemplateById(88L)).thenReturn(game);
        GrowthBadge badge = new GrowthBadge(); badge.setBadgeCode("ROAM_POI_42"); badge.setStatus("0");
        when(definitions.selectByCode("ROAM_POI_42")).thenReturn(badge);
        when(pois.insertRewardLogIgnore(eq(42L), eq(7L), isNull(), any(), any(), eq(1), any())).thenReturn(1);
        when(pois.savePoiEvidence(eq(42L), eq(7L), any(), any())).thenReturn(1);
        when(earned.insertPlayerBadge(any())).thenReturn(1);
        Controller c = new Controller();
        ReflectionTestUtils.setField(c, "roamMapper", pois); ReflectionTestUtils.setField(c, "templateService", templates);
        ReflectionTestUtils.setField(c, "growthBadges", definitions); ReflectionTestUtils.setField(c, "playerBadges", earned);
        return c;
    }

    @Test void wrongAnswerDoesNotUploadVerifyWriteOrAward() {
        Controller c = controller(); assertEquals(500, c.complete(42L,31.2,121.4,"A","","").get("code"));
        verify(pois, never()).insertRewardLogIgnore(any(),any(),any(),any(),any(),any(),any());
        verifyNoInteractions(earned);
    }

    @Test void foreignPhotoIsRejectedBeforeCompletionOrBadge() {
        Controller c = controller();
        try (MockedStatic<FileOssUploadUtils> photos = mockStatic(FileOssUploadUtils.class)) {
            photos.when(() -> FileOssUploadUtils.verifyUploadedPhoto("foreign",7L)).thenThrow(new IllegalArgumentException());
            assertEquals(500, c.complete(42L,31.2,121.4,"B","foreign","").get("code"));
            verify(pois, never()).insertRewardLogIgnore(any(),any(),any(),any(),any(),any(),any());
            verifyNoInteractions(earned);
        }
    }

    @Test void completionSavesPrivateEvidenceAndAwardsOnce() {
        Controller c = controller();
        try (MockedStatic<FileOssUploadUtils> photos = mockStatic(FileOssUploadUtils.class)) {
            assertEquals(200, c.complete(42L,31.2,121.4,"B","owned","").get("code"));
            verify(pois).savePoiEvidence(42L,7L,"owned","B");
            verify(earned).insertPlayerBadge(any(PlayerBadge.class));
            when(pois.insertRewardLogIgnore(eq(42L),eq(7L),isNull(),any(),any(),eq(1),any())).thenReturn(0);
            assertEquals(true, ((java.util.Map<?,?>) c.complete(42L,31.2,121.4,"B","owned","").get("data")).get("alreadyClaimed"));
            verify(earned, times(1)).insertPlayerBadge(any());
        }
    }
}
