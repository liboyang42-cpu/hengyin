package com.chengyinhub.business.service.channel.platform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.mapper.channel.platform.ChannelGovernanceMapper;
import com.chengyinhub.business.mapper.opsconsole.ChannelModerationAdminMapper;
import com.chengyinhub.business.opsconsole.OpsConsoleAudit;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;

/**
 * R10 BE-23 平台频道治理：搜频道 / 搜消息 / 看举报 / 屏蔽或恢复消息 / 禁言 / 移出空间 / 撤销处置，每一步写平台审计。
 *
 * <p>处置复用空间治理同一张 im_moderation_action 表与同一个发送闸（{@code countActiveRestriction}），所以平台禁言与
 * 空间管理员禁言在 App 里效果完全一致。平台操作者是后台账号，不是会员：operator_id 记 0，空间审计投影显示为 SYSTEM，
 * 具体是哪个后台账号记在 business_audit_event。
 *
 * <p>移出空间 = 空间级封禁（同时拦截该空间所有频道的发言与加入）；队伍会同时结束队员关系。
 * 俱乐部会员关系涉及会费与权益，这里刻意不动，要终止俱乐部会籍请走俱乐部治理（club-governance）。
 */
@Service
public class ChannelModerationAdminService
{
    static final int MESSAGE_PAGE = 50;
    static final int MUTE_MIN = 60;
    static final int MUTE_MAX = 2419200;
    static final int BAN_MIN = 60;
    static final int BAN_MAX = 7776000;

    @Autowired
    private ChannelModerationAdminMapper mapper;
    @Autowired
    private ChannelGovernanceMapper gov;
    @Autowired
    private OpsConsoleAudit audit;

    public ChannelModerationAdminService() {}

    ChannelModerationAdminService(ChannelModerationAdminMapper mapper, ChannelGovernanceMapper gov, OpsConsoleAudit audit)
    {
        this.mapper = mapper;
        this.gov = gov;
        this.audit = audit;
    }

    public List<Map<String, Object>> channels(String keyword, Integer guildKind, Long guildId, Integer status)
    {
        return mapper.searchChannels(trim(keyword, 64), guildKind, guildId, status);
    }

    public List<Map<String, Object>> messages(Long channelId, Long senderId, String keyword, Integer status, Long beforeId)
    {
        if (channelId == null && senderId == null && (keyword == null || keyword.isBlank()))
            throw bad("请至少按频道、发送人或关键词之一筛选");
        return mapper.searchMessages(channelId, senderId, trim(keyword, 64), status, beforeId, MESSAGE_PAGE);
    }

    public List<Map<String, Object>> reports(String status, Long channelId, Long beforeId)
    {
        if (status != null && !status.isBlank() && !List.of("PENDING", "RESOLVED", "DISMISSED").contains(status)) throw bad("不认识的举报状态");
        return mapper.listReports(status, channelId, beforeId, MESSAGE_PAGE);
    }

