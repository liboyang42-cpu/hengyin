package com.chengyinhub.business.service.channel.messaging;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.mapper.channel.messaging.ImMessagingMapper;
import com.chengyinhub.business.service.IChannelConversationGate;
import com.chengyinhub.business.service.channel.platform.ChannelMessageService;
import com.chengyinhub.common.exception.ServiceException;

/**
 * W5 C2：文字消息发送时的回复、话题与 @提及。
 *
 * <p>客户端在 {@code extra_json} 里可带（都可省略，其他键原样保留）：
 * <pre>{"reply_to_id":"123","thread_root_id":"100","mention_member_ids":["7"],"mention_role_ids":["3"],"mention_everyone":true}</pre>
 * <ul>
 *   <li>回复/话题：被引用的消息必须在同一会话且未撤回；话题首条若本身是话题回复，归到它的首条（话题不嵌套）。
 *       只存 id（im_message_ref），引用摘要读取时现算，撤回或注销后不会留在别人消息里。</li>
 *   <li>提及：与发送同一事务写 im_message_mention（USER / ROLE / EVERYONE，回复别人再写一行 REPLY），
 *       推送在提交后读这些行，所以发送时的 @ 能推到被提及的人。</li>
 *   <li>@角色、@全体只在频道（type 5）里可用：@全体要 MENTION_EVERYONE；@角色要角色属于本空间且可被提及
 *       （有 MENTION_EVERYONE 时任何角色都可）。单聊/群聊里 @ 的成员必须仍在会话中，不在的忽略。</li>
 * </ul>
 */
@Service
public class MessageComposeSupport
{
    static final int MENTION_LIMIT = 20;

    @Autowired
    private ImMessagingMapper mapper;
    @Autowired(required = false)
    private ChannelMessageService channels;

    public MessageComposeSupport() { }

    MessageComposeSupport(ImMessagingMapper mapper, ChannelMessageService channels)
    {
        this.mapper = mapper;
        this.channels = channels;
    }

    /** 发送计划：落库用的 extra_json 与提交前要写的行。 */
    public record Plan(String extraJson, long replyToId, long replyToSenderId, long threadRootId,
            List<Long> memberIds, List<Long> roleIds, boolean everyone)
    {
        static Plan untouched(String extraJson)
        {
            return new Plan(extraJson, 0, 0, 0, List.of(), List.of(), false);
        }

        public boolean empty()
        {
            return replyToId == 0 && threadRootId == 0 && memberIds.isEmpty() && roleIds.isEmpty() && !everyone;
        }
    }

    public Plan prepareText(long sender, long conversationId, int conversationType, String extraJson)
    {
        if (extraJson == null || extraJson.isBlank()) return Plan.untouched(extraJson);
        JSONObject in;
        try { in = JSON.parseObject(extraJson); }
        catch (RuntimeException notJson) { return Plan.untouched(extraJson); } // 旧客户端的非 JSON 附加信息原样保留
        if (in == null) return Plan.untouched(extraJson);
        boolean any = in.containsKey("reply_to_id") || in.containsKey("thread_root_id") || in.containsKey("mention_member_ids")
                || in.containsKey("mention_role_ids") || in.containsKey("mention_everyone");
        if (!any) return Plan.untouched(extraJson);

        long replyTo = optionalId(in, "reply_to_id");
        long replySender = 0;
        if (replyTo > 0) replySender = requireSameConversation(replyTo, conversationId, "被回复的消息不存在或已撤回");
        long root = optionalId(in, "thread_root_id");
        if (root > 0)
        {
            requireSameConversation(root, conversationId, "话题不存在或首条已撤回");
            Map<String, Object> parent = mapper.selectRef(root);
            long parentRoot = parent == null ? 0 : number(parent.get("threadRootId"));
            if (parentRoot > 0) root = parentRoot;
        }
        List<Long> members = ids(in, "mention_member_ids");
        members.remove(sender);
        List<Long> roles = ids(in, "mention_role_ids");
        Object everyoneRaw = in.get("mention_everyone");
        if (everyoneRaw != null && !(everyoneRaw instanceof Boolean)) throw bad("mention_everyone 不合法");
        boolean everyone = Boolean.TRUE.equals(everyoneRaw);

        if (conversationType == IChannelConversationGate.CONVERSATION_TYPE_CHANNEL)
        {
            if (channels == null) throw new ServiceException("频道服务不可用", 503);
            ChannelMessageService.MentionScope scope = channels.mentionScope(sender, conversationId);
            if (everyone && !scope.everyone()) throw new ServiceException("没有 @全体 权限", 403);
            if (!roles.isEmpty())
            {
                List<Map<String, Object>> rows = mapper.selectRoles(roles);
                if (rows.size() != roles.size()) throw bad("角色不存在");
                for (Map<String, Object> r : rows)
                {
                    boolean sameGuild = number(r.get("guildKind")) == scope.guildKind() && number(r.get("guildId")) == scope.guildId();
                    if (!sameGuild || number(r.get("status")) != 1) throw bad("角色不存在");
                    if (number(r.get("mentionable")) != 1 && !scope.everyone()) throw new ServiceException("该角色不能被 @", 403);
                }
            }
        }
        else
        {
            if (everyone || !roles.isEmpty()) throw bad("只有频道支持 @角色 和 @全体");
            if (!members.isEmpty()) members = new ArrayList<>(mapper.selectActiveMemberIds(conversationId, members));
        }

        JSONObject out = new JSONObject(in);
        out.remove("reply_to_id");
        out.remove("thread_root_id");
        out.remove("mention_member_ids");
        out.remove("mention_role_ids");
        out.remove("mention_everyone");
        if (replyTo > 0) out.put("reply_to_id", String.valueOf(replyTo));
        if (root > 0) out.put("thread_root_id", String.valueOf(root));
        if (!members.isEmpty()) out.put("mention_member_ids", strings(members));
        if (!roles.isEmpty()) out.put("mention_role_ids", strings(roles));
        if (everyone) out.put("mention_everyone", true);
        return new Plan(out.toJSONString(), replyTo, replySender, root, List.copyOf(members), List.copyOf(roles), everyone);
    }

