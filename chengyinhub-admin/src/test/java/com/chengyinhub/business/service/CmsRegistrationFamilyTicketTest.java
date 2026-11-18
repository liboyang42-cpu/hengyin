package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.domain.dto.RegistrationTicketItem;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.business.service.support.FamilyTicketRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** F12 亲子票下单:只登记儿童年龄;超出年龄段拒单;party_snapshot_json 正确写入;数量仍限 1。 */
class CmsRegistrationFamilyTicketTest {
    private static final Long FAMILY = 501L;
    private static final Long PLAIN = 502L;

    private OmsTicketMapper ticketMapper;
    private CmsRegistrationMapper registrationMapper;
    private CmsRegistrationServiceImpl service;

    @BeforeEach
    void setUp() {
        ticketMapper = mock(OmsTicketMapper.class);
        registrationMapper = mock(CmsRegistrationMapper.class);
        service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrationMapper);
        when(ticketMapper.selectOmsTicketById(FAMILY)).thenReturn(ticket(FAMILY, 1));
        when(ticketMapper.selectOmsTicketById(PLAIN)).thenReturn(ticket(PLAIN, 0));
    }

    @Test
    void ageOutsideTheBandIsRejectedBeforeAnyOrderIsCreated() {
        RuntimeException tooOld = assertThrows(RuntimeException.class, () -> resolve(single(FAMILY, 13)));
        assertEquals("儿童年龄需在 3–12 岁", tooOld.getMessage());
        assertThrows(RuntimeException.class, () -> resolve(single(FAMILY, 2)));
        RuntimeException missing = assertThrows(RuntimeException.class, () -> resolve(single(FAMILY, null)));
        assertEquals("请选择儿童年龄", missing.getMessage());
    }

    @Test
    void nonFamilyTicketsRejectAChildAgeAndCarryNoParty() {
        assertNull(resolve(single(PLAIN, null)));
        assertThrows(RuntimeException.class, () -> resolve(single(PLAIN, 7)));
        RegistrationRequest pass = new RegistrationRequest();
        pass.setOwnerType(1);
        pass.setOwnerId(9L);
        pass.setChildAge(7);
        assertThrows(RuntimeException.class, () -> resolve(pass));
    }

    @Test
    void partySnapshotIsWrittenToTheFamilyTicketRow() {
        Object party = resolve(single(FAMILY, 7));
        CmsRegistration created = new CmsRegistration();
        created.setId(9001L);
        created.setTicketId(FAMILY);
        when(registrationMapper.bindPartySnapshot(eq(9001L), anyString())).thenReturn(1);
        ReflectionTestUtils.invokeMethod(service, "bindFamilyParty", Collections.singletonList(created), party);
        verify(registrationMapper).bindPartySnapshot(9001L, "{\"adultCount\":1,\"childCount\":1,\"childAge\":7}");
        Map<String, Object> read = created.getParty();
        assertEquals(1, read.get("adultCount"));
        assertEquals(1, read.get("childCount"));
        assertEquals(7, read.get("childAge"));
    }

    @Test
    void idempotentReplayWithADifferentAgeIsAConflict() {
        Object party = resolve(single(FAMILY, 7));
        CmsRegistration created = new CmsRegistration();
        created.setId(9002L);
        created.setTicketId(FAMILY);
        when(registrationMapper.bindPartySnapshot(eq(9002L), anyString())).thenReturn(0);
        assertThrows(RegistrationRequestConflictException.class, () ->
                ReflectionTestUtils.invokeMethod(service, "bindFamilyParty", Collections.singletonList(created), party));
    }

    @Test
    void quantityStaysOneAndFamilyTicketsAreOrderedAlone() {
        RegistrationRequest twoFamily = multi(item(FAMILY, 2));
        twoFamily.setChildAge(7);
        assertEquals("亲子票限 1 大 1 小，每单限 1 张",
                assertThrows(RuntimeException.class, () -> resolve(twoFamily)).getMessage());
        RegistrationRequest mixed = multi(item(FAMILY, 1), item(PLAIN, 1));
        mixed.setChildAge(7);
        assertEquals("亲子票请单独下单", assertThrows(RuntimeException.class, () -> resolve(mixed)).getMessage());
        RegistrationRequest alone = multi(item(FAMILY, 1));
        alone.setChildAge(7);
        Object party = resolve(alone);
        assertEquals(FAMILY, ReflectionTestUtils.getField(party, "ticketId"));
    }

    @Test
    void plainOrdersNeverTouchThePartyColumn() {
        CmsRegistration created = new CmsRegistration();
        created.setId(9003L);
        created.setTicketId(PLAIN);
        ReflectionTestUtils.invokeMethod(service, "bindFamilyParty", Collections.singletonList(created), (Object) null);
        verify(registrationMapper, never()).bindPartySnapshot(anyLong(), anyString());
        assertNull(created.getParty());
        assertNull(FamilyTicketRules.partyOf("{\"adultCount\":1,\"childCount\":0}"));
        assertNull(FamilyTicketRules.partyOf("not json"));
    }

    private Object resolve(RegistrationRequest request) {
        return ReflectionTestUtils.invokeMethod(service, "resolveFamilyOrderParty", request);
    }

    private static RegistrationRequest single(Long ticketId, Integer childAge) {
        RegistrationRequest request = new RegistrationRequest();
        request.setOwnerType(2);
        request.setOwnerId(77L);
        request.setTicketId(ticketId);
        request.setChildAge(childAge);
        return request;
    }

    private static RegistrationRequest multi(RegistrationTicketItem... items) {
        RegistrationRequest request = new RegistrationRequest();
        request.setOwnerType(2);
        request.setOwnerId(77L);
        request.setTickets(Arrays.asList(items));
        return request;
    }

    private static RegistrationTicketItem item(Long ticketId, int count) {
        RegistrationTicketItem item = new RegistrationTicketItem();
        item.setTicketId(ticketId);
        item.setCount(count);
        return item;
    }

    private static OmsTicket ticket(Long id, int childCount) {
        OmsTicket ticket = new OmsTicket();
        ticket.setId(id);
        ticket.setAdultCount(1);
        ticket.setChildCount(childCount);
        if (childCount > 0) {
            ticket.setChildAgeMin(3);
            ticket.setChildAgeMax(12);
        }
        return ticket;
    }
}
