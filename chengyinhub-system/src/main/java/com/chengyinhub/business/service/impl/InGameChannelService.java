package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ImConversation;
import com.chengyinhub.business.domain.ImConversationMember;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.domain.PlayTeamMember;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.CityGameMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.ImConversationMapper;
import com.chengyinhub.business.mapper.ImConversationMemberMapper;
import com.chengyinhub.business.mapper.ImMessageMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.support.CanonicalDigest;
import com.chengyinhub.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * In-game IM channel coordinator built on top of ImConversation (TYPE_GROUP = 4).
 *
 * <p>Channel biz_key conventions (all fit im_conversation.biz_key VARCHAR(64)):
 * <ul>
 *   <li>{@code activity_<id>} - Activity session channel (auto-joined on registration)</li>
 *   <li>{@code topic_<id>} - Topic self-play session channel</li>
 *   <li>{@code roam_<district>} - Nearby / district roam channel</li>
 *   <li>{@code city_team_<uuid>} - CITY board team channel</li>
 *   <li>{@code team_<id>} - Existing play team channel</li>
 * </ul>
 */
@Service
public class InGameChannelService
{
    private static final Logger log = LoggerFactory.getLogger(InGameChannelService.class);

    public static final String ACTIVITY_PREFIX = "activity_";
    public static final String TOPIC_PREFIX = "topic_";
    public static final String ROAM_PREFIX = "roam_";
    public static final String CITY_TEAM_PREFIX = "city_team_";
    public static final String TEAM_PREFIX = "team_";

    public static final String EVENT_CHECKIN = "checkin";
    public static final String EVENT_CLAIM = "claim";
    public static final String EVENT_TAKEOVER = "takeover";
    public static final String EVENT_FINISH = "finish";

    @Autowired(required = false)
    private ImConversationMapper conversationMapper;
    @Autowired(required = false)
    private ImConversationMemberMapper memberMapper;
    @Autowired(required = false)
    private ImMessageMapper messageMapper;
    @Autowired(required = false)
    private IUmsMemberService umsMemberService;
    @Autowired(required = false)
    private CmsActivityMapper cmsActivityMapper;
    @Autowired(required = false)
    private CmsTopicMapper cmsTopicMapper;
    @Autowired(required = false)
    private PlayTeamMapper playTeamMapper;
    @Autowired(required = false)
    private CityGameMapper cityGameMapper;
    @Autowired(required = false)
    private com.chengyinhub.business.mapper.RoamMapper roamMapper;
    @Autowired(required = false)
    @Lazy
    private IImService imService;

    public static boolean isInGameBizKey(String bizKey)
    {
        return bizKey != null && (bizKey.startsWith(ACTIVITY_PREFIX)
                || bizKey.startsWith(TOPIC_PREFIX)
                || bizKey.startsWith(ROAM_PREFIX)
                || bizKey.startsWith(CITY_TEAM_PREFIX));
    }

    public static String roamBizKey(String district)
    {
        String normalized = normalizeDistrict(district);
        if ("nearby".equalsIgnoreCase(normalized))
        {
            return "roam_nearby";
        }
        if (normalized.matches("[A-Za-z0-9_-]{1,48}"))
        {
            return ROAM_PREFIX + normalized.toLowerCase(Locale.ROOT);
        }
        return "roam_d_" + CanonicalDigest.sha256Hex(normalized).substring(0, 24);
    }

    public static String roamTitle(String district)
    {
        String normalized = normalizeDistrict(district);
        if ("nearby".equalsIgnoreCase(normalized) || "全部".equals(normalized) || "all".equalsIgnoreCase(normalized))
        {
            return "附近漫游频道";
        }
        return normalized + " · 附近频道";
    }

