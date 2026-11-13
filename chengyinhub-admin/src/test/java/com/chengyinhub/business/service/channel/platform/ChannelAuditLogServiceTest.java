package com.chengyinhub.business.service.channel.platform;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.mapper.channel.platform.ChannelAuditMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** GET /api/im/audit-log：VIEW_AUDIT 闸、原始行按时间投影成哈希链、分页游标、verify 证明与断链冻结。 */
class ChannelAuditLogServiceTest
{
    static final long VIEWER = 7, GUILD = 10;
    ChannelAuditMapper mapper;
    ChannelGuildAccess access;
    ChannelAuditLogService service;
    final List<Map<String, Object>> stored = new ArrayList<>();
    final Map<String, Object> head = new HashMap<>();
    final List<Map<String, Object>> ops = new ArrayList<>();
    final List<Map<String, Object>> moderations = new ArrayList<>();
    final List<Map<String, Object>> revocations = new ArrayList<>();

    @BeforeEach void setUp()
    {
        mapper = mock(ChannelAuditMapper.class);
        access = mock(ChannelGuildAccess.class);
        service = new ChannelAuditLogService();
        ReflectionTestUtils.setField(service, "audit", mapper);
        ReflectionTestUtils.setField(service, "access", access);
        when(access.guildBits(1, GUILD, VIEWER)).thenReturn(ChannelCapability.of(ChannelCapability.VIEW_CHANNEL, ChannelCapability.VIEW_AUDIT));
        head.put("headId", 0L);
        head.put("headHash", ChannelAuditLogService.GENESIS);
        when(mapper.lockHead(1, GUILD)).thenReturn(head);
        when(mapper.updateHead(eq(1), eq(GUILD), anyLong(), anyString())).thenAnswer(i -> {
            head.put("headId", i.getArgument(2));
            head.put("headHash", i.getArgument(3));
            return 1;
        });
        when(mapper.pendingChannelOps(eq(1), eq(GUILD), anyInt())).thenAnswer(i -> drain(ops));
        when(mapper.pendingModerations(eq(1), eq(GUILD), anyInt())).thenAnswer(i -> drain(moderations));
        when(mapper.pendingRevocations(eq(1), eq(GUILD), anyInt())).thenAnswer(i -> drain(revocations));
        when(mapper.insertRow(anyMap())).thenAnswer(i -> {
            Map<String, Object> row = i.getArgument(0);
            row.put("id", (long) stored.size() + 1);
            stored.add(new LinkedHashMap<>(row));
            return 1;
        });
        when(mapper.listRows(eq(1), eq(GUILD), any(), anyInt())).thenAnswer(i -> {
            Long before = i.getArgument(2);
            int limit = i.getArgument(3);
            List<Map<String, Object>> out = new ArrayList<>();
            for (int k = stored.size() - 1; k >= 0 && out.size() < limit; k--)
                if (before == null || (long) stored.get(k).get("id") < before) out.add(stored.get(k));
            return out;
        });
        when(mapper.chainRows(eq(1), eq(GUILD), anyLong(), anyInt())).thenAnswer(i -> {
            long after = i.getArgument(2);
            return stored.stream().filter(r -> (long) r.get("id") > after).toList();
        });
    }

    static List<Map<String, Object>> drain(List<Map<String, Object>> source)
    {
        List<Map<String, Object>> out = new ArrayList<>(source);
        source.clear();
        return out;
    }

    static Map<String, Object> op(long id, long channel, String action, long at)
    {
        Map<String, Object> o = new HashMap<>();
        o.put("id", id);
        o.put("channelId", channel);
        o.put("action", action);
        o.put("fromStatus", 0);
        o.put("toStatus", 1);
        o.put("reason", "整理频道");
        o.put("operatorId", VIEWER);
        o.put("originalTime", new Timestamp(at));
        o.put("originalTimeText", String.format("2026-11-13 10:00:%02d.%03d000", at / 1000, at % 1000));
        return o;
    }

    static Map<String, Object> moderation(long id, long channel, long at)
    {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("channelId", channel);
        m.put("targetMemberId", 99L);
        m.put("actionType", "TIMEOUT");
        m.put("scopeType", "CHANNEL");
        m.put("scopeId", channel);
        m.put("reasonCode", "SPAM");
        m.put("durationSec", 600);
        m.put("deleteMessageSec", 0);
        m.put("operatorId", VIEWER);
        m.put("revokedBy", 8L);
        m.put("originalTime", new Timestamp(at));
        m.put("originalTimeText", String.format("2026-11-13 10:00:%02d.%03d000", at / 1000, at % 1000));
        return m;
    }

    @Test void viewAuditIsRequired()
    {
        when(access.guildBits(1, GUILD, 3L)).thenReturn(ChannelCapability.of(ChannelCapability.VIEW_CHANNEL));
        ChannelRejection e = assertThrows(ChannelRejection.class, () -> service.read(3, 1, GUILD, null, 50, false));
        assertEquals("CHANNEL_FORBIDDEN", e.getErrorCode());
        when(access.guildBits(1, GUILD, 4L)).thenReturn(0L);
        assertEquals("GUILD_FORBIDDEN", assertThrows(ChannelRejection.class, () -> service.read(4, 1, GUILD, null, 50, false)).getErrorCode());
        assertEquals("INVALID_FIELD", assertThrows(ChannelRejection.class, () -> service.read(VIEWER, 1, GUILD, null, 51, false)).getErrorCode());
        verify(mapper, never()).insertRow(anyMap());
    }

