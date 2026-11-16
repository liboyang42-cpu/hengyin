package com.chengyinhub.business.channelevent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.channelevent.mapper.ChannelEventMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.chengyinhub.business.moneyswitch.MoneySwitchService;
import com.chengyinhub.business.service.IImRealtimePublisher;
import com.chengyinhub.business.service.channel.platform.ChannelCapability;
import com.chengyinhub.business.service.channel.platform.ChannelGuildAccess;
import com.chengyinhub.business.service.channel.platform.ChannelPermissionCalculator;
import com.chengyinhub.business.service.channel.platform.ChannelPermissionCalculator.GuildStanding;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.business.service.channel.platform.ChannelRow;
import com.chengyinhub.business.service.impl.AppMediaAssetService;
import com.chengyinhub.business.service.impl.InGameChannelService;
import com.chengyinhub.common.exception.ServiceException;

/**
 * C3 channel events: posting rules, card posting and the review wait, RSVP against enrolment and capacity, the
 * thread, reminders for "maybe", album access and moderation.
 */
class ChannelEventServiceTest
{
    static final long OWNER = 1L, ADMIN = 2L, MEMBER = 3L, OUTSIDER = 4L;
    static final long CHANNEL = 50L, CLUB = 42L, ACTIVITY = 900L, EVENT = 7L, TICKET = 300L;
    static final long HOUR = 3600_000L;

    ChannelEventMapper mapper;
    ChannelPlatformMapper channels;
    ChannelGuildAccess access;
    MoneySwitchService money;
    InGameChannelService inGame;
    AppMediaAssetService media;
    ChannelEventService service;
    ChannelRow channel;
    Map<String, Boolean> moneyAreas;

    @BeforeEach
    void setUp()
    {
        mapper = mock(ChannelEventMapper.class);
        channels = mock(ChannelPlatformMapper.class);
        access = mock(ChannelGuildAccess.class);
        money = mock(MoneySwitchService.class);
        inGame = mock(InGameChannelService.class);
        media = mock(AppMediaAssetService.class);
        service = new ChannelEventService(mapper, channels, access, money, inGame, media);

        channel = new ChannelRow();
        channel.setId(CHANNEL);
        channel.setGuildKind(ChannelGuildAccess.GUILD_CLUB);
        channel.setGuildId(CLUB);
        channel.setKind(1);
        channel.setStatus(0);
        when(channels.selectChannel(CHANNEL)).thenReturn(channel);

        long memberBits = ChannelPermissionCalculator.MEMBER_BASE;
        when(access.standing(ChannelGuildAccess.GUILD_CLUB, CLUB, OWNER)).thenReturn(GuildStanding.OWNER);
        when(access.standing(ChannelGuildAccess.GUILD_CLUB, CLUB, ADMIN)).thenReturn(GuildStanding.ADMIN);
        when(access.standing(ChannelGuildAccess.GUILD_CLUB, CLUB, MEMBER)).thenReturn(GuildStanding.MEMBER);
        when(access.standing(ChannelGuildAccess.GUILD_CLUB, CLUB, OUTSIDER)).thenReturn(GuildStanding.NONE);
        when(access.effective(channel, OWNER)).thenReturn(memberBits | ChannelPermissionCalculator.ADMIN_BASE);
        when(access.effective(channel, ADMIN)).thenReturn(memberBits | ChannelPermissionCalculator.ADMIN_BASE);
        when(access.effective(channel, MEMBER)).thenReturn(memberBits);
        when(access.effective(channel, OUTSIDER)).thenReturn(0L);
        when(access.requireMember(ChannelGuildAccess.GUILD_CLUB, CLUB, OUTSIDER))
                .thenThrow(new ChannelRejection(403, "GUILD_FORBIDDEN", "加入后才能查看频道"));

        moneyAreas = new HashMap<>();
        moneyAreas.put("tickets", false);
        when(money.snapshot()).thenReturn(moneyAreas);

        when(mapper.selectChannelConversation(CHANNEL)).thenReturn(Map.of("channelId", CHANNEL, "channelStatus", 0, "conversationId", CHANNEL));
        doAnswer(inv -> {
            Map<String, Object> m = inv.getArgument(0);
            m.put("id", 5555L);
            return 1;
        }).when(mapper).insertCardMessage(anyMap());
        when(mapper.markPosted(anyLong(), anyLong())).thenReturn(1);
        when(inGame.ensureActivityThreadMember(eq(ACTIVITY), anyLong())).thenReturn(8800L);
    }

