package com.chengyinhub.business.push;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.mapper.push.PushMapper;
import com.chengyinhub.business.service.IChannelConversationGate;
import com.chengyinhub.business.service.IPushFanoutService;
import com.chengyinhub.common.core.redis.RedisCache;

/**
 * 远程推送扇出：聊天/频道消息与 @、俱乐部通知（APNS 渠道）、出发前提醒。
 * <ul>
 * <li>调用方线程只登记“提交后执行”，读偏好、查设备、发 APNs 都在线程池里，绝不阻塞请求或回滚业务。</li>
 * <li>偏好与静音沿用 {@link PushPolicy}；同一 (会员, thread) 30 秒内最多推一条，apns-collapse-id = thread。</li>
 * <li>未配置 APNs 时全部空操作（不查库）。</li>
 * </ul>
 */
@Service
public class PushFanoutService implements IPushFanoutService
{
    private static final Logger log = LoggerFactory.getLogger(PushFanoutService.class);
    static final long CHAT_EXPIRATION_SECONDS = 24L * 3600L;
    static final long NOTICE_EXPIRATION_SECONDS = 3L * 24L * 3600L;
    static final String CHAT_PAGE = "/subpackageB/pages/im/chat/index";
    /** SOC-2: audience pages (keyset on member_id) instead of one LIMIT 2000; 200 pages = 100k members at most. */
    static final int AUDIENCE_PAGE = 500;
    static final int AUDIENCE_MAX_PAGES = 200;
    /** Same value as ChannelEventService.CARD_TYPE (kept here to avoid a push → channelevent dependency). */
    static final String EVENT_CARD_TYPE = "channel_event";

    private final PushMapper mapper;
    private final ApnsSender sender;
    private final PushCollapseWindow collapse;
    private final PushCollapseWindow reminderFallback = new PushCollapseWindow(48L * 3600L * 1000L, System::currentTimeMillis);

    @Autowired(required = false)
    @Qualifier("threadPoolTaskExecutor")
    private Executor executor;

    @Autowired(required = false)
    private IChannelConversationGate channelGate;

    @Autowired(required = false)
    private RedisCache redisCache;

    /**
     * In-game channels (activity/topic/roam/CITY team): the same live access check the IM read/send paths use
     * (security r3 HIGH-S3). A conversation row alone is not enough, e.g. after leaving a CITY team.
     */
    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.chengyinhub.business.service.impl.InGameChannelService inGameChannels;

    void setInGameChannels(com.chengyinhub.business.service.impl.InGameChannelService inGameChannels)
    {
        this.inGameChannels = inGameChannels;
    }

    @Autowired
    public PushFanoutService(PushMapper mapper, ApnsSender sender)
    {
        this(mapper, sender, new PushCollapseWindow());
    }

    public PushFanoutService(PushMapper mapper, ApnsSender sender, PushCollapseWindow collapse)
    {
        this.mapper = mapper;
        this.sender = sender;
        this.collapse = collapse;
    }

    @Override
    public boolean apnsConfigured()
    {
        return sender.configured();
    }

    // ================================================================ chat

    @Override
    public void chatMessageSent(Long messageId)
    {
        if (messageId == null || messageId <= 0 || !sender.configured()) return;
        afterCommit(() -> fanOutChat(messageId));
    }