    @Test void originalsAreProjectedInTimeOrderAndPagedNewestFirst()
    {
        ops.add(op(5, 100, "ARCHIVE", 3000));
        moderations.add(moderation(2, 100, 1000));
        revocations.add(moderation(2, 100, 2000));
        Map<String, Object> page = service.read(VIEWER, 1, GUILD, null, 2, false);
        assertEquals(1, page.get("guild_kind"));
        assertEquals("10", page.get("guild_id"));
        List<?> list = (List<?>) page.get("list");
        assertEquals(2, list.size());
        Map<?, ?> newest = (Map<?, ?>) list.get(0);
        assertEquals("3", newest.get("id"));
        assertEquals("CHANNEL_ARCHIVE", newest.get("action"));
        assertEquals("CHANNEL", newest.get("target_type"));
        assertEquals(Boolean.TRUE, newest.get("changes_available"));
        assertEquals(Boolean.FALSE, newest.get("changes_redacted"));
        Map<?, ?> revoke = (Map<?, ?>) list.get(1);
        assertEquals("MODERATION_REVOKE", revoke.get("action"));
        assertEquals("8", revoke.get("actor_member_id"));
        assertEquals(Boolean.TRUE, revoke.get("changes_redacted"));
        assertNull(revoke.get("reason"), "revoke reason text never enters the audit");
        assertEquals(Boolean.TRUE, page.get("has_more"));
        assertEquals("2", page.get("next_cursor"));
        Map<String, Object> rest = service.read(VIEWER, 1, GUILD, "2", 2, false);
        Map<?, ?> first = (Map<?, ?>) ((List<?>) rest.get("list")).get(0);
        assertEquals("MODERATION_TIMEOUT", first.get("action"));
        assertEquals("SPAM", first.get("reason"));
        assertEquals(Boolean.FALSE, rest.get("has_more"));
        assertNull(rest.get("next_cursor"));
        assertEquals(3, stored.size(), "each original is projected once");
        assertEquals(ChannelAuditLogService.GENESIS, stored.get(0).get("prevHash"));
        assertEquals(stored.get(0).get("rowHash"), stored.get(1).get("prevHash"));
        assertEquals(stored.get(2).get("rowHash"), head.get("headHash"));
    }

    @Test void verifyReportsAnUnanchoredLinkedChain()
    {
        ops.add(op(1, 100, "CREATE", 1000));
        ops.add(op(2, 100, "EDIT", 2000));
        Map<String, Object> page = service.read(VIEWER, 1, GUILD, null, 50, true);
        Map<?, ?> proof = (Map<?, ?>) page.get("verify");
        assertEquals(Boolean.TRUE, proof.get("chain_ok"));
        assertEquals(Boolean.TRUE, proof.get("linked_projection_ok"));
        assertEquals(0L, proof.get("unproven_original_rows"));
        assertEquals(2L, proof.get("checked_rows"));
        assertEquals("2", proof.get("through_original_id"));
        assertEquals("2", proof.get("verified_through_original_id"));
        assertEquals(stored.get(1).get("rowHash"), proof.get("head_hash"));
        assertEquals(Boolean.FALSE, proof.get("frozen"));
        assertNull(proof.get("anchor_evidence"));
        assertEquals(Boolean.FALSE, proof.get("externally_anchored"));
        assertEquals(ChannelAuditLogService.BOUNDARY, proof.get("boundary"));
    }

    @Test void unprojectedOriginalsKeepTheChainUnproven()
    {
        ops.add(op(1, 100, "CREATE", 1000));
        when(mapper.countUnprojected(1, GUILD)).thenReturn(4L);
        Map<?, ?> proof = (Map<?, ?>) service.read(VIEWER, 1, GUILD, null, 50, true).get("verify");
        assertEquals(Boolean.FALSE, proof.get("chain_ok"));
        assertEquals(Boolean.TRUE, proof.get("linked_projection_ok"));
        assertEquals(4L, proof.get("unproven_original_rows"));
    }

    @Test void aTamperedRowFreezesTheReadAndListsNothing()
    {
        ops.add(op(1, 100, "CREATE", 1000));
        ops.add(op(2, 100, "EDIT", 2000));
        service.read(VIEWER, 1, GUILD, null, 50, false);
        stored.get(0).put("reason", "改过的理由");
        Map<String, Object> page = service.read(VIEWER, 1, GUILD, null, 50, true);
        Map<?, ?> proof = (Map<?, ?>) page.get("verify");
        assertEquals(Boolean.FALSE, proof.get("linked_projection_ok"));
        assertEquals(Boolean.FALSE, proof.get("chain_ok"));
        assertEquals(Boolean.TRUE, proof.get("frozen"));
        assertEquals(2L, proof.get("unproven_original_rows"));
        assertEquals("0", proof.get("verified_through_original_id"));
        assertEquals("2", proof.get("through_original_id"));
        assertEquals(List.of(), page.get("list"));
        assertEquals(Boolean.FALSE, page.get("has_more"));
        // Without verify the list is still served (the client only freezes on a reported break).
        assertEquals(2, ((List<?>) service.read(VIEWER, 1, GUILD, null, 50, false).get("list")).size());
    }
}
