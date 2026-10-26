package com.chengyinhub.business.service.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.BeanUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ImBlock;
import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.domain.ImConversation;
import com.chengyinhub.business.domain.ImConversationMember;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.domain.RoamHangout;
import com.chengyinhub.business.domain.PlayTeamMember;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.ImBlockMapper;
import com.chengyinhub.business.mapper.ImConversationMapper;
import com.chengyinhub.business.mapper.ImConversationMemberMapper;
import com.chengyinhub.business.mapper.ImMessageMapper;
import com.chengyinhub.business.mapper.ImNotificationIntentMapper;
import com.chengyinhub.business.mapper.ImPollMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.mapper.RoamHangoutMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IClubParticipationGuard;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.ISystemNotificationIntentService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.IWxContentCallbackInboxService;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.business.service.support.CanonicalDigest;

/**
 * 站内消息(IM) Service 实现。
 */
@Service
public class ImServiceImpl implements IImService, ISystemNotificationIntentService
{
    @Autowired
    private ImConversationMapper conversationMapper;
    @Autowired
    private ImConversationMemberMapper memberMapper;
    @Autowired
    private ImMessageMapper messageMapper;

    @Autowired
    private ImNotificationIntentMapper notificationIntentMapper;
    @Autowired
    private ImPollMapper pollMapper;
    @Autowired
    private ImBlockMapper blockMapper;
    @Autowired
    private IUmsMemberService umsMemberService;
    @Autowired
    private IWxContentSecurityService wxContentSecurityService;
    @Autowired
    private IWxContentCallbackInboxService wxContentCallbackInboxService;
    @Autowired
    @Lazy
    private IAuditTaskService auditTaskService;
    @Autowired
    private IClubParticipationGuard clubParticipationGuard;
    @Autowired
    private CmsTopicMapper cmsTopicMapper;
    @Autowired
    private ClubMapper clubMapper;
    @Autowired
    private ClubMemberMapper clubMemberMapper;
    @Autowired
    private PlayTeamMapper playTeamMapper;
    @Autowired
    private RoamHangoutMapper roamHangoutMapper;

    private String singleBizKey(Long a, Long b)
    {
        long lo = Math.min(a, b), hi = Math.max(a, b);
        return lo + "_" + hi;
    }

