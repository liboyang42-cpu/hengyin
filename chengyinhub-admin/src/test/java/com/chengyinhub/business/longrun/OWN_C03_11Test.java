package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.dto.ActivityTicketRequest;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** NOT_RUN. Ticket identity selection and occupied inventory using actual saveTickets, stub SQL. */
class OWN_C03_11Test {
    private CmsTopicServiceImpl service;
    private OmsTicketMapper mapper;

    @BeforeEach
    void setup() {
        service = new CmsTopicServiceImpl();
        mapper = mock(OmsTicketMapper.class);
        ReflectionTestUtils.setField(service, "omsTicketMapper", mapper);
        when(mapper.selectOmsTicketList(any(OmsTicket.class))).thenReturn(Arrays.asList(stored(11L), stored(12L)));
        when(mapper.updateTopicTicketOnRebuild(any(OmsTicket.class))).thenReturn(1);
    }

    @Test
    void sameBusinessKeyReorderKeepsExplicitTicketIdentityAndConfiguration() {
        save(request(12L, "second"), request(11L, "first"));
        List<OmsTicket> saved = updates();
        assertEquals(Long.valueOf(12), saved.get(0).getId());
        assertEquals("second", saved.get(0).getMeetingPoint());
        assertEquals(Long.valueOf(11), saved.get(1).getId());
        assertEquals("first", saved.get(1).getMeetingPoint());
        assertEquals(Long.valueOf(9), saved.get(0).getRemainingInventory());
        verify(mapper, never()).deleteOmsTicketById(any());
    }

    @Test
    void earlierLegacyEntryCannotStealLaterExplicitId() {
        save(request(null, "legacy"), request(11L, "explicit"));
        List<OmsTicket> saved = updates();
        assertEquals(Long.valueOf(12), saved.get(0).getId());
        assertEquals(Long.valueOf(11), saved.get(1).getId());
    }

    @Test
    void legacyEntriesStillUseBusinessKeyBuckets() {
        save(request(null, "first"), request(null, "second"));
        List<OmsTicket> saved = updates();
        assertEquals(Long.valueOf(11), saved.get(0).getId());
        assertEquals(Long.valueOf(12), saved.get(1).getId());
    }

    @Test
    void removedExplicitIdCreatesNewTicketInsteadOfTakingAnotherId() {
        save(request(999L, "new"));
        ArgumentCaptor<OmsTicket> inserted = ArgumentCaptor.forClass(OmsTicket.class);
        verify(mapper).insertOmsTicket(inserted.capture());
        assertNull(inserted.getValue().getId(), "Old/deleted ID must not be reused as the new identity");
        verify(mapper, never()).updateTopicTicketOnRebuild(any());
        verify(mapper).deleteOmsTicketById(11L);
        verify(mapper).deleteOmsTicketById(12L);
    }

    @Test
    void duplicateExistingIdIsRejectedBeforeAnyTicketWrite() {
        assertThrows(IllegalArgumentException.class, () -> save(request(11L, "first"), request(11L, "second")));
        verify(mapper, never()).updateTopicTicketOnRebuild(any());
        verify(mapper, never()).insertOmsTicket(any());
        verify(mapper, never()).deleteOmsTicketById(any());
    }

    @Test
    void explicitIdentitySurvivesChangeToBusinessKey() {
        ActivityTicketRequest changed = request(11L, "updated");
        changed.setName("new name");
        save(changed);
        ArgumentCaptor<OmsTicket> saved = ArgumentCaptor.forClass(OmsTicket.class);
        verify(mapper).updateTopicTicketOnRebuild(saved.capture());
        assertEquals(Long.valueOf(11), saved.getValue().getId());
        assertEquals("new name", saved.getValue().getName());
        verify(mapper, never()).insertOmsTicket(any());
    }

    private OmsTicket stored(Long id) {
        OmsTicket ticket = new OmsTicket();
        ticket.setId(id);
        ticket.setName("same");
        ticket.setPrice(new BigDecimal("1.00"));
        ticket.setTotalInventory(10L);
        ticket.setRemainingInventory(7L);
        return ticket;
    }

    private ActivityTicketRequest request(Long id, String meetingPoint) {
        ActivityTicketRequest ticket = new ActivityTicketRequest();
        ticket.setId(id);
        ticket.setName("same");
        ticket.setPrice(new BigDecimal("1.00"));
        ticket.setTotalStock(12);
        ticket.setMeetingPoint(meetingPoint);
        return ticket;
    }

    private void save(ActivityTicketRequest... tickets) {
        ReflectionTestUtils.invokeMethod(service, "saveTickets", Arrays.asList(tickets), 700L, 42L,
                "creator", ProductType.ORIENTEERING, true);
    }

    private List<OmsTicket> updates() {
        ArgumentCaptor<OmsTicket> saved = ArgumentCaptor.forClass(OmsTicket.class);
        verify(mapper, times(2)).updateTopicTicketOnRebuild(saved.capture());
        return saved.getAllValues();
    }
}
