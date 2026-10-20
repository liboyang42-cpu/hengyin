package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.service.impl.ShopDayProofServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ShopDayProofServiceTest {
    private PlayerNodeProgressMapper mapper;
    private ShopDayProofServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(PlayerNodeProgressMapper.class);
        service = new ShopDayProofServiceImpl();
        ReflectionTestUtils.setField(service, "playerNodeProgressMapper", mapper);
        CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        CmsTopicNode node = new CmsTopicNode();
        node.setId(31L);
        node.setTopicId(7L);
        when(nodes.selectCmsTopicNodeById(31L)).thenReturn(node);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodes);
        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        when(runtime.playInstance(9L, 7L, 11L)).thenReturn("default");
        when(runtime.assertCurrentPlayInstance(9L, 7L, 11L, "default")).thenReturn(false);
        ReflectionTestUtils.setField(service, "routeRuntimeService", runtime);
    }

    @Test
    void arrivalCreatesPendingProgressWithoutPretendingCompletion() {
        when(mapper.selectPlayerNodeProgressListForInstance(any(), eq("default"))).thenReturn(Collections.emptyList());
        when(mapper.insertPlayerNodeProgress(any())).thenReturn(1);
        CmsTopicNode node = new CmsTopicNode();
        node.setId(31L);
        node.setTopicId(7L);

        PlayerNodeProgress saved = service.markArrived(9L, 11L, node);

        assertEquals(0, saved.getStatus());
        assertNotNull(saved.getCheckinTime());
        assertNull(saved.getPicUrl());
        verify(mapper).insertPlayerNodeProgress(saved);
    }

    @Test
    void photoRequiresArrivalAndKeepsProgressPending() {
        PlayerNodeProgress arrived = scopedProgress();
        arrived.setId(41L);
        arrived.setStatus(0);
        arrived.setCheckinTime(new java.util.Date());
        when(mapper.selectPlayerNodeProgressListForInstance(any(), eq("default"))).thenReturn(Collections.singletonList(arrived));
        when(mapper.updateShopDayPhotoForInstance(41L, 9L, 11L, 7L, 31L, "default", "https://img/proof.jpg")).thenReturn(1);

        PlayerNodeProgress saved = service.submitPhoto(9L, 11L, 31L, "https://img/proof.jpg");

        assertEquals(0, saved.getStatus());
        assertEquals(0, saved.getReviewStatus());
        assertEquals("https://img/proof.jpg", saved.getPicUrl());
        verify(mapper).updateShopDayPhotoForInstance(41L, 9L, 11L, 7L, 31L, "default", "https://img/proof.jpg");
    }

    @Test
    void photoBeforeArrivalFailsClosed() {
        when(mapper.selectPlayerNodeProgressListForInstance(any(), eq("default"))).thenReturn(Collections.emptyList());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.submitPhoto(9L, 11L, 31L, "https://img/proof.jpg"));
        assertEquals("请先扫码确认到店", ex.getMessage());
        verify(mapper, never()).updateShopDayPhotoForInstance(anyLong(), anyLong(), anyLong(), anyLong(),
                anyLong(), anyString(), anyString());
    }

    @Test
    void repeatedPhotoReadsBackTheStoredCounterpartInsteadOfReportingFalseFailure() {
        PlayerNodeProgress arrived = scopedProgress();
        arrived.setId(41L);
        arrived.setStatus(0);
        arrived.setCheckinTime(new java.util.Date());
        PlayerNodeProgress stored = scopedProgress();
        stored.setId(41L);
        stored.setStatus(0);
        stored.setCheckinTime(arrived.getCheckinTime());
        stored.setPicUrl("https://img/proof.jpg");
        stored.setReviewStatus(0);
        when(mapper.selectPlayerNodeProgressListForInstance(any(), eq("default"))).thenReturn(
                Collections.singletonList(arrived), Collections.singletonList(stored));
        when(mapper.updateShopDayPhotoForInstance(41L, 9L, 11L, 7L, 31L, "default", "https://img/proof.jpg")).thenReturn(0);

        PlayerNodeProgress result = service.submitPhoto(9L, 11L, 31L, "https://img/proof.jpg");

        assertEquals("https://img/proof.jpg", result.getPicUrl());
        assertEquals(0, result.getStatus());
        verify(mapper, times(2)).selectPlayerNodeProgressListForInstance(any(), eq("default"));
    }

    @Test
    void differentSecondPhotoCannotOverwriteOriginalProof() {
        PlayerNodeProgress arrived = scopedProgress();
        arrived.setId(41L);
        arrived.setStatus(0);
        arrived.setCheckinTime(new java.util.Date());
        arrived.setPicUrl("https://img/original.jpg");
        when(mapper.selectPlayerNodeProgressListForInstance(any(), eq("default"))).thenReturn(Collections.singletonList(arrived));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.submitPhoto(9L, 11L, 31L, "https://img/replacement.jpg"));

        assertEquals("到店照片已提交，不能覆盖原始凭证", error.getMessage());
        verify(mapper, never()).updateShopDayPhotoForInstance(anyLong(), anyLong(), anyLong(), anyLong(),
                anyLong(), anyString(), anyString());
    }
    private PlayerNodeProgress scopedProgress() {
        PlayerNodeProgress progress = new PlayerNodeProgress();
        progress.setMemberId(9L);
        progress.setActivityId(11L);
        progress.setTopicId(7L);
        progress.setNodeId(31L);
        progress.setPlayInstanceId("default");
        progress.setDelFlag(0);
        return progress;
    }

}