    static ChannelEventDraft draft(String price)
    {
        Map<String, Object> b = ChannelEventDraftTest.form();
        long now = System.currentTimeMillis();
        b.put("startAt", now + 24 * HOUR);
        b.put("endAt", now + 26 * HOUR);
        if (price != null) b.put("price", price);
        return ChannelEventDraft.parse(b, new Date());
    }

    ChannelEventRow event(int status, long startOffset, long endOffset)
    {
        long now = System.currentTimeMillis();
        ChannelEventRow e = new ChannelEventRow();
        e.setId(EVENT);
        e.setClubId(CLUB);
        e.setChannelId(CHANNEL);
        e.setActivityId(ACTIVITY);
        e.setTicketId(TICKET);
        e.setPosterMemberId(OWNER);
        e.setTitle("周六夜跑");
        e.setStartAt(new Date(now + startOffset));
        e.setEndAt(new Date(now + endOffset));
        e.setPlaceName("滨江步道");
        e.setLat(new BigDecimal("31.2304000"));
        e.setLng(new BigDecimal("121.4737000"));
        e.setCapacity(10);
        e.setPaid(0);
        e.setStatus(status);
        when(mapper.selectEvent(EVENT)).thenReturn(e);
        return e;
    }

    static Map<String, Object> activity(int status, int publishStatus)
    {
        Map<String, Object> s = new HashMap<>();
        s.put("id", ACTIVITY);
        s.put("status", status);
        s.put("publishStatus", publishStatus);
        s.put("delFlag", 0);
        s.put("clubId", CLUB);
        s.put("memberId", OWNER);
        return s;
    }

    static String code(Runnable r)
    {
        return assertThrows(ChannelRejection.class, r::run).getErrorCode();
    }

    // ------------------------------------------------------------------ who may post

    @Test
    void clubOwnerMayPostAFreeEvent()
    {
        ChannelEventService.PostTarget t = service.preparePost(OWNER, CHANNEL, draft(null));
        assertEquals(CHANNEL, t.channelId());
        assertEquals(CLUB, t.clubId());
    }

    @Test
    void adminsMembersAndOutsidersMayNotPost()
    {
        assertEquals("EVENT_POST_FORBIDDEN", code(() -> service.preparePost(ADMIN, CHANNEL, draft(null))));
        assertEquals("EVENT_POST_FORBIDDEN", code(() -> service.preparePost(MEMBER, CHANNEL, draft(null))));
        assertEquals("GUILD_FORBIDDEN", code(() -> service.preparePost(OUTSIDER, CHANNEL, draft(null))));
    }

    @Test
    void ownerWithoutSendRightInThisChannelMayNotPost()
    {
        when(access.effective(channel, OWNER)).thenReturn(ChannelCapability.of(ChannelCapability.VIEW_CHANNEL));
        assertEquals("EVENT_POST_FORBIDDEN", code(() -> service.preparePost(OWNER, CHANNEL, draft(null))));
    }

    @Test
    void archivedChannelsAndTeamSpacesTakeNoEvents()
    {
        channel.setStatus(1);
        assertEquals("CHANNEL_NOT_ACTIVE", code(() -> service.preparePost(OWNER, CHANNEL, draft(null))));
        channel.setStatus(0);
        channel.setGuildKind(ChannelGuildAccess.GUILD_TEAM);
        assertEquals("EVENT_CLUB_ONLY", code(() -> service.preparePost(OWNER, CHANNEL, draft(null))));
    }

