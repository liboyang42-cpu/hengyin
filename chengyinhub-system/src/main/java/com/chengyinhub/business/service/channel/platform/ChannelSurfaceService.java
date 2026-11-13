package com.chengyinhub.business.service.channel.platform;

import static com.chengyinhub.business.service.channel.platform.ChannelCapability.READ_HISTORY;
import static com.chengyinhub.business.service.channel.platform.ChannelCapability.VIEW_CHANNEL;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.mapper.channel.platform.ChannelGovernanceMapper;

/**
 * 频道周边只读面：单图消息附件描述（/api/im/message/{id}/attachment）、空间欢迎屏（/api/welcome-screen/get）、
 * 入会引导（/api/onboarding/get|submit）。欢迎屏以俱乐部既有「入会欢迎语」为真源；引导没有服务端配置源，如实回 ONBOARDING_NOT_CONFIGURED。
 */
@Service
public class ChannelSurfaceService
{
    static final int ATTACHMENT_TTL_SEC = 300;

    @Autowired
    private ChannelGovernanceMapper gov;
    @Autowired
    private ChannelMessageService messages;
    @Autowired
    private ChannelPlatformService platform;
    @Autowired
    private ChannelGuildAccess access;

    /** 旧单图消息（msg_type=2）的附件描述：只有审核通过且可见的消息给地址，地址 300 秒内有效。 */
    public Map<String, Object> attachment(long viewer, long messageId)
    {
        Map<String, Object> m = gov.selectMessageForAttachment(messageId);
        if (m == null) throw new ChannelRejection(404, "MESSAGE_NOT_FOUND", "消息不存在");
        long conv = ChannelGuildAccess.toLong(m.get("conversationId"));
        if (messages.isChannel(conv)) messages.authorize(viewer, conv, ChannelCapability.of(VIEW_CHANNEL, READ_HISTORY), false);
        else if (gov.countConversationMember(conv, viewer) == 0) throw new ChannelRejection(404, "MESSAGE_NOT_FOUND", "消息不存在");
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("message_id", String.valueOf(messageId));
        d.put("conversation_id", String.valueOf(conv));
        d.put("kind", "IMAGE");
        d.put("source", "LEGACY_SINGLE_IMAGE");
        String content = m.get("content") == null ? "" : m.get("content").toString().trim();
        String state;
        if (ChannelGuildAccess.toLong(m.get("msgType")) != 2) state = "UNSUPPORTED";
        else if (ChannelGuildAccess.toLong(m.get("status")) != 0) state = "UNAVAILABLE";
        else if (m.get("checkStatus") == null || ChannelGuildAccess.toLong(m.get("checkStatus")) == 0) state = "PENDING";
        else if (ChannelGuildAccess.toLong(m.get("checkStatus")) != 1) state = "REJECTED";
        else if (!content.matches("https://[^\\s]{1,2000}")) state = "SOURCE_UNCONFIRMED";
        else state = "READY";
        d.put("state", state);
        if ("READY".equals(state))
        {
            d.put("asset_id", String.valueOf(messageId));
            d.put("asset_version", 0L);
            d.put("message_version", Math.max(1L, ChannelGuildAccess.toLong(m.get("version"))));
            d.put("url", content);
            d.put("expires_in_seconds", ATTACHMENT_TTL_SEC);
        }
        return d;
    }

    public Map<String, Object> welcome(long viewer, Map<String, Object> body)
    {
        Map<String, Object> b = ChannelGovernanceService.body(body, java.util.Set.of("guild_kind", "guild_id"));
        int kind = (int) ChannelGovernanceService.positive(b.get("guild_kind"), "guild_kind");
        long guild = ChannelGovernanceService.positive(b.get("guild_id"), "guild_id");
        access.requireMember(kind, guild, viewer);
        Map<String, Object> club = kind == ChannelGuildAccess.GUILD_CLUB ? gov.selectClubWelcome(guild) : null;
        String note = club == null || club.get("welcomeNote") == null ? "" : club.get("welcomeNote").toString().trim();
        if (note.isEmpty()) throw new ChannelRejection(404, "WELCOME_SCREEN_NOT_CONFIGURED", "本空间尚未配置欢迎屏");
        String entry = null;
        Map<String, Object> dir = platform.directory(viewer, kind, guild, false);
        for (Object g : (List<?>) dir.get("groups"))
            for (Object row : (List<?>) ((Map<?, ?>) g).get("channels"))
                if (entry == null || Integer.valueOf(1).equals(((Map<?, ?>) row).get("is_default"))) entry = (String) ((Map<?, ?>) row).get("id");
        for (Object row : (List<?>) dir.get("ungrouped"))
            if (entry == null || Integer.valueOf(1).equals(((Map<?, ?>) row).get("is_default"))) entry = (String) ((Map<?, ?>) row).get("id");
        if (entry == null) throw new ChannelRejection(404, "WELCOME_SCREEN_NOT_CONFIGURED", "本空间还没有可进入的频道");
        String title = club.get("name") == null ? "欢迎" : club.get("name").toString();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("viewer_id", String.valueOf(viewer));
        out.put("guild_id", String.valueOf(guild));
        out.put("entry_channel_id", entry);
        out.put("guild_kind", kind);
        out.put("version", Math.max(0L, ChannelGuildAccess.toLong(club.get("version"))));
        out.put("title", title.length() > 100 ? title.substring(0, 100) : title);
        out.put("description", note.length() > 2000 ? note.substring(0, 2000) : note);
        out.put("signposts", List.of());
        return out;
    }

    /** 入会引导没有服务端配置源（问题/选项/授角色映射）：成员可读到明确的未配置，非成员 403。 */
    public Map<String, Object> onboarding(long viewer, int guildKind, long guildId)
    {
        access.requireMember(guildKind, guildId, viewer);
        throw new ChannelRejection(404, "ONBOARDING_NOT_CONFIGURED", "本空间尚未配置入会引导");
    }
}
