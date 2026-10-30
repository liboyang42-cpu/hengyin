package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 实走 B4:商家核销即补记到店,只补本章本商家的站点,失败不影响已成立的核销。 */
class ImpliedShopArrivalTest {

    private CmsRegistrationServiceImpl service;
    private IShopDayProofService proofs;
    private ICmsTopicNodeService nodes;

    @BeforeEach
    void setUp() {
        service = new CmsRegistrationServiceImpl();
        proofs = mock(IShopDayProofService.class);
        nodes = mock(ICmsTopicNodeService.class);
        ReflectionTestUtils.setField(service, "shopDayProofService", proofs);
        ReflectionTestUtils.setField(service, "topicNodeService", nodes);
    }

    private static CmsRegistration activityOrder() {
        CmsRegistration registration = new CmsRegistration();
        registration.setId(74L);
        registration.setMemberId(7L);
        registration.setOwnerType(2L);
        registration.setOwnerId(31L);
        return registration;
    }

    @Test
    void marksArrivalForThisChapterAndMerchantOnly() {
        CmsTopicNode a = new CmsTopicNode(); a.setId(501L);
        CmsTopicNode b = new CmsTopicNode(); b.setId(502L);
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(Arrays.asList(a, b));

        service.recordImpliedShopArrival(activityOrder(), 12L, 900L);

        ArgumentCaptor<CmsTopicNode> probe = ArgumentCaptor.forClass(CmsTopicNode.class);
        verify(nodes).selectCmsTopicNodeList(probe.capture());
        assertEquals(12L, probe.getValue().getChapterId());
        assertEquals(900L, probe.getValue().getMerchantMemberId());
        verify(proofs).markArrived(7L, 31L, a);
        verify(proofs).markArrived(7L, 31L, b);
    }

    @Test
    void topicOrdersAndMissingFactsAreLeftAlone() {
        CmsRegistration topicOrder = activityOrder();
        topicOrder.setOwnerType(1L);
        service.recordImpliedShopArrival(topicOrder, 12L, 900L);
        service.recordImpliedShopArrival(activityOrder(), null, 900L);
        service.recordImpliedShopArrival(null, 12L, 900L);
        verify(nodes, never()).selectCmsTopicNodeList(any());
        verify(proofs, never()).markArrived(anyLong(), anyLong(), any());
    }

    @Test
    void arrivalFailureNeverEscapesTheCommittedRedemption() {
        CmsTopicNode a = new CmsTopicNode(); a.setId(501L);
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(Arrays.asList(a));
        when(proofs.markArrived(anyLong(), anyLong(), any())).thenThrow(new IllegalStateException("到店记录缺少游玩实例"));

        assertDoesNotThrow(() -> service.recordImpliedShopArrival(activityOrder(), 12L, 900L));
        verify(proofs, times(1)).markArrived(7L, 31L, a);
    }
}