    public List<Map<String, Object>> actions(Integer guildKind, Long guildId, Long targetMemberId, String status)
    {
        return mapper.listActions(guildKind, guildId, targetMemberId, status, 100);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> hideMessage(Long sysUserId, long messageId, String reason)
    {
        String why = requireReason(reason);
        Map<String, Object> m = mapper.lockMessage(messageId);
        if (m == null) throw new ServiceException("频道消息不存在", HttpStatus.NOT_FOUND);
        if (ChannelGuildAccess.toLong(m.get("status")) != 0L) throw conflict("消息已不是正常状态，请刷新");
        if (mapper.setMessageStatus(messageId, 0, 2) != 1) throw conflict("消息已变化，请刷新");
        long version = ChannelGuildAccess.toLong(m.get("version")) + 1;
        mapper.insertMutation(ChannelGuildAccess.toLong(m.get("channelId")), messageId, version, "RECALL");
        int closed = mapper.closeReportsForMessage(messageId, "RESOLVED");
        Map<String, Object> after = messageState(m, 2, version);
        after.put("reportsResolved", closed);
        audit.record(sysUserId, "CHANNEL_MESSAGE_HIDE", "IM_MESSAGE", messageId, messageState(m, 0, version - 1), after, why);
        return after;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> restoreMessage(Long sysUserId, long messageId, String reason)
    {
        String why = requireReason(reason);
        Map<String, Object> m = mapper.lockMessage(messageId);
        if (m == null) throw new ServiceException("频道消息不存在", HttpStatus.NOT_FOUND);
        if (ChannelGuildAccess.toLong(m.get("status")) != 2L) throw conflict("只有被平台屏蔽的消息可以恢复");
        if (mapper.setMessageStatus(messageId, 2, 0) != 1) throw conflict("消息已变化，请刷新");
        long version = ChannelGuildAccess.toLong(m.get("version")) + 1;
        mapper.insertMutation(ChannelGuildAccess.toLong(m.get("channelId")), messageId, version, "RESTORE");
        Map<String, Object> after = messageState(m, 0, version);
        audit.record(sysUserId, "CHANNEL_MESSAGE_RESTORE", "IM_MESSAGE", messageId, messageState(m, 2, version - 1), after, why);
        return after;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> dismissReport(Long sysUserId, long reportId, String reason)
    {
        String why = requireReason(reason);
        Map<String, Object> r = mapper.lockReport(reportId);
        if (r == null) throw new ServiceException("举报不存在", HttpStatus.NOT_FOUND);
        if (!"PENDING".equals(r.get("status"))) throw conflict("举报已处理");
        if (mapper.closeReport(reportId, "DISMISSED") != 1) throw conflict("举报已处理");
        Map<String, Object> after = new LinkedHashMap<>(r);
        after.put("status", "DISMISSED");
        audit.record(sysUserId, "CHANNEL_REPORT_DISMISS", "IM_CHANNEL_REPORT", reportId, r, after, why);
        return after;
    }

    /** 频道级禁言：与空间管理员的 TIMEOUT 同形，到期自动失效。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> mute(Long sysUserId, long channelId, long memberId, int durationSec, String reasonCode, String reasonText)
    {
        String code = requireCode(reasonCode);
        String why = requireReason(reasonText);
        if (durationSec < MUTE_MIN || durationSec > MUTE_MAX) throw bad("禁言时长必须在 1 分钟到 28 天之间");
        Map<String, Object> channel = mapper.selectChannel(channelId);
        if (channel == null) throw new ServiceException("频道不存在", HttpStatus.NOT_FOUND);
        int guildKind = (int) ChannelGuildAccess.toLong(channel.get("guildKind"));
        long guildId = ChannelGuildAccess.toLong(channel.get("guildId"));
        requireSpaceMember(guildKind, guildId, memberId, false);
        return applyAction(sysUserId, guildKind, guildId, memberId, "TIMEOUT", "CHANNEL", channelId, durationSec, code, why);
    }

    /** 移出空间：空间级封禁 + 队伍结束队员关系；俱乐部会籍不动（见类注释）。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> remove(Long sysUserId, int guildKind, long guildId, long memberId, int durationSec,
                                      String reasonCode, String reasonText)
    {
        String code = requireCode(reasonCode);
        String why = requireReason(reasonText);
        if (durationSec < BAN_MIN || durationSec > BAN_MAX) throw bad("移出时长必须在 1 分钟到 90 天之间");
        requireSpaceMember(guildKind, guildId, memberId, true);
        Map<String, Object> out = applyAction(sysUserId, guildKind, guildId, memberId, "BAN", "GUILD", guildId, durationSec, code, why);
        if (guildKind == ChannelGuildAccess.GUILD_TEAM)
        {
            out.put("teamMembershipEnded", gov.endTeamMembership(guildId, memberId) > 0);
        }
        else out.put("clubMembershipKept", true);
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> revoke(Long sysUserId, long actionId, String reason)
    {
        String why = requireReason(reason);
        Map<String, Object> a = gov.lockAction(actionId);
        if (a == null) throw new ServiceException("处置不存在", HttpStatus.NOT_FOUND);
        if (!"ACTIVE".equals(a.get("status"))) throw conflict("处置已失效或已撤销");
        if (gov.revokeAction(actionId, ChannelGuildAccess.toLong(a.get("version")), 0L, why) != 1) throw conflict("处置已变化，请刷新");
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("id", actionId);
        after.put("status", "REVOKED");
        audit.record(sysUserId, "CHANNEL_MODERATION_REVOKE", "IM_MODERATION_ACTION", actionId, actionView(a), after, why);
        return after;
    }

    private Map<String, Object> applyAction(Long sysUserId, int guildKind, long guildId, long memberId, String type, String scopeType,
                                            long scopeId, int durationSec, String code, String why)
    {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("guildKind", guildKind);
        row.put("guildId", guildId);
        row.put("targetMemberId", memberId);
        row.put("actionType", type);
        row.put("status", "ACTIVE");
        row.put("scopeType", scopeType);
        row.put("scopeId", scopeId);
        row.put("reasonCode", code);
        row.put("reasonText", why);
        row.put("durationSec", durationSec);
        row.put("deleteMessageSec", 0);
        row.put("operatorId", 0L);
        row.put("requestId", UUID.randomUUID().toString());
        if (gov.insertAction(row) != 1) throw new IllegalStateException("处置写入失败");
        long actionId = ChannelGuildAccess.toLong(row.get("id"));
        Map<String, Object> after = actionView(row);
        after.put("id", actionId);
        audit.record(sysUserId, "CHANNEL_MODERATION_" + type, "IM_MODERATION_ACTION", actionId, null, after, why);
        return after;
    }

    private void requireSpaceMember(int guildKind, long guildId, long memberId, boolean refuseOwner)
    {
        if (memberId <= 0) throw bad("请填写会员 ID");
        if (guildKind == ChannelGuildAccess.GUILD_CLUB)
        {
            Long owner = mapper.selectClubOwner(guildId);
            if (owner == null) throw new ServiceException("俱乐部不存在", HttpStatus.NOT_FOUND);
            if (refuseOwner && owner == memberId) throw conflict("不能移出主理人，请走俱乐部治理");
            if (owner != memberId && mapper.countClubMember(guildId, memberId) == 0) throw new ServiceException("对方不是该俱乐部成员", HttpStatus.NOT_FOUND);
        }
        else if (guildKind == ChannelGuildAccess.GUILD_TEAM)
        {
            Long leader = mapper.selectTeamLeader(guildId);
            if (leader == null) throw new ServiceException("队伍不存在", HttpStatus.NOT_FOUND);
            if (refuseOwner && leader == memberId) throw conflict("不能移出队长");
            if (leader != memberId && mapper.countTeamMember(guildId, memberId) == 0) throw new ServiceException("对方不是该队伍成员", HttpStatus.NOT_FOUND);
        }
        else throw bad("该频道不属于俱乐部或队伍空间，无法处置成员");
    }

    private static Map<String, Object> messageState(Map<String, Object> m, int status, long version)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", m.get("id"));
        v.put("channelId", m.get("channelId"));
        v.put("senderId", m.get("senderId"));
        v.put("status", status);
        v.put("version", version);
        return v;
    }

    private static Map<String, Object> actionView(Map<String, Object> a)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        for (String k : List.of("guildKind", "guildId", "targetMemberId", "actionType", "status", "scopeType", "scopeId",
                "reasonCode", "durationSec"))
            v.put(k, a.get(k));
        return v;
    }

    private static String requireCode(String code)
    {
        if (code == null || !ChannelGovernanceService.REASON_CODES.contains(code) || "NOISE".equals(code)) throw bad("请选择处置理由");
        return code;
    }

    private static String requireReason(String reason)
    {
        if (reason == null || reason.isBlank() || reason.length() > 500) throw bad("请填写 500 字内的处置说明");
        return reason.strip();
    }

    private static String trim(String s, int max)
    {
        if (s == null) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static ServiceException bad(String m) { return new ServiceException(m, HttpStatus.BAD_REQUEST); }
    private static ServiceException conflict(String m) { return new ServiceException(m, HttpStatus.CONFLICT); }
}