    /** 同步扇出（已在线程池里）。返回被 APNs 接受的设备数。 */
    int fanOutChat(long messageId)
    {
        Map<String, Object> msg = mapper.selectChatMessage(messageId);
        if (msg == null || PushPolicy.integer(msg.get("status")) == null || PushPolicy.integer(msg.get("status")) != ImMessage.STATUS_NORMAL
                || !Integer.valueOf(1).equals(PushPolicy.integer(msg.get("checkStatus")))) return 0;
        long conversationId = ((Number) msg.get("conversationId")).longValue();
        long senderId = ((Number) msg.get("senderId")).longValue();
        int type = PushPolicy.integer(msg.get("conversationType")) == null ? 0 : PushPolicy.integer(msg.get("conversationType"));
        if (type == 2 || type == 3) return 0; // 系统/商家会话不走聊天推送
        boolean single = type == 1;
        boolean channel = type == IChannelConversationGate.CONVERSATION_TYPE_CHANNEL;
        String bizKey = (String) msg.get("bizKey");
        com.chengyinhub.business.domain.ImConversation inGame = null;
        if (!single && com.chengyinhub.business.service.impl.InGameChannelService.isInGameBizKey(bizKey))
        {
            inGame = new com.chengyinhub.business.domain.ImConversation();
            inGame.setId(conversationId);
            inGame.setBizKey(bizKey);
            inGame.setStatus(PushPolicy.integer(msg.get("conversationStatus")));
        }
        // SOC-2/SOC-9: one read of the channel's name, guild and default level (tolerated when it fails).
        ChannelContext ctx = channel ? channelContext(conversationId) : null;
        Integer clubDefault = null;
        Long clubId = channel ? (ctx != null ? ctx.clubId() : mapper.selectChannelClubId(conversationId)) : clubIdOf((String) msg.get("bizKey"));
        if (clubId != null)
        {
            Integer level = mapper.selectClubDefaultLevel(clubId);
            clubDefault = level == null ? 1 : level;
        }
        Integer channelDefault = ctx == null ? null : ctx.notifyDefault();
        boolean everyone = false;
        List<Long> mentioned = new ArrayList<>();
        List<Long> roles = new ArrayList<>();
        for (Map<String, Object> m : mapper.selectMentions(messageId))
        {
            Object kind = m.get("mentionType");
            if ("EVERYONE".equals(kind)) everyone = true;
            // W5 C2：回复别人也算提到他；@角色 展开成角色成员（只在频道里写入）。
            else if (("USER".equals(kind) || "REPLY".equals(kind)) && m.get("targetId") instanceof Number n) mentioned.add(n.longValue());
            else if ("ROLE".equals(kind) && m.get("targetId") instanceof Number n) roles.add(n.longValue());
        }
        if (!roles.isEmpty())
        {
            List<Long> roleMembers = mapper.selectRoleMemberIds(roles);
            if (roleMembers != null) mentioned.addAll(roleMembers);
        }
        // SOC-2: an event card in a channel pings like @everyone (members who switched @everyone off still opt out).
        // Only a real posted card counts (its id is in channel_event.message_id): a member cannot fake one in extra_json.
        JSONObject card = channel ? eventCard(msg.get("extraJson")) : null;
        if (card != null && !postedEventCard(messageId)) card = null;
        if (card != null) everyone = true;
        String thread = "chat-" + conversationId;
        String page = CHAT_PAGE + "?conversationId=" + conversationId + "&type=" + type + "&messageId=" + messageId;
        // SOC-9: channel pushes carry the guild so the app can open the channel screen at this message (additive keys).
        if (ctx != null && ctx.guildKind() > 0 && ctx.guildId() > 0) page += "&guildKind=" + ctx.guildKind() + "&guildId=" + ctx.guildId();
        String senderName = msg.get("senderName") == null ? null : msg.get("senderName").toString();
        String text = summary(msg);
        int accepted = 0;
        long after = 0;
        for (int pageNo = 0; pageNo < AUDIENCE_MAX_PAGES; pageNo++)
        {
            List<Map<String, Object>> audience = mapper.selectChatAudience(conversationId, senderId, single, after, AUDIENCE_PAGE);
            if (audience == null || audience.isEmpty()) break;
            Map<Long, Map<String, Object>> allowed = new LinkedHashMap<>();
            Map<Long, Boolean> mentionOf = new LinkedHashMap<>();
            for (Map<String, Object> row : audience)
            {
                long member = ((Number) row.get("memberId")).longValue();
                after = Math.max(after, member);
                boolean me = mentioned.contains(member);
                if (!PushPolicy.chatAllowed(row, me, everyone, channelDefault, clubDefault)) continue;
                if (channel && !canRead(member, conversationId)) continue;
                if (inGame != null && !inGameAccess(inGame, member)) continue;
                allowed.put(member, row);
                // An event card is announced, not "mentioned": no [有人@你] prefix for it.
                mentionOf.put(member, me || (card == null && everyone && !PushPolicy.flag(row.get("suppressBroadcast"))));
            }
            for (Map.Entry<Long, List<PushDevice>> e : devicesByMember(allowed.keySet()).entrySet())
            {
                long member = e.getKey();
                if (!collapse.tryAcquire(member, thread)) continue;
                boolean preview = PushPolicy.showPreview(allowed.get(member));
                boolean mention = Boolean.TRUE.equals(mentionOf.get(member));
                for (PushDevice device : e.getValue())
                {
                    boolean en = device.english();
                    String name = senderName == null || senderName.isBlank() ? (en ? "Chengyin" : "城瘾") : senderName;
                    String title;
                    String body;
                    if (!preview)
                    {
                        // Privacy: previews off keep the generic title and body (no club, channel or sender).
                        title = single ? name : (en ? "Group message" : "群消息");
                        body = en ? "You have a new message" : "你收到一条新消息";
                    }
                    else if (ctx != null)
                    {
                        title = ctx.guildName() == null || ctx.guildName().isBlank() ? (en ? "Group message" : "群消息") : ctx.guildName();
                        body = channelBody(ctx, card, name, text, en);
                    }
                    else
                    {
                        title = single ? name : (en ? "Group message" : "群消息");
                        body = (single ? "" : name + (en ? ": " : "：")) + (text == null ? (en ? "[Message]" : "[消息]") : text);
                    }
                    if (mention) body = (en ? "[Mentioned you] " : "[有人@你] ") + body;
                    accepted += deliver(List.of(device), new PushMessage(PushMessage.KIND_CHAT, title, body, thread, page,
                            String.valueOf(conversationId), String.valueOf(messageId)), CHAT_EXPIRATION_SECONDS);
                }
            }
            if (audience.size() < AUDIENCE_PAGE) break;
        }
        return accepted;
    }

