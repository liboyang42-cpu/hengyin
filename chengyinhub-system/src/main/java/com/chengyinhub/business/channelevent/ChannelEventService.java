package com.chengyinhub.business.channelevent;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.channelevent.mapper.ChannelEventMapper;
import com.chengyinhub.business.domain.dto.ActivityTicketRequest;
import com.chengyinhub.business.domain.dto.CmsActivityPublishRequest;
import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.chengyinhub.business.moneyswitch.MoneyArea;
import com.chengyinhub.business.moneyswitch.MoneySwitchService;
import com.chengyinhub.business.service.IPushFanoutService;
import com.chengyinhub.business.service.channel.platform.ChannelCapability;
import com.chengyinhub.business.service.channel.platform.ChannelGuildAccess;
import com.chengyinhub.business.service.channel.platform.ChannelPermissionCalculator;
import com.chengyinhub.business.service.channel.platform.ChannelPermissionCalculator.GuildStanding;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.business.service.channel.platform.ChannelRow;
import com.chengyinhub.business.service.impl.AppMediaAssetService;
import com.chengyinhub.business.service.impl.InGameChannelService;

/**
 * C3: events posted in club channels.
 *
 * <p>An event is not a parallel system. Posting one publishes an ordinary club activity with one ticket through the
 * existing activity publish path (the controller runs {@code /api/activity/publish}: identity, quota, content safety,
 * review), then this service links the activity to the channel and posts an event card message into the channel's
 * conversation using the existing message model ({@code msg_type} 1 + {@code extra_json.cardType = "channel_event"};
 * old clients show the plain text). Enrolment, capacity (ticket stock), payment and refunds stay in the existing
 * registration path; "going" is a confirmed enrolment. Reminders come from the existing before-start job. The chat
 * thread under the card is the existing per-activity channel ({@code activity_<id>}). Album photos are app media
 * assets that go through the existing media review and show only once approved.
 */
@Service
public class ChannelEventService
{
    private static final Logger log = LoggerFactory.getLogger(ChannelEventService.class);

    public static final String CARD_TYPE = "channel_event";
    public static final String GOING = "GOING";
    public static final String MAYBE = "MAYBE";
    public static final String NO = "NO";
    static final int LIST_LIMIT = 50;
    static final int ATTENDEE_LIMIT = 200;
    static final int PHOTO_PAGE = 30;
    static final int PHOTOS_PER_MEMBER = 30;
    /** The album stays open for uploads this long after the event ends. */
    static final long ALBUM_UPLOAD_WINDOW_MS = 30L * 24 * 3600 * 1000;

    @Autowired
    private ChannelEventMapper mapper;
    @Autowired
    private ChannelPlatformMapper channels;
    @Autowired
    private ChannelGuildAccess access;
    @Autowired(required = false)
    private MoneySwitchService moneySwitch;
    @Autowired(required = false)
    @Lazy
    private IPushFanoutService pushFanout;
    @Autowired(required = false)
    @Lazy
    private InGameChannelService inGameChannels;
    @Autowired(required = false)
    @Lazy
    private AppMediaAssetService media;

    public ChannelEventService() { }

    ChannelEventService(ChannelEventMapper mapper, ChannelPlatformMapper channels, ChannelGuildAccess access,
            MoneySwitchService moneySwitch, InGameChannelService inGameChannels, AppMediaAssetService media)
    {
        this.mapper = mapper;
        this.channels = channels;
        this.access = access;
        this.moneySwitch = moneySwitch;
        this.inGameChannels = inGameChannels;
        this.media = media;
    }

    /** Where an accepted post goes: the channel and its club. */
    public record PostTarget(long channelId, long clubId) { }

    // ------------------------------------------------------------------ posting

