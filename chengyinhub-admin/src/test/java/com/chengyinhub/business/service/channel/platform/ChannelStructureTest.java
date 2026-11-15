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
import com.chengyinhub.business.service.channel.platform.ChannelPermissionCalculator.Overwrite;

import static com.chengyinhub.business.service.channel.platform.ChannelCapability.*;
import static com.chengyinhub.business.service.channel.platform.ChannelPermissionCalculator.has;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** C1 channels-structure: announcement posting rule and channel/category reorder. */
class ChannelStructureTest
{
    static final long OWNER = 11, MEMBER = 22, ADMIN = 23, STRANGER = 33, CLUB = 42;
    static final long CAT_A = 900, CAT_B = 901, DEFAULT = 1000, NEWS = 1001, CHAT = 1002;

    ChannelPlatformMapper mapper;
    ChannelGuildAccess access;
    ChannelPlatformService service;
    Map<Long, ChannelRow> rows;
    List<Map<String, Object>> opLog;

    @BeforeEach void setUp()
    {
        mapper = mock(ChannelPlatformMapper.class);
        ReceiptFake.install(mapper);
        when(mapper.selectClubGuild(CLUB)).thenReturn(Map.of("id", CLUB, "ownerId", OWNER, "status", 1, "delFlag", 0));
        when(mapper.countClubMember(CLUB, MEMBER)).thenReturn(1);
        when(mapper.countClubMember(CLUB, ADMIN)).thenReturn(1);
        when(mapper.countClubAdmin(CLUB, ADMIN)).thenReturn(1);
        when(mapper.selectMemberRoles(anyInt(), anyLong(), anyLong())).thenReturn(List.of());
        when(mapper.selectOverwrites(anyLong())).thenReturn(List.of());
        access = new ChannelGuildAccess(mapper);
        service = new ChannelPlatformService(mapper, access);
        rows = new HashMap<>();
        put(row(CAT_A, 0, 0, "A", 1));
        put(row(CAT_B, 0, 0, "B", 2));
        ChannelRow def = row(DEFAULT, 0, 1, "闲聊", 0);
        def.setIsDefault(1);
        put(def);
        put(row(NEWS, 0, 2, "公告", 1));
        put(row(CHAT, CAT_A, 1, "chat", 1));
        when(mapper.selectChannel(anyLong())).thenAnswer(i -> rows.get(i.<Long>getArgument(0)));
        when(mapper.lockChannel(anyLong())).thenAnswer(i -> rows.get(i.<Long>getArgument(0)));
        when(mapper.updatePlacement(anyLong(), anyLong(), anyLong(), anyInt())).thenAnswer(i -> {
            ChannelRow r = rows.get(i.<Long>getArgument(0));
            if (r == null || r.getMetaVersion() != i.<Long>getArgument(1).longValue()) return 0;
            r.setParentId(i.getArgument(2));
            r.setPosition(i.getArgument(3));
            r.setMetaVersion(r.getMetaVersion() + 1);
            return 1;
        });
        opLog = new ArrayList<>();
        when(mapper.insertOpLog(anyMap())).thenAnswer(i -> { opLog.add(new HashMap<>(i.<Map<String, Object>>getArgument(0))); return 1; });
    }

    void put(ChannelRow r) { rows.put(r.getId(), r); }

    static ChannelRow row(long id, long parent, int kind, String name, int position)
    {
        ChannelRow r = new ChannelRow();
        r.setId(id); r.setGuildKind(1); r.setGuildId(CLUB); r.setParentId(parent); r.setKind(kind); r.setName(name);
        r.setPosition(position); r.setIsDefault(0); r.setStatus(0); r.setVisibility(1); r.setSlowmodeSec(0);
        r.setPermVersion(0L); r.setMetaVersion(3L); r.setDelFlag(0);
        return r;
    }

