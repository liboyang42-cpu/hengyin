package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.RoamAiContent;
import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.mapper.RoamAiContentMapper;
import com.chengyinhub.business.service.RoamAiContentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoamAiContentServiceTest {

    private RoamAiContentMapper mapper;
    private RoamAiContentService service;

    @BeforeEach
    void setUp() {
        mapper = mock(RoamAiContentMapper.class);
        service = new RoamAiContentService();
        ReflectionTestUtils.setField(service, "roamAiContentMapper", mapper);
    }

    @Test
    void poiMeaningNeverReadsMerchantContent() {
        RoamPoi merchant = new RoamPoi();
        merchant.setId(7L);
        merchant.setType(2);
        merchant.setMerchantId(99L);

        assertNull(service.approvedPoiMeaning(merchant));
        verify(mapper, never()).selectApprovedPoiMeaning(7L);
    }

    @Test
    void poiMeaningComesOnlyFromApprovedContentMapper() {
        RoamPoi landmark = new RoamPoi();
        landmark.setId(7L);
        landmark.setType(1);
        RoamAiContent meaning = new RoamAiContent();
        meaning.setContentText("这里曾是街区公共记忆的一部分。");
        when(mapper.selectApprovedPoiMeaning(7L)).thenReturn(meaning);

        assertEquals("这里曾是街区公共记忆的一部分。", service.approvedPoiMeaning(landmark));
    }

    @Test
    void poiMeaningFailsClosedForForwardGuidanceAlreadyApproved() {
        RoamPoi landmark = new RoamPoi();
        landmark.setId(7L);
        landmark.setType(1);
        RoamAiContent meaning = new RoamAiContent();
        meaning.setContentText("下次建议先去街角小店，再回到这里。");
        when(mapper.selectApprovedPoiMeaning(7L)).thenReturn(meaning);

        assertNull(service.approvedPoiMeaning(landmark));
    }

    @Test
    void routeStoryRendersOnlyWhitelistedVerifiedMetrics() {
        RoamSession session = finishedSession(11L, 999999, 8, 3);
        RoamAiContent template = new RoamAiContent();
        template.setContentText("本次点亮 {{tiles}} 格，发现 {{pois}} 处。");
        when(mapper.selectApprovedRouteStories()).thenReturn(Collections.singletonList(template));

        assertEquals("本次点亮 8 格，发现 3 处。", service.approvedRouteStory(session));
    }

    @Test
    void routeStoryFailsClosedForUnknownTemplateTokenOrUnfinishedSession() {
        RoamAiContent unsafe = new RoamAiContent();
        unsafe.setContentText("{{distanceKm}} 公里的漫游");
        when(mapper.selectApprovedRouteStories()).thenReturn(Arrays.asList(unsafe));

        assertNull(service.approvedRouteStory(finishedSession(1L, 10, 1, 1)));
        assertNull(service.approvedRouteStory(new RoamSession()));
    }

    @Test
    void routeStoryFailsClosedForForwardLookingGuidanceAlreadyInApprovedData() {
        for (String text : Arrays.asList(
                "下次可以回到这片街区。",
                "建议去街角小店看看。",
                "先去公园，再去街角小店。")) {
            RoamAiContent unsafe = new RoamAiContent();
            unsafe.setContentText(text);
            when(mapper.selectApprovedRouteStories()).thenReturn(Collections.singletonList(unsafe));

            assertNull(service.approvedRouteStory(finishedSession(1L, 10, 1, 1)), text);
        }
    }

    private RoamSession finishedSession(Long id, int distanceM, int tiles, int pois) {
        RoamSession session = new RoamSession();
        session.setId(id);
        session.setStatus(1);
        session.setDistanceM(distanceM);
        session.setTilesRevealed(tiles);
        session.setPoisFound(pois);
        return session;
    }
}
