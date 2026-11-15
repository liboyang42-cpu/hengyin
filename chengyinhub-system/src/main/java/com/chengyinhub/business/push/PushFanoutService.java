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
        Integer clubDefault = null;
        Long clubId = channel ? mapper.selectChannelClubId(conversationId) : clubIdOf((String) msg.get("bizKey"));
        if (clubId != null)
        {
            Integer level = mapper.selectClubDefaultLevel(clubId);
            clubDefault = level == null ? 1 : level;
        }
        boolean everyone = false;
        List<Long> mentioned = new ArrayList<>();
        for (Map<String, Object> m : mapper.selectMentions(messageId))
        {
            if ("EVERYONE".equals(m.get("mentionType"))) everyone = true;
            else if ("USER".equals(m.get("mentionType")) && m.get("targetId") instanceof Number n) mentioned.add(n.longValue());
        }
        Map<Long, Map<String, Object>> allowed = new LinkedHashMap<>();
        Map<Long, Boolean> mentionOf = new LinkedHashMap<>();
        for (Map<String, Object> row : mapper.selectChatAudience(conversationId, senderId, single))
        {
            long member = ((Number) row.get("memberId")).longValue();
            boolean me = mentioned.contains(member);
            if (!PushPolicy.chatAllowed(row, me, everyone, clubDefault)) continue;
            if (channel && !canRead(member, conversationId)) continue;
            allowed.put(member, row);
            mentionOf.put(member, me || (everyone && !PushPolicy.flag(row.get("suppressBroadcast"))));
        }
        if (allowed.isEmpty()) return 0;
        String thread = "chat-" + conversationId;
        String page = CHAT_PAGE + "?conversationId=" + conversationId + "&type=" + type;
        String senderName = msg.get("senderName") == null ? null : msg.get("senderName").toString();
        String text = summary(msg);
        int accepted = 0;
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
                String title = single ? name : (en ? "Group message" : "群消息");
                String body;
                if (!preview) body = en ? "You have a new message" : "你收到一条新消息";
                else body = (single ? "" : name + (en ? ": " : "：")) + (text == null ? (en ? "[Message]" : "[消息]") : text);
                if (mention) body = (en ? "[Mentioned you] " : "[有人@你] ") + body;
                accepted += deliver(List.of(device), new PushMessage(PushMessage.KIND_CHAT, title, body, thread, page,
                        String.valueOf(conversationId)), CHAT_EXPIRATION_SECONDS);
            }
        }
        return accepted;
    }

    private boolean canRead(long member, long conversationId)
    {
        if (channelGate == null) return false; // 频道可见性无法确认时不推内容
        try { channelGate.requireRead(member, conversationId); return true; }
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