    @Test
    void paidEventsOnlyWhileTheTicketsMoneyAreaIsOpen()
    {
        assertEquals("PAID_EVENTS_OFF", code(() -> service.preparePost(OWNER, CHANNEL, draft("39.90"))));
        moneyAreas.put("tickets", true);
        assertEquals(CLUB, service.preparePost(OWNER, CHANNEL, draft("39.90")).clubId());
        when(money.snapshot()).thenThrow(new IllegalStateException("db down"));
        assertEquals("PAID_EVENTS_OFF", code(() -> service.preparePost(OWNER, CHANNEL, draft("39.90"))),
                "a money switch read error keeps paid events off");
    }

    // ------------------------------------------------------------------ creation and the card

    @Test
    void attachLinksTheActivityAndPostsTheCardWhenTheActivityIsLive()
    {
        when(mapper.selectActivityState(ACTIVITY)).thenReturn(activity(1, 1));
        when(mapper.selectFirstTicketId(ACTIVITY)).thenReturn(TICKET);
        doAnswer(inv -> {
            ChannelEventRow r = inv.getArgument(0);
            r.setId(EVENT);
            when(mapper.selectEvent(EVENT)).thenReturn(r);
            return 1;
        }).when(mapper).insertEvent(any());

        Map<String, Object> view = service.attach(OWNER, new ChannelEventService.PostTarget(CHANNEL, CLUB), draft(null), ACTIVITY);

        ArgumentCaptor<ChannelEventRow> row = ArgumentCaptor.forClass(ChannelEventRow.class);
        verify(mapper).insertEvent(row.capture());
        assertEquals(TICKET, row.getValue().getTicketId());
        assertEquals(20, row.getValue().getCapacity());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> msg = ArgumentCaptor.forClass(Map.class);
        verify(mapper).insertCardMessage(msg.capture());
        assertEquals(CHANNEL, msg.getValue().get("conversationId"), "the card goes into the channel's own conversation");
        assertEquals(OWNER, msg.getValue().get("senderId"));
        assertTrue(msg.getValue().get("content").toString().startsWith("【活动】周六夜跑"), "old clients still read the text");
        JSONObject extra = JSON.parseObject(msg.getValue().get("extraJson").toString());
        assertEquals("channel_event", extra.getString("cardType"));
        assertEquals(String.valueOf(EVENT), extra.getString("eventId"));
        assertEquals(ACTIVITY, extra.getLongValue("activityId"));
        assertEquals("滨江步道", extra.getString("target"), "same field name as game event cards");
        assertTrue(extra.toJSONString().length() < 1000, "fits im_message.extra_json");
        verify(mapper).markPosted(EVENT, 5555L);
        verify(mapper).fanOutUnread(CHANNEL, OWNER);
        verify(inGame).ensureActivityThreadMember(ACTIVITY, OWNER);
        assertEquals("UPCOMING", view.get("state"));
    }

    @Test
    void anActivityHeldForReviewGetsItsCardOnlyAfterApproval()
    {
        Map<String, Object> waiting = activity(0, 0);
        when(mapper.selectActivityState(ACTIVITY)).thenReturn(waiting);
        doAnswer(inv -> {
            ChannelEventRow r = inv.getArgument(0);
            r.setId(EVENT);
            when(mapper.selectEvent(EVENT)).thenReturn(r);
            return 1;
        }).when(mapper).insertEvent(any());
        Map<String, Object> view = service.attach(OWNER, new ChannelEventService.PostTarget(CHANNEL, CLUB), draft(null), ACTIVITY);
        assertEquals("IN_REVIEW", view.get("state"));
        verify(mapper, never()).insertCardMessage(anyMap());

        ChannelEventRow e = event(ChannelEventRow.STATUS_WAITING_REVIEW, 24 * HOUR, 26 * HOUR);
        when(mapper.selectWaitingByChannel(CHANNEL)).thenReturn(List.of(e));
        service.syncWaiting(CHANNEL);
        verify(mapper, never()).insertCardMessage(anyMap());

        when(mapper.selectActivityState(ACTIVITY)).thenReturn(activity(1, 1));
        service.syncWaiting(CHANNEL);
        verify(mapper).insertCardMessage(anyMap());

        when(mapper.selectActivityState(ACTIVITY)).thenReturn(activity(2, 0));
        service.syncWaiting(CHANNEL);
        verify(mapper).markCancelled(EVENT);
    }

