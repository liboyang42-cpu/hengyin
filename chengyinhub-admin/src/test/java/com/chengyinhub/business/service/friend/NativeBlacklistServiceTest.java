package com.chengyinhub.business.service.friend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.chengyinhub.business.mapper.friend.NativeBlacklistMapper;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.business.service.channel.platform.ReceiptFake;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NativeBlacklistServiceTest
{
    static final long ME = 1, PEER = 9;
    NativeBlacklistMapper mapper;
    NativeBlacklistService service;
    Long blockId;

    @BeforeEach void setUp()
    {
        mapper = mock(NativeBlacklistMapper.class);
        ChannelPlatformMapper receipts = mock(ChannelPlatformMapper.class);
        ReceiptFake.install(receipts);
        service = new NativeBlacklistService();
        ReflectionTestUtils.setField(service, "blocks", mapper);
        ReflectionTestUtils.setField(service, "receipts", receipts);
        when(mapper.lockBlockId(ME, PEER)).thenAnswer(i -> blockId);
        when(mapper.selectBlockId(ME, PEER)).thenAnswer(i -> blockId);
        when(mapper.deleteBlock(anyLong(), eq(ME), eq(PEER))).thenAnswer(i -> {
            if (blockId == null || blockId != (long) i.getArgument(0)) return 0;
            blockId = null;
            return 1;
        });
    }

    static Map<String, Object> body(long peer, long expected, String requestId)
    {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("peer_id", peer);
        b.put("expected_block_id", expected);
        b.put("request_id", requestId);
        return b;
    }

    @Test void fingerprintMatchesTheAppVector()
    {
        // Swift NativeBlacklistCommand.fingerprint = SHA-256("native-blacklist-v1\n9\n30").
        assertEquals("be7d470a362f16f91917725e90fc0a617d9545c2b9f36069a32a5bbf0103d7da", NativeBlacklistService.proof(9, 30));
    }

    @Test void pageIsDescendingWithExactCursorRule()
    {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (long id = 200; id > 149; id--) rows.add(Map.of("block_id", id, "peer_id", id + 1000, "nickname", "n" + id));
        when(mapper.selectPage(ME, 0, 51)).thenReturn(rows);
        Map<String, Object> page = service.page(ME, 0);
        List<?> items = (List<?>) page.get("items");
        assertEquals(50, items.size());
        assertEquals(true, page.get("has_more"));
        assertEquals(151L, page.get("next_cursor"), "next cursor is the last returned block id");
        assertEquals(true, page.get("write_enabled"));
        assertEquals(ME, page.get("viewer_id"));

        when(mapper.selectPage(ME, 151, 51)).thenReturn(List.of(Map.of("block_id", 150L, "peer_id", 1150L, "nickname", "last")));
        Map<String, Object> tail = service.page(ME, 151);
        assertEquals(false, tail.get("has_more"));
        assertNull(tail.get("next_cursor"));
        assertEquals("INVALID_FIELD", code(() -> service.page(ME, -1)));
    }

    @Test void currentReportsTheLiveRowIncarnation()
    {
        blockId = 30L;
        Map<String, Object> on = service.current(ME, PEER);
        assertEquals(true, on.get("blocked"));
        assertEquals(30L, on.get("block_id"));
        blockId = null;
        Map<String, Object> off = service.current(ME, PEER);
        assertEquals(false, off.get("blocked"));
        assertTrue(off.containsKey("block_id"));
        assertNull(off.get("block_id"));
        assertEquals("INVALID_FIELD", code(() -> service.current(ME, ME)));
    }

    @Test void unblockAppliesOnceAndReplays()
    {
        blockId = 30L;
        String id = UUID.randomUUID().toString();
        Map<String, Object> first = service.unblock(ME, body(PEER, 30, id));
        assertEquals("APPLIED", first.get("outcome"));
        assertEquals("UNBLOCKED", first.get("reason"));
        assertEquals("UNBLOCK", first.get("action"));
        assertEquals(NativeBlacklistService.proof(PEER, 30), first.get("payload_hash"));
        assertNull(blockId);
        Map<String, Object> again = service.unblock(ME, body(PEER, 30, id));
        assertEquals(first, again, "same request id replays the stored receipt");
        assertFalse(again.containsKey("replayed"));
        verify(mapper, times(1)).deleteBlock(30, ME, PEER);
        assertEquals("REQUEST_ID_CONFLICT", code(() -> service.unblock(ME, body(PEER, 31, id))));
    }

    @Test void unblockRejectsAChangedOrAbsentRowWithoutDeleting()
    {
        blockId = 31L; // re-blocked after the list was read
        Map<String, Object> changed = service.unblock(ME, body(PEER, 30, UUID.randomUUID().toString()));
        assertEquals("REJECTED", changed.get("outcome"));
        assertEquals("BLOCK_CHANGED", changed.get("reason"));
        assertEquals(31L, blockId);
        blockId = null;
        Map<String, Object> absent = service.unblock(ME, body(PEER, 30, UUID.randomUUID().toString()));
        assertEquals("BLOCK_ABSENT", absent.get("reason"));
        verify(mapper, never()).deleteBlock(anyLong(), anyLong(), anyLong());
    }

    @Test void receiptIsReadOnlyAndUnknownUntilSettled()
    {
        blockId = 30L;
        String id = UUID.randomUUID().toString();
        Map<String, Object> unknown = service.receipt(ME, PEER, 30, id);
        assertEquals("UNKNOWN", unknown.get("outcome"));
        assertFalse(unknown.containsKey("reason"));

        Map<String, Object> settled = service.settle(ME, body(PEER, 30, id));
        assertEquals("REJECTED", settled.get("outcome"));
        assertEquals("FINALIZED_WITHOUT_CHANGE", settled.get("reason"));
        assertEquals(settled, service.receipt(ME, PEER, 30, id));
        // The settled original can never apply later.
        assertEquals("FINALIZED_WITHOUT_CHANGE", service.unblock(ME, body(PEER, 30, id)).get("reason"));
        assertEquals(30L, blockId);
        assertEquals("REQUEST_ID_CONFLICT", code(() -> service.receipt(ME, PEER, 31, id)));
    }

    @Test void settleAfterApplyReturnsTheOriginal()
    {
        blockId = 30L;
        String id = UUID.randomUUID().toString();
        Map<String, Object> applied = service.unblock(ME, body(PEER, 30, id));
        assertEquals(applied, service.settle(ME, body(PEER, 30, id)));
    }

    @Test void commandShapeIsStrict()
    {
        String id = UUID.randomUUID().toString();
        Map<String, Object> extra = body(PEER, 30, id);
        extra.put("x", 1);
        assertEquals("INVALID_FIELD", code(() -> service.unblock(ME, extra)));
        assertEquals("INVALID_FIELD", code(() -> service.unblock(ME, body(ME, 30, id))));
        assertEquals("INVALID_FIELD", code(() -> service.unblock(ME, body(PEER, 0, id))));
        assertEquals("INVALID_FIELD", code(() -> service.unblock(ME, body(PEER, 30, "nope"))));
        Map<String, Object> fractional = body(PEER, 30, id);
        fractional.put("expected_block_id", 30.0);
        assertEquals("INVALID_FIELD", code(() -> service.unblock(ME, fractional)));
        assertEquals("INVALID_FIELD", code(() -> service.unblock(ME, null)));
        verify(mapper, never()).lockBlockId(anyLong(), anyLong());
    }

    static String code(Runnable r)
    {
        try { r.run(); return null; }
        catch (ChannelRejection e) { return e.getErrorCode(); }
    }
}