    /** SOC-2/SOC-9: what a channel push needs to say where it is. */
    record ChannelContext(String channelName, int channelKind, int guildKind, long guildId, String guildName, Integer notifyDefault)
    {
        Long clubId() { return guildKind == 1 && guildId > 0 ? guildId : null; }
    }

    private ChannelContext channelContext(long conversationId)
    {
        try
        {
            Map<String, Object> row = mapper.selectChannelPushContext(conversationId);
            if (row == null) return null;
            Integer kind = PushPolicy.integer(row.get("channelKind"));
            Integer guildKind = PushPolicy.integer(row.get("guildKind"));
            Object guildId = row.get("guildId");
            return new ChannelContext(row.get("channelName") == null ? null : row.get("channelName").toString(),
                    kind == null ? -1 : kind, guildKind == null ? 0 : guildKind,
                    guildId instanceof Number n ? n.longValue() : 0L,
                    row.get("guildName") == null ? null : row.get("guildName").toString(),
                    PushPolicy.integer(row.get("notifyDefault")));
        }
        catch (RuntimeException unavailable)
        {
            // e.g. notify_default not migrated yet: fall back to the old club lookup and wording.
            log.warn("[推送] 频道上下文读取失败，按旧口径推送 reason={}", unavailable.getClass().getSimpleName());
            return null;
        }
    }

    private boolean postedEventCard(long messageId)
    {
        try { return mapper.countPostedEventCard(messageId) > 0; }
        catch (RuntimeException unavailable) { return false; }
    }

    /** The channel_event card payload of a message, or null. */
    static JSONObject eventCard(Object extraJson)
    {
        if (extraJson == null) return null;
        try
        {
            JSONObject extra = JSON.parseObject(extraJson.toString());
            return extra != null && EVENT_CARD_TYPE.equals(extra.getString("cardType")) ? extra : null;
        }
        catch (RuntimeException malformed)
        {
            return null;
        }
    }

