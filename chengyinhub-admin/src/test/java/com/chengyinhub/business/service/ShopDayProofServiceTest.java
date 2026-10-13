package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
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
    }

    @Test
    void arrivalCreatesPendingProgressWithoutPretendingCompletion() {
        when(mapper.selectPlayerNodeProgressList(any())).thenReturn(Collections.emptyList());
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
        PlayerNodeProgress arrived = new PlayerNodeProgress();
        arrived.setId(41L);
        arrived.setStatus(0);
        arrived.setCheckinTime(new java.util.Date());
        when(mapper.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(arrived));
        when(mapper.updateShopDayPhoto(41L, "https://img/proof.jpg")).thenReturn(1);

        PlayerNodeProgress saved = service.submitPhoto(9L, 11L, 31L, "https://img/proof.jpg");

        assertEquals(0, saved.getStatus());
        assertEquals(0, saved.getReviewStatus());
        assertEquals("https://img/proof.jpg", saved.getPicUrl());
        verify(mapper).updateShopDayPhoto(41L, "https://img/proof.jpg");
    }

    @Test
    void photoBeforeArrivalFailsClosed() {
        when(mapper.selectPlayerNodeProgressList(any())).thenReturn(Collections.emptyList());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.submitPhoto(9L, 11L, 31L, "https://img/proof.jpg"));
        assertEquals("请先扫码确认到店", ex.getMessage());
        verify(mapper, never()).updateShopDayPhoto(anyLong(), anyString());
    }

    @Test
    void repeatedPhotoReadsBackTheStoredCounterpartInsteadOfReportingFalseFailure() {
        PlayerNodeProgress arrived = new PlayerNodeProgress();
        arrived.setId(41L);
        arrived.setStatus(0);
        arrived.setCheckinTime(new java.util.Date());
        PlayerNodeProgress stored = new PlayerNodeProgress();
        stored.setId(41L);
        stored.setStatus(0);
        stored.setCheckinTime(arrived.getCheckinTime());
        stored.setPicUrl("https://img/proof.jpg");
        stored.setReviewStatus(0);
        when(mapper.selectPlayerNodeProgressList(any())).thenReturn(
                Collections.singletonList(arrived), Collections.singletonList(stored));
        when(mapper.updateShopDayPhoto(41L, "https://img/proof.jpg")).thenReturn(0);

        PlayerNodeProgress result = service.submitPhoto(9L, 11L, 31L, "https://img/proof.jpg");

        assertEquals("https://img/proof.jpg", result.getPicUrl());
        assertEquals(0, result.getStatus());
        verify(mapper, times(2)).selectPlayerNodeProgressList(any());
    }

    @Test
    void differentSecondPhotoCannotOverwriteOriginalProof() {
        PlayerNodeProgress arrived = new PlayerNodeProgress();
        arrived.setId(41L);
        arrived.setStatus(0);
        arrived.setCheckinTime(new java.util.Date());
        arrived.setPicUrl("https://img/original.jpg");
        when(mapper.selectPlayerNodeProgressList(any())).thenReturn(Collections.singletonList(arrived));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.submitPhoto(9L, 11L, 31L, "https://img/replacement.jpg"));

        assertEquals("到店照片已提交，不能覆盖原始凭证", error.getMessage());
        verify(mapper, never()).updateShopDayPhoto(anyLong(), anyString());
    }
}
