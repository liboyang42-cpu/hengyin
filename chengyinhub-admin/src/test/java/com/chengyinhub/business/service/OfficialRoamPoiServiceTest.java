package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.domain.dto.OfficialRoamPoiDraft;
import com.chengyinhub.business.mapper.GrowthBadgeMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.common.exception.ServiceException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OfficialRoamPoiServiceTest {
    private OfficialRoamPoiDraft draft() {
        OfficialRoamPoiDraft d = new OfficialRoamPoiDraft();
        d.poi = new RoamPoi(); d.game = new CmsMemberTemplate();
        d.poi.setId(42L); d.poi.setName("街角艺术馆"); d.poi.setAddress("测试街42号");
        d.poi.setDescription("入口观察挑战"); d.poi.setCityCode("310100");
        d.poi.setLat(new BigDecimal("31.2")); d.poi.setLng(new BigDecimal("121.4"));
        d.poi.setCoverImg("curated/entrance.jpg"); d.poi.setGalleryJson("[\"curated/entrance.jpg\"]");
        d.game.setTitle("寻找入口线索"); d.game.setPhotoRequireDesc("拍摄公共入口标识");
        d.game.setQuestionName("标识里出现哪一个字？"); d.game.setQuestionA("艺");
        d.game.setQuestionB("海"); d.game.setCorrectAnswer("A"); return d;
    }

    @Test void savesOfficialDraftWithoutCreatingMerchantOrPublishing() {
        RoamMapper pois = mock(RoamMapper.class);
        ICmsMemberTemplateService templates = mock(ICmsMemberTemplateService.class);
        IOfficialEventV2Service operators = mock(IOfficialEventV2Service.class);
        when(operators.resolveAuthorizedOfficialMember(1L)).thenReturn(7L);
        when(pois.selectOfficialPoiForUpdate(42L)).thenReturn(new RoamPoi());
        doAnswer(call -> { ((CmsMemberTemplate) call.getArgument(0)).setId(88L); return 1; })
                .when(templates).insertCmsMemberTemplate(any());
        when(pois.updateOfficialPoi(any())).thenReturn(1);
        OfficialRoamPoiDraft d = draft(); d.poi.setMerchantId(999L); d.poi.setStatus(1);
        OfficialRoamPoiService service = new OfficialRoamPoiService(pois, templates, operators, mock(GrowthBadgeMapper.class));
        assertEquals(42L, service.save(1L, d));
        assertNull(d.poi.getMerchantId()); assertEquals(1, d.poi.getType());
        assertEquals(0, d.poi.getStatus()); assertEquals(0, d.poi.getAuditStatus());
        assertTrue(d.poi.getPhotoRequired()); assertEquals(88L, d.poi.getTemplateId());
        verify(pois, never()).approveOfficialPoi(any(), any());
    }

    @Test void rejectsInvalidAnswersCoordinatesAndRemoteImagesBeforeWriting() {
        OfficialRoamPoiDraft badAnswer = draft(); badAnswer.game.setCorrectAnswer("D");
        assertThrows(ServiceException.class, () -> OfficialRoamPoiService.validate(badAnswer));
        OfficialRoamPoiDraft badLocation = draft(); badLocation.poi.setLat(new BigDecimal("91"));
        assertThrows(ServiceException.class, () -> OfficialRoamPoiService.validate(badLocation));
        OfficialRoamPoiDraft badImage = draft(); badImage.poi.setGalleryJson("[\"https://other.example/image.jpg\"]");
        assertThrows(ServiceException.class, () -> OfficialRoamPoiService.validate(badImage));
    }

    @Test void approvalCreatesTheCollectionBadgeAndOnlyPublishesPendingOfficialPoints() {
        RoamMapper pois = mock(RoamMapper.class);
        ICmsMemberTemplateService templates = mock(ICmsMemberTemplateService.class);
        GrowthBadgeMapper badges = mock(GrowthBadgeMapper.class);
        OfficialRoamPoiDraft d = draft(); d.poi.setApplicationType(3); d.poi.setAuditStatus(0);
        d.poi.setTemplateId(88L);
        when(pois.selectOfficialPoiForUpdate(42L)).thenReturn(d.poi);
        when(templates.selectCmsMemberTemplateById(88L)).thenReturn(d.game);
        when(pois.approveOfficialPoi(42L, "ROAM_POI_42")).thenReturn(1);
        when(badges.insertGrowthBadge(any())).thenReturn(1);
        OfficialRoamPoiService service = new OfficialRoamPoiService(pois, templates,
                mock(IOfficialEventV2Service.class), badges);
        service.approve(42L);
        verify(badges).insertGrowthBadge(argThat(b -> "COLLECTION".equals(b.getAssetType())
                && "ROAM_POI_42".equals(b.getBadgeCode())));
        d.poi.setAuditStatus(1);
        assertThrows(ServiceException.class, () -> service.approve(42L));
        verify(pois, times(1)).approveOfficialPoi(any(), any());
    }
}
