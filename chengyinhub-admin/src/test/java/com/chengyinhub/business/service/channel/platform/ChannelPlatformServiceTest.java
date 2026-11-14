package com.chengyinhub.business.service.channel.platform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.chengyinhub.business.service.channel.platform.ChannelPermissionCalculator.GuildStanding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChannelPlatformServiceTest
{
    static final long OWNER = 11, MEMBER = 22, STRANGER = 33, CLUB = 42, CH = 1001;

    ChannelPlatformMapper mapper;
    ChannelGuildAccess access;
    ChannelPlatformService service;
    ChannelRow channel;

    @BeforeEach void setUp()
    {
        mapper = mock(ChannelPlatformMapper.class);
        ReceiptFake.install(mapper);
        when(mapper.selectClubGuild(CLUB)).thenReturn(Map.of("id", CLUB, "ownerId", OWNER, "status", 1, "delFlag", 0));
        when(mapper.countClubMember(CLUB, MEMBER)).thenReturn(1);
        when(mapper.selectMemberRoles(anyInt(), anyLong(), anyLong())).thenReturn(List.of());
        when(mapper.selectOverwrites(anyLong())).thenReturn(List.of());
        access = new ChannelGuildAccess(mapper);
        service = new ChannelPlatformService(mapper, access);
        channel = row(CH, 0, 1, "general");
        when(mapper.selectChannel(CH)).thenAnswer(i -> channel);
        when(mapper.lockChannel(CH)).thenAnswer(i -> channel);
    }

    static ChannelRow row(long id, long parent, int kind, String name)
    {
        ChannelRow r = new ChannelRow();
        r.setId(id); r.setGuildKind(1); r.setGuildId(CLUB); r.setParentId(parent); r.setKind(kind); r.setName(name);
        r.setPosition(1); r.setIsDefault(0); r.setStatus(0); r.setVisibility(1); r.setSlowmodeSec(0);
        r.setPermVersion(0L); r.setMetaVersion(3L); r.setDelFlag(0);
        return r;
    }

    static Map<String, Object> body(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static int status(Runnable r)
    {
        ChannelRejection e = assertThrows(ChannelRejection.class, r::run);
        return e.getStatus();
    }

    static String code(Runnable r) { return assertThrows(ChannelRejection.class, r::run).getErrorCode(); }

    @Test void standingFollowsClubTruth()
    {
        assertEquals(GuildStanding.OWNER, access.standing(1, CLUB, OWNER));
        assertEquals(GuildStanding.MEMBER, access.standing(1, CLUB, MEMBER));
        assertEquals(GuildStanding.NONE, access.standing(1, CLUB, STRANGER));
        when(mapper.countClubAdmin(CLUB, MEMBER)).thenReturn(1);
        assertEquals(GuildStanding.ADMIN, access.standing(1, CLUB, MEMBER));
        assertEquals("GUILD_KIND_UNSUPPORTED", code(() -> access.standing(2, CLUB, OWNER)));
    }

    @Test void nonMemberIsDeniedEverywhere()
    {
        assertEquals("GUILD_FORBIDDEN", code(() -> service.directory(STRANGER, 1, CLUB, true)));
        assertEquals(403, status(() -> service.detail(STRANGER, CH)));
        assertEquals(403, status(() -> service.members(STRANGER, CH, null, 50)));
    }

    @Test void directoryGroupsVisibleChannelsWithDecimalStringIds()
    {
        ChannelRow category = row(1000, 0, 0, "Text");
        channel.setParentId(1000L);
        ChannelRow hidden = row(1002, 0, 1, "staff");
        hidden.setVisibility(2);
        when(mapper.selectGuildChannels(1, CLUB, false)).thenReturn(List.of(category, channel, hidden));
        Map<String, Object> o = new HashMap<>();
        o.put("channelId", 1002L); o.put("targetType", 1); o.put("targetId", 0L); o.put("allowBits", 0L);
        o.put("denyBits", ChannelCapability.VIEW_CHANNEL.mask());
        when(mapper.selectOverwritesOf(anyList())).thenReturn(List.of(o));
        when(mapper.countGuildMembers(1, CLUB)).thenReturn(80);
        Map<String, Object> dir = service.directory(MEMBER, 1, CLUB, false);
        assertEquals("42", dir.get("guild_id"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> groups = (List<Map<String, Object>>) dir.get("groups");
        assertEquals(1, groups.size());
        @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) groups.get(0).get("channels");
        assertEquals("1001", rows.get(0).get("id"));
        assertEquals("3", rows.get(0).get("version"));
        assertEquals(50, rows.get(0).get("member_count"));
        assertEquals(1, rows.get(0).get("member_count_capped"));
        assertEquals(List.of(), dir.get("ungrouped"), "private channel denied to @everyone is not listed");
    }

    @Test void createRequiresReasonHeaderAndManageChannel()
    {
        Map<String, Object> b = body("name", "events", "topic", null, "kind", 1, "parent_id", "0", "visibility", 1,
                "slowmode_sec", 0, "request_id", "req-create-1", "reason", "new");
        assertEquals("INVALID_FIELD", code(() -> service.create(OWNER, 1, CLUB, new LinkedHashMap<>(b), "other")));
        assertEquals("CHANNEL_FORBIDDEN", code(() -> service.create(MEMBER, 1, CLUB, new LinkedHashMap<>(b), "new")));
        Map<String, Object> extra = new LinkedHashMap<>(b);
        extra.put("position", 3);
        assertEquals("INVALID_FIELD", code(() -> service.create(OWNER, 1, CLUB, extra, "new")));
    }

    @Test void createAllocatesConversationIdAndReplaysOriginal()
    {
        when(mapper.insertConversation(anyMap())).thenAnswer(i -> { ((Map<String, Object>) i.getArgument(0)).put("id", 2001L); return 1; });
        ChannelRow created = row(2001, 0, 1, "events");
        created.setMetaVersion(0L);
        when(mapper.selectChannel(2001L)).thenReturn(created);
        Map<String, Object> b = body("name", "events", "topic", null, "kind", 1, "parent_id", "0", "visibility", 1,
                "slowmode_sec", 0, "request_id", "req-create-2", "reason", "new");
        Map<String, Object> first = service.create(OWNER, 1, CLUB, new LinkedHashMap<>(b), "new");
        assertEquals("APPLIED", first.get("state"));
        assertEquals("2001", first.get("id"));
        assertEquals("0", first.get("version"));
        assertEquals(0, first.get("status"));
        Map<String, Object> again = service.create(OWNER, 1, CLUB, new LinkedHashMap<>(b), "new");
        assertEquals(Boolean.TRUE, again.get("replayed"));
        verify(mapper, times(1)).insertChannel(any());
        Map<String, Object> changed = new LinkedHashMap<>(b);
        changed.put("name", "other");
        assertEquals("REQUEST_ID_CONFLICT", code(() -> service.create(OWNER, 1, CLUB, changed, "new")));
    }

    @Test void createMapsDuplicateNameTo409()
    {
        when(mapper.insertConversation(anyMap())).thenAnswer(i -> { ((Map<String, Object>) i.getArgument(0)).put("id", 2002L); return 1; });
        when(mapper.insertChannel(any())).thenThrow(new DuplicateKeyException("uk_channel_name"));
        Map<String, Object> b = body("name", "general", "topic", null, "kind", 1, "parent_id", "0", "visibility", 1,
                "slowmode_sec", 0, "request_id", "req-create-3", "reason", "dup");
        assertEquals("CHANNEL_NAME_TAKEN", code(() -> service.create(OWNER, 1, CLUB, b, "dup")));
    }

    @Test void editIsVersionCasAndUnchangedKeepsVersion()
    {
        Map<String, Object> stale = body("name", "renamed", "expected_version", "2", "request_id", "req-edit-1", "reason", "r");
        assertEquals("VERSION_CONFLICT", code(() -> service.edit(OWNER, CH, stale, "r")));
        Map<String, Object> same = body("name", "general", "expected_version", "3", "request_id", "req-edit-2", "reason", "r");
        Map<String, Object> result = service.edit(OWNER, CH, same, "r");
        assertEquals(Boolean.TRUE, result.get("unchanged"));
        assertEquals("3", result.get("version"));
        verify(mapper, never()).updateMetadata(anyLong(), anyLong(), any(), any(), anyBoolean(), any(), any());
        Map<String, Object> numeric = body("name", "x", "expected_version", 3, "request_id", "req-edit-3", "reason", "r");
        assertEquals("INVALID_FIELD", code(() -> service.edit(OWNER, CH, numeric, "r")));
    }

    @Test void archiveAndDeleteRespectLifecycle()
    {
        when(mapper.updateLifecycle(anyLong(), anyLong(), anyInt(), anyInt(), any(), any(), any())).thenReturn(1);
        Map<String, Object> unarchive = body("archived", 0, "expected_version", "3", "request_id", "req-arch-1", "reason", "r");
        assertEquals("CHANNEL_STATE_CONFLICT", code(() -> service.archive(OWNER, CH, unarchive, "r")));
        channel.setIsDefault(1);
        Map<String, Object> delete = body("expected_version", "3", "request_id", "req-del-1", "reason", "r");
        assertEquals("CHANNEL_STATE_CONFLICT", code(() -> service.delete(OWNER, CH, delete, "r")));
        channel.setIsDefault(0);
        Map<String, Object> archive = body("archived", 1, "expected_version", "3", "request_id", "req-arch-2", "reason", "r");
        Map<String, Object> archived = service.archive(OWNER, CH, archive, "r");
        assertEquals(1, archived.get("status"));
        verify(mapper).updateLifecycle(CH, 3L, 0, 1, "manual", null, null);
        verify(mapper).insertOpLog(argThat(m -> "ARCHIVE".equals(m.get("action"))));
    }

    @Test void overwriteRejectsGuildBitsAndChecksBothVersions()
    {
        Map<String, Object> guildBit = body("allow_bits", "0x400", "deny_bits", "0x0", "note", null,
                "expected_version", "3", "expected_perm_version", "0", "request_id", "req-perm-1", "reason", "r");
        assertEquals("GUILD_PERMISSION_IN_OVERWRITE", code(() -> service.putOverwrite(OWNER, CH, 1, "0", guildBit, "r")));
        Map<String, Object> stalePerm = body("allow_bits", "0x0", "deny_bits", "0x1", "note", null,
                "expected_version", "3", "expected_perm_version", "5", "request_id", "req-perm-2", "reason", "r");
        assertEquals("PERM_VERSION_CONFLICT", code(() -> service.putOverwrite(OWNER, CH, 1, "0", stalePerm, "r")));
        assertEquals("INVALID_FIELD", code(() -> service.putOverwrite(OWNER, CH, 1, "5", stalePerm, "r")));
        when(mapper.bumpPermissionVersion(CH, 3L, 0L)).thenReturn(1);
        when(mapper.insertOpLog(anyMap())).thenAnswer(i -> { ((Map<String, Object>) i.getArgument(0)).put("id", 77L); return 1; });
        Map<String, Object> ok = body("allow_bits", "0x0", "deny_bits", "0x1", "note", "lock",
                "expected_version", "3", "expected_perm_version", "0", "request_id", "req-perm-3", "reason", "r");
        Map<String, Object> result = service.putOverwrite(OWNER, CH, 1, "0", ok, "r");
        assertEquals("4", result.get("version"));
        assertEquals("1", result.get("perm_version"));
        assertEquals("0x1", result.get("deny_bits"));
        assertEquals(Boolean.FALSE, result.get("inherited"));
        assertEquals("77", result.get("audit_id"));
        assertEquals("CHANNEL_FORBIDDEN", code(() -> service.permissions(MEMBER, CH)));
    }

    @Test void receiptRedactsWhenViewerLostAccess()
    {
        when(mapper.selectReceipt(STRANGER, "META", "req-receipt")).thenReturn(Map.of("fingerprint", "x",
                "channelId", CH, "response", "{\"state\":\"APPLIED\"}"));
        Map<String, Object> r = service.receipt(STRANGER, "META", "req-receipt");
        assertEquals("APPLIED", r.get("state"));
        assertEquals(Boolean.TRUE, r.get("result_redacted"));
        assertFalse(r.containsKey("result"));
        assertEquals("NOT_FOUND", service.receipt(OWNER, "META", "req-missing").get("state"));
    }

    @Test void membersPageUsesIntegerHasMoreAndStringIds()
    {
        List<Map<String, Object>> page = new ArrayList<>();
        for (long id : new long[] { OWNER, MEMBER })
        {
            Map<String, Object> m = new HashMap<>();
            m.put("memberId", id); m.put("nickname", "n" + id);
            page.add(m);
        }
        when(mapper.selectGuildMembers(1, CLUB, 0L, 100)).thenReturn(page);
        Map<String, Object> result = service.members(MEMBER, CH, null, 1);
        assertEquals(1, result.get("has_more"));
        assertEquals("11", result.get("next_cursor"));
    }
}
