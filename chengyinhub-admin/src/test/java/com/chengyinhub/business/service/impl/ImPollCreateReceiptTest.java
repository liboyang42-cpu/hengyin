package com.chengyinhub.business.service.impl;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.domain.ImPoll;
import com.chengyinhub.business.mapper.ImPollMapper;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.common.exception.ServiceException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImPollCreateReceiptTest
{
    @Test void receiptIsReadOnlyAndCarriesTheOriginalPollAndHash()
    {
        ImPollMapper mapper = mock(ImPollMapper.class);
        IImService im = mock(IImService.class);
        ImPollServiceImpl service = new ImPollServiceImpl();
        ReflectionTestUtils.setField(service, "pollMapper", mapper);
        ReflectionTestUtils.setField(service, "imService", im);
        String key = "0f8fad5b-d9cb-469f-a165-70867728950e";

        Map<String, Object> missing = service.createReceipt(7L, 70L, key);
        assertEquals("NOT_FOUND", missing.get("state"));
        assertEquals("original-poll-create-v1", missing.get("source"));
        assertFalse(missing.containsKey("poll"));

        ImPoll poll = new ImPoll();
        poll.setId(9L);
        poll.setConversationId(70L);
        poll.setCreatorMemberId(7L);
        poll.setClientPollKey(key);
        poll.setRequestHash("a".repeat(64));
        poll.setStatus(ImPoll.STATUS_OPEN);
        when(mapper.selectByBusinessKey(70L, 7L, key)).thenReturn(poll);
        assertEquals("UNKNOWN", service.createReceipt(7L, 70L, key).get("state"));

        poll.setMessageId(900L);
        when(mapper.selectResults(9L)).thenReturn(List.of());
        Map<String, Object> applied = service.createReceipt(7L, 70L, key);
        assertEquals("APPLIED", applied.get("state"));
        assertEquals("9", applied.get("poll_id"));
        assertEquals("900", applied.get("message_id"));
        assertEquals("a".repeat(64), applied.get("request_hash"));
        assertSame(poll, applied.get("poll"));
        verify(mapper, never()).insertPoll(any());

        doThrow(new ServiceException("无权", 403)).when(im).requireActiveGroupMembership(8L, 70L);
        assertThrows(ServiceException.class, () -> service.createReceipt(8L, 70L, key));
        assertThrows(ServiceException.class, () -> service.createReceipt(7L, 70L, " "));
    }
}