    private static String normalizeDistrict(String district)
    {
        if (district == null) return "nearby";
        String trimmed = district.trim();
        return trimmed.isEmpty() ? "nearby" : trimmed;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> resolveChannel(Long memberId, String scope, Long activityId, Long topicId,
                                              Long teamId, String district, String cityTeamId, String boardId)
    {
        if (memberId == null || memberId <= 0)
        {
            throw new ServiceException("请先登录", 401);
        }
        if (conversationMapper == null || memberMapper == null)
        {
            throw new ServiceException("频道服务暂不可用", 503);
        }
        Target target = resolveTarget(memberId, scope, activityId, topicId, teamId, district, cityTeamId, boardId, true);
        if ("team".equals(target.scope) && target.teamId != null && imService != null)
        {
            Long convId = imService.getOrCreateTeamGroup(target.teamId, memberId);
            ImConversation conv = conversationMapper.selectImConversationById(convId);
            ImConversationMember membership = memberMapper.selectByConvAndMember(convId, memberId);
            return buildChannelPayload(conv, membership, target, countActiveMembers(convId));
        }

        ImConversation conv = getOrCreateConversation(target.bizKey, target.title);
        conversationMapper.selectImConversationByIdForUpdate(conv.getId());
        ensureMember(conv.getId(), memberId, target.role, true);
        for (Long extraMember : target.extraMembers)
        {
            if (extraMember != null && extraMember > 0 && !extraMember.equals(memberId))
            {
                ensureMember(conv.getId(), extraMember, 0, false);
            }
        }
        ImConversationMember membership = memberMapper.selectByConvAndMember(conv.getId(), memberId);
        return buildChannelPayload(conv, membership, target, countActiveMembers(conv.getId()));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getSummary(Long memberId, String scope, Long activityId, Long topicId,
                                          Long teamId, String district, String cityTeamId, String boardId)
    {
        if (memberId == null || memberId <= 0)
        {
            throw new ServiceException("请先登录", 401);
        }
        if (conversationMapper == null || memberMapper == null)
        {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("exists", false);
            empty.put("unread", 0);
            return empty;
        }
        Target target = resolveTarget(memberId, scope, activityId, topicId, teamId, district, cityTeamId, boardId, false);
        ImConversation conv = conversationMapper.selectByTypeAndBizKey(ImConversation.TYPE_GROUP, target.bizKey);
        if (conv == null || !Integer.valueOf(0).equals(conv.getStatus()))
        {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("exists", false);
            out.put("scope", target.scope);
            out.put("bizKey", target.bizKey);
            out.put("title", target.title);
            out.put("unread", 0);
            out.put("muted", 0);
            return out;
        }
        ImConversationMember membership = memberMapper.selectByConvAndMember(conv.getId(), memberId);
        if (membership == null || Integer.valueOf(1).equals(membership.getDeleted()))
        {
            Map<String, Object> out = new LinkedHashMap<>();
            // MED-S6:非成员只知道频道存在,不返回会话 id 与最后一条消息。
            out.put("exists", true);
            out.put("scope", target.scope);
            out.put("bizKey", target.bizKey);
            out.put("title", displayTitle(conv, target.title));
            out.put("unread", 0);
            out.put("muted", 0);
            return out;
        }
        Map<String, Object> out = buildChannelPayload(conv, membership, target, countActiveMembers(conv.getId()));
        out.put("exists", true);
        return out;
    }

    /** Auto-join player into the activity/topic session channel when registration is confirmed. */
    @Transactional(rollbackFor = Exception.class)
    public Long onRegistrationConfirmed(CmsRegistration registration)
    {
        if (registration == null || registration.getMemberId() == null || registration.getMemberId() <= 0
                || registration.getOwnerId() == null || registration.getOwnerId() <= 0
                || conversationMapper == null || memberMapper == null)
        {
            return null;
        }
        Long ownerType = registration.getOwnerType();
        Long ownerId = registration.getOwnerId();
        String bizKey;
        String title = trimToNull(registration.getActivityTitle());
        Long creatorId = null;

        if (Long.valueOf(2L).equals(ownerType))
        {
            bizKey = ACTIVITY_PREFIX + ownerId;
            if (cmsActivityMapper != null)
            {
                CmsActivity activity = cmsActivityMapper.selectCmsActivityById(ownerId);
                if (activity != null)
                {
                    if (title == null) title = trimToNull(activity.getName());
                    creatorId = activity.getMemberId();
                }
            }
            if (title == null) title = "活动频道";
        }
        else if (Long.valueOf(1L).equals(ownerType))
        {
            bizKey = TOPIC_PREFIX + ownerId;
            if (cmsTopicMapper != null)
            {
                CmsTopic topic = cmsTopicMapper.selectCmsTopicById(ownerId);
                if (topic != null)
                {
                    if (title == null) title = trimToNull(topic.getName());
                    creatorId = topic.getMemberId();
                }
            }
            if (title == null) title = "场次频道";
        }
        else
        {
            return null;
        }

        ImConversation conv = getOrCreateConversation(bizKey, title);
        conversationMapper.selectImConversationByIdForUpdate(conv.getId());
        ensureMember(conv.getId(), registration.getMemberId(), 0, false);
        if (creatorId != null && creatorId > 0)
        {
            ensureMember(conv.getId(), creatorId, 1, false);
        }
        return conv.getId();
    }

    public boolean hasActiveAccess(ImConversation conversation, Long memberId)
    {
        if (conversation == null || memberId == null || memberId <= 0 || conversation.getBizKey() == null)
        {
            return false;
        }
        String bizKey = conversation.getBizKey();
        if (!Integer.valueOf(0).equals(conversation.getStatus()))
        {
            return false;
        }
        if (bizKey.startsWith(CITY_TEAM_PREFIX))
        {
            String teamId = bizKey.substring(CITY_TEAM_PREFIX.length());
            if (teamId.isEmpty()) return false;
            if (cityGameMapper == null) return hasActiveConversationRow(conversation.getId(), memberId);
            List<Map<String, Object>> members = cityGameMapper.selectTeamMembers(teamId);
            if (members == null) return false;
            for (Map<String, Object> m : members)
            {
                Object idObj = m.get("memberId");
                if (idObj instanceof Number && ((Number) idObj).longValue() == memberId.longValue())
                {
                    return true;
                }
            }
            return false;
        }
        if (bizKey.startsWith(ACTIVITY_PREFIX))
        {
            Long actId = parsePositiveLong(bizKey.substring(ACTIVITY_PREFIX.length()));
            if (actId == null) return false;
            if (hasActiveConversationRow(conversation.getId(), memberId)) return true;
            if (cmsActivityMapper != null)
            {
                CmsActivity act = cmsActivityMapper.selectCmsActivityById(actId);
                if (act != null && memberId.equals(act.getMemberId())) return true;
            }
            return playTeamMapper != null && playTeamMapper.hasPaidRegistration(memberId, 2, actId) > 0;
        }
        if (bizKey.startsWith(TOPIC_PREFIX))
        {
            Long topicId = parsePositiveLong(bizKey.substring(TOPIC_PREFIX.length()));
            if (topicId == null) return false;
            if (hasActiveConversationRow(conversation.getId(), memberId)) return true;
            if (cmsTopicMapper != null)
            {
                CmsTopic topic = cmsTopicMapper.selectCmsTopicById(topicId);
                if (topic != null && memberId.equals(topic.getMemberId())) return true;
            }
            return playTeamMapper != null && playTeamMapper.hasPaidRegistration(memberId, 1, topicId) > 0;
        }
        if (bizKey.startsWith(ROAM_PREFIX))
        {
            return hasActiveConversationRow(conversation.getId(), memberId);
        }
        return false;
    }

    public Map<String, Object> counterpartyOf(ImConversation conversation, Long memberId)
    {
        Map<String, Object> cp = new HashMap<>();
        cp.put("id", 0);
        cp.put("avatar", "");
        String bizKey = conversation == null ? "" : String.valueOf(conversation.getBizKey());
        cp.put("bizKey", bizKey);
        String fallback = "游戏频道";
        if (bizKey.startsWith(ACTIVITY_PREFIX))
        {
            fallback = "活动频道";
            Long actId = parsePositiveLong(bizKey.substring(ACTIVITY_PREFIX.length()));
            if (actId != null && cmsActivityMapper != null)
            {
                CmsActivity act = cmsActivityMapper.selectCmsActivityById(actId);
                if (act != null && trimToNull(act.getName()) != null)
                {
                    fallback = act.getName().trim();
                }
            }
        }
        else if (bizKey.startsWith(TOPIC_PREFIX))
        {
            fallback = "场次频道";
            Long topicId = parsePositiveLong(bizKey.substring(TOPIC_PREFIX.length()));
            if (topicId != null && cmsTopicMapper != null)
            {
                CmsTopic topic = cmsTopicMapper.selectCmsTopicById(topicId);
                if (topic != null && trimToNull(topic.getName()) != null)
                {
                    fallback = topic.getName().trim();
                }
            }
        }
        else if (bizKey.startsWith(ROAM_PREFIX))
        {
            fallback = "附近漫游频道";
        }
        else if (bizKey.startsWith(CITY_TEAM_PREFIX))
        {
            fallback = "CITY 小队频道";
            String cityTeamId = bizKey.substring(CITY_TEAM_PREFIX.length());
            if (!cityTeamId.isEmpty() && cityGameMapper != null)
            {
                Map<String, Object> team = cityGameMapper.selectTeam(cityTeamId);
                if (team != null && team.get("name") != null)
                {
                    fallback = "CITY · " + String.valueOf(team.get("name")).trim();
                }
            }
        }
        String remark = conversation == null ? null : trimToNull(conversation.getRemark());
        cp.put("nickname", remark != null ? remark : fallback);
        if (conversation != null && conversation.getId() != null && memberMapper != null)
        {
            cp.put("memberCount", countActiveMembers(conversation.getId()));
        }
        return cp;
    }

    @Transactional(rollbackFor = Exception.class)
    public void postPlayEvent(String eventType, Long actorMemberId, Long activityId, Long topicId,
                              String nodeTitle, String detail)
    {
        if (conversationMapper == null || memberMapper == null || messageMapper == null) return;
        String actorName = actorLabel(actorMemberId);
        String verb = eventVerb(eventType);
        String targetLabel = trimToNull(nodeTitle) == null ? "" : "「" + nodeTitle.trim() + "」";
        String suffix = trimToNull(detail) == null ? "" : " · " + detail.trim();
        String text = "[" + verb + "] " + actorName + " " + verb + " " + targetLabel + suffix;

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("eventType", eventType);
        if (activityId != null) extra.put("activityId", activityId);
        if (topicId != null) extra.put("topicId", topicId);
        if (nodeTitle != null) extra.put("target", nodeTitle);

        // 1) If player belongs to a play team for this activity, notify the team channel
        if (actorMemberId != null && actorMemberId > 0 && activityId != null && activityId > 0 && playTeamMapper != null)
        {
            List<PlayTeam> teams = playTeamMapper.selectMyTeams(actorMemberId);
            if (teams != null)
            {
                for (PlayTeam team : teams)
                {
                    if (team != null && Integer.valueOf(2).equals(team.getOwnerType())
                            && activityId.equals(team.getOwnerId())
                            && (team.getStatus() == null || team.getStatus() == 0))
                    {
                        Set<Long> memberIds = new LinkedHashSet<>();
                        memberIds.add(actorMemberId);
                        List<PlayTeamMember> teamMembers = playTeamMapper.selectActiveMembers(team.getId());
                        if (teamMembers != null)
                        {
                            for (PlayTeamMember tm : teamMembers)
                            {
                                if (tm != null && tm.getMemberId() != null && tm.getMemberId() > 0)
                                {
                                    memberIds.add(tm.getMemberId());
                                }
                            }
                        }
                        postGameEvent(TEAM_PREFIX + team.getId(), team.getTitle(), eventType,
                                actorMemberId, text, extra, memberIds, false);
                    }
                }
            }
        }
        // 2) Notify the activity or topic session channel
        if (activityId != null && activityId > 0)
        {
            postGameEvent(ACTIVITY_PREFIX + activityId, "活动频道", eventType,
                    actorMemberId, text, extra, actorMemberId == null ? List.of() : List.of(actorMemberId), false);
        }
        else if (topicId != null && topicId > 0)
        {
            postGameEvent(TOPIC_PREFIX + topicId, "场次频道", eventType,
                    actorMemberId, text, extra, actorMemberId == null ? List.of() : List.of(actorMemberId), false);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void postRoamEvent(String eventType, Long actorMemberId, String district, String placeTitle, String detail)
    {
        if (conversationMapper == null || memberMapper == null || messageMapper == null) return;
        // HIGH-S9:公开漫游频道会暴露"谁此刻在哪",只为已单独开启附近位置分享的会员发,
        // 署名用脱敏 id,不自动拉动作者进频道。
        if (!hasPresenceOptIn(actorMemberId)) return;
        String actorName = maskedActor(actorMemberId);
        String verb = eventVerb(eventType);
        String targetLabel = trimToNull(placeTitle) == null ? "" : "「" + placeTitle.trim() + "」";
        String suffix = trimToNull(detail) == null ? "" : " · " + detail.trim();
        String text = "[" + verb + "] " + actorName + " " + verb + " " + targetLabel + suffix;

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("eventType", eventType);
        extra.put("district", normalizeDistrict(district));
        if (placeTitle != null) extra.put("target", placeTitle);

        List<Long> actors = List.of();
        String primaryKey = roamBizKey(district);
        postGameEvent(primaryKey, roamTitle(district), eventType, actorMemberId, text, extra, actors, false);
        if (!"roam_nearby".equals(primaryKey))
        {
            ImConversation nearby = conversationMapper.selectByTypeAndBizKey(ImConversation.TYPE_GROUP, "roam_nearby");
            if (nearby != null && Integer.valueOf(0).equals(nearby.getStatus()))
            {
                postGameEvent("roam_nearby", "附近漫游频道", eventType, actorMemberId, text, extra, actors, true);
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void postCityGameEvent(String eventType, Long actorMemberId, String boardId, String cityTeamId,
                                  String propertyTitle, String detail)
    {
        if (conversationMapper == null || memberMapper == null || messageMapper == null) return;
        if (cityTeamId == null || cityTeamId.trim().isEmpty()) return;
        String actorName = actorLabel(actorMemberId);
        String verb = eventVerb(eventType);
        String targetLabel = trimToNull(propertyTitle) == null ? "" : "「" + propertyTitle.trim() + "」";
        String suffix = trimToNull(detail) == null ? "" : " · " + detail.trim();
        String text = "[" + verb + "] " + actorName + " " + verb + " " + targetLabel + suffix;

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("eventType", eventType);
        if (boardId != null) extra.put("boardId", boardId);
        extra.put("cityTeamId", cityTeamId);
        if (propertyTitle != null) extra.put("target", propertyTitle);

        Set<Long> memberIds = new LinkedHashSet<>();
        if (actorMemberId != null && actorMemberId > 0) memberIds.add(actorMemberId);
        String title = "CITY 小队频道";
        if (cityGameMapper != null)
        {
            Map<String, Object> team = cityGameMapper.selectTeam(cityTeamId);
            if (team != null && team.get("name") != null)
            {
                title = "CITY · " + String.valueOf(team.get("name")).trim();
            }
            List<Map<String, Object>> teamMembers = cityGameMapper.selectTeamMembers(cityTeamId);
            if (teamMembers != null)
            {
                for (Map<String, Object> m : teamMembers)
                {
                    Object idObj = m.get("memberId");
                    if (idObj instanceof Number)
                    {
                        memberIds.add(((Number) idObj).longValue());
                    }
                }
            }
        }
        postGameEvent(CITY_TEAM_PREFIX + cityTeamId.trim(), title, eventType, actorMemberId, text, extra, memberIds, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public Long postGameEvent(String bizKey, String defaultTitle, String eventType, Long actorMemberId,
                              String content, Map<String, Object> extra, Collection<Long> ensureMemberIds,
                              boolean existingOnly)
    {
        if (conversationMapper == null || memberMapper == null || messageMapper == null
                || bizKey == null || bizKey.isEmpty() || content == null || content.trim().isEmpty())
        {
            return null;
        }
        ImConversation conv = existingOnly
                ? conversationMapper.selectByTypeAndBizKey(ImConversation.TYPE_GROUP, bizKey)
                : getOrCreateConversation(bizKey, defaultTitle);
        if (conv == null || !Integer.valueOf(0).equals(conv.getStatus()))
        {
            return null;
        }
        conversationMapper.selectImConversationByIdForUpdate(conv.getId());
        if (ensureMemberIds != null)
        {
            for (Long uid : ensureMemberIds)
            {
                if (uid != null && uid > 0)
                {
                    ensureMember(conv.getId(), uid, 0, false);
                }
            }
        }

        JSONObject extraJsonObj = new JSONObject();
        extraJsonObj.put("cardType", "game_event");
        extraJsonObj.put("eventType", eventType);
        if (extra != null) extraJsonObj.putAll(extra);

        ImMessage msg = new ImMessage();
        msg.setConversationId(conv.getId());
        msg.setSenderId(0L);
        msg.setReceiverId(0L);
        msg.setMsgType(ImMessage.MSG_TEXT);
        msg.setContent(content.length() > 1900 ? content.substring(0, 1900) : content);
        msg.setExtraJson(extraJsonObj.toJSONString());
        msg.setStatus(ImMessage.STATUS_NORMAL);
        msg.setCheckStatus(1);
        messageMapper.insertImMessage(msg);

        String summary = msg.getContent().length() > 200 ? msg.getContent().substring(0, 200) : msg.getContent();
        conversationMapper.updateLastMessage(conv.getId(), ImMessage.MSG_TEXT, summary, new Date());

        List<ImConversationMember> members = memberMapper.selectByConversation(conv.getId());
        if (members != null)
        {
            for (ImConversationMember m : members)
            {
                if (m.getMemberId() == null || m.getMemberId() <= 0) continue;
                if (Integer.valueOf(1).equals(m.getDeleted())) continue;
                if (actorMemberId != null && actorMemberId.equals(m.getMemberId())) continue;
                memberMapper.addUnreadForActiveMember(conv.getId(), m.getMemberId(), 1);
            }
        }
        return msg.getId();
    }

    private Target resolveTarget(Long memberId, String scope, Long activityId, Long topicId,
                                 Long teamId, String district, String cityTeamId, String boardId, boolean requireWriteAuth)
    {
        String normalizedScope = scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT);
        if (normalizedScope.isEmpty())
        {
            if (cityTeamId != null && !cityTeamId.trim().isEmpty()) normalizedScope = "city_team";
            else if (teamId != null && teamId > 0) normalizedScope = "team";
            else if (activityId != null && activityId > 0) normalizedScope = "activity";
            else if (topicId != null && topicId > 0) normalizedScope = "topic";
            else if (boardId != null && !boardId.trim().isEmpty()) normalizedScope = "city_team";
            else normalizedScope = "roam";
        }

        if ("team".equals(normalizedScope))
        {
            if (teamId == null || teamId <= 0) throw new ServiceException("队伍不存在", 400);
            String title = "玩家队伍";
            Set<Long> extras = new LinkedHashSet<>();
            if (playTeamMapper != null)
            {
                PlayTeam team = playTeamMapper.selectPlayTeamById(teamId);
                if (team == null) throw new ServiceException("队伍不存在", 404);
                if (trimToNull(team.getTitle()) != null) title = team.getTitle().trim();
                List<PlayTeamMember> teamMembers = playTeamMapper.selectActiveMembers(teamId);
                if (teamMembers != null)
                {
                    for (PlayTeamMember tm : teamMembers)
                    {
                        if (tm != null && tm.getMemberId() != null && tm.getMemberId() > 0)
                        {
                            extras.add(tm.getMemberId());
                        }
                    }
                }
            }
            return new Target("team", TEAM_PREFIX + teamId, title, teamId, null, 0, extras);
        }

        if ("activity".equals(normalizedScope) || ("session".equals(normalizedScope) && activityId != null && activityId > 0))
        {
            if (activityId == null || activityId <= 0) throw new ServiceException("活动不存在", 400);
            String title = "活动频道";
            int role = 0;
            Set<Long> extras = new LinkedHashSet<>();
            boolean allowed = !requireWriteAuth;
            if (cmsActivityMapper != null)
            {
                CmsActivity act = cmsActivityMapper.selectCmsActivityById(activityId);
                if (act == null) throw new ServiceException("活动不存在", 404);
                if (trimToNull(act.getName()) != null) title = act.getName().trim();
                if (act.getMemberId() != null && act.getMemberId() > 0)
                {
                    extras.add(act.getMemberId());
                    if (memberId.equals(act.getMemberId()))
                    {
                        role = 1;
                        allowed = true;
                    }
                }
            }
            if (!allowed)
            {
                if (playTeamMapper == null || playTeamMapper.hasPaidRegistration(memberId, 2, activityId) > 0)
                {
                    allowed = true;
                }
                else
                {
                    ImConversation existing = conversationMapper.selectByTypeAndBizKey(ImConversation.TYPE_GROUP, ACTIVITY_PREFIX + activityId);
                    if (existing != null && memberMapper.selectByConvAndMember(existing.getId(), memberId) != null)
                    {
                        allowed = true;
                    }
                }
            }
            if (!allowed)
            {
                throw new ServiceException("报名后即可加入活动频道", 403);
            }
            return new Target("activity", ACTIVITY_PREFIX + activityId, title, null, null, role, extras);
        }

        if ("topic".equals(normalizedScope) || "session".equals(normalizedScope))
        {
            if (topicId == null || topicId <= 0) throw new ServiceException("场次不存在", 400);
            String title = "场次频道";
            int role = 0;
            Set<Long> extras = new LinkedHashSet<>();
            boolean allowed = !requireWriteAuth;
            if (cmsTopicMapper != null)
            {
                CmsTopic topic = cmsTopicMapper.selectCmsTopicById(topicId);
                if (topic == null) throw new ServiceException("场次不存在", 404);
                if (trimToNull(topic.getName()) != null) title = topic.getName().trim();
                if (topic.getMemberId() != null && topic.getMemberId() > 0)
                {
                    extras.add(topic.getMemberId());
                    if (memberId.equals(topic.getMemberId()))
                    {
                        role = 1;
                        allowed = true;
                    }
                }
            }
            // HIGH-S3:场次频道只对场次主人或已付费报名者开放;映射缺失时失败关闭。
            if (!allowed && playTeamMapper != null && playTeamMapper.hasPaidRegistration(memberId, 1, topicId) > 0)
            {
                allowed = true;
            }
            if (!allowed)
            {
                throw new ServiceException("报名后即可加入场次频道", 403);
            }
            return new Target("topic", TOPIC_PREFIX + topicId, title, null, null, role, extras);
        }

        if ("city_team".equals(normalizedScope) || "city".equals(normalizedScope))
        {
            String resolvedTeamId = trimToNull(cityTeamId);
            if (resolvedTeamId == null && trimToNull(boardId) != null && cityGameMapper != null)
            {
                Map<String, Object> participation = cityGameMapper.selectParticipation(boardId.trim(), memberId);
                if (participation != null && participation.get("teamId") != null)
                {
                    resolvedTeamId = String.valueOf(participation.get("teamId")).trim();
                }
            }
            if (resolvedTeamId == null)
            {
                throw new ServiceException("请先加入 CITY 小队", 403);
            }
            String title = "CITY 小队频道";
            Set<Long> extras = new LinkedHashSet<>();
            if (cityGameMapper != null)
            {
                Map<String, Object> team = cityGameMapper.selectTeam(resolvedTeamId);
                if (team == null) throw new ServiceException("CITY 小队不存在", 404);
                if (team.get("name") != null) title = "CITY · " + String.valueOf(team.get("name")).trim();
                List<Map<String, Object>> members = cityGameMapper.selectTeamMembers(resolvedTeamId);
                boolean isTeamMember = false;
                if (members != null)
                {
                    for (Map<String, Object> m : members)
                    {
                        Object idObj = m.get("memberId");
                        if (idObj instanceof Number)
                        {
                            long uid = ((Number) idObj).longValue();
                            extras.add(uid);
                            if (uid == memberId.longValue()) isTeamMember = true;
                        }
                    }
                }
                if (requireWriteAuth && !isTeamMember)
                {
                    throw new ServiceException("只有小队成员可以进入 CITY 小队频道", 403);
                }
            }
            return new Target("city_team", CITY_TEAM_PREFIX + resolvedTeamId, title, null, resolvedTeamId, 0, extras);
        }

        if ("roam".equals(normalizedScope))
        {
            String bizKey = roamBizKey(district);
            String title = roamTitle(district);
            return new Target("roam", bizKey, title, null, null, 0, Set.of());
        }

        throw new ServiceException("不支持的频道范围", 400);
    }

    private ImConversation getOrCreateConversation(String bizKey, String remark)
    {
        ImConversation c = conversationMapper.selectByTypeAndBizKey(ImConversation.TYPE_GROUP, bizKey);
        if (c != null) return c;

        ImConversation created = new ImConversation();
        created.setType(ImConversation.TYPE_GROUP);
        created.setBizKey(bizKey);
        created.setStatus(0);
        created.setLastMsgAt(new Date());
        created.setRemark(remark);
        try
        {
            conversationMapper.insertImConversation(created);
            return created;
        }
        catch (DuplicateKeyException dup)
        {
            ImConversation existing = conversationMapper.selectByTypeAndBizKey(ImConversation.TYPE_GROUP, bizKey);
            if (existing != null) return existing;
            throw dup;
        }
    }

    private void ensureMember(Long conversationId, Long memberId, int role, boolean restoreIfDeleted)
    {
        ImConversationMember existing = memberMapper.selectByConvAndMember(conversationId, memberId);
        if (existing == null)
        {
            ImConversationMember m = new ImConversationMember();
            m.setConversationId(conversationId);
            m.setMemberId(memberId);
            m.setRole(role);
            try
            {
                memberMapper.insertImConversationMember(m);
            }
            catch (DuplicateKeyException ignored)
            {
                // Concurrent join already inserted the row
            }
        }
        else if (restoreIfDeleted && Integer.valueOf(1).equals(existing.getDeleted()))
        {
            memberMapper.addUnread(conversationId, memberId, 0);
        }
    }

    private boolean hasActiveConversationRow(Long conversationId, Long memberId)
    {
        if (conversationId == null || memberMapper == null) return false;
        ImConversationMember m = memberMapper.selectByConvAndMember(conversationId, memberId);
        return m != null && !Integer.valueOf(1).equals(m.getDeleted());
    }

    private int countActiveMembers(Long conversationId)
    {
        if (conversationId == null || memberMapper == null) return 0;
        List<ImConversationMember> list = memberMapper.selectByConversation(conversationId);
        if (list == null) return 0;
        int count = 0;
        for (ImConversationMember m : list)
        {
            if (m.getMemberId() != null && m.getMemberId() > 0 && !Integer.valueOf(1).equals(m.getDeleted()))
            {
                count++;
            }
        }
        return count;
    }

    private Map<String, Object> buildChannelPayload(ImConversation conv, ImConversationMember membership,
                                                    Target target, int memberCount)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("conversationId", conv.getId());
        out.put("type", conv.getType());
        out.put("scope", target.scope);
        out.put("bizKey", conv.getBizKey());
        out.put("title", displayTitle(conv, target.title));
        out.put("unread", membership == null || membership.getUnreadCount() == null ? 0 : membership.getUnreadCount());
        out.put("muted", membership == null || membership.getMuted() == null ? 0 : membership.getMuted());
        out.put("memberCount", memberCount);
        out.put("lastMsgText", conv.getLastMsgText() == null ? "" : conv.getLastMsgText());
        out.put("lastMsgAt", conv.getLastMsgAt());
        if (target.teamId != null) out.put("teamId", target.teamId);
        if (target.cityTeamId != null) out.put("cityTeamId", target.cityTeamId);
        return out;
    }

    private String displayTitle(ImConversation conv, String fallback)
    {
        String remark = conv == null ? null : trimToNull(conv.getRemark());
        return remark != null ? remark : fallback;
    }

    private String actorLabel(Long memberId)
    {
        if (memberId == null || memberId <= 0 || umsMemberService == null)
        {
            return "玩家";
        }
        try
        {
            UmsMember m = umsMemberService.selectUmsMemberById(memberId);
            if (m != null)
            {
                // HIGH-S9:ums_member.name 是真实姓名,任何频道署名都不得使用。
                String nick = trimToNull(m.getNickname());
                if (nick != null) return nick;
            }
        }
        catch (Exception e)
        {
            log.debug("Resolve actor label failed for {}", memberId, e);
        }
        return "玩家#" + memberId;
    }

    /** 与 CityMonopolyService.mask 同口径的脱敏署名。 */
    static String maskedActor(Long memberId)
    {
        if (memberId == null || memberId <= 0) return "玩家";
        String s = String.valueOf(memberId);
        return s.length() <= 2 ? "玩家*" : "玩家" + s.charAt(0) + "***" + s.charAt(s.length() - 1);
    }

    private boolean hasPresenceOptIn(Long memberId)
    {
        if (memberId == null || memberId <= 0 || roamMapper == null) return false;
        try
        {
            return roamMapper.countPresenceVisibleSessions(memberId) > 0;
        }
        catch (RuntimeException e)
        {
            log.warn("Presence opt-in check failed, roam event skipped reason={}", e.getClass().getSimpleName());
            return false;
        }
    }

    private static String eventVerb(String eventType)
    {
        if (EVENT_CHECKIN.equals(eventType)) return "打卡";
        if (EVENT_CLAIM.equals(eventType)) return "占领";
        if (EVENT_TAKEOVER.equals(eventType)) return "接管";
        if (EVENT_FINISH.equals(eventType)) return "通关";
        return "动态";
    }

    private static Long parsePositiveLong(String raw)
    {
        if (raw == null || raw.isEmpty()) return null;
        try
        {
            long val = Long.parseLong(raw);
            return val > 0 ? val : null;
        }
        catch (NumberFormatException ignored)
        {
            return null;
        }
    }

    private static String trimToNull(String value)
    {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record Target(String scope, String bizKey, String title, Long teamId, String cityTeamId,
                          int role, Set<Long> extraMembers) {}
}