    private String openidOf(Long memberId)
    {
        try
        {
            UmsMember m = umsMemberService.selectUmsMemberById(memberId);
            return m == null ? null : m.getWxOpenId();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    @Override
    public List<Map<String, Object>> listConversations(Long memberId)
    {
        List<ImConversationMember> mine = memberMapper.selectMyConversations(memberId);
        List<Map<String, Object>> out = new ArrayList<>();
        Map<Long, ImMessage> latestVisible = new HashMap<>();
        if (mine == null)
        {
            return out;
        }
        for (ImConversationMember m : mine)
        {
            ImConversation c = conversationMapper.selectImConversationById(m.getConversationId());
            if (c == null)
            {
                continue;
            }
            if (!hasActiveGroupAccess(c, memberId, false))
            {
                continue;
            }
            Map<String, Object> row = new HashMap<>();
            row.put("conversationId", c.getId());
            row.put("type", c.getType());
            List<ImMessage> visible = messageMapper.selectVisiblePage(c.getId(), memberId, null, 1);
            ImMessage latest = visible == null || visible.isEmpty() ? null : visible.get(0);
            if (latest != null)
            {
                latestVisible.put(c.getId(), latest);
            }
            row.put("lastMsgType", latest == null ? null : latest.getMsgType());
            // 摘要取当前读者可见的最新消息，路线卡按读者当前权限投影，不回显会话行里的旧名称。
            row.put("lastMsgText", latest == null ? "" : Integer.valueOf(ImMessage.MSG_IMAGE).equals(latest.getMsgType())
                    ? "[图片]" : projectRouteMessage(latest, memberId).getContent());
            row.put("lastMsgAt", latest == null ? null : latest.getCreateTime());
            row.put("unread", m.getUnreadCount());
            row.put("muted", m.getMuted() == null ? 0 : m.getMuted());
            row.put("counterparty", counterpartyOf(c, memberId));
            out.add(row);
        }
        out.sort((left, right) -> {
            ImMessage a = latestVisible.get(left.get("conversationId"));
            ImMessage b = latestVisible.get(right.get("conversationId"));
            String aOrder = a == null || a.getVisibleOrderAt() == null ? "" : a.getVisibleOrderAt();
            String bOrder = b == null || b.getVisibleOrderAt() == null ? "" : b.getVisibleOrderAt();
            int order = bOrder.compareTo(aOrder);
            if (order != 0)
            {
                return order;
            }
            long aId = a == null || a.getId() == null ? 0L : a.getId();
            long bId = b == null || b.getId() == null ? 0L : b.getId();
            return Long.compare(bId, aId);
        });
        return out;
    }

    /** 只投影当前读者所见卡片；历史原文、请求摘要与数据库行保持原值。 */
    private ImMessage projectRouteMessage(ImMessage source, Long viewerId)
    {
        if (source == null) return null;
        ImMessage projected = new ImMessage();
        BeanUtils.copyProperties(source, projected);
        if (!Integer.valueOf(ImMessage.MSG_CARD).equals(source.getMsgType())) return projected;
        JSONObject extra = null;
        try { extra = JSON.parseObject(source.getExtraJson()); }
        catch (RuntimeException ignored) { /* 历史坏 JSON 仍按已有路线摘要识别，不回显旧名称。 */ }
        boolean route = extra != null && "route".equals(extra.getString("cardType"));
        if (!route && (source.getContent() == null || !source.getContent().startsWith("[路线]"))) return projected;
        Long topicId = null;
        try { if (extra != null) topicId = extra.getLong("topicId"); }
        catch (RuntimeException ignored) { /* 无合法引用时只显示不可用。 */ }
        CmsTopic topic = topicId == null || topicId <= 0 ? null
                : cmsTopicMapper.selectCmsTopicDetailById(topicId, viewerId);
        boolean visible = isPublicRouteTopic(topic)
                && Integer.valueOf(ImMessage.STATUS_NORMAL).equals(source.getStatus());
        JSONObject safe = new JSONObject();
        safe.put("cardType", "route");
        if (topicId != null) safe.put("topicId", topicId);
        if (visible) {
            safe.put("imgUrl", topic.getImgUrl());
            safe.put("totalMileage", topic.getTotalMileage());
            safe.put("locationCount", topic.getLocationCount());
        }
        projected.setExtraJson(safe.toJSONString());
        projected.setContent(visible ? "[路线] " + topic.getName() : "路线已不可用");
        return projected;
    }

    private Map<String, Object> counterpartyOf(ImConversation c, Long memberId)
    {
        Map<String, Object> cp = new HashMap<>();
        if (c.getType() != null && c.getType() == ImConversation.TYPE_SYSTEM)
        {
            cp.put("id", 0);
            cp.put("nickname", "系统通知");
            cp.put("avatar", "");
            return cp;
        }
        if (c.getType() != null && c.getType() == ImConversation.TYPE_MERCHANT)
        {
            cp.put("id", 0);
            cp.put("nickname", "官方客服");
            cp.put("avatar", "");
            return cp;
        }
        if (c.getType() != null && c.getType() == ImConversation.TYPE_GROUP)
        {
            cp.put("id", 0);
            cp.put("bizKey", c.getBizKey());
            PlayTeam team = teamOfBizKey(c.getBizKey());
            if (team != null)
            {
                cp.put("nickname", team.getTitle() == null || team.getTitle().trim().isEmpty()
                        ? "玩家队伍" : team.getTitle());
                cp.put("avatar", "");
                cp.put("memberCount", team.getJoinedCount());
                cp.put("teamId", team.getId());
                return cp;
            }
            Long hangoutId = hangoutIdOfBizKey(c.getBizKey());
            if (hangoutId != null)
            {
                RoamHangout hangout = roamHangoutMapper.selectById(hangoutId, memberId);
                cp.put("nickname", hangout == null || hangout.getTitle() == null ? "附近的局" : hangout.getTitle());
                cp.put("avatar", hangout == null || hangout.getCoverUrl() == null ? "" : hangout.getCoverUrl());
                cp.put("memberCount", hangout == null ? 0 : hangout.getMemberCount());
                cp.put("hangoutId", hangoutId);
                return cp;
            }
            Club club = clubOfBizKey(c.getBizKey());
            if (club == null)
            {
                // 俱乐部已删 / bizKey 脏数据 → 降级,不 NPE
                cp.put("nickname", "群聊");
                cp.put("avatar", "");
                return cp;
            }
            cp.put("nickname", club.getName());
            cp.put("avatar", club.getLogo() == null ? "" : club.getLogo());
            cp.put("memberCount", club.getMemberCount());
            cp.put("clubId", club.getId());
            return cp;
        }
        List<ImConversationMember> parts = memberMapper.selectByConversation(c.getId());
        Long otherId = null;
        if (parts != null)
        {
            for (ImConversationMember p : parts)
            {
                if (!p.getMemberId().equals(memberId))
                {
                    otherId = p.getMemberId();
                    break;
                }
            }
        }
        if (otherId != null)
        {
            UmsMember u = umsMemberService.selectUmsMemberById(otherId);
            cp.put("id", otherId);
            cp.put("nickname", u != null ? (u.getNickname() != null ? u.getNickname() : u.getName()) : "");
            cp.put("avatar", u != null ? u.getAvatar() : "");
        }
        else
        {
            cp.put("id", 0);
            cp.put("nickname", "");
            cp.put("avatar", "");
        }
        return cp;
    }

    /** 从群会话 bizKey(club_<id>)取俱乐部;脏数据/已删一律返回 null 由调用方降级。 */
    private Club clubOfBizKey(String bizKey)
    {
        if (bizKey == null || !bizKey.startsWith("club_"))
        {
            return null;
        }
        try
        {
            Long clubId = Long.valueOf(bizKey.substring("club_".length()));
            return clubMapper.selectClubById(clubId);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private PlayTeam teamOfBizKey(String bizKey)
    {
        Long teamId = teamIdOfBizKey(bizKey);
        return teamId == null ? null : playTeamMapper.selectPlayTeamById(teamId);
    }

    @Override
    public Map<String, Object> listMessages(Long memberId, Long conversationId, Long cursorId, int size)
    {
        return listMessages(memberId, conversationId, cursorId, size, null);
    }

    @Override
    public Map<String, Object> listMessages(Long memberId, Long conversationId, Long cursorId, int size, Long afterId)
    {
        if (afterId != null && afterId < 0) throw new ServiceException("消息增量位置无效", 400);
        ImConversation conversation = conversationMapper.selectImConversationById(conversationId);
        if (isClosedHangoutForHistoricalMember(conversation, memberId))
        {
            throw new ServiceException("该组局已结束，聊天已关闭", 409).setDetailMessage("HANGOUT_CLOSED");
        }
        requireConversationMembership(conversationId, memberId, "无权查看该会话");
        if (!hasActiveGroupAccess(conversation, memberId, false))
        {
            throw new RuntimeException(hangoutIdOfBizKey(conversation.getBizKey()) != null ? "已退出该局，无权查看群消息"
                    : teamIdOf(conversation) == null
                    ? "已退出俱乐部，无权查看群消息" : "已退出队伍，无权查看群消息");
        }
        if (conversation != null && Integer.valueOf(ImConversation.TYPE_GROUP).equals(conversation.getType())
                && !Integer.valueOf(0).equals(conversation.getStatus()))
        {
            throw new RuntimeException("群聊已结束");
        }
        int s = size <= 0 ? 20 : Math.min(size, 50);
        boolean incremental = afterId != null && afterId >= 0;
        // 增量按可见序正序补拉；历史分页沿用可见序倒序窗口。两者都只返回当前读者可见的消息。
        List<ImMessage> desc = incremental ? messageMapper.selectVisibleAfter(conversationId, memberId, afterId, s + 1)
                : messageMapper.selectVisiblePage(conversationId, memberId, cursorId, s + 1);
        boolean hasMore = desc.size() > s;
        if (hasMore) {
            desc = new ArrayList<>(desc.subList(0, s));
        }
        List<ImMessage> asc = new ArrayList<>();
        for (ImMessage message : desc) asc.add(projectRouteMessage(message, memberId));
        if (!incremental) Collections.reverse(asc);
        // 群聊渲染:按 senderId 回填发送者昵称+头像(瞬态字段,带缓存避免重复查;系统消息 sender=0 不回填)
        Map<Long, UmsMember> senderCache = new HashMap<>();
        for (ImMessage m : asc) {
            Long sid = m.getSenderId();
            if (sid == null || sid == 0) continue;
            if (!senderCache.containsKey(sid)) {
                senderCache.put(sid, umsMemberService.selectUmsMemberById(sid));
            }
            UmsMember u = senderCache.get(sid);
            if (u != null) {
                m.setSenderName(u.getNickname() != null ? u.getNickname() : u.getName());
                m.setSenderAvatar(u.getAvatar());
            }
        }
        Long nextCursor = desc.isEmpty() ? null : desc.get(desc.size() - 1).getId();
        Map<String, Object> res = new HashMap<>();
        res.put("list", asc);
        res.put("nextCursor", nextCursor);
        if (incremental) res.put("nextAfter", desc.isEmpty() ? afterId : desc.get(desc.size() - 1).getId());
        res.put("hasMore", hasMore);
        // 3-22:1:1 会话回读拉黑态,重进会话也能禁用输入框(拉黑只存服务端,前端内存态重进即丢)
        Long otherId = null;
        if (conversation != null && !Integer.valueOf(ImConversation.TYPE_GROUP).equals(conversation.getType()))
        {
            List<ImConversationMember> parts = memberMapper.selectByConversation(conversationId);
            if (parts != null)
            {
                for (ImConversationMember p : parts)
                {
                    if (p.getMemberId() != null && !p.getMemberId().equals(memberId)) { otherId = p.getMemberId(); break; }
                }
            }
        }
        res.put("blocked", otherId != null && blockMapper.existsBetween(memberId, otherId) > 0);
        res.put("blockedByMe", otherId != null && blockMapper.selectOne(memberId, otherId) != null);
        return res;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImMessage readSentMessage(Long memberId, Long conversationId, String clientMessageId)
    {
        if (clientMessageId == null || clientMessageId.trim().isEmpty() || clientMessageId.length() > 100)
            throw new ServiceException("原消息请求无效", 400);
        if (lockReadMember(conversationMapper.selectImConversationByIdForUpdate(conversationId), memberId, conversationId) == null)
            throw new ServiceException("无权查看该会话", 403);
        return projectRouteMessage(messageMapper.selectByClientMessageIdForUpdate(
                conversationId, memberId, clientMessageId), memberId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long startSingle(Long memberId, Long targetMemberId)
    {
        if (memberId == null || memberId <= 0)
        {
            throw new ServiceException("请先登录");
        }
        if (targetMemberId == null || targetMemberId <= 0)
        {
            throw new ServiceException("对象不存在");
        }
        if (memberId.equals(targetMemberId))
        {
            throw new ServiceException("不能和自己聊天");
        }
        if (blockMapper.existsBetween(memberId, targetMemberId) > 0)
        {
            throw new ServiceException("对方暂不可联系");
        }
        String key = singleBizKey(memberId, targetMemberId);
        ImConversation c = conversationMapper.selectByTypeAndBizKey(ImConversation.TYPE_SINGLE, key);
        if (c == null)
        {
            c = new ImConversation();
            c.setType(ImConversation.TYPE_SINGLE);
            c.setBizKey(key);
            c.setStatus(0);
            conversationMapper.insertImConversation(c);
        }
        ensureMember(c.getId(), memberId, 0);
        ensureMember(c.getId(), targetMemberId, 0);
        return c.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long getOrCreateClubGroup(Long clubId, Long requesterId)
    {
        if (clubId == null || clubId <= 0)
        {
            throw new RuntimeException("俱乐部不存在");
        }
        Club club = clubMapper.selectClubByIdForUpdate(clubId);
        if (club == null)
        {
            throw new RuntimeException("俱乐部已解散，群聊不可用");
        }
        boolean owner = requesterId != null && requesterId.equals(club.getMemberId());
        boolean member = requesterId != null
                && clubMemberMapper.selectByClubAndMember(clubId, requesterId) != null;
        if (!owner && !member)
        {
            throw new RuntimeException("加入俱乐部后才能进群聊");
        }
        String key = "club_" + clubId;
        ImConversation c = conversationMapper.selectByTypeAndBizKey(ImConversation.TYPE_GROUP, key);
        if (c == null)
        {
            c = new ImConversation();
            c.setType(ImConversation.TYPE_GROUP);
            c.setBizKey(key);
            c.setStatus(0);
            conversationMapper.insertImConversation(c);
        }
        else if (!Integer.valueOf(0).equals(c.getStatus()))
        {
            throw new RuntimeException("群聊已结束");
        }
        List<Long> memberIds = new ArrayList<Long>();
        if (club.getMemberId() != null)
        {
            memberIds.add(club.getMemberId());
        }
        ClubMember memberQuery = new ClubMember();
        memberQuery.setClubId(clubId);
        memberQuery.setStatus(1);
        List<ClubMember> activeMembers = clubMemberMapper.selectClubMemberList(memberQuery);
        if (activeMembers != null)
        {
            for (ClubMember activeMember : activeMembers)
            {
                Long mid = activeMember == null ? null : activeMember.getMemberId();
                if (mid != null && mid > 0 && !memberIds.contains(mid))
                {
                    memberIds.add(mid);
                }
            }
        }
        for (Long mid : memberIds)
        {
            ensureMember(c.getId(), mid, 0);
        }
        return c.getId();
    }

    @Override
    public void leaveClubGroup(Long clubId, Long memberId)
    {
        if (clubId == null || memberId == null)
        {
            return;
        }
        ImConversation c = conversationMapper.selectByTypeAndBizKey(ImConversation.TYPE_GROUP, "club_" + clubId);
        if (c != null)
        {
            memberMapper.markDeleted(c.getId(), memberId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void closeClubGroup(Long clubId)
    {
        if (clubId == null) return;
        ImConversation conversation = conversationMapper.selectByTypeAndBizKey(
                ImConversation.TYPE_GROUP, "club_" + clubId);
        if (conversation == null) return;
        ImConversation locked = conversationMapper.selectImConversationByIdForUpdate(conversation.getId());
        if (locked == null || !Integer.valueOf(0).equals(locked.getStatus())) return;
        conversationMapper.closeConversation(locked.getId());
        memberMapper.markAllDeleted(conversation.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long getOrCreateHangoutGroup(Long hangoutId, Long requesterId, Long ownerId)
    {
        if (hangoutId == null || hangoutId <= 0) throw new RuntimeException("局不存在");
        if (requesterId == null || requesterId <= 0) throw new RuntimeException("请先登录");
        String key = "hangout_" + hangoutId;
        ImConversation conversation = conversationMapper.selectByTypeAndBizKey(ImConversation.TYPE_GROUP, key);
        if (conversation == null)
        {
            conversation = new ImConversation();
            conversation.setType(ImConversation.TYPE_GROUP);
            conversation.setBizKey(key);
            conversation.setStatus(0);
            conversationMapper.insertImConversation(conversation);
        }
        else if (!Integer.valueOf(0).equals(conversation.getStatus()))
        {
            throw new RuntimeException("群聊已结束");
        }
        if (ownerId != null && ownerId > 0) ensureMember(conversation.getId(), ownerId, 1);
        ensureMember(conversation.getId(), requesterId, requesterId.equals(ownerId) ? 1 : 0);
        return conversation.getId();
    }

    @Override
    public void leaveHangoutGroup(Long hangoutId, Long memberId)
    {
        if (hangoutId == null || memberId == null) return;
        ImConversation c = conversationMapper.selectByTypeAndBizKey(ImConversation.TYPE_GROUP, "hangout_" + hangoutId);
        if (c != null) memberMapper.markDeleted(c.getId(), memberId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void closeHangoutGroup(Long hangoutId)
    {
        if (hangoutId == null) return;
        ImConversation conversation = conversationMapper.selectByTypeAndBizKey(
                ImConversation.TYPE_GROUP, "hangout_" + hangoutId);
        if (conversation == null) return;
        // 与 closeClubGroup 同:先拿会话行锁,关闭一旦提交,旧发送请求不能再插消息
        ImConversation locked = conversationMapper.selectImConversationByIdForUpdate(conversation.getId());
        if (locked == null || !Integer.valueOf(0).equals(locked.getStatus())) return;
        conversationMapper.closeConversation(locked.getId());
        memberMapper.markAllDeleted(conversation.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long getOrCreateTeamGroup(Long teamId, Long requesterId)
    {
        if (teamId == null || teamId <= 0) throw new RuntimeException("队伍不存在");
        PlayTeam team = playTeamMapper.selectPlayTeamByIdForUpdate(teamId);
        if (team == null || Integer.valueOf(PlayTeam.STATUS_DISBANDED).equals(team.getStatus())
                || Integer.valueOf(PlayTeam.STATUS_ENDED).equals(team.getStatus()))
        {
            throw new RuntimeException("队伍已结束，群聊不可用");
        }
        if (requesterId == null || playTeamMapper.selectActiveMember(teamId, requesterId) == null)
        {
            throw new RuntimeException("加入队伍后才能进群聊");
        }
        String key = "team_" + teamId;
        ImConversation conversation = conversationMapper.selectByTypeAndBizKey(ImConversation.TYPE_GROUP, key);
        if (conversation == null)
        {
            conversation = new ImConversation();
            conversation.setType(ImConversation.TYPE_GROUP);
            conversation.setBizKey(key);
            conversation.setStatus(0);
            conversationMapper.insertImConversation(conversation);
        }
        else if (!Integer.valueOf(0).equals(conversation.getStatus()))
        {
            throw new RuntimeException("群聊已结束");
        }
        List<PlayTeamMember> activeMembers = playTeamMapper.selectActiveMembers(teamId);
        if (activeMembers != null)
        {
            for (PlayTeamMember activeMember : activeMembers)
            {
                if (activeMember != null && activeMember.getMemberId() != null)
                {
                    ensureMember(conversation.getId(), activeMember.getMemberId(),
                            Integer.valueOf(1).equals(activeMember.getRole()) ? 1 : 0);
                }
            }
        }
        return conversation.getId();
    }

    @Override
    public void leaveTeamGroup(Long teamId, Long memberId)
    {
        if (teamId == null || memberId == null) return;
        ImConversation conversation = conversationMapper.selectByTypeAndBizKey(
                ImConversation.TYPE_GROUP, "team_" + teamId);
        if (conversation != null) memberMapper.markDeleted(conversation.getId(), memberId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void closeTeamGroup(Long teamId)
    {
        if (teamId == null) return;
        ImConversation conversation = conversationMapper.selectByTypeAndBizKey(
                ImConversation.TYPE_GROUP, "team_" + teamId);
        if (conversation == null) return;
        ImConversation locked = conversationMapper.selectImConversationByIdForUpdate(conversation.getId());
        if (locked == null || !Integer.valueOf(0).equals(locked.getStatus())) return;
        conversationMapper.closeConversation(locked.getId());
        memberMapper.markAllDeleted(locked.getId());
    }

    private void ensureMember(Long convId, Long memberId, int role)
    {
        ImConversationMember m = memberMapper.selectByConvAndMember(convId, memberId);
        if (m == null)
        {
            m = new ImConversationMember();
            m.setConversationId(convId);
            m.setMemberId(memberId);
            m.setRole(role);
            memberMapper.insertImConversationMember(m);
        }
        else if (m.getDeleted() != null && m.getDeleted() == 1)
        {
            // 之前退群/隐藏过(deleted=1):重新加入时复位,否则永远看不到群(addUnread 的 SET 会置 deleted=0)
            memberMapper.addUnread(convId, memberId, 0);
        }
    }

    /** 用户可发的卡片类型白名单。generic 等官方卡仅 sendSystem/sendFromAdmin 可发。 */
    private static final Set<String> USER_CARD_TYPES =
            new HashSet<>(Arrays.asList("location", "route"));

    /** 净化结果:落库用的 extraJson + 服务端重建的 content 摘要 */
    private static class SafeCard
    {
        final String extraJson;
        final String content;
        SafeCard(String extraJson, String content) { this.extraJson = extraJson; this.content = content; }
    }

    /**
     * 用户发的卡片:只信 id / 坐标,展示字段一律服务端重建。
     * 重建结果绝不含 action / buttons / bcId —— 这三个是钓鱼载体。
     */
    private SafeCard sanitizeUserCard(String extraJson, String openid)
    {
        JSONObject in;
        try
        {
            in = JSON.parseObject(extraJson == null ? "" : extraJson);
        }
        catch (Exception e)
        {
            throw new ServiceException("卡片格式不正确");
        }
        if (in == null)
        {
            throw new ServiceException("卡片格式不正确");
        }

        String cardType = in.getString("cardType");
        if (!USER_CARD_TYPES.contains(cardType))
        {
            throw new ServiceException("不支持的卡片类型");
        }

        JSONObject out = new JSONObject();
        out.put("cardType", cardType);

        if ("location".equals(cardType))
        {
            String name = in.getString("name");
            if (name == null || name.trim().length() == 0)
            {
                throw new ServiceException("位置名称不能为空");
            }
            String address = in.getString("address");
            Double lat = in.getDouble("lat");
            Double lng = in.getDouble("lng");
            if (lat == null || lng == null
                    || lat < -90D || lat > 90D || lng < -180D || lng > 180D)
            {
                throw new ServiceException("位置坐标不合法");
            }
            // 位置名/地址是用户可控文本 —— 这正是现状 type==3 绕过的内容安全
            String secMsg = wxContentSecurityService.checkText(
                    name + " " + (address == null ? "" : address), openid);
            if (secMsg != null)
            {
                throw new ServiceException(secMsg);
            }
            out.put("name", name);
            out.put("address", address == null ? "" : address);
            out.put("lat", lat);
            out.put("lng", lng);
            return new SafeCard(out.toJSONString(), "[位置] " + name);
        }

        // route:只收 topicId,展示字段前端按 id 现拉(spec 决策 8)
        Long topicId = in.getLong("topicId");
        if (topicId == null || topicId <= 0)
        {
            throw new ServiceException("路线不存在");
        }
        // F0412:发送资格 = 匿名口径的当前公开可见(未删、审核通过、已上架、非「仅俱乐部成员可见」),
        // 这样任何收件人点开都看得到。不区分「不存在 / 已删 / 未过审 / 私密」:能猜 ID 不等于能确认对象存在。
        CmsTopic topic = cmsTopicMapper.selectCmsTopicDetailById(topicId, null);
        if (!isPublicRouteTopic(topic))
        {
            throw new ServiceException(ROUTE_CARD_UNAVAILABLE, 404);
        }
        out.put("topicId", topicId);
        // 落库摘要不写主题名:主题之后下架或转私密时摘要无法收回;展示由 projectRouteMessage 按 topicId 现拉。
        return new SafeCard(out.toJSONString(), ROUTE_CARD_SUMMARY);
    }

    /** 路线卡的落库摘要。只说「这是一张路线卡」,不带主题名。 */
    static final String ROUTE_CARD_SUMMARY = "[路线]";

    /** 路线当前不能发送时的统一提示,不暗示下架/私密等具体原因。 */
    static final String ROUTE_CARD_UNAVAILABLE = "该路线暂不可用";

    private static boolean isPublicRouteTopic(CmsTopic topic)
    {
        return topic != null && Integer.valueOf(1).equals(topic.getStatus())
                && Integer.valueOf(1).equals(topic.getUserStatus())
                && (topic.getDelFlag() == null || Integer.valueOf(0).equals(topic.getDelFlag()))
                && !Integer.valueOf(1).equals(topic.getClubMemberOnly());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImMessage send(Long memberId, Long conversationId, Long targetMemberId, Integer msgType, String content, String extraJson)
    {
        return send(memberId, conversationId, targetMemberId, msgType, content, extraJson, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImMessage send(Long memberId, Long conversationId, Long targetMemberId, Integer msgType,
                          String content, String extraJson, String clientMessageId)
    {
        if (clientMessageId != null && !clientMessageId.matches("[A-Za-z0-9_-]{16,64}"))
        {
            throw new ServiceException("消息标识格式不正确", 400);
        }
        if (memberId == null || memberId <= 0)
        {
            throw new ServiceException("请先登录");
        }
        if (blockMapper.existsBetween(0L, memberId) > 0)
        {
            throw new ServiceException("账号已被限制发言");
        }
        int type = msgType == null ? ImMessage.MSG_TEXT : msgType;
        if (content == null || content.trim().length() == 0)
        {
            throw new ServiceException("内容不能为空");
        }

        Long convId = conversationId;
        if (convId == null || convId <= 0)
        {
            if (targetMemberId == null)
            {
                throw new ServiceException("缺少会话或对象");
            }
            convId = startSingle(memberId, targetMemberId);
        }
        ImConversation observed = conversationMapper.selectImConversationById(convId);
        if (observed != null && Integer.valueOf(ImConversation.TYPE_GROUP).equals(observed.getType()))
        {
            Long governedClubId = clubIdOf(observed);
            if (governedClubId != null)
            {
                // 不能只信 /api/club/chat 入口：旧 conversation_id 可直接打 /api/im/send。
                clubParticipationGuard.requireAllowed(governedClubId, memberId);
            }
            if (!hasActiveGroupAccess(observed, memberId, true))
            {
                throw new ServiceException(hangoutIdOfBizKey(observed.getBizKey()) != null ? "已退出该局，不能继续发送群消息"
                        : teamIdOf(observed) == null
                        ? "已退出俱乐部，不能继续发送群消息" : "已退出队伍，不能继续发送群消息", 403);
            }
        }
        requireConversationMembership(convId, memberId, "无权在该会话发送");

        // 与 closeClubGroup 共用会话行锁：关闭一旦提交，旧发送请求不能再在其后插入消息/复活未读。
        ImConversation conv = conversationMapper.selectImConversationByIdForUpdate(convId);
        if (conv != null && Integer.valueOf(ImConversation.TYPE_SYSTEM).equals(conv.getType()))
        {
            throw new ServiceException("系统会话不允许用户发送消息");
        }
        ImConversationMember currentSender = memberMapper.selectByConvAndMemberForUpdate(convId, memberId);
        if (currentSender == null || Integer.valueOf(1).equals(currentSender.getDeleted()))
        {
            throw new ServiceException("无权在该会话发送", 403);
        }
        boolean isGroup = conv != null && conv.getType() != null && conv.getType() == ImConversation.TYPE_GROUP;
        if (isGroup && !Integer.valueOf(0).equals(conv.getStatus()))
        {
            throw closedConversationError();
        }

        Long receiverId = 0L;
        List<ImConversationMember> parts = memberMapper.selectByConversation(convId);
        if (!isGroup && parts != null)
        {
            for (ImConversationMember p : parts)
            {
                if (!p.getMemberId().equals(memberId))
                {
                    receiverId = p.getMemberId();
                    break;
                }
            }
        }
        if (!isGroup && receiverId > 0 && blockMapper.existsBetween(memberId, receiverId) > 0)
        {
            throw new ServiceException("对方暂不可联系");
        }

        String requestHash = clientMessageId == null ? null
                : CanonicalDigest.sha256Hex(JSON.toJSONString(Arrays.asList(type, content, extraJson)));
        if (clientMessageId != null)
        {
            ImMessage previous = messageMapper.selectByClientMessageIdForUpdate(convId, memberId, clientMessageId);
            if (previous != null)
            {
                if (!requestHash.equals(previous.getRequestHash()))
                {
                    throw new ServiceException("消息标识已用于其他内容", 409);
                }
                if (Integer.valueOf(ImMessage.STATUS_BLOCKED).equals(previous.getStatus()))
                {
                    throw new ServiceException("该消息未通过审核", 409);
                }
                return projectRouteMessage(previous, memberId);
            }
        }

        String openid = openidOf(memberId);
        int checkStatus = 1;
        String traceId = null;
        if (type == ImMessage.MSG_TEXT)
        {
            String secMsg = wxContentSecurityService.checkText(content, openid);
            if (secMsg != null)
            {
                throw new ServiceException(secMsg);
            }
        }
        else if (type == ImMessage.MSG_IMAGE)
        {
            traceId = wxContentSecurityService.checkMediaAsync(content, openid);
            if (traceId == null || traceId.trim().length() == 0)
            {
                throw new ServiceException("内容审核服务繁忙，请稍后重试");
            }
            checkStatus = 0;
        }
        else if (type == ImMessage.MSG_CARD)
        {
            SafeCard safe = sanitizeUserCard(extraJson, openid);
            extraJson = safe.extraJson;
            content = safe.content;
        }
        else if (type == ImMessage.MSG_POLL)
        {
            if (!isGroup)
            {
                throw new ServiceException("投票只能发送到群聊");
            }
            JSONObject supplied;
            try
            {
                supplied = JSON.parseObject(extraJson == null ? "" : extraJson);
            }
            catch (Exception e)
            {
                throw new ServiceException("投票卡片格式不正确");
            }
            Long pollId = supplied == null ? null : supplied.getLong("pollId");
            com.chengyinhub.business.domain.ImPoll poll = pollId == null ? null : pollMapper.selectById(pollId);
            if (poll == null || !convId.equals(poll.getConversationId())
                    || !memberId.equals(poll.getCreatorMemberId()))
            {
                throw new ServiceException("投票不存在或不属于当前群聊");
            }
            JSONObject safe = new JSONObject();
            safe.put("pollId", pollId);
            safe.put("cardType", "poll");
            extraJson = safe.toJSONString();
            content = "[投票] " + poll.getQuestion();
        }
        else
        {
            throw new ServiceException("不支持的消息类型");
        }

        ImMessage msg = new ImMessage();
        msg.setConversationId(convId);
        msg.setSenderId(memberId);
        msg.setReceiverId(receiverId);
        msg.setMsgType(type);
        msg.setContent(content);
        msg.setExtraJson(extraJson);
        msg.setStatus(ImMessage.STATUS_NORMAL);
        msg.setCheckStatus(checkStatus);
        msg.setTraceId(traceId);
        msg.setClientMessageId(clientMessageId);
        msg.setRequestHash(requestHash);
        messageMapper.insertImMessage(msg);
        if (type == ImMessage.MSG_IMAGE && traceId != null
                && !wxContentCallbackInboxService.register(traceId,
                        IWxContentSecurityService.BIZ_TYPE_IM_MESSAGE, msg.getId(), content))
        {
            // send 已有 REQUIRED 事务：登记失败抛出，IM insert 与会话副作用一并回滚。
            throw new ServiceException("图片审核任务登记失败，请稍后重试");
        }

        String summary = type == ImMessage.MSG_TEXT ? content
                : (type == ImMessage.MSG_IMAGE ? "[图片]"
                : ((type == ImMessage.MSG_CARD || type == ImMessage.MSG_POLL) ? content : "[消息]"));
        if (summary != null && summary.length() > 200)
        {
            summary = summary.substring(0, 200);
        }
        conversationMapper.updateLastMessage(convId, type, summary, new Date());
        if (type != ImMessage.MSG_IMAGE && isGroup)
        {
            // 图片在审核通过后才给收件人增加未读。
            // 群:给除发送者外的所有成员各 +1 未读(替代单聊的单一 receiver 扇出)
            if (parts != null)
            {
                for (ImConversationMember p : parts)
                {
                    if (!p.getMemberId().equals(memberId))
                    {
                        memberMapper.addUnreadForActiveMember(convId, p.getMemberId(), 1);
                    }
                }
            }
        }
        else if (type != ImMessage.MSG_IMAGE && receiverId > 0)
        {
            memberMapper.addUnread(convId, receiverId, 1);
        }
        memberMapper.markRead(convId, memberId, msg.getId());
        return projectRouteMessage(msg, memberId);
    }

    private Long clubIdOf(ImConversation conversation)
    {
        if (conversation == null || conversation.getBizKey() == null
                || !conversation.getBizKey().startsWith("club_"))
        {
            return null;
        }
        try
        {
            return Long.valueOf(conversation.getBizKey().substring("club_".length()));
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private Long teamIdOf(ImConversation conversation)
    {
        return conversation == null ? null : teamIdOfBizKey(conversation.getBizKey());
    }

    private Long hangoutIdOfBizKey(String bizKey)
    {
        if (bizKey == null || !bizKey.startsWith("hangout_")) return null;
        try
        {
            return Long.valueOf(bizKey.substring("hangout_".length()));
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private Long teamIdOfBizKey(String bizKey)
    {
        if (bizKey == null || !bizKey.startsWith("team_")) return null;
        try
        {
            return Long.valueOf(bizKey.substring("team_".length()));
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    /** 读写入口共用 IM 成员闸；deleted 脏行不能继续作为授权凭据。 */
    private ImConversationMember requireConversationMembership(Long conversationId, Long memberId,
                                                               String errorMessage)
    {
        ImConversationMember membership = memberMapper.selectByConvAndMember(conversationId, memberId);
        if (membership == null || Integer.valueOf(1).equals(membership.getDeleted()))
        {
            throw new ServiceException(errorMessage, 403);
        }
        return membership;
    }

    @Override
    public void requireActiveGroupMembership(Long memberId, Long conversationId)
    {
        if (memberId == null || memberId <= 0 || conversationId == null || conversationId <= 0)
        {
            throw new ServiceException("请先登录并选择群聊", 401);
        }
        requireConversationMembership(conversationId, memberId, "无权操作该群聊");
        ImConversation conversation = conversationMapper.selectImConversationById(conversationId);
        if (conversation == null || !Integer.valueOf(ImConversation.TYPE_GROUP).equals(conversation.getType())
                || !Integer.valueOf(0).equals(conversation.getStatus()))
        {
            throw new ServiceException("群聊不存在或已结束", 404);
        }
        if (!hasActiveGroupAccess(conversation, memberId, false))
        {
            throw new ServiceException("已退出群聊，无权操作投票", 403);
        }
    }

    @Override
    public boolean isActiveGroupMember(Long memberId, Long conversationId)
    {
        try
        {
            requireActiveGroupMembership(memberId, conversationId);
            return true;
        }
        catch (ServiceException e)
        {
            return false;
        }
    }

    /** 群聊权限以实时俱乐部/队伍关系为真源；IM 成员行只能作为会话索引，不能独立授权。 */
    private boolean hasActiveGroupAccess(ImConversation conversation, Long memberId, boolean forUpdate)
    {
        if (conversation == null || !Integer.valueOf(ImConversation.TYPE_GROUP).equals(conversation.getType()))
        {
            return true;
        }
        Long teamId = teamIdOf(conversation);
        if (teamId != null)
        {
            PlayTeam team = forUpdate ? playTeamMapper.selectPlayTeamByIdForUpdate(teamId)
                    : playTeamMapper.selectPlayTeamById(teamId);
            return team != null && memberId != null
                    && !Integer.valueOf(PlayTeam.STATUS_ENDED).equals(team.getStatus())
                    && !Integer.valueOf(PlayTeam.STATUS_DISBANDED).equals(team.getStatus())
                    && playTeamMapper.selectActiveMember(teamId, memberId) != null;
        }
        Long hangoutId = hangoutIdOfBizKey(conversation.getBizKey());
        if (hangoutId != null)
        {
            // 局群成员真源就是群成员表本身:局还开着 + 我还在群里(未退)即可
            // 每条消息都走这里:非锁路径只取状态,别把两个成员计数子查询也带上
            Integer status;
            if (forUpdate)
            {
                RoamHangout locked = roamHangoutMapper.selectByIdForUpdate(hangoutId);
                status = locked == null ? null : locked.getStatus();
            }
            else
            {
                status = roamHangoutMapper.selectStatusById(hangoutId);
            }
            if (status == null || memberId == null || !Integer.valueOf(RoamHangout.STATUS_OPEN).equals(status))
            {
                return false;
            }
            ImConversationMember mine = memberMapper.selectByConvAndMember(conversation.getId(), memberId);
            return mine != null && !Integer.valueOf(1).equals(mine.getDeleted());
        }
        Long clubId = clubIdOf(conversation);
        Club club = clubId == null ? null : (forUpdate
                ? clubMapper.selectClubByIdForUpdate(clubId) : clubMapper.selectClubById(clubId));
        if (club == null || memberId == null || !Long.valueOf(1L).equals(club.getStatus())
                || !Integer.valueOf(0).equals(club.getDelFlag()))
        {
            return false;
        }
        return memberId.equals(club.getMemberId())
                || clubMemberMapper.selectByClubAndMember(clubId, memberId) != null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long memberId, Long conversationId)
    {
        ImConversation conversation = conversationMapper.selectImConversationByIdForShare(conversationId);
        // 当前读最新可见消息：RR 旧快照不能把已读截止点停在锁外新提交之前；
        // 锁序 会话(S)→消息(S)→成员(X)，与媒体审核放行的「消息→会话→成员」同向。
        ImMessage latest = messageMapper.selectLatestViewerVisibleForShare(conversationId, memberId);
        ImConversationMember mine = lockReadMember(conversation, memberId, conversationId);
        if (mine == null)
        {
            return;
        }
        Long lastId = latest == null ? 0L : latest.getId();
        String cutoff = latest == null ? "1970-01-01 00:00:00.000000" : latest.getVisibleOrderAt();
        if (memberMapper.markReadVisible(conversationId, memberId, lastId, cutoff) <= 0) {
            throw new ServiceException("消息可见状态已变化，请刷新后重试");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long memberId, Long conversationId, Long readTo)
    {
        if (readTo == null) { markRead(memberId, conversationId); return; }
        if (readTo < 0) throw new ServiceException("已读位置无效", 400);
        ImConversation conversation = conversationMapper.selectImConversationByIdForShare(conversationId);
        // 已读位沿用可见序游标：截止点取 min(readTo, 当前最新可见)，且不回退到已存已读位之前；
        // readTo 对当前读者不可见(待审图片/已屏蔽/伪造)时不前进。锁序同 markRead(会话S→消息S→成员X)。
        ImMessage latest = messageMapper.selectLatestViewerVisibleForShare(conversationId, memberId);
        ImMessage target = readTo == 0 ? null
                : messageMapper.selectViewerVisibleByIdForShare(conversationId, memberId, readTo);
        ImConversationMember mine = lockReadMember(conversation, memberId, conversationId);
        if (mine == null) return;
        ImMessage previous = mine.getLastReadMsgId() == null || mine.getLastReadMsgId() <= 0 ? null
                : messageMapper.selectViewerVisibleByIdForShare(conversationId, memberId, mine.getLastReadMsgId());
        ImMessage cutoff = target != null && latest != null && compareVisibleOrder(target, latest) > 0 ? latest : target;
        if (cutoff == null || (previous != null && compareVisibleOrder(previous, cutoff) > 0)) {
            cutoff = previous;
        }
        Long lastId = cutoff == null ? 0L : cutoff.getId();
        String order = cutoff == null ? "1970-01-01 00:00:00.000000" : cutoff.getVisibleOrderAt();
        if (memberMapper.markReadVisible(conversationId, memberId, lastId, order) <= 0) {
            throw new ServiceException("消息可见状态已变化，请刷新后重试");
        }
    }

    private static int compareVisibleOrder(ImMessage a, ImMessage b)
    {
        String aOrder = a.getVisibleOrderAt() == null ? "" : a.getVisibleOrderAt();
        String bOrder = b.getVisibleOrderAt() == null ? "" : b.getVisibleOrderAt();
        int order = aOrder.compareTo(bOrder);
        return order != 0 ? order : Long.compare(a.getId() == null ? 0L : a.getId(), b.getId() == null ? 0L : b.getId());
    }

    private ImConversationMember lockReadMember(ImConversation conversation, Long memberId, Long conversationId)
    {
        ImConversationMember mine = memberMapper.selectByConvAndMemberForUpdate(conversationId, memberId);
        if (mine == null)
        {
            return null;
        }
        if (Integer.valueOf(1).equals(mine.getDeleted())
                || !hasActiveGroupAccess(conversation, memberId, false))
        {
            throw new ServiceException("无权操作该会话", 403);
        }
        if (conversation != null && Integer.valueOf(ImConversation.TYPE_GROUP).equals(conversation.getType())
                && !Integer.valueOf(0).equals(conversation.getStatus()))
        {
            throw closedConversationError();
        }
        return mine;
    }

    private ServiceException closedConversationError()
    {
        return new ServiceException("群聊已结束", 409).setDetailMessage("IM_CONVERSATION_CLOSED");
    }

    @Override
    public void deleteConversation(Long memberId, Long conversationId)
    {
        if (memberId == null || conversationId == null)
        {
            return;
        }
        memberMapper.markDeleted(conversationId, memberId);
    }

    @Override
    public void setMuted(Long memberId, Long conversationId, boolean muted)
    {
        ImConversationMember mine = memberMapper.selectByConvAndMember(conversationId, memberId);
        if (mine == null)
        {
            throw new RuntimeException("无权操作该会话");
        }
        memberMapper.updateMuted(conversationId, memberId, muted ? 1 : 0);
    }

    @Override
    public int unreadTotal(Long memberId)
    {
        int total = 0;
        for (Map<String, Object> row : listConversations(memberId))
        {
            if (Integer.valueOf(1).equals(row.get("muted")))
            {
                continue;
            }
            // A-06:群聊(type=4)在小程序不露出(im/list 显式过滤),总红点必须与列表同口径排除 ——
            // 否则用户会看到一个点进去也清不掉的角标(未读在被隐藏的群会话里)。
            if (Integer.valueOf(ImConversation.TYPE_GROUP).equals(row.get("type")))
            {
                continue;
            }
            Object unread = row.get("unread");
            if (unread instanceof Number)
            {
                total += ((Number) unread).intValue();
            }
        }
        return total;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long sendSystem(Long toMemberId, Integer msgType, String content, String extraJson)
    {
        return systemMessage(toMemberId, msgType, content, extraJson, null);
    }

    private Long systemMessage(Long toMemberId, Integer msgType, String content, String extraJson, String clientMessageId)
    {
        if (toMemberId == null || toMemberId <= 0)
        {
            throw new RuntimeException("接收人不存在");
        }
        String key = "sys_" + toMemberId;
        ImConversation c = conversationMapper.selectByTypeAndBizKey(ImConversation.TYPE_SYSTEM, key);
        if (c == null)
        {
            c = new ImConversation();
            c.setType(ImConversation.TYPE_SYSTEM);
            c.setBizKey(key);
            c.setStatus(0);
            conversationMapper.insertImConversation(c);
        }
        conversationMapper.selectImConversationByIdForUpdate(c.getId());
        ensureMember(c.getId(), toMemberId, 0);
        if (clientMessageId != null) {
            ImMessage existing = messageMapper.selectByClientMessageIdForUpdate(c.getId(), 0L, clientMessageId);
            if (existing != null) return existing.getId();
        }

        int type = msgType == null ? ImMessage.MSG_CARD : msgType;
        ImMessage msg = new ImMessage();
        msg.setConversationId(c.getId());
        msg.setSenderId(0L);
        msg.setReceiverId(toMemberId);
        msg.setMsgType(type);
        msg.setContent(content);
        msg.setExtraJson(extraJson);
        msg.setClientMessageId(clientMessageId);
        msg.setStatus(ImMessage.STATUS_NORMAL);
        msg.setCheckStatus(1);
        messageMapper.insertImMessage(msg);

        String summary = (content != null && content.length() > 200) ? content.substring(0, 200) : content;
        conversationMapper.updateLastMessage(c.getId(), type, summary, new Date());
        memberMapper.addUnread(c.getId(), toMemberId, 1);
        return msg.getId();
    }

    @Override
    @Transactional(propagation = Propagation.NESTED, rollbackFor = Exception.class)
    public Long sendSystemInNestedTransaction(Long toMemberId, Integer msgType, String content, String extraJson)
    {
        return sendSystem(toMemberId, msgType, content, extraJson);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long findIntentId(String eventKey) {
        if (eventKey == null || !eventKey.matches("[A-Za-z0-9:_-]{1,96}"))
            throw new ServiceException("通知原事件身份无效", 400);
        Map<String, Object> intent = notificationIntentMapper.selectByKeyForUpdate(eventKey);
        return intent == null ? null : ((Number) intent.get("id")).longValue();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long enqueue(Long recipient, Integer msgType, String content, String extraJson, String eventKey) {
        return enqueueSystemNotification(recipient, msgType, content, extraJson, eventKey);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long enqueueSystemNotification(Long toMemberId, Integer msgType, String content, String extraJson, String eventKey)
    {
        if (toMemberId == null || toMemberId <= 0 || eventKey == null
                || !eventKey.matches("[A-Za-z0-9:_-]{1,96}"))
            throw new ServiceException("通知原事件身份无效", 400);
        int type = msgType == null ? ImMessage.MSG_CARD : msgType;
        // JSON数组编码避免null/分隔符拼接歧义；原payload首次固定，重试不读live业务对象。
        String hash = CanonicalDigest.sha256Hex(JSON.toJSONString(
                Arrays.asList(toMemberId, type, content, extraJson)));
        notificationIntentMapper.insert(eventKey, toMemberId, type, content, extraJson, hash);
        Map<String, Object> intent = notificationIntentMapper.selectByKeyForUpdate(eventKey);
        if (intent == null || !hash.equals(intent.get("requestHash")))
            throw new ServiceException("通知原事件内容冲突", 409);
        return ((Number) intent.get("id")).longValue();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long deliverSystemNotification(Long intentId)
    {
        Map<String, Object> intent = notificationIntentMapper.selectByIdForUpdate(intentId);
        if (intent == null) throw new ServiceException("原通知不存在", 404);
        if ("DELIVERED".equals(intent.get("status")))
            return ((Number) intent.get("messageId")).longValue();
        if (!"PENDING".equals(intent.get("status"))) throw new ServiceException("原通知状态待核", 409);
        Long messageId = systemMessage(((Number) intent.get("recipient")).longValue(),
                ((Number) intent.get("msgType")).intValue(), (String) intent.get("content"),
                (String) intent.get("extraJson"), "notification_" + intentId);
        if (notificationIntentMapper.delivered(intentId, messageId) != 1)
            throw new IllegalStateException("原通知收据写入失败");
        return messageId;
    }

    @Override
    public void checkUserGeneratedText(Long senderMemberId, String content)
    {
        if (senderMemberId == null || senderMemberId <= 0)
        {
            throw new RuntimeException("请先登录");
        }
        if (content == null || content.trim().length() == 0)
        {
            throw new RuntimeException("内容不能为空");
        }
        String secMsg = wxContentSecurityService.checkText(content, openidOf(senderMemberId));
        if (secMsg != null)
        {
            throw new RuntimeException(secMsg);
        }
    }

    @Override
    public void block(Long memberId, Long targetMemberId)
    {
        if (memberId == null || targetMemberId == null || memberId.equals(targetMemberId))
        {
            throw new ServiceException("参数有误");
        }
        ImBlock b = new ImBlock();
        b.setMemberId(memberId);
        b.setBlockedMemberId(targetMemberId);
        blockMapper.insertImBlock(b);
    }

    @Override
    public void unblock(Long memberId, Long targetMemberId)
    {
        if (memberId == null || targetMemberId == null)
        {
            return;
        }
        blockMapper.deleteImBlock(memberId, targetMemberId);
    }

    @Override
    public boolean isBlockedBetween(Long memberId, Long targetMemberId)
    {
        if (memberId == null || targetMemberId == null || memberId <= 0 || targetMemberId <= 0)
        {
            return false;
        }
        return blockMapper.existsBetween(memberId, targetMemberId) > 0;
    }

    @Override
    public void report(Long memberId, Long messageId, String reason)
    {
        ImMessage m = messageMapper.selectImMessageById(messageId);
        if (m == null)
        {
            throw new ServiceException("消息不存在");
        }
        // RV-p3 F2:举报人也必须受会话边界约束 —— 非本会话成员按 id 直调 API 不能举报,
        // 也不能举报自己发的消息(前端已拦,后端这里兜住 App 与其他直调方)。
        requireConversationMembership(m.getConversationId(), memberId, "无权举报该会话的消息");
        if (m.getSenderId() != null && m.getSenderId().equals(memberId))
        {
            throw new ServiceException("不能举报自己的消息");
        }
        // 幂等:同一条消息多次举报汇聚到同一未结审核单 + 每次 append REPORT 记录(与 comment/square 统一走 reportContent)
        auditTaskService.reportContent("IM_MESSAGE", messageId, memberId, reason);
    }

    // ===== 后台客服端 =====

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long sendFromAdmin(Long toMemberId, Integer msgType, String content, String extraJson)
    {
        return sendAdminMessage(toMemberId, msgType, content, extraJson, null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long sendFromAdmin(Long adminId, Long toMemberId, Integer msgType, String content,
            String extraJson, String requestId)
    {
        String commandKey = adminCommandKey(adminId, toMemberId, requestId);
        int type = msgType == null ? ImMessage.MSG_TEXT : msgType;
        String hash = CanonicalDigest.sha256Hex(JSON.toJSONString(
                Arrays.asList(adminId, toMemberId, type, content, extraJson)));
        return sendAdminMessage(toMemberId, type, content, extraJson, commandKey, hash);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> adminSendReceipt(Long adminId, Long toMemberId, String requestId)
    {
        String commandKey = adminCommandKey(adminId, toMemberId, requestId);
        Map<String, Object> receipt = new HashMap<>();
        receipt.put("found", false);
        receipt.put("requestId", requestId);
        receipt.put("memberId", String.valueOf(toMemberId));
        // The administrator permission is checked at the HTTP boundary. Resolve only the
        // recipient's existing customer-service conversation, never a caller-supplied conversation.
        ImConversation c = conversationMapper.selectByTypeAndBizKey(
                ImConversation.TYPE_MERCHANT, "cs_" + toMemberId);
        if (c == null) return receipt;
        ImMessage message = messageMapper.selectByClientMessageId(c.getId(), 0L, commandKey);
        if (message == null) return receipt;
        if (!toMemberId.equals(message.getReceiverId()))
            throw new ServiceException("原消息接收人不匹配", 409);
        receipt.put("found", true);
        receipt.put("messageId", String.valueOf(message.getId()));
        receipt.put("msgType", message.getMsgType());
        receipt.put("content", message.getContent());
        receipt.put("extraJson", message.getExtraJson());
        receipt.put("status", message.getStatus());
        return receipt;
    }

    private String adminCommandKey(Long adminId, Long toMemberId, String requestId)
    {
        if (adminId == null || adminId <= 0 || toMemberId == null || toMemberId <= 0)
            throw new ServiceException("管理员或接收人无效", 400);
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{8,96}"))
            throw new ServiceException("须携带原发送请求号", 400);
        return CanonicalDigest.sha256Hex(JSON.toJSONString(
                Arrays.asList("admin-send", adminId, toMemberId, requestId)));
    }

    private Long sendAdminMessage(Long toMemberId, Integer msgType, String content, String extraJson,
            String commandKey, String requestHash)
    {
        if (toMemberId == null || toMemberId <= 0)
        {
            throw new RuntimeException("接收人不存在");
        }
        if (content == null || content.trim().length() == 0)
        {
            throw new RuntimeException("内容不能为空");
        }
        String key = "cs_" + toMemberId;
        ImConversation c = conversationMapper.selectByTypeAndBizKey(ImConversation.TYPE_MERCHANT, key);
        if (c == null)
        {
            c = new ImConversation();
            c.setType(ImConversation.TYPE_MERCHANT);
            c.setBizKey(key);
            c.setStatus(0);
            conversationMapper.insertImConversation(c);
        }
        conversationMapper.selectImConversationByIdForUpdate(c.getId());
        // Serialize the receipt check and all message/unread effects in the existing transaction.
        // A concurrent first-conversation insert may fail; the caller retains this same request key.
        if (commandKey != null)
        {
            ImMessage previous = messageMapper.selectByClientMessageIdForUpdate(c.getId(), 0L, commandKey);
            if (previous != null)
            {
                if (!requestHash.equals(previous.getRequestHash()))
                    throw new ServiceException("原请求号已用于其他消息内容", 409);
                return previous.getId();
            }
        }
        ensureMember(c.getId(), toMemberId, 0);

        int type = msgType == null ? ImMessage.MSG_TEXT : msgType;
        ImMessage msg = new ImMessage();
        msg.setConversationId(c.getId());
        msg.setSenderId(0L);
        msg.setReceiverId(toMemberId);
        msg.setMsgType(type);
        msg.setClientMessageId(commandKey);
        msg.setRequestHash(requestHash);
        msg.setContent(content);
        msg.setExtraJson(extraJson);
        msg.setStatus(ImMessage.STATUS_NORMAL);
        msg.setCheckStatus(1);
        messageMapper.insertImMessage(msg);

        String summary = content.length() > 200 ? content.substring(0, 200) : content;
        if (type == ImMessage.MSG_IMAGE) summary = "[图片]";
        conversationMapper.updateLastMessage(c.getId(), type, summary, new Date());
        memberMapper.addUnread(c.getId(), toMemberId, 1);
        return msg.getId();
    }

    @Override
    public List<Map<String, Object>> adminListConversations()
    {
        List<ImConversation> convs = conversationMapper.selectByType(ImConversation.TYPE_MERCHANT);
        List<Map<String, Object>> out = new ArrayList<>();
        if (convs == null)
        {
            return out;
        }
        for (ImConversation c : convs)
        {
            // 客服会话只有用户一个真实参与者
            Long userId = null;
            List<ImConversationMember> parts = memberMapper.selectByConversation(c.getId());
            if (parts != null)
            {
                for (ImConversationMember p : parts)
                {
                    if (p.getMemberId() != null && p.getMemberId() > 0)
                    {
                        userId = p.getMemberId();
                        break;
                    }
                }
            }
            Map<String, Object> row = new HashMap<>();
            row.put("conversationId", c.getId());
            row.put("memberId", userId);
            row.put("lastMsgText", c.getLastMsgText());
            row.put("lastMsgAt", c.getLastMsgAt());
            // 待回复:最后一条来自用户(senderId!=0) → 客服需回复
            List<ImMessage> latest = messageMapper.selectPage(c.getId(), null, 1);
            boolean needReply = latest != null && !latest.isEmpty()
                    && latest.get(0).getSenderId() != null && latest.get(0).getSenderId() != 0;
            row.put("needReply", needReply);
            if (userId != null)
            {
                UmsMember u = umsMemberService.selectUmsMemberById(userId);
                row.put("nickname", u != null ? (u.getNickname() != null ? u.getNickname() : u.getName()) : "");
                row.put("avatar", u != null ? u.getAvatar() : "");
                // 脱敏后再下发:客服认人靠 nickname/avatar 就够,不需要完整手机号。
                // ⚠️ 这里塞的是 HashMap 不是 UmsMember 对象,所以给 domain 加 @Sensitive 之类的
                // 注解对它无效(序列化器只认 domain 字段),只能在这儿显式 mask。
                row.put("phone", u != null ? PiiCryptoService.maskPhone(u.getPhone()) : "");
                row.put("blocked", isPlatformBlocked(userId));
            }
            out.add(row);
        }
        return out;
    }

    /** 已被移出关闭局群的历史成员只能得到终态，不得读取旧消息或重新获得会话权限。 */
    private boolean isClosedHangoutForHistoricalMember(ImConversation conversation, Long memberId)
    {
        if (conversation == null || !Integer.valueOf(ImConversation.TYPE_GROUP).equals(conversation.getType())
                || Integer.valueOf(0).equals(conversation.getStatus())
                || hangoutIdOfBizKey(conversation.getBizKey()) == null) return false;
        return memberId != null && memberMapper.selectByConvAndMember(conversation.getId(), memberId) != null;
    }

    @Override
    public Map<String, Object> adminListMessages(Long conversationId, Long cursorId, int size)
    {
        // 客服只能看客服会话。不校验的话,conversationId 是原样递给 mapper 的裸主键,
        // 持 business:im:list 的账号遍历 ID 即可读到 TYPE_SINGLE(用户之间的私聊)全文。
        // 前端只会递交 adminListConversations 给出的客服会话 ID(那边已按 TYPE_MERCHANT 收敛),
        // 所以这个洞只有从接口层能打到、纯看页面发现不了。
        // C 端 listMessages 早有同款守卫(见上方"无权查看该会话"),后台这条一直漏着。
        ImConversation conv = conversationMapper.selectImConversationById(conversationId);
        if (conv == null || conv.getType() == null || conv.getType() != ImConversation.TYPE_MERCHANT)
        {
            throw new RuntimeException("无权查看该会话");
        }
        int s = size <= 0 ? 30 : Math.min(size, 100);
        List<ImMessage> desc = messageMapper.selectPage(conversationId, cursorId, s);
        List<ImMessage> asc = new ArrayList<>(desc);
        Collections.reverse(asc);
        Map<String, Object> res = new HashMap<>();
        res.put("list", asc);
        res.put("nextCursor", desc.isEmpty() ? null : desc.get(desc.size() - 1).getId());
        res.put("hasMore", desc.size() >= s);
        return res;
    }

    @Override
    public void adminBlock(Long targetMemberId)
    {
        if (targetMemberId == null || targetMemberId <= 0)
        {
            throw new RuntimeException("用户不存在");
        }
        ImBlock b = new ImBlock();
        b.setMemberId(0L);
        b.setBlockedMemberId(targetMemberId);
        blockMapper.insertImBlock(b);
    }

    @Override
    public void adminUnblock(Long targetMemberId)
    {
        if (targetMemberId == null || targetMemberId <= 0)
        {
            return;
        }
        blockMapper.deleteImBlock(0L, targetMemberId);
    }

    @Override
    public boolean isPlatformBlocked(Long memberId)
    {
        if (memberId == null)
        {
            return false;
        }
        return blockMapper.selectOne(0L, memberId) != null;
    }
}