    /**
     * SOC-9 body: zh "#闲聊 小李：今晚七点集合" / en "#闲聊 小李: ..."; 公告 adds 【公告】/[Announcement];
     * an event card becomes zh "新活动：城市夜跑 · 11月20日 19:00" / en "New event: 城市夜跑 · Nov 20, 19:00" (China time).
     */
    static String channelBody(ChannelContext ctx, JSONObject card, String sender, String text, boolean en)
    {
        if (card != null)
        {
            String title = card.getString("title") == null ? "" : card.getString("title");
            Long startAt = card.getLong("startAt");
            String when = startAt == null ? "" : " · " + formatEventTime(startAt, en);
            return (en ? "New event: " : "新活动：") + title + when;
        }
        String channelTag = ctx.channelName() == null || ctx.channelName().isBlank() ? "" : "#" + ctx.channelName() + " ";
        String prefix = ctx.channelKind() == 2 ? (en ? "[Announcement] " : "【公告】") : "";
        return prefix + channelTag + sender + (en ? ": " : "：") + (text == null ? (en ? "[Message]" : "[消息]") : text);
    }

    static String formatEventTime(long epochMillis, boolean en)
    {
        java.text.SimpleDateFormat f = en ? new java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.ENGLISH)
                : new java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA);
        f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        return f.format(new Date(epochMillis));
    }

    private boolean canRead(long member, long conversationId)
    {
        if (channelGate == null) return false; // 频道可见性无法确认时不推内容
        try { channelGate.requireRead(member, conversationId); return true; }
        catch (RuntimeException denied) { return false; }
    }

    private boolean inGameAccess(com.chengyinhub.business.domain.ImConversation conversation, long member)
    {
        if (inGameChannels == null) return false; // 游戏频道资格无法确认时不推内容
        try { return inGameChannels.hasActiveAccess(conversation, member); }
        catch (RuntimeException denied) { return false; }
    }

    static String summary(Map<String, Object> msg)
    {
        Integer msgType = PushPolicy.integer(msg.get("msgType"));
        Object content = msg.get("content");
        if (msgType != null && (msgType == ImMessage.MSG_TEXT || msgType == ImMessage.MSG_CARD || msgType == ImMessage.MSG_POLL) && content != null)
            return content.toString();
        Object last = msg.get("summary");
        return last == null || last.toString().isBlank() ? null : last.toString();
    }

    static Long clubIdOf(String bizKey)
    {
        if (bizKey == null || !bizKey.startsWith("club_")) return null;
        try { return Long.valueOf(bizKey.substring("club_".length())); }
        catch (NumberFormatException invalid) { return null; }
    }

    // ================================================================ club notices

    @Override
    public void markClubCampaignPush(long campaignId)
    {
        mapper.markCampaignPush(campaignId);
    }

    @Override
    public boolean clubCampaignPush(long campaignId)
    {
        return mapper.countCampaignPush(campaignId) > 0;
    }

    @Override
    public void clubNoticeDelivered(long campaignId, long clubId, Long activityId, String title, String content, long recipientId)
    {
        if (!sender.configured()) return;
        afterCommit(() ->
        {
            if (!PushPolicy.noticeAllowed(mapper.selectPref(recipientId, 4, recipientId), mapper.selectPref(recipientId, 2, clubId))) return;
            String page = activityId != null && activityId > 0 ? "/pages/activity/detail/index?id=" + activityId
                    : "/pages/club/detail/index?id=" + clubId;
            String thread = "club-" + clubId;
            if (!collapse.tryAcquire(recipientId, thread)) return;
            List<PushDevice> devices = devicesByMember(List.of(recipientId)).get(recipientId);
            if (devices != null) deliver(devices, new PushMessage(PushMessage.KIND_CLUB, title, content, thread, page, null),
                    NOTICE_EXPIRATION_SECONDS);
        });
    }

    // ================================================================ event reminders

    @Override
    public void eventReminder(long ticketId, long memberId, String ticketName, Date startTime, Long ownerType, Long ownerId)
    {
        if (!sender.configured() || ownerId == null || ownerId <= 0) return;
        afterCommit(() ->
        {
            if (!firstReminder(ticketId, memberId)) return;
            if (!PushPolicy.noticeAllowed(mapper.selectPref(memberId, 4, memberId), null)) return;
            String page = Long.valueOf(1L).equals(ownerType) ? "/pages/topic/index/index?id=" + ownerId
                    : "/pages/activity/detail/index?id=" + ownerId;
            String thread = "event-" + ticketId;
            List<PushDevice> devices = devicesByMember(List.of(memberId)).get(memberId);
            if (devices == null) return;
            String when = startTime == null ? "" : new java.text.SimpleDateFormat("MM-dd HH:mm").format(startTime);
            for (PushDevice device : devices)
            {
                boolean en = device.english();
                String title = en ? "Event reminder" : "出发提醒";
                String body = (ticketName == null ? "" : ticketName) + (when.isEmpty() ? "" : (en ? " starts " : " 将于 ") + when + (en ? "" : " 开始"));
                deliver(List.of(device), new PushMessage(PushMessage.KIND_EVENT, title, body, thread, page, null), NOTICE_EXPIRATION_SECONDS);
            }
        });
    }

    /** 每个 (票次, 会员) 只推一次：优先 Redis（48h），没有 Redis 时退回本机内存。 */
    private boolean firstReminder(long ticketId, long memberId)
    {
        if (redisCache != null)
        {
            try
            {
                Boolean first = redisCache.setIfAbsent("push:beforestart:" + ticketId + ":" + memberId, 1, 48, TimeUnit.HOURS);
                return Boolean.TRUE.equals(first);
            }
            catch (RuntimeException redisDown)
            {
                log.warn("[推送] 出发提醒去重 Redis 不可用，改用本机窗口 reason={}", redisDown.getClass().getSimpleName());
            }
        }
        return reminderFallback.tryAcquire(memberId, "event-" + ticketId);
    }

    // ================================================================ R11 growth-loops: single-member notices

    @Override
    public int notifyMemberNow(long memberId, MemberNotice notice)
    {
        if (memberId <= 0 || notice == null || !sender.configured()) return 0;
        if (!PushPolicy.noticeAllowed(mapper.selectPref(memberId, 4, memberId), null)) return 0;
        List<PushDevice> devices = devicesByMember(List.of(memberId)).get(memberId);
        if (devices == null) return 0;
        int accepted = 0;
        for (PushDevice device : devices)
            accepted += deliver(List.of(device), notice.message(device.english()), notice.ttlSeconds());
        return accepted;
    }

    @Override
    public void notifyMemberAfterCommit(long memberId, MemberNotice notice)
    {
        if (memberId <= 0 || notice == null || !sender.configured()) return;
        afterCommit(() ->
        {
            if (!collapse.tryAcquire(memberId, notice.threadId())) return;
            notifyMemberNow(memberId, notice);
        });
    }

    @Override
    public boolean hasPushableDevice(long memberId)
    {
        if (memberId <= 0 || !sender.configured()) return false;
        List<PushDevice> devices = devicesByMember(List.of(memberId)).get(memberId);
        return devices != null && !devices.isEmpty();
    }

    // ================================================================ plumbing

    private Map<Long, List<PushDevice>> devicesByMember(java.util.Collection<Long> members)
    {
        Map<Long, List<PushDevice>> out = new LinkedHashMap<>();
        if (members.isEmpty() || sender.environment() == null) return out;
        for (PushDevice d : mapper.selectEnabledDevices(members, sender.environment()))
            out.computeIfAbsent(d.getMemberId(), k -> new ArrayList<>()).add(d);
        return out;
    }

    private int deliver(List<PushDevice> devices, PushMessage message, long ttlSeconds)
    {
        String payload = PushPayloadBuilder.build(message);
        return sender.send(devices, payload, message.threadId(), System.currentTimeMillis() / 1000L + ttlSeconds);
    }

    /** 事务内：提交后再进线程池；事务外：直接进线程池。任何异常只记日志。 */
    void afterCommit(Runnable task)
    {
        Runnable guarded = () ->
        {
            try { task.run(); }
            catch (Throwable failure) { log.warn("[推送] 扇出失败 reason={}", failure.getClass().getSimpleName(), failure); }
        };
        Runnable submit = () ->
        {
            try
            {
                if (executor != null) executor.execute(guarded);
                else guarded.run();
            }
            catch (RuntimeException rejected) { log.warn("[推送] 线程池拒绝任务 reason={}", rejected.getClass().getSimpleName()); }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive())
        {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
            {
                @Override
                public void afterCommit() { submit.run(); }
            });
        }
        else submit.run();
    }
}