    /**
     * Checks everything that does not need the activity yet: a live text channel of a club, the poster's right to
     * post an event there, and, for a paid event, the money switch. Throws {@link ChannelRejection} when refused.
     */
    public PostTarget preparePost(long me, long channelId, ChannelEventDraft draft)
    {
        ChannelRow row = liveChannel(channelId);
        GuildStanding standing = access.standing(row.getGuildKind(), row.getGuildId(), me);
        if (standing == GuildStanding.NONE) throw new ChannelRejection(403, "GUILD_FORBIDDEN", "加入俱乐部后才能发活动");
        long bits = access.effective(row, me);
        if (!ChannelPermissionCalculator.has(bits, ChannelCapability.SEND_MESSAGE))
            throw new ChannelRejection(403, "EVENT_POST_FORBIDDEN", "你在这个频道不能发消息");
        if (!mayPostEvents(standing, bits))
            throw new ChannelRejection(403, "EVENT_POST_FORBIDDEN", "只有俱乐部主理人可以在频道发活动");
        if (draft.paid() && !paidEventsOpen())
            throw new ChannelRejection(409, "PAID_EVENTS_OFF", "收费活动暂未开放，可以先发免费活动");
        return new PostTarget(row.getId(), row.getGuildId());
    }

    /**
     * Only the club owner may post: the event is a club activity, and the existing activity publish path accepts only
     * the club's owner as its host (quota, settlement and refunds are the owner's). C1's roles decide who may send in
     * the channel ({@code SEND_MESSAGE}); letting a role host events needs the owner to decide who carries the activity.
     */
    static boolean mayPostEvents(GuildStanding standing, long bits)
    {
        return standing == GuildStanding.OWNER;
    }