    /** 与消息插入同一事务：写提及行与引用行。 */
    public void afterInsert(ImMessage msg, Plan plan)
    {
        if (msg == null || msg.getId() == null || plan == null || plan.empty()) return;
        long id = msg.getId();
        for (Long m : plan.memberIds()) mapper.insertMention(id, "USER", m);
        for (Long r : plan.roleIds()) mapper.insertMention(id, "ROLE", r);
        if (plan.everyone()) mapper.insertMention(id, "EVERYONE", 0L);
        if (plan.replyToSenderId() > 0 && plan.replyToSenderId() != number(msg.getSenderId()))
            mapper.insertMention(id, "REPLY", plan.replyToSenderId());
        if (plan.replyToId() > 0 || plan.threadRootId() > 0)
            mapper.insertRef(id, msg.getConversationId(), plan.replyToId(), plan.threadRootId());
    }

    // ---------------------------------------------------------------- helpers

    private long requireSameConversation(long messageId, long conversationId, String message)
    {
        Map<String, Object> head = mapper.selectMessageHead(messageId);
        if (head == null || number(head.get("conversationId")) != conversationId
                || number(head.get("status")) != ImMessage.STATUS_NORMAL) throw new ServiceException(message, 404);
        return number(head.get("senderId"));
    }

    private static long optionalId(JSONObject in, String key)
    {
        Object raw = in.get(key);
        if (raw == null) return 0;
        String text = String.valueOf(raw);
        if (!(raw instanceof String || raw instanceof Number) || !text.matches("[1-9][0-9]{0,18}")) throw bad(key + " 不合法");
        return Long.parseLong(text);
    }

    private static List<Long> ids(JSONObject in, String key)
    {
        Object raw = in.get(key);
        if (raw == null) return new ArrayList<>();
        if (!(raw instanceof JSONArray items) || items.size() > MENTION_LIMIT) throw bad(key + " 不合法");
        Set<Long> ids = new LinkedHashSet<>();
        for (Object item : items)
        {
            String text = String.valueOf(item);
            if (!(item instanceof String || item instanceof Number) || !text.matches("[1-9][0-9]{0,18}")) throw bad(key + " 不合法");
            ids.add(Long.parseLong(text));
        }
        return new ArrayList<>(ids);
    }

    private static List<String> strings(List<Long> ids)
    {
        List<String> out = new ArrayList<>();
        for (Long id : ids) out.add(String.valueOf(id));
        return out;
    }

    static long number(Object raw)
    {
        if (raw instanceof Number n) return n.longValue();
        if (raw instanceof Boolean b) return b ? 1 : 0;
        if (raw == null) return 0;
        try { return Long.parseLong(raw.toString()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static ServiceException bad(String message)
    {
        return new ServiceException(message, 400);
    }
}
