package com.chengyinhub.business.service.friend;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.chengyinhub.business.mapper.friend.FriendMapper;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.business.service.channel.platform.ReceiptFake;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FriendServiceTest
{
    static final long ME = 1, PEER = 2;
    FriendMapper mapper;
    FriendService service;
    Map<String, Object> pair;

    @BeforeEach void setUp()
    {
        mapper = mock(FriendMapper.class);
        ChannelPlatformMapper receipts = mock(ChannelPlatformMapper.class);
        ReceiptFake.install(receipts);
        service = new FriendService();
        ReflectionTestUtils.setField(service, "friends", mapper);
        ReflectionTestUtils.setField(service, "receipts", receipts);
        when(mapper.nickname(anyLong())).thenReturn("nick");
        when(mapper.lockPair(1, 2)).thenAnswer(i -> pair);
        when(mapper.selectPair(1, 2)).thenAnswer(i -> pair);
        when(mapper.transition(anyLong(), anyLong(), anyInt(), anyLong(), any(), anyInt(), any())).thenAnswer(i -> {
            pair.put("status", i.getArgument(2));
            pair.put("requesterId", i.getArgument(3));
            pair.put("version", ((Number) i.getArgument(1)).longValue() + 1);
            return 1;
        });
    }

    static Map<String, Object> pair(int status, long requester, long version)
    {
        return new HashMap<>(Map.of("id", 9L, "status", status, "requesterId", requester, "version", version, "expired", 0));
    }

    @Test void stateProjectionIsViewerRelative()
    {
        assertEquals(0, FriendService.state(ME, null));
        assertEquals(2, FriendService.state(ME, pair(0, ME, 1)));
        assertEquals(3, FriendService.state(PEER, pair(0, ME, 1)));
        assertEquals(1, FriendService.state(PEER, pair(1, ME, 2)));
        Map<String, Object> expired = pair(0, ME, 1);
        expired.put("expired", 1);
        assertEquals(0, FriendService.state(ME, expired), "an expired request is no longer pending");
    }

    @Test void requestValidationRejectsBeforeAnyWrite()
    {
        assertEquals("INVALID_FIELD", code(() -> service.write(ME, "request", Map.of("peer_id", 2, "request_id", UUID.randomUUID().toString(), "msg", " "))));
        assertEquals("INVALID_FIELD", code(() -> service.write(ME, "request", Map.of("peer_id", 1, "request_id", UUID.randomUUID().toString(), "msg", "hi"))));
        assertEquals("INVALID_FIELD", code(() -> service.write(ME, "accept", Map.of("peer_id", 2, "request_id", UUID.randomUUID().toString(), "msg", ""))));
        assertEquals("INVALID_FIELD", code(() -> service.write(ME, "request", Map.of("peer_id", 2, "request_id", "not-a-uuid", "msg", "hi"))));
        verify(mapper, never()).insertPending(anyLong(), anyLong(), anyLong(), any(), anyInt(), any());
    }

    @Test void blockedPeerIsUnavailableWithoutDisclosure()
    {
        when(mapper.countBlock(ME, PEER)).thenReturn(1);
        assertEquals("FRIEND_UNAVAILABLE", code(() -> service.write(ME, "request",
                Map.of("peer_id", 2, "request_id", UUID.randomUUID().toString(), "msg", "hi"))));
        pair = pair(1, ME, 3);
        assertEquals(0, service.relationship(ME, PEER).get("state"), "a blocked friendship projects as none");
    }

    @Test void acceptChecksVersionAndOpensDirectConversation()
    {
        pair = pair(0, PEER, 4);
        assertEquals("FRIEND_VERSION_CONFLICT", code(() -> service.write(ME, "accept",
                Map.of("peer_id", 2, "request_id", UUID.randomUUID().toString(), "msg", "", "expected_version", 3))));
        when(mapper.selectSingleConversation("1_2")).thenReturn(77L);
        Map<String, Object> receipt = service.write(ME, "accept", Map.of("peer_id", 2, "request_id", UUID.randomUUID().toString(), "msg", "",
                "expected_version", 4));
        Map<?, ?> rel = (Map<?, ?>) receipt.get("relationship");
        assertEquals(1, rel.get("state"));
        assertEquals(77, ((Number) rel.get("conversation_id")).intValue());
        verify(mapper).insertSingleConversation("1_2");
        verify(mapper).ensureConversationMember(77L, ME);
        verify(mapper).ensureConversationMember(77L, PEER);
        assertEquals(FriendProofs.command(2, "accept", "", 4L, null, 0, 0, null), receipt.get("payload_hash"));
    }

    @Test void removalRefusalIsAReceiptNotAnException()
    {
        pair = pair(1, PEER, 5);
        Map<String, Object> refused = service.remove(ME, Map.of("peer_id", 2, "expected_version", 4, "request_id", UUID.randomUUID().toString()));
        assertEquals("REJECTED", refused.get("outcome"));
        assertEquals("VERSION_CONFLICT", refused.get("reason"));
        assertEquals(409, refused.get(FriendService.STATUS_KEY));
        Map<String, Object> applied = service.remove(ME, Map.of("peer_id", 2, "expected_version", 5, "request_id", UUID.randomUUID().toString()));
        assertEquals("APPLIED", applied.get("outcome"));
        assertEquals(6, ((Number) applied.get("removed_version")).intValue());
        verify(mapper).clearPairMeta(ME, PEER);
    }

    @Test void directoryRejectsForeignGroupsAndCapsCounts()
    {
        when(mapper.listGroups(ME)).thenReturn(List.of());
        assertEquals("GROUP_NOT_FOUND", code(() -> service.directory(ME, "all", false, 99L, null, 50)));
        assertEquals("INVALID_FIELD", code(() -> service.directory(ME, "nearby", false, null, null, 50)));
        when(mapper.countDirectory(eq(ME), anyString(), any(), anyBoolean())).thenReturn(900);
        when(mapper.listDirectory(eq(ME), anyString(), any(), anyBoolean(), anyLong(), anyInt())).thenReturn(List.of());
        Map<String, Object> page = service.directory(ME, "all", false, null, null, 50);
        assertEquals(500, page.get("total"));
        assertTrue(((String) page.get("revision")).matches("[0-9a-f]{64}"));
    }

    static String code(Runnable r)
    {
        return assertThrows(ChannelRejection.class, r::run).getErrorCode();
    }
}
