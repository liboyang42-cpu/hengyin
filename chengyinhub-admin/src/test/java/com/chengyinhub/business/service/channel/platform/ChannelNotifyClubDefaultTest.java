package com.chengyinhub.business.service.channel.platform;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.mapper.channel.platform.ChannelNotifyMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** /api/club/settings/notify-level：成员读、管理员按版本 CAS 写，回执 NOTIFY/CLUB_DEFAULT（含 REJECTED）。 */
class ChannelNotifyClubDefaultTest
{
    static final long OWNER = 11, MEMBER = 22, STRANGER = 33, CLUB = 42;
    static final String REQ = "club-default-request-0001", REQ2 = "club-default-request-0002", REQ3 = "club-default-request-0003";

    ChannelNotifyMapper notify;
    ChannelPlatformMapper platform;
    ChannelNotifyService service;
    Map<String, Object> row;

    @BeforeEach void setUp()
    {
        notify = mock(ChannelNotifyMapper.class);
        platform = mock(ChannelPlatformMapper.class);
        ReceiptFake.install(platform);
        when(platform.selectClubGuild(CLUB)).thenReturn(Map.of("id", CLUB, "ownerId", OWNER, "status", 1, "delFlag", 0));
        when(platform.countClubMember(CLUB, MEMBER)).thenReturn(1);
        when(platform.selectMemberRoles(anyInt(), anyLong(), anyLong())).thenReturn(List.of());
        when(notify.selectClubDefault(CLUB)).thenAnswer(i -> row == null ? null : new HashMap<>(row));
        when(notify.lockClubDefault(CLUB)).thenAnswer(i -> row == null ? null : new HashMap<>(row));
        when(notify.insertClubDefault(eq(CLUB), anyInt(), anyLong())).thenAnswer(i -> {
            if (row != null) return 0;
            row = new HashMap<>(Map.of("clubId", CLUB, "level", i.getArgument(1), "version", 1L, "updatedAt", new java.util.Date()));
            return 1;
        });
        when(notify.updateClubDefault(eq(CLUB), anyInt(), anyLong(), anyLong())).thenAnswer(i -> {
            if (row == null || (long) row.get("version") != (long) i.getArgument(2)) return 0;
            row.put("level", i.getArgument(1));
            row.put("version", (long) row.get("version") + 1);
            row.put("updatedAt", new java.util.Date());
            return 1;
        });
        service = new ChannelNotifyService();
        ReflectionTestUtils.setField(service, "notify", notify);
        ReflectionTestUtils.setField(service, "channels", platform);
        ReflectionTestUtils.setField(service, "access", new ChannelGuildAccess(platform));
    }

    static Map<String, Object> body(String req, Object club, Object level, Object version)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("request_id", req); m.put("clubId", club); m.put("level", level); m.put("version", version);
        return m;
    }

    static String code(Runnable r)
    {
        try { r.run(); return null; }
        catch (ChannelRejection e) { return e.getStatus() + " " + e.getErrorCode(); }
    }

    @Test void unconfiguredClubReadsTheFormalDefault()
    {
        Map<String, Object> v = service.clubDefault(MEMBER, "42");
        assertEquals("42", v.get("club_id"));
        assertEquals(1, v.get("level"));
        assertEquals(0L, v.get("version"));
        assertEquals(false, v.get("configured"));
        assertNull(v.get("updated_at"));
        assertEquals("403 GUILD_FORBIDDEN", code(() -> service.clubDefault(STRANGER, "42")));
    }

    @Test void ownerSavesWithVersionCasAndReplaysTheSameReceipt()
    {
        Map<String, Object> first = service.saveClubDefault(OWNER, body(REQ, "42", 0, 0));
        assertEquals("APPLIED", first.get("status"));
        assertEquals("CLUB_DEFAULT", first.get("operation"));
        assertEquals(2, first.get("scope_type"));
        assertEquals("42", first.get("scope_id"));
        assertEquals(1, ((Number) ((Map<?, ?>) first.get("result")).get("version")).intValue());
        Map<String, Object> read = service.clubDefault(MEMBER, "42");
        assertEquals(0, read.get("level"));
        assertEquals(1L, read.get("version"));
        assertEquals(true, read.get("configured"));
        assertNotNull(read.get("updated_at"));

        Map<String, Object> replay = service.saveClubDefault(OWNER, body(REQ, "42", 0, 0));
        assertEquals(Boolean.TRUE, replay.remove("replayed"));
        assertEquals(first, replay);
        verify(notify, times(1)).insertClubDefault(CLUB, 0, OWNER);
        Map<String, Object> stored = service.notificationReceipt(OWNER, REQ);
        assertEquals(first, stored);

        assertEquals("409 REQUEST_ID_CONFLICT", code(() -> service.saveClubDefault(OWNER, body(REQ, "42", 1, 0))));
    }

    @Test void staleVersionIsAStoredRejection()
    {
        service.saveClubDefault(OWNER, body(REQ, "42", 0, 0));
        Map<String, Object> stale = service.saveClubDefault(OWNER, body(REQ2, "42", 1, 0));
        assertEquals("REJECTED", stale.get("status"));
        assertEquals("REJECTED", stale.get("outcome"));
        assertEquals("CLUB_DEFAULT_VERSION_CONFLICT", ((Map<?, ?>) stale.get("result")).get("rejection_code"));
        assertEquals("REJECTED", service.notificationReceipt(OWNER, REQ2).get("status"));
        assertEquals(0, service.clubDefault(MEMBER, "42").get("level"));

        Map<String, Object> next = service.saveClubDefault(OWNER, body(REQ3, "42", 1, 1));
        assertEquals("APPLIED", next.get("status"));
        assertEquals(2, ((Number) ((Map<?, ?>) next.get("result")).get("version")).intValue());
    }

    @Test void onlyManagersWriteAndTheBodyIsExact()
    {
        assertEquals("403 CLUB_DEFAULT_FORBIDDEN", code(() -> service.saveClubDefault(MEMBER, body(REQ, "42", 0, 0))));
        assertEquals("403 GUILD_FORBIDDEN", code(() -> service.saveClubDefault(STRANGER, body(REQ2, "42", 0, 0))));
        assertEquals("400 INVALID_FIELD", code(() -> service.saveClubDefault(OWNER, body(REQ3, "42", 2, 0))));
        Map<String, Object> extra = body(REQ3, "42", 0, 0);
        extra.put("scope_type", 2);
        assertEquals("400 INVALID_FIELD", code(() -> service.saveClubDefault(OWNER, extra)));
        assertEquals("400 INVALID_FIELD", code(() -> service.saveClubDefault(OWNER, body("short", "42", 0, 0))));
        verify(notify, never()).insertClubDefault(anyLong(), anyInt(), anyLong());
    }
}