    boolean paidEventsOpen()
    {
        if (moneySwitch == null) return false;
        try
        {
            return Boolean.TRUE.equals(moneySwitch.snapshot().get(MoneyArea.TICKETS.key()));
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    /** The request handed to the existing activity publish path: a club activity with one ticket. */
    public static CmsActivityPublishRequest toPublishRequest(PostTarget target, ChannelEventDraft d)
    {
        CmsActivityPublishRequest r = new CmsActivityPublishRequest();
        if (d.requestId() != null) r.setRequestId("chev-" + d.requestId());
        r.setName(d.title());
        r.setDescription(d.description() == null ? d.title() : d.description());
        r.setAddressName(d.placeName());
        r.setAddress(d.address() == null ? d.placeName() : d.address());
        r.setLatitude(d.lat().toPlainString());
        r.setLongitude(d.lng().toPlainString());
        r.setStartDate(d.startAt());
        r.setEndDate(d.endAt());
        r.setClubId(target.clubId());
        ActivityTicketRequest t = new ActivityTicketRequest();
        t.setName(d.paid() ? "活动票" : "免费报名");
        t.setPrice(d.price());
        t.setStartTime(d.startAt());
        t.setEndTime(d.endAt());
        t.setTotalStock(d.capacity());
        t.setMode(1);
        t.setMeetingPoint(d.placeName());
        t.setMeetingPointAddress(d.address() == null ? d.placeName() : d.address());
        t.setGatherLat(d.lat());
        t.setGatherLng(d.lng());
        t.setMaxPeople(d.capacity());
        t.setSignupDeadline(d.startAt());
        t.setNeedAudit(0);
        r.setTickets(List.of(t));
        return r;
    }

    /**
     * Links the freshly published activity to the channel and posts the card when the activity is already visible.
     * An activity held for review gets its card on a later read once it is approved ({@link #syncWaiting}).
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> attach(long me, PostTarget target, ChannelEventDraft d, long activityId)
    {
        ChannelEventRow existing = mapper.selectByActivity(activityId);
        if (existing != null) return view(existing, me, true);
        Map<String, Object> state = mapper.selectActivityState(activityId);
        if (state == null || toLong(state.get("memberId")) != me || toLong(state.get("clubId")) != target.clubId())
            throw new ChannelRejection(409, "EVENT_ACTIVITY_MISMATCH", "活动与频道不匹配");
        ChannelEventRow row = new ChannelEventRow();
        row.setClubId(target.clubId());
        row.setChannelId(target.channelId());
        row.setActivityId(activityId);
        row.setTicketId(mapper.selectFirstTicketId(activityId));
        row.setPosterMemberId(me);
        row.setTitle(d.title());
        row.setStartAt(d.startAt());
        row.setEndAt(d.endAt());
        row.setPlaceName(d.placeName());
        row.setLat(d.lat());
        row.setLng(d.lng());
        row.setCapacity(d.capacity());
        row.setPaid(d.paid() ? 1 : 0);
        row.setStatus(ChannelEventRow.STATUS_WAITING_REVIEW);
        mapper.insertEvent(row);
        if (activityVisible(state)) postCard(row);
        ensureThreadMember(row, me);
        return view(mapper.selectEvent(row.getId()), me, true);
    }

    /** Posts cards for events whose activity passed review; closes those whose activity was refused or removed. */
    public void syncWaiting(long channelId)
    {
        for (ChannelEventRow row : mapper.selectWaitingByChannel(channelId))
        {
            Map<String, Object> state = mapper.selectActivityState(row.getActivityId());
            if (activityVisible(state)) postCard(row);
            else if (activityGone(state)) mapper.markCancelled(row.getId());
        }
    }

    static boolean activityVisible(Map<String, Object> state)
    {
        return state != null && toLong(state.get("status")) == 1 && toLong(state.get("publishStatus")) == 1
                && toLong(state.get("delFlag")) == 0 && state.get("cancelTime") == null;
    }

    /** Refused in review (status 2), deleted or cancelled. */
    static boolean activityGone(Map<String, Object> state)
    {
        return state == null || toLong(state.get("delFlag")) != 0 || toLong(state.get("status")) == 2 || state.get("cancelTime") != null;
    }

    void postCard(ChannelEventRow row)
    {
        Map<String, Object> bridge = mapper.selectChannelConversation(row.getChannelId());
        if (bridge == null || bridge.get("conversationId") == null || toLong(bridge.get("channelStatus")) != 0) return;
        long conversationId = toLong(bridge.get("conversationId"));
        String text = cardText(row);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("conversationId", conversationId);
        message.put("senderId", row.getPosterMemberId());
        message.put("content", text);
        message.put("extraJson", cardExtra(row).toJSONString());
        message.put("clientMessageId", "chev-" + row.getId());
        if (mapper.insertCardMessage(message) != 1) throw new IllegalStateException("Event card insert failed");
        long messageId = toLong(message.get("id"));
        if (mapper.markPosted(row.getId(), messageId) != 1) throw new IllegalStateException("Event card already posted");
        row.setMessageId(messageId);
        row.setStatus(ChannelEventRow.STATUS_POSTED);
        mapper.touchConversation(conversationId, text.length() > 200 ? text.substring(0, 200) : text);
        mapper.fanOutUnread(conversationId, row.getPosterMemberId());
        pushAfterCommit(messageId);
    }

    private void pushAfterCommit(long messageId)
    {
        if (pushFanout == null) return;
        Runnable push = () -> {
            try
            {
                pushFanout.chatMessageSent(messageId);
            }
            catch (RuntimeException e)
            {
                log.warn("频道活动卡片推送登记失败 messageId={}", messageId);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive())
        {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
            {
                @Override
                public void afterCommit() { push.run(); }
            });
        }
        else push.run();
    }

    /** Plain text old clients and notifications show; the card itself is drawn from {@code extra_json}. */
    static String cardText(ChannelEventRow row)
    {
        return "【活动】" + row.getTitle() + " · " + format(row.getStartAt()) + " · " + row.getPlaceName();
    }

    /**
     * The card payload. Shares the field names of the in-game {@code game_event} cards where they overlap
     * ({@code cardType}, {@code eventType}, {@code activityId}, {@code target}) so one card view can draw both.
     * Counts are not frozen here: the card reads them live from {@code /api/channel-events/{id}}.
     */
    static JSONObject cardExtra(ChannelEventRow row)
    {
        JSONObject extra = new JSONObject();
        extra.put("cardType", CARD_TYPE);
        extra.put("eventType", "event");
        extra.put("eventId", String.valueOf(row.getId()));
        extra.put("activityId", row.getActivityId());
        extra.put("title", row.getTitle());
        extra.put("target", row.getPlaceName());
        extra.put("startAt", row.getStartAt() == null ? null : row.getStartAt().getTime());
        extra.put("endAt", row.getEndAt() == null ? null : row.getEndAt().getTime());
        extra.put("capacity", row.getCapacity());
        extra.put("paid", row.isPaid());
        return extra;
    }

    // ------------------------------------------------------------------ reads

    public Map<String, Object> list(long me, long channelId)
    {
        ChannelRow row = viewableChannel(me, channelId);
        syncWaiting(row.getId());
        List<Map<String, Object>> items = new ArrayList<>();
        for (ChannelEventRow e : mapper.selectByChannel(row.getId(), new Date(), LIST_LIMIT))
        {
            if (e.getStatus() == ChannelEventRow.STATUS_WAITING_REVIEW && e.getPosterMemberId() != me) continue;
            items.add(view(e, me, false));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("channel_id", String.valueOf(row.getId()));
        data.put("can_post", canPost(me, row));
        data.put("paid_events_open", paidEventsOpen());
        data.put("events", items);
        return data;
    }

    public Map<String, Object> detail(long me, long eventId)
    {
        ChannelEventRow e = readableEvent(me, eventId);
        return view(e, me, true);
    }

    boolean canPost(long me, ChannelRow row)
    {
        try
        {
            GuildStanding standing = access.standing(row.getGuildKind(), row.getGuildId(), me);
            long bits = access.effective(row, me);
            return ChannelPermissionCalculator.has(bits, ChannelCapability.SEND_MESSAGE) && mayPostEvents(standing, bits);
        }
        catch (ChannelRejection e)
        {
            return false;
        }
    }

    Map<String, Object> view(ChannelEventRow e, long me, boolean full)
    {
        Date now = new Date();
        long activityId = e.getActivityId();
        int going = mapper.countGoing(activityId);
        int maybe = mapper.countRsvpWithoutEnrolment(e.getId(), activityId, MAYBE);
        int no = mapper.countRsvpWithoutEnrolment(e.getId(), activityId, NO);
        boolean enrolled = mapper.countMyConfirmed(activityId, me) > 0;
        String rsvp = enrolled ? GOING : mapper.selectMyRsvp(e.getId(), me);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", String.valueOf(e.getId()));
        v.put("channel_id", String.valueOf(e.getChannelId()));
        v.put("club_id", String.valueOf(e.getClubId()));
        v.put("activity_id", String.valueOf(activityId));
        v.put("ticket_id", e.getTicketId() == null ? null : String.valueOf(e.getTicketId()));
        v.put("message_id", e.getMessageId() == null ? null : String.valueOf(e.getMessageId()));
        v.put("title", e.getTitle());
        v.put("start_at", e.getStartAt() == null ? null : e.getStartAt().getTime());
        v.put("end_at", e.getEndAt() == null ? null : e.getEndAt().getTime());
        v.put("place_name", e.getPlaceName());
        v.put("lat", e.getLat());
        v.put("lng", e.getLng());
        v.put("capacity", e.getCapacity());
        v.put("paid", e.isPaid());
        v.put("state", state(e, now));
        v.put("going_count", going);
        v.put("maybe_count", maybe);
        v.put("no_count", no);
        v.put("spots_left", Math.max(0, (e.getCapacity() == null ? 0 : e.getCapacity()) - going));
        v.put("my_rsvp", rsvp);
        v.put("enrolled", enrolled);
        if (full)
        {
            Map<String, Object> mine = mapper.selectMyOpenRegistration(activityId, me);
            v.put("enrolment_waiting_payment", !enrolled && mine != null && toLong(mine.get("registrationStatus")) == 1);
            boolean host = isHost(e, me);
            v.put("is_host", host);
            v.put("can_check_in", host);
            v.put("can_upload_photos", canUpload(e, me, enrolled, now));
            v.put("can_moderate_album", host || isClubAdmin(e, me));
        }
        return v;
    }

    static String state(ChannelEventRow e, Date now)
    {
        if (e.getStatus() != null && e.getStatus() == ChannelEventRow.STATUS_CANCELLED) return "CANCELLED";
        if (e.getStatus() != null && e.getStatus() == ChannelEventRow.STATUS_WAITING_REVIEW) return "IN_REVIEW";
        if (now.before(e.getStartAt())) return "UPCOMING";
        if (now.before(e.getEndAt())) return "LIVE";
        return "ENDED";
    }

    // ------------------------------------------------------------------ RSVP

    /**
     * Going / maybe / no. Going needs a confirmed enrolment through the existing registration path (capacity, consent,
     * payment). Without one this refuses with {@code ENROL_REQUIRED} and tells the app what to enrol in; the app
     * enrols with {@code /api/registration/create} and calls again. Saying no keeps an existing enrolment (refund rules
     * live in the cancel path); the response says so.
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> rsvp(long me, long eventId, String rawStatus)
    {
        String status = rawStatus == null ? "" : rawStatus.strip().toUpperCase(Locale.ROOT);
        if (!GOING.equals(status) && !MAYBE.equals(status) && !NO.equals(status))
            throw new ChannelRejection(400, "RSVP_INVALID", "请选择去、可能去或不去");
        ChannelEventRow e = readableEvent(me, eventId);
        String st = state(e, new Date());
        if (!"UPCOMING".equals(st) && !"LIVE".equals(st))
            throw new ChannelRejection(409, "EVENT_CLOSED", "这场活动已结束或暂不能报名");
        boolean enrolled = mapper.countMyConfirmed(e.getActivityId(), me) > 0;
        if (GOING.equals(status) && !enrolled)
        {
            ChannelRejection r = new ChannelRejection(409, "ENROL_REQUIRED", e.isPaid() ? "购票后即算参加" : "报名后即算参加");
            throw r;
        }
        mapper.upsertRsvp(e.getId(), me, status);
        if (GOING.equals(status) || MAYBE.equals(status)) ensureThreadMember(e, me);
        Map<String, Object> v = view(mapper.selectEvent(e.getId()), me, true);
        v.put("still_enrolled", NO.equals(status) && enrolled);
        return v;
    }

    /** What the app needs to enrol with the existing registration path after an {@code ENROL_REQUIRED} refusal. */
    public Map<String, Object> enrolTarget(long me, long eventId)
    {
        ChannelEventRow e = readableEvent(me, eventId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("owner_type", 2);
        out.put("owner_id", String.valueOf(e.getActivityId()));
        out.put("ticket_id", e.getTicketId() == null ? null : String.valueOf(e.getTicketId()));
        out.put("paid", e.isPaid());
        out.put("paid_events_open", paidEventsOpen());
        Integer remaining = e.getTicketId() == null ? null : mapper.selectRemaining(e.getTicketId());
        out.put("remaining", remaining);
        return out;
    }

    public Map<String, Object> attendees(long me, long eventId)
    {
        ChannelEventRow e = readableEvent(me, eventId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("going", people(mapper.selectGoingMembers(e.getActivityId(), ATTENDEE_LIMIT)));
        out.put("maybe", people(mapper.selectMaybeMembers(e.getId(), e.getActivityId(), ATTENDEE_LIMIT)));
        return out;
    }

    private static List<Map<String, Object>> people(List<Map<String, Object>> rows)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows == null) return out;
        for (Map<String, Object> r : rows)
        {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("member_id", String.valueOf(toLong(r.get("memberId"))));
            p.put("nickname", r.get("nickname"));
            Object avatar = r.get("avatar");
            p.put("avatar", avatar != null && avatar.toString().matches("(?i)https?://.+") ? avatar : null);
            out.add(p);
        }
        return out;
    }

    /** The chat thread under the card: the existing activity channel. Opening it joins the viewer. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> thread(long me, long eventId)
    {
        ChannelEventRow e = readableEvent(me, eventId);
        Long conversationId = ensureThreadMember(e, me);
        if (conversationId == null) throw new ChannelRejection(409, "THREAD_UNAVAILABLE", "活动讨论暂不可用");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("conversation_id", String.valueOf(conversationId));
        out.put("conversation_type", 2);
        out.put("title", e.getTitle());
        return out;
    }

    private Long ensureThreadMember(ChannelEventRow e, long me)
    {
        if (inGameChannels == null) return null;
        return inGameChannels.ensureActivityThreadMember(e.getActivityId(), me);
    }

    // ------------------------------------------------------------------ reminders

    /**
     * MAYBE members of a channel event on this ticket. The existing before-start job already reminds enrolled
     * (going) members; it also reminds these through the same push fan-out (one push per ticket and member).
     */
    public List<Long> maybeMembersForReminder(long ticketId)
    {
        List<Long> ids = mapper.selectMaybeMemberIdsByTicket(ticketId);
        return ids == null ? Collections.emptyList() : ids;
    }

    // ------------------------------------------------------------------ album (CLB-12)

    public Map<String, Object> photos(long me, long eventId, Long beforeId)
    {
        ChannelEventRow e = readableEvent(me, eventId);
        List<Map<String, Object>> items = new ArrayList<>();
        Long next = null;
        int scanned = 0;
        for (Map<String, Object> p : mapper.selectPhotos(e.getId(), false, beforeId, PHOTO_PAGE))
        {
            scanned++;
            next = toLong(p.get("id"));
            Map<String, Object> item = photoView(p, me);
            if (item != null) items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("photos", items);
        out.put("next_before_id", scanned == PHOTO_PAGE && next != null ? String.valueOf(next) : null);
        out.put("can_upload", canUpload(e, me, mapper.countMyConfirmed(e.getActivityId(), me) > 0, new Date()));
        return out;
    }

    /** Approved photos for everyone allowed to read; the uploader also sees their own photo while it is in review. */
    Map<String, Object> photoView(Map<String, Object> p, long me)
    {
        long assetId = toLong(p.get("assetId"));
        boolean mine = toLong(p.get("memberId")) == me;
        String url = null;
        String review = "APPROVED";
        if (media != null)
        {
            try
            {
                url = media.approvedUrl(assetId);
            }
            catch (RuntimeException notApproved)
            {
                if (!mine) return null;
                try
                {
                    review = String.valueOf(media.status(assetId, me).get("reviewState"));
                    if (!"REJECTED".equals(review)) url = media.ownerPreview(assetId, me);
                }
                catch (RuntimeException gone)
                {
                    return null;
                }
            }
        }
        if (url == null && !mine) return null;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", String.valueOf(toLong(p.get("id"))));
        item.put("member_id", String.valueOf(toLong(p.get("memberId"))));
        item.put("url", url);
        item.put("review_state", review);
        item.put("mine", mine);
        return item;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> addPhoto(long me, long eventId, long assetId)
    {
        ChannelEventRow e = readableEvent(me, eventId);
        boolean enrolled = mapper.countMyConfirmed(e.getActivityId(), me) > 0;
        if (!canUpload(e, me, enrolled, new Date()))
            throw new ChannelRejection(403, "ALBUM_UPLOAD_FORBIDDEN", "活动开始后，参加的人可以上传照片");
        if (assetId <= 0) throw new ChannelRejection(400, "ALBUM_ASSET_INVALID", "照片无效，请重新上传");
        if (media == null) throw new ChannelRejection(409, "ALBUM_UNAVAILABLE", "相册暂不可用");
        try
        {
            media.status(assetId, me); // must be my own upload; it goes through the existing media review
        }
        catch (RuntimeException notMine)
        {
            throw new ChannelRejection(403, "ALBUM_ASSET_NOT_OWNED", "只能上传自己的照片");
        }
        if (mapper.countMyPhotos(e.getId(), me) >= PHOTOS_PER_MEMBER)
            throw new ChannelRejection(409, "ALBUM_LIMIT", "每人最多上传 30 张");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("eventId", e.getId());
        row.put("memberId", me);
        row.put("assetId", assetId);
        try
        {
            mapper.insertPhoto(row);
        }
        catch (RuntimeException dup)
        {
            if (!ChannelRejection.isDuplicateKey(dup)) throw dup;
            throw new ChannelRejection(409, "ALBUM_DUPLICATE", "这张照片已在相册里");
        }
        Map<String, Object> p = mapper.selectPhoto(toLong(row.get("id")));
        Map<String, Object> view = photoView(p, me);
        return view == null ? Map.of("id", String.valueOf(toLong(row.get("id")))) : view;
    }

    /** The uploader, the host and club admins may remove a photo (members' moderation). */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> removePhoto(long me, long photoId)
    {
        Map<String, Object> p = mapper.selectPhoto(photoId);
        if (p == null) throw new ChannelRejection(404, "PHOTO_NOT_FOUND", "照片不存在");
        ChannelEventRow e = readableEvent(me, toLong(p.get("eventId")));
        boolean allowed = toLong(p.get("memberId")) == me || isHost(e, me) || isClubAdmin(e, me);
        if (!allowed) throw new ChannelRejection(403, "PHOTO_REMOVE_FORBIDDEN", "只能移除自己的照片");
        mapper.setPhotoRemoved(photoId, 1, me);
        return Map.of("id", String.valueOf(photoId), "removed", true);
    }

    boolean canUpload(ChannelEventRow e, long me, boolean enrolled, Date now)
    {
        if (e.getStatus() == null || e.getStatus() != ChannelEventRow.STATUS_POSTED) return false;
        if (now.before(e.getStartAt())) return false;
        if (now.getTime() > e.getEndAt().getTime() + ALBUM_UPLOAD_WINDOW_MS) return false;
        return enrolled || isHost(e, me);
    }

    // ------------------------------------------------------------------ admin console

    public List<Map<String, Object>> adminEvents(Long clubId, Long beforeId)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChannelEventRow e : mapper.selectForAdmin(clubId, beforeId, LIST_LIMIT))
        {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", e.getId());
            v.put("clubId", e.getClubId());
            v.put("channelId", e.getChannelId());
            v.put("activityId", e.getActivityId());
            v.put("title", e.getTitle());
            v.put("startAt", e.getStartAt());
            v.put("endAt", e.getEndAt());
            v.put("placeName", e.getPlaceName());
            v.put("capacity", e.getCapacity());
            v.put("paid", e.isPaid());
            v.put("state", state(e, new Date()));
            v.put("goingCount", mapper.countGoing(e.getActivityId()));
            v.put("maybeCount", mapper.countRsvpWithoutEnrolment(e.getId(), e.getActivityId(), MAYBE));
            v.put("posterMemberId", e.getPosterMemberId());
            out.add(v);
        }
        return out;
    }

    /** Every photo (removed and in review included) with a reviewer preview link. */
    public List<Map<String, Object>> adminPhotos(long eventId)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : mapper.selectPhotos(eventId, true, null, 200))
        {
            Map<String, Object> v = new LinkedHashMap<>(p);
            long assetId = toLong(p.get("assetId"));
            if (media != null)
            {
                try
                {
                    v.put("previewUrl", media.reviewerPreview(assetId));
                }
                catch (RuntimeException e)
                {
                    v.put("previewUrl", null);
                }
            }
            out.add(v);
        }
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean adminSetPhotoRemoved(long photoId, boolean removed, Long adminUserId)
    {
        if (mapper.selectPhoto(photoId) == null) throw new ChannelRejection(404, "PHOTO_NOT_FOUND", "照片不存在");
        return mapper.setPhotoRemoved(photoId, removed ? 1 : 0, removed ? adminUserId : null) == 1;
    }

    // ------------------------------------------------------------------ access

    private ChannelRow liveChannel(long channelId)
    {
        ChannelRow row = channelId <= 0 ? null : channels.selectChannel(channelId);
        if (row == null || row.getStatus() == null || row.getStatus() == 4) throw ChannelRejection.notFound();
        if (row.getGuildKind() == null || row.getGuildKind() != ChannelGuildAccess.GUILD_CLUB)
            throw new ChannelRejection(409, "EVENT_CLUB_ONLY", "只有俱乐部频道可以发活动");
        if (row.isCategory() || row.getStatus() != 0) throw new ChannelRejection(409, "CHANNEL_NOT_ACTIVE", "频道已归档，不能发活动");
        return row;
    }

    /** Club members who can see the channel. Not visible → 404, never leaking existence. */
    private ChannelRow viewableChannel(long me, long channelId)
    {
        ChannelRow row = channelId <= 0 ? null : channels.selectChannel(channelId);
        if (row == null || row.getStatus() == null || row.getStatus() == 4) throw ChannelRejection.notFound();
        access.requireMember(row.getGuildKind(), row.getGuildId(), me);
        if (row.isCategory() || row.getStatus() > 1
                || !ChannelPermissionCalculator.has(access.effective(row, me), ChannelCapability.VIEW_CHANNEL))
            throw ChannelRejection.notFound();
        return row;
    }

    /**
     * Readable by members who can see the channel, and by anyone holding a confirmed enrolment (an attendee who is
     * not a club member still sees the event, its thread and its album). An event still in review only by its poster.
     */
    ChannelEventRow readableEvent(long me, long eventId)
    {
        ChannelEventRow e = eventId <= 0 ? null : mapper.selectEvent(eventId);
        if (e == null) throw new ChannelRejection(404, "EVENT_NOT_FOUND", "活动不存在");
        if (e.getStatus() != null && e.getStatus() == ChannelEventRow.STATUS_WAITING_REVIEW)
        {
            syncWaiting(e.getChannelId());
            e = mapper.selectEvent(eventId);
            if (e.getStatus() == ChannelEventRow.STATUS_WAITING_REVIEW && e.getPosterMemberId() != me)
                throw new ChannelRejection(404, "EVENT_NOT_FOUND", "活动不存在");
        }
        if (mapper.countMyConfirmed(e.getActivityId(), me) > 0) return e;
        viewableChannel(me, e.getChannelId());
        return e;
    }

    boolean isHost(ChannelEventRow e, long me)
    {
        return e.getPosterMemberId() != null && e.getPosterMemberId() == me;
    }

    boolean isClubAdmin(ChannelEventRow e, long me)
    {
        try
        {
            GuildStanding s = access.standing(ChannelGuildAccess.GUILD_CLUB, e.getClubId(), me);
            return s == GuildStanding.OWNER || s == GuildStanding.ADMIN;
        }
        catch (ChannelRejection gone)
        {
            return false;
        }
    }

    // ------------------------------------------------------------------ helpers

    static String format(Date d)
    {
        if (d == null) return "";
        SimpleDateFormat f = new SimpleDateFormat("M月d日 HH:mm");
        f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return f.format(d);
    }

    static long toLong(Object value)
    {
        if (value == null) return 0L;
        if (value instanceof Number n) return n.longValue();
        if (value instanceof Boolean b) return b ? 1L : 0L;
        if (value instanceof BigDecimal b) return b.longValue();
        try
        {
            return Long.parseLong(value.toString().strip());
        }
        catch (NumberFormatException e)
        {
            return 0L;
        }
    }
}
