package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.service.impl.OmsTicketServiceImpl;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OmsTicketCopyToActivityTest {

    @Test
    void clubCopyAndSyncUseActualSessionWindowWhileSelfHostKeepsSourceWindow() {
        for (boolean club : new boolean[] { true, false }) {
            for (boolean sync : new boolean[] { false, true }) {
                OmsTicketMapper mapper = mock(OmsTicketMapper.class);
                OmsTicketServiceImpl service = serviceWith(mapper);
                CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
                ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
                CmsActivity activity = new CmsActivity();
                activity.setClubId(club ? 7L : null);
                activity.setStartDate(new Date(2_000_000L));
                activity.setEndDate(new Date(3_000_000L));
                when(activityMapper.selectCmsActivityByIdForUpdate(22L)).thenReturn(activity);
                OmsTicket source = topicTicket(44L, 2L);
                source.setStartTime(new Date(1_000_000L));
                source.setEndTime(new Date(1_500_000L));
                source.setPrice(BigDecimal.ZERO);
                source.setSignupDeadline(new Date(900_000L));
                OmsTicket existing = activityTicket(99L, 44L, 2L, 1L);
                when(mapper.selectOmsTicketList(any(OmsTicket.class)))
                        .thenReturn(sync ? Collections.singletonList(existing) : Collections.emptyList())
                        .thenReturn(Collections.singletonList(source));
                when(mapper.updateOmsTicket(any(OmsTicket.class))).thenReturn(1);
                if (sync) service.syncTopicTicketsToActivity(11L, 22L, 33L);
                else service.copyTopicTicketsToActivity(11L, 22L, 33L);
                ArgumentCaptor<OmsTicket> changed = ArgumentCaptor.forClass(OmsTicket.class);
                if (sync) verify(mapper).updateOmsTicket(changed.capture());
                else verify(mapper).insertOmsTicket(changed.capture());
                assertEquals(club ? activity.getStartDate() : source.getStartTime(), changed.getValue().getStartTime());
                assertEquals(club ? activity.getEndDate() : source.getEndTime(), changed.getValue().getEndTime());
                assertEquals(source.getSignupDeadline(), changed.getValue().getSignupDeadline());
                assertEquals(source.getPrice(), changed.getValue().getPrice());
                if (sync) assertNull(changed.getValue().getRemainingInventory(), "同步不改已有占位");
            }
        }
    }

    @Test
    void copiesOnlyTheSixApprovedFieldsAndKeepsLegacyTeamFieldsEmpty() {
        OmsTicketMapper mapper = mock(OmsTicketMapper.class);
        OmsTicketServiceImpl service = new OmsTicketServiceImpl();
        ReflectionTestUtils.setField(service, "memberIdentityService", mock(com.chengyinhub.business.service.impl.MemberIdentityService.class));
        ReflectionTestUtils.setField(service, "omsTicketMapper", mapper);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);

        Date signupDeadline = new Date(1_700_000_000_000L);
        OmsTicket source = new OmsTicket();
        source.setId(44L);
        source.setName("探店日午场");
        source.setPrice(new BigDecimal("69.00"));
        source.setTotalInventory(30L);
        source.setSignupDeadline(signupDeadline);
        source.setGatherLng(new BigDecimal("121.473701"));
        source.setGatherLat(new BigDecimal("31.230416"));
        source.setSubType("guided");
        source.setRefundRule("开场前24小时可退");
        source.setNeedAudit(1);
        source.setMinPeople(24);
        source.setMaxPeople(30);
        source.setTeamStatus(1);

        when(mapper.selectOmsTicketList(any(OmsTicket.class)))
                .thenReturn(Collections.emptyList())
                .thenReturn(Collections.singletonList(source));

        assertEquals(1, service.copyTopicTicketsToActivity(11L, 22L, 33L));
        org.mockito.Mockito.verify(activityMapper).selectCmsActivityByIdForUpdate(22L);

        ArgumentCaptor<OmsTicket> inserted = ArgumentCaptor.forClass(OmsTicket.class);
        org.mockito.Mockito.verify(mapper).insertOmsTicket(inserted.capture());
        OmsTicket copied = inserted.getValue();
        assertEquals(Long.valueOf(44L), copied.getSourceTicketId(), "活动壳票必须记录本次复制的来源票键");
        assertEquals(signupDeadline, copied.getSignupDeadline(), "signupDeadline 必须复制");
        assertEquals(source.getGatherLng(), copied.getGatherLng(), "gatherLng 必须复制");
        assertEquals(source.getGatherLat(), copied.getGatherLat(), "gatherLat 必须复制");
        assertEquals(source.getSubType(), copied.getSubType(), "subType 必须复制");
        assertEquals(source.getRefundRule(), copied.getRefundRule(), "refundRule 必须复制");
        assertEquals(source.getNeedAudit(), copied.getNeedAudit(), "needAudit 必须复制");
        assertNull(copied.getMinPeople(), "H11: minPeople 不得复制");
        assertNull(copied.getMaxPeople(), "H11: maxPeople 不得复制");
        assertNull(copied.getTeamStatus(), "H11: teamStatus 不得复制");
    }

    @Test
    void syncingEditedTopicTicketPreservesAlreadySoldSeats() {
        OmsTicketMapper mapper = mock(OmsTicketMapper.class);
        OmsTicketServiceImpl service = new OmsTicketServiceImpl();
        ReflectionTestUtils.setField(service, "memberIdentityService", mock(com.chengyinhub.business.service.impl.MemberIdentityService.class));
        ReflectionTestUtils.setField(service, "omsTicketMapper", mapper);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);

        OmsTicket existing = new OmsTicket();
        existing.setId(99L);
        existing.setSourceTicketId(44L);
        existing.setTotalInventory(30L);
        existing.setRemainingInventory(5L);

        OmsTicket source = new OmsTicket();
        source.setId(44L);
        source.setName("探店日午场（后台改名）");
        source.setTotalInventory(40L);
        when(mapper.selectOmsTicketList(any(OmsTicket.class)))
                .thenReturn(Collections.singletonList(existing))
                .thenReturn(Collections.singletonList(source));
        when(mapper.updateOmsTicket(any(OmsTicket.class))).thenReturn(1);

        assertEquals(1, service.syncTopicTicketsToActivity(11L, 22L, 33L));

        ArgumentCaptor<OmsTicket> updated = ArgumentCaptor.forClass(OmsTicket.class);
        org.mockito.Mockito.verify(mapper).updateOmsTicket(updated.capture());
        assertEquals(Long.valueOf(40L), updated.getValue().getTotalInventory());
        assertEquals(Long.valueOf(15L), updated.getValue().getRemainingInventory(),
                "原票已售 25 张，后台把总量 30 改 40 后余席只能变 15，不能复活成 40");
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).insertOmsTicket(any());
    }

    @Test
    void unkeyedActivityTicketFailsClosedBeforeAnySyncWrite() {
        OmsTicketMapper mapper = mock(OmsTicketMapper.class);
        OmsTicketServiceImpl service = serviceWith(mapper);
        OmsTicket unkeyed = activityTicket(99L, null, 30L, 30L);
        when(mapper.selectOmsTicketList(any(OmsTicket.class)))
                .thenReturn(Collections.singletonList(unkeyed))
                .thenReturn(Collections.singletonList(topicTicket(61L, 30L)));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.syncTopicTicketsToActivity(11L, 22L, 33L));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        assertTrue(error.getMessage().contains("ticketId=99"));
        verify(mapper, never()).insertOmsTicket(any());
        verify(mapper, never()).updateOmsTicket(any());
        verify(mapper, never()).deleteUnsoldActivityOrphanTicket(any(), any(), any());
    }

    @Test
    void unsoldOrphanIsDeletedBeforeReplacementTicketIsInserted() {
        OmsTicketMapper mapper = mock(OmsTicketMapper.class);
        OmsTicketServiceImpl service = serviceWith(mapper);
        OmsTicket orphan = activityTicket(99L, 44L, 30L, 30L);
        when(mapper.selectOmsTicketList(any(OmsTicket.class)))
                .thenReturn(Collections.singletonList(orphan))
                .thenReturn(Collections.singletonList(topicTicket(61L, 30L)));
        when(mapper.deleteUnsoldActivityOrphanTicket(99L, 22L, 44L)).thenReturn(1);
        when(mapper.insertOmsTicket(any(OmsTicket.class))).thenReturn(1);

        service.syncTopicTicketsToActivity(11L, 22L, 33L);

        verify(mapper).deleteUnsoldActivityOrphanTicket(99L, 22L, 44L);
        ArgumentCaptor<OmsTicket> inserted = ArgumentCaptor.forClass(OmsTicket.class);
        verify(mapper).insertOmsTicket(inserted.capture());
        assertEquals(Long.valueOf(61L), inserted.getValue().getSourceTicketId());
    }

    @Test
    void soldOrphanFailsClosedWithoutReplacingOrRevivingInventory() {
        OmsTicketMapper mapper = mock(OmsTicketMapper.class);
        OmsTicketServiceImpl service = serviceWith(mapper);
        OmsTicket sold = activityTicket(99L, 44L, 30L, 5L);
        when(mapper.selectOmsTicketList(any(OmsTicket.class)))
                .thenReturn(Collections.singletonList(sold))
                .thenReturn(Collections.singletonList(topicTicket(61L, 40L)));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.syncTopicTicketsToActivity(11L, 22L, 33L));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        assertTrue(error.getMessage().contains("ticketId=99"));
        assertEquals(Long.valueOf(5L), sold.getRemainingInventory());
        verify(mapper, never()).insertOmsTicket(any());
        verify(mapper, never()).updateOmsTicket(any());
        verify(mapper, never()).deleteUnsoldActivityOrphanTicket(any(), any(), any());
    }

    @Test
    void orphanWithInvalidInventoryFailsClosed() {
        OmsTicketMapper mapper = mock(OmsTicketMapper.class);
        OmsTicketServiceImpl service = serviceWith(mapper);
        OmsTicket invalid = activityTicket(99L, 44L, 30L, 31L);
        when(mapper.selectOmsTicketList(any(OmsTicket.class)))
                .thenReturn(Collections.singletonList(invalid))
                .thenReturn(Collections.singletonList(topicTicket(61L, 40L)));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.syncTopicTicketsToActivity(11L, 22L, 33L));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        assertTrue(error.getMessage().contains("ticketId=99"));
        verify(mapper, never()).insertOmsTicket(any());
        verify(mapper, never()).deleteUnsoldActivityOrphanTicket(any(), any(), any());
    }

    @Test
    void deletingTopicTicketDeletesItsUnsoldActivityShellTicket() {
        OmsTicketMapper mapper = mock(OmsTicketMapper.class);
        OmsTicketServiceImpl service = serviceWith(mapper);
        OmsTicket orphan = activityTicket(99L, 44L, 30L, 30L);
        when(mapper.selectOmsTicketList(any(OmsTicket.class)))
                .thenReturn(Collections.singletonList(orphan))
                .thenReturn(Collections.emptyList());
        when(mapper.deleteUnsoldActivityOrphanTicket(99L, 22L, 44L)).thenReturn(1);

        assertEquals(1, service.syncTopicTicketsToActivity(11L, 22L, 33L));

        verify(mapper).deleteUnsoldActivityOrphanTicket(99L, 22L, 44L);
        verify(mapper, never()).insertOmsTicket(any());
    }

    @Test
    void consecutiveRefreshCallShapeWithChangedTopicTicketIdsKeepsOneShellPerCurrentTicket() {
        OmsTicketMapper mapper = mock(OmsTicketMapper.class);
        OmsTicketServiceImpl service = serviceWith(mapper);
        List<OmsTicket> activityTickets = new ArrayList<>();
        List<OmsTicket> topicTickets = new ArrayList<>(Arrays.asList(
                topicTicket(41L, 30L), topicTicket(42L, 20L)));
        AtomicLong activityTicketId = new AtomicLong(100L);
        when(mapper.selectOmsTicketList(any(OmsTicket.class))).thenAnswer(invocation -> {
            OmsTicket query = invocation.getArgument(0);
            return new ArrayList<>(Long.valueOf(2L).equals(query.getOwnerType())
                    ? activityTickets : topicTickets);
        });
        when(mapper.insertOmsTicket(any(OmsTicket.class))).thenAnswer(invocation -> {
            OmsTicket inserted = invocation.getArgument(0);
            inserted.setId(activityTicketId.incrementAndGet());
            activityTickets.add(inserted);
            return 1;
        });
        when(mapper.deleteUnsoldActivityOrphanTicket(any(), any(), any())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return activityTickets.removeIf(ticket -> id.equals(ticket.getId())) ? 1 : 0;
        });

        service.syncTopicTicketsToActivity(11L, 22L, 33L);
        assertEquals(2, activityTickets.size());
        topicTickets.clear();
        topicTickets.add(topicTicket(61L, 30L));
        topicTickets.add(topicTicket(62L, 20L));

        service.syncTopicTicketsToActivity(11L, 22L, 33L);

        assertEquals(2, activityTickets.size(), "主题票换 id 后活动壳不能累加重复票");
        assertEquals(Arrays.asList(61L, 62L), Arrays.asList(
                activityTickets.get(0).getSourceTicketId(), activityTickets.get(1).getSourceTicketId()));
    }

    private static OmsTicketServiceImpl serviceWith(OmsTicketMapper mapper) {
        OmsTicketServiceImpl service = new OmsTicketServiceImpl();
        ReflectionTestUtils.setField(service, "memberIdentityService", mock(com.chengyinhub.business.service.impl.MemberIdentityService.class));
        ReflectionTestUtils.setField(service, "omsTicketMapper", mapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", mock(CmsActivityMapper.class));
        return service;
    }

    private static OmsTicket topicTicket(Long id, Long totalInventory) {
        OmsTicket ticket = new OmsTicket();
        ticket.setId(id);
        ticket.setTotalInventory(totalInventory);
        return ticket;
    }

    private static OmsTicket activityTicket(Long id, Long sourceTicketId,
                                            Long totalInventory, Long remainingInventory) {
        OmsTicket ticket = new OmsTicket();
        ticket.setId(id);
        ticket.setSourceTicketId(sourceTicketId);
        ticket.setTotalInventory(totalInventory);
        ticket.setRemainingInventory(remainingInventory);
        return ticket;
    }
}