    /** W7 SOC-7: the review decision itself posts the held card (once) or closes it, without waiting for a list read. */
    @Test
    void approvalPostsTheHeldCardOnceAndRefusalClosesIt()
    {
        IImRealtimePublisher realtime = mock(IImRealtimePublisher.class);
        service.setRealtime(realtime);
        ChannelEventRow e = event(ChannelEventRow.STATUS_WAITING_REVIEW, 24 * HOUR, 26 * HOUR);
        when(mapper.selectByActivity(ACTIVITY)).thenReturn(e);

        when(mapper.selectActivityState(ACTIVITY)).thenReturn(activity(0, 0));
        assertFalse(service.syncByActivity(ACTIVITY), "still in review: nothing posted");
        verify(mapper, never()).insertCardMessage(anyMap());

        when(mapper.selectActivityState(ACTIVITY)).thenReturn(activity(1, 1));
        assertTrue(service.syncByActivity(ACTIVITY));
        verify(mapper).insertCardMessage(anyMap());
        verify(mapper).markPosted(EVENT, 5555L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> hint = ArgumentCaptor.forClass(Map.class);
        verify(realtime).publish(eq(CHANNEL), eq(IImRealtimePublisher.MESSAGE_CREATED), hint.capture());
        assertEquals("5555", hint.getValue().get("message_id"), "ids only, never the card text");
        assertEquals(2, hint.getValue().size());

        assertFalse(service.syncByActivity(ACTIVITY), "already posted: a second decision posts nothing");
        verify(mapper).insertCardMessage(anyMap());

        ChannelEventRow refused = event(ChannelEventRow.STATUS_WAITING_REVIEW, 24 * HOUR, 26 * HOUR);
        when(mapper.selectByActivity(ACTIVITY)).thenReturn(refused);
        when(mapper.selectActivityState(ACTIVITY)).thenReturn(activity(2, 0));
        assertFalse(service.syncByActivity(ACTIVITY));
        verify(mapper).markCancelled(EVENT);
        assertFalse(service.syncByActivity(0L));
    }

    @Test
    void aCardPostedWithoutARealtimePublisherStillPosts()
    {
        ChannelEventRow e = event(ChannelEventRow.STATUS_WAITING_REVIEW, 24 * HOUR, 26 * HOUR);
        when(mapper.selectByActivity(ACTIVITY)).thenReturn(e);
        when(mapper.selectActivityState(ACTIVITY)).thenReturn(activity(1, 1));
        assertTrue(service.syncByActivity(ACTIVITY));
    }

    @Test
    void attachRefusesAnActivityOfAnotherClubOrHost()
    {
        Map<String, Object> other = activity(1, 1);
        other.put("clubId", 99L);
        when(mapper.selectActivityState(ACTIVITY)).thenReturn(other);
        assertEquals("EVENT_ACTIVITY_MISMATCH",
                code(() -> service.attach(OWNER, new ChannelEventService.PostTarget(CHANNEL, CLUB), draft(null), ACTIVITY)));
    }

    @Test
    void anEventInReviewIsHiddenFromEveryoneButItsPoster()
    {
        event(ChannelEventRow.STATUS_WAITING_REVIEW, 24 * HOUR, 26 * HOUR);
        when(mapper.selectActivityState(ACTIVITY)).thenReturn(activity(0, 0));
        when(mapper.selectWaitingByChannel(CHANNEL)).thenReturn(new ArrayList<>());
        assertEquals("EVENT_NOT_FOUND", code(() -> service.detail(MEMBER, EVENT)));
        assertEquals("IN_REVIEW", service.detail(OWNER, EVENT).get("state"));
    }

    // ------------------------------------------------------------------ RSVP and capacity

    @Test
    void goingNeedsAConfirmedEnrolmentThroughTheExistingRegistrationPath()
    {
        event(ChannelEventRow.STATUS_POSTED, 24 * HOUR, 26 * HOUR);
        assertEquals("ENROL_REQUIRED", code(() -> service.rsvp(MEMBER, EVENT, "going")));
        verify(mapper, never()).upsertRsvp(anyLong(), anyLong(), any());

        Map<String, Object> target = service.enrolTarget(MEMBER, EVENT);
        assertEquals(2, target.get("owner_type"));
        assertEquals(String.valueOf(ACTIVITY), target.get("owner_id"));
        assertEquals(String.valueOf(TICKET), target.get("ticket_id"));

        when(mapper.countMyConfirmed(ACTIVITY, MEMBER)).thenReturn(1);
        when(mapper.countGoing(ACTIVITY)).thenReturn(4);
        Map<String, Object> v = service.rsvp(MEMBER, EVENT, "GOING");
        verify(mapper).upsertRsvp(EVENT, MEMBER, "GOING");
        verify(inGame).ensureActivityThreadMember(ACTIVITY, MEMBER);
        assertEquals("GOING", v.get("my_rsvp"));
        assertEquals(4, v.get("going_count"));
        assertEquals(6, v.get("spots_left"), "capacity 10 minus 4 confirmed");
    }

    @Test
    void maybeAndNoNeedNoEnrolmentAndNoKeepsAnExistingOne()
    {
        event(ChannelEventRow.STATUS_POSTED, 24 * HOUR, 26 * HOUR);
        when(mapper.selectMyRsvp(EVENT, MEMBER)).thenReturn("MAYBE");
        Map<String, Object> maybe = service.rsvp(MEMBER, EVENT, "maybe");
        verify(mapper).upsertRsvp(EVENT, MEMBER, "MAYBE");
        assertEquals("MAYBE", maybe.get("my_rsvp"));
        assertEquals(false, maybe.get("still_enrolled"));

        when(mapper.countMyConfirmed(ACTIVITY, MEMBER)).thenReturn(1);
        Map<String, Object> no = service.rsvp(MEMBER, EVENT, "NO");
        assertEquals(true, no.get("still_enrolled"), "saying no never cancels a paid or free enrolment by itself");
        assertEquals("GOING", no.get("my_rsvp"), "a confirmed enrolment reads as going");
    }

    @Test
    void fullEventsShowNoSpotsLeftAndCountsNeverGoNegative()
    {
        event(ChannelEventRow.STATUS_POSTED, 24 * HOUR, 26 * HOUR);
        when(mapper.countGoing(ACTIVITY)).thenReturn(12);
        assertEquals(0, service.detail(MEMBER, EVENT).get("spots_left"));
    }

    @Test
    void rsvpRefusedOnEndedOrCancelledEventsAndForBadValues()
    {
        event(ChannelEventRow.STATUS_POSTED, -26 * HOUR, -24 * HOUR);
        assertEquals("EVENT_CLOSED", code(() -> service.rsvp(MEMBER, EVENT, "MAYBE")));
        event(ChannelEventRow.STATUS_CANCELLED, 24 * HOUR, 26 * HOUR);
        assertEquals("EVENT_CLOSED", code(() -> service.rsvp(MEMBER, EVENT, "MAYBE")));
        assertEquals("RSVP_INVALID", code(() -> service.rsvp(MEMBER, EVENT, "YES")));
    }

    @Test
    void outsidersCannotSeeAnEventUnlessTheyHoldAConfirmedEnrolment()
    {
        event(ChannelEventRow.STATUS_POSTED, 24 * HOUR, 26 * HOUR);
        assertEquals("GUILD_FORBIDDEN", code(() -> service.detail(OUTSIDER, EVENT)));
        when(mapper.countMyConfirmed(ACTIVITY, OUTSIDER)).thenReturn(1);
        assertEquals(true, service.detail(OUTSIDER, EVENT).get("enrolled"));
    }

    @Test
    void openingTheThreadJoinsTheActivityChannel()
    {
        event(ChannelEventRow.STATUS_POSTED, 24 * HOUR, 26 * HOUR);
        Map<String, Object> t = service.thread(MEMBER, EVENT);
        assertEquals("8800", t.get("conversation_id"));
        verify(inGame).ensureActivityThreadMember(ACTIVITY, MEMBER);
    }

    @Test
    void maybeMembersAreHandedToTheExistingReminderJob()
    {
        when(mapper.selectMaybeMemberIdsByTicket(TICKET)).thenReturn(List.of(MEMBER, ADMIN));
        assertEquals(List.of(MEMBER, ADMIN), service.maybeMembersForReminder(TICKET));
        when(mapper.selectMaybeMemberIdsByTicket(TICKET)).thenReturn(null);
        assertTrue(service.maybeMembersForReminder(TICKET).isEmpty());
    }

    // ------------------------------------------------------------------ album

    @Test
    void uploadsOpenAtTheStartForAttendeesAndTheHostOnly()
    {
        event(ChannelEventRow.STATUS_POSTED, 24 * HOUR, 26 * HOUR);
        assertEquals("ALBUM_UPLOAD_FORBIDDEN", code(() -> service.addPhoto(OWNER, EVENT, 11L)), "not before the start");

        event(ChannelEventRow.STATUS_POSTED, -2 * HOUR, -HOUR);
        assertEquals("ALBUM_UPLOAD_FORBIDDEN", code(() -> service.addPhoto(MEMBER, EVENT, 11L)), "members who did not attend");
        when(mapper.countMyConfirmed(ACTIVITY, MEMBER)).thenReturn(1);
        when(media.status(11L, MEMBER)).thenThrow(new ServiceException("not yours"));
        assertEquals("ALBUM_ASSET_NOT_OWNED", code(() -> service.addPhoto(MEMBER, EVENT, 11L)));

        when(media.status(12L, MEMBER)).thenReturn(Map.of("reviewState", "PENDING"));
        doAnswer(inv -> {
            Map<String, Object> m = inv.getArgument(0);
            m.put("id", 77L);
            return 1;
        }).when(mapper).insertPhoto(anyMap());
        when(mapper.selectPhoto(77L)).thenReturn(Map.of("id", 77L, "eventId", EVENT, "memberId", MEMBER, "assetId", 12L));
        when(media.approvedUrl(12L)).thenThrow(new ServiceException("媒体尚未审核通过"));
        when(media.ownerPreview(12L, MEMBER)).thenReturn("https://preview/12");
        Map<String, Object> added = service.addPhoto(MEMBER, EVENT, 12L);
        assertEquals("PENDING", added.get("review_state"), "the photo waits in the existing media review");
        assertEquals("https://preview/12", added.get("url"));

        event(ChannelEventRow.STATUS_POSTED, -40 * 24 * HOUR, -32 * 24 * HOUR);
        assertEquals("ALBUM_UPLOAD_FORBIDDEN", code(() -> service.addPhoto(MEMBER, EVENT, 12L)), "closed 30 days after the end");
    }

    @Test
    void othersSeeOnlyApprovedPhotos()
    {
        Map<String, Object> pending = Map.of("id", 1L, "eventId", EVENT, "memberId", MEMBER, "assetId", 21L);
        Map<String, Object> approved = Map.of("id", 2L, "eventId", EVENT, "memberId", MEMBER, "assetId", 22L);
        when(media.approvedUrl(21L)).thenThrow(new ServiceException("媒体尚未审核通过"));
        when(media.approvedUrl(22L)).thenReturn("https://cdn/22");
        assertNull(service.photoView(pending, ADMIN));
        assertEquals("https://cdn/22", service.photoView(approved, ADMIN).get("url"));

        when(media.status(21L, MEMBER)).thenReturn(Map.of("reviewState", "REJECTED"));
        Map<String, Object> mine = service.photoView(pending, MEMBER);
        assertEquals("REJECTED", mine.get("review_state"));
        assertNull(mine.get("url"), "a rejected photo is never linked, even for its owner");
    }

    @Test
    void albumListIsForMembersAndAttendees()
    {
        event(ChannelEventRow.STATUS_POSTED, -2 * HOUR, -HOUR);
        when(mapper.selectPhotos(EVENT, false, null, ChannelEventService.PHOTO_PAGE)).thenReturn(new ArrayList<>());
        assertTrue(((List<?>) service.photos(MEMBER, EVENT, null).get("photos")).isEmpty());
        assertEquals("GUILD_FORBIDDEN", code(() -> service.photos(OUTSIDER, EVENT, null)));
    }

    @Test
    void uploaderHostAndAdminsMayRemoveAPhotoOtherMembersMayNot()
    {
        event(ChannelEventRow.STATUS_POSTED, -2 * HOUR, -HOUR);
        when(mapper.selectPhoto(5L)).thenReturn(Map.of("id", 5L, "eventId", EVENT, "memberId", MEMBER, "assetId", 22L));
        long other = 6L;
        when(access.standing(ChannelGuildAccess.GUILD_CLUB, CLUB, other)).thenReturn(GuildStanding.MEMBER);
        when(access.effective(channel, other)).thenReturn(ChannelPermissionCalculator.MEMBER_BASE);
        assertEquals("PHOTO_REMOVE_FORBIDDEN", code(() -> service.removePhoto(other, 5L)));
        service.removePhoto(MEMBER, 5L);
        service.removePhoto(ADMIN, 5L);
        service.removePhoto(OWNER, 5L);
        verify(mapper).setPhotoRemoved(5L, 1, MEMBER);
        verify(mapper).setPhotoRemoved(5L, 1, ADMIN);
        verify(mapper).setPhotoRemoved(5L, 1, OWNER);
    }

    @Test
    void adminConsoleRemovesAndRestores()
    {
        when(mapper.selectPhoto(5L)).thenReturn(Map.of("id", 5L));
        when(mapper.setPhotoRemoved(5L, 1, 100L)).thenReturn(1);
        when(mapper.setPhotoRemoved(5L, 0, null)).thenReturn(1);
        assertTrue(service.adminSetPhotoRemoved(5L, true, 100L));
        assertTrue(service.adminSetPhotoRemoved(5L, false, 100L));
        assertEquals("PHOTO_NOT_FOUND", code(() -> service.adminSetPhotoRemoved(6L, true, 100L)));
    }

    @Test
    void stateFollowsTheClock()
    {
        long now = System.currentTimeMillis();
        ChannelEventRow e = event(ChannelEventRow.STATUS_POSTED, HOUR, 2 * HOUR);
        assertEquals("UPCOMING", ChannelEventService.state(e, new Date(now)));
        assertEquals("LIVE", ChannelEventService.state(e, new Date(now + 90 * 60_000L)));
        assertEquals("ENDED", ChannelEventService.state(e, new Date(now + 3 * HOUR)));
        assertFalse(ChannelEventService.activityVisible(null));
        assertTrue(ChannelEventService.activityGone(null));
    }

    @Test
    void albumAcceptsTheMembersOwnUploadedImageUrl()
    {
        assertEquals("upload/0123456789abcdef0123456789abcdef.jpg",
                ChannelEventService.objectKey("https://cdn.example.com/upload/0123456789abcdef0123456789abcdef.jpg"));
        assertNull(ChannelEventService.objectKey("https://cdn.example.com/upload/0123456789abcdef0123456789abcdef.exe"));
        assertNull(ChannelEventService.objectKey("ftp://cdn/upload/0123456789abcdef0123456789abcdef.jpg"));
        assertNull(ChannelEventService.objectKey("https://u:p@cdn/upload/0123456789abcdef0123456789abcdef.jpg"));
        assertNull(ChannelEventService.objectKey(null));

        com.chengyinhub.business.mapper.AppMediaAssetMapper assets = mock(com.chengyinhub.business.mapper.AppMediaAssetMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "mediaAssets", assets);
        String url = "https://cdn.example.com/upload/0123456789abcdef0123456789abcdef.jpg";
        when(assets.selectByObjectKey("upload/0123456789abcdef0123456789abcdef.jpg")).thenReturn(Map.of("id", 31L, "memberId", OWNER));
        event(ChannelEventRow.STATUS_POSTED, -2 * HOUR, -HOUR);
        when(mapper.countMyConfirmed(ACTIVITY, MEMBER)).thenReturn(1);
        assertEquals("ALBUM_ASSET_NOT_OWNED", code(() -> service.addPhotoByUrl(MEMBER, EVENT, url)), "someone else's upload");
        assertEquals("ALBUM_ASSET_INVALID", code(() -> service.addPhotoByUrl(MEMBER, EVENT, "https://evil/x.jpg")));
    }
}