    static Map<String, Object> item(long id, long parent, int position)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(id));
        m.put("parent_id", String.valueOf(parent));
        m.put("position", position);
        return m;
    }

    static Map<String, Object> reorderBody(String requestId, Object... items)
    {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("items", new ArrayList<>(List.of(items)));
        b.put("request_id", requestId);
        b.put("reason", "tidy");
        return b;
    }

    static String code(Runnable r) { return assertThrows(ChannelRejection.class, r::run).getErrorCode(); }

    // ------------------------------------------------------------------ announcement rule

    @Test void memberReadsButCannotPostInAnnouncement()
    {
        long bits = access.effective(rows.get(NEWS), MEMBER);
        assertTrue(has(bits, VIEW_CHANNEL));
        assertTrue(has(bits, READ_HISTORY));
        assertTrue(has(bits, ADD_REACTION), "members still react to notices");
        assertFalse(has(bits, SEND_MESSAGE));
        assertFalse(has(bits, ATTACH_FILE));
        assertFalse(has(bits, CREATE_POLL));
    }

    @Test void ownerAndAdminPostInAnnouncement()
    {
        assertTrue(has(access.effective(rows.get(NEWS), OWNER), SEND_MESSAGE));
        assertTrue(has(access.effective(rows.get(NEWS), ADMIN), SEND_MESSAGE));
    }

    @Test void textChannelsAreUnchangedForMembers()
    {
        assertTrue(has(access.effective(rows.get(CHAT), MEMBER), SEND_MESSAGE));
        assertTrue(has(access.effective(rows.get(DEFAULT), MEMBER), SEND_MESSAGE));
    }

    @Test void everyoneOverwriteCannotReopenAnnouncementButRoleOverwriteCan()
    {
        List<Overwrite> everyone = List.of(new Overwrite(1, 0, SEND_MESSAGE.mask(), 0));
        long bits = ChannelPermissionCalculator.effective(GuildStanding.MEMBER, 0, List.of(), everyone, 0, 2);
        assertFalse(has(bits, SEND_MESSAGE));
        List<Overwrite> announcer = List.of(new Overwrite(2, 77, SEND_MESSAGE.mask(), 0));
        bits = ChannelPermissionCalculator.effective(GuildStanding.MEMBER, 0, List.of(77L), announcer, 0, 2);
        assertTrue(has(bits, SEND_MESSAGE), "a chosen role can be allowed to post notices");
        bits = ChannelPermissionCalculator.effective(GuildStanding.MEMBER, 0, List.of(), announcer, 0, 2);
        assertFalse(has(bits, SEND_MESSAGE), "members without the role stay read-only");
    }

    @Test void roleWithManageChannelPostsInAnnouncement()
    {
        long bits = ChannelPermissionCalculator.effective(GuildStanding.MEMBER, MANAGE_CHANNEL.mask(), List.of(5L), List.of(), 0, 2);
        assertTrue(has(bits, SEND_MESSAGE));
    }

    @Test void legacyOverloadKeepsPreviousBehaviour()
    {
        long before = ChannelPermissionCalculator.effective(GuildStanding.MEMBER, 0, List.of(), List.of(), 0);
        assertTrue(has(before, SEND_MESSAGE));
    }

    @Test void directoryShowsAnnouncementWithoutSendForMembers()
    {
        when(mapper.selectGuildChannels(1, CLUB, false)).thenReturn(List.of(rows.get(DEFAULT), rows.get(NEWS)));
        when(mapper.selectOverwritesOf(anyList())).thenReturn(List.of());
        Map<String, Object> dir = service.directory(MEMBER, 1, CLUB, false);
        @SuppressWarnings("unchecked") List<Map<String, Object>> ungrouped = (List<Map<String, Object>>) dir.get("ungrouped");
        Map<String, Object> news = ungrouped.stream().filter(v -> "1001".equals(v.get("id"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked") List<String> perms = (List<String>) news.get("my_permissions_list");
        assertFalse(perms.contains("SEND_MESSAGE"));
        assertTrue(perms.contains("VIEW_CHANNEL"));
    }

    // ------------------------------------------------------------------ reorder: permission checks

    @Test void reorderNeedsManageChannel()
    {
        assertEquals("CHANNEL_FORBIDDEN", code(() -> service.reorder(MEMBER, 1, CLUB, reorderBody("req-move-001", item(CHAT, 0, 5)), "tidy")));
        assertEquals("GUILD_FORBIDDEN", code(() -> service.reorder(STRANGER, 1, CLUB, reorderBody("req-move-002", item(CHAT, 0, 5)), "tidy")));
        verify(mapper, never()).updatePlacement(anyLong(), anyLong(), anyLong(), anyInt());
    }

    @Test void reorderNeedsMatchingReasonHeader()
    {
        assertEquals("INVALID_FIELD", code(() -> service.reorder(OWNER, 1, CLUB, reorderBody("req-move-003", item(CHAT, 0, 5)), "other")));
    }

    @Test void adminReordersAndMovesBetweenCategories()
    {
        Map<String, Object> r = service.reorder(ADMIN, 1, CLUB,
                reorderBody("req-move-004", item(CAT_B, 0, 1), item(CAT_A, 0, 2), item(CHAT, CAT_B, 3)), "tidy");
        assertEquals("APPLIED", r.get("state"));
        assertEquals(CAT_B, rows.get(CHAT).getParentId());
        assertEquals(3, rows.get(CHAT).getPosition());
        assertEquals(1, rows.get(CAT_B).getPosition());
        assertEquals(2, rows.get(CAT_A).getPosition());
        @SuppressWarnings("unchecked") List<Map<String, Object>> moved = (List<Map<String, Object>>) r.get("moved");
        assertEquals(3, moved.size());
        assertEquals(3, opLog.size());
        assertTrue(opLog.stream().allMatch(l -> "MOVE".equals(l.get("action")) && "tidy".equals(l.get("reason"))
                && Long.valueOf(ADMIN).equals(l.get("operatorId"))));
    }

    @Test void reorderReplaysTheOriginalReceipt()
    {
        Map<String, Object> b = reorderBody("req-move-005", item(CHAT, 0, 4));
        service.reorder(OWNER, 1, CLUB, new LinkedHashMap<>(b), "tidy");
        Map<String, Object> again = service.reorder(OWNER, 1, CLUB, new LinkedHashMap<>(b), "tidy");
        assertEquals(Boolean.TRUE, again.get("replayed"));
        verify(mapper, times(1)).updatePlacement(anyLong(), anyLong(), anyLong(), anyInt());
        assertEquals("REQUEST_ID_CONFLICT",
                code(() -> service.reorder(OWNER, 1, CLUB, reorderBody("req-move-005", item(CHAT, 0, 6)), "tidy")));
    }

    @Test void unchangedPlacementWritesNothing()
    {
        Map<String, Object> r = service.reorder(OWNER, 1, CLUB, reorderBody("req-move-006", item(CHAT, CAT_A, 1)), "tidy");
        assertEquals(Boolean.TRUE, r.get("unchanged"));
        verify(mapper, never()).updatePlacement(anyLong(), anyLong(), anyLong(), anyInt());
        assertTrue(opLog.isEmpty());
    }

    // ------------------------------------------------------------------ reorder: ordering rules

    @Test void categoriesStayTopLevel()
    {
        assertEquals("INVALID_FIELD", code(() -> service.reorder(OWNER, 1, CLUB, reorderBody("req-move-007", item(CAT_A, CAT_B, 1)), "tidy")));
    }

    @Test void channelParentMustBeALiveCategoryOfTheSameClub()
    {
        assertEquals("INVALID_FIELD", code(() -> service.reorder(OWNER, 1, CLUB, reorderBody("req-move-008", item(CHAT, NEWS, 1)), "tidy")));
        rows.get(CAT_B).setStatus(1);
        assertEquals("INVALID_FIELD", code(() -> service.reorder(OWNER, 1, CLUB, reorderBody("req-move-009", item(CHAT, CAT_B, 1)), "tidy")));
        ChannelRow foreign = row(950, 0, 0, "X", 1);
        foreign.setGuildId(99L);
        put(foreign);
        assertEquals("INVALID_FIELD", code(() -> service.reorder(OWNER, 1, CLUB, reorderBody("req-move-010", item(CHAT, 950, 1)), "tidy")));
    }

    @Test void defaultChannelStaysFirst()
    {
        assertEquals("CHANNEL_STATE_CONFLICT", code(() -> service.reorder(OWNER, 1, CLUB, reorderBody("req-move-011", item(DEFAULT, 0, 3)), "tidy")));
        assertEquals("INVALID_FIELD", code(() -> service.reorder(OWNER, 1, CLUB, reorderBody("req-move-012", item(CHAT, CAT_A, 0)), "tidy")));
    }

    @Test void foreignOrDeletedChannelIsNotFound()
    {
        ChannelRow foreign = row(1500, 0, 1, "other", 1);
        foreign.setGuildId(99L);
        put(foreign);
        assertEquals("CHANNEL_NOT_FOUND", code(() -> service.reorder(OWNER, 1, CLUB, reorderBody("req-move-013", item(1500, 0, 1)), "tidy")));
        rows.get(CHAT).setStatus(3);
        assertEquals("CHANNEL_NOT_FOUND", code(() -> service.reorder(OWNER, 1, CLUB, reorderBody("req-move-014", item(CHAT, 0, 1)), "tidy")));
    }

    @Test void staleVersionIsAConflict()
    {
        Map<String, Object> stale = item(CHAT, 0, 4);
        stale.put("expected_version", "2");
        assertEquals("VERSION_CONFLICT", code(() -> service.reorder(OWNER, 1, CLUB, reorderBody("req-move-015", stale), "tidy")));
        Map<String, Object> fresh = item(CHAT, 0, 4);
        fresh.put("expected_version", "3");
        assertEquals("APPLIED", service.reorder(OWNER, 1, CLUB, reorderBody("req-move-016", fresh), "tidy").get("state"));
    }

    @Test void sameNameInTargetCategoryIsRefused()
    {
        when(mapper.updatePlacement(eq(CHAT), anyLong(), anyLong(), anyInt())).thenThrow(new DuplicateKeyException("uk_channel_name"));
        assertEquals("CHANNEL_NAME_TAKEN", code(() -> service.reorder(OWNER, 1, CLUB, reorderBody("req-move-017", item(CHAT, CAT_B, 1)), "tidy")));
    }

    @Test void malformedItemsAreRefused()
    {
        assertEquals("INVALID_FIELD", code(() -> service.reorder(OWNER, 1, CLUB, reorderBody("req-move-018"), "tidy")));
        assertEquals("INVALID_FIELD", code(() -> service.reorder(OWNER, 1, CLUB,
                reorderBody("req-move-019", item(CHAT, 0, 1), item(CHAT, 0, 2)), "tidy")));
        Map<String, Object> extra = item(CHAT, 0, 1);
        extra.put("name", "x");
        assertEquals("INVALID_FIELD", code(() -> service.reorder(OWNER, 1, CLUB, reorderBody("req-move-020", extra), "tidy")));
        assertEquals("INVALID_FIELD", code(() -> service.reorder(OWNER, 1, CLUB, reorderBody("req-move-021", item(CHAT, 0, 100_001)), "tidy")));
        Map<String, Object> numericId = item(CHAT, 0, 1);
        numericId.put("id", CHAT);
        assertEquals("INVALID_FIELD", code(() -> service.reorder(OWNER, 1, CLUB, reorderBody("req-move-022", numericId), "tidy")));
    }
}
