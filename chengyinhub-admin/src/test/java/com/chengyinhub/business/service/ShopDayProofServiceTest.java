package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.service.impl.PlayerNodeProgressServiceImpl;
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
    private TopicRouteSessionMapper routeSessions;

    @BeforeEach
    void setUp() {
        mapper = mock(PlayerNodeProgressMapper.class);
        service = new ShopDayProofServiceImpl();
        ReflectionTestUtils.setField(service, "playerNodeProgressMapper", mapper);
        PlayerNodeProgressServiceImpl owner = new PlayerNodeProgressServiceImpl();
        ReflectionTestUtils.setField(owner, "playerNodeProgressMapper", mapper);
        routeSessions = mock(TopicRouteSessionMapper.class);
        // 原用例是无route session的default局；实例解析使用真实owner，不mock服务成功。
        when(routeSessions.selectCurrentByOwner(any(), any(), any())).thenReturn(null);
        ReflectionTestUtils.setField(owner, "routeSessions", routeSessions);
        ReflectionTestUtils.setField(service, "playerNodeProgressService", owner);
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

    @Test
    void arrivalAndSiblingsKeepTheOneResolvedInstanceWhenLatestChanges() {
        TopicRouteSession initial = new TopicRouteSession(); initial.setPlayInstanceId("replay:old");
        TopicRouteSession later = new TopicRouteSession(); later.setPlayInstanceId("replay:new");
        java.util.concurrent.atomic.AtomicReference<TopicRouteSession> current =
                new java.util.concurrent.atomic.AtomicReference<>(initial);
        when(routeSessions.selectCurrentByOwner(11L, 7L, 9L)).thenAnswer(call -> current.get());
        when(mapper.selectPlayerNodeProgressList(any())).thenAnswer(call -> {
            current.set(later); // 入口解析后发生重玩切换，兄弟节点不得重新解析latest。
            return Collections.emptyList();
        });
        when(mapper.insertPlayerNodeProgress(any())).thenReturn(1);
        CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(arrivalNode(32L)));
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodes);

        service.markArrived(9L, 11L, arrivalNode(31L));

        verify(routeSessions, times(1)).selectCurrentByOwner(11L, 7L, 9L);
        org.mockito.ArgumentCaptor<PlayerNodeProgress> reads = org.mockito.ArgumentCaptor.forClass(PlayerNodeProgress.class);
        verify(mapper, times(2)).selectPlayerNodeProgressList(reads.capture());
        org.mockito.ArgumentCaptor<PlayerNodeProgress> writes = org.mockito.ArgumentCaptor.forClass(PlayerNodeProgress.class);
        verify(mapper, times(2)).insertPlayerNodeProgress(writes.capture());
        for (PlayerNodeProgress row : reads.getAllValues()) {
            assertEquals("replay:old", row.getPlayInstanceId());
            assertEquals(7L, row.getTopicId());
        }
        for (PlayerNodeProgress row : writes.getAllValues()) {
            assertEquals("replay:old", row.getPlayInstanceId());
            assertEquals(7L, row.getTopicId());
            assertEquals(0, row.getStatus()); assertNull(row.getPicUrl());
        }
    }

    @Test
    void duplicateReadbackKeepsTheResolvedInstanceAndOriginalProof() {
        TopicRouteSession initial = new TopicRouteSession(); initial.setPlayInstanceId("replay:old");
        TopicRouteSession later = new TopicRouteSession(); later.setPlayInstanceId("replay:new");
        when(routeSessions.selectCurrentByOwner(11L, 7L, 9L)).thenReturn(initial, later);
        PlayerNodeProgress stored = new PlayerNodeProgress();
        stored.setPlayInstanceId("replay:old"); stored.setStatus(1);
        stored.setPicUrl("https://img/original.jpg"); stored.setCheckinTime(new java.util.Date(1234L));
        when(mapper.selectPlayerNodeProgressList(any())).thenReturn(Collections.emptyList(), Collections.singletonList(stored));
        when(mapper.insertPlayerNodeProgress(any())).thenThrow(new org.springframework.dao.DuplicateKeyException("same run"));

        assertSame(stored, service.markArrived(9L, 11L, arrivalNode(31L)));

        verify(routeSessions, times(1)).selectCurrentByOwner(11L, 7L, 9L);
        org.mockito.ArgumentCaptor<PlayerNodeProgress> reads = org.mockito.ArgumentCaptor.forClass(PlayerNodeProgress.class);
        verify(mapper, times(2)).selectPlayerNodeProgressList(reads.capture());
        for (PlayerNodeProgress row : reads.getAllValues()) {
            assertEquals("replay:old", row.getPlayInstanceId()); assertEquals(7L, row.getTopicId());
        }
        assertEquals(1, stored.getStatus()); assertEquals("https://img/original.jpg", stored.getPicUrl());
        assertEquals(1234L, stored.getCheckinTime().getTime());
        verify(mapper, never()).updateShopDayPhoto(anyLong(), anyString());
    }

    @Test
    void missingTopicFailsBeforeAnyArrivalLookupOrWrite() {
        CmsTopicNode node = new CmsTopicNode(); node.setId(31L);
        assertThrows(IllegalArgumentException.class, () -> service.markArrived(9L, 11L, node));
        verifyNoInteractions(mapper, routeSessions);
    }

    @Test
    void anUnresolvedInstanceCannotFallBackToADefaultInsert() {
        TopicRouteSession invalid = new TopicRouteSession();
        when(routeSessions.selectCurrentByOwner(11L, 7L, 9L)).thenReturn(invalid);
        when(mapper.selectPlayerNodeProgressList(any())).thenReturn(Collections.emptyList());
        assertThrows(IllegalStateException.class, () -> service.markArrived(9L, 11L, arrivalNode(31L)));
        verify(mapper, never()).insertPlayerNodeProgress(any());
    }

    private static CmsTopicNode arrivalNode(long id) {
        CmsTopicNode node = new CmsTopicNode(); node.setId(id); node.setTopicId(7L);
        node.setChapterId(100L); node.setMerchantMemberId(88L); return node;
    }
}
