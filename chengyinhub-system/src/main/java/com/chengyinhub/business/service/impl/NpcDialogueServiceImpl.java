package com.chengyinhub.business.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.dto.ai.NpcDialogueReq;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.service.INpcDialogueService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.ai.AiGatewayResult;
import com.chengyinhub.business.service.ai.AiGatewayService;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * AI NPC 主题对话实现:
 * <ul>
 *   <li>校验玩家本局会话与主题归属;</li>
 *   <li>仅向模型暴露本主题已登记的 relation.* / tag.* 键;</li>
 *   <li>严禁模型授奖(item/badge/coin/xp)或泄露谜底;</li>
 *   <li>服务端二次校验后通过 topic_route_action(routeActionId 幂等) + CAS 提交。</li>
 * </ul>
 */
@Service
public class NpcDialogueServiceImpl implements INpcDialogueService
{
    private static final Logger log = LoggerFactory.getLogger(NpcDialogueServiceImpl.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_UTTERANCE_LENGTH = 500;
    private static final String DEFAULT_REPLY = "嗯，我记下了你的意思。";

    private final NpcProfileMapper npcProfileMapper;
    private final ITopicRouteRuntimeService topicRouteRuntimeService;
    private final AiGatewayService aiGatewayService;

    @Autowired(required = false)
    private com.chengyinhub.business.service.NpcDialogueTestResponder e2eTestNpcDialogueResponder;

    @Autowired
    public NpcDialogueServiceImpl(NpcProfileMapper npcProfileMapper,
            ITopicRouteRuntimeService topicRouteRuntimeService,
            AiGatewayService aiGatewayService)
    {
        this.npcProfileMapper = npcProfileMapper;
        this.topicRouteRuntimeService = topicRouteRuntimeService;
        this.aiGatewayService = aiGatewayService;
    }

    @Override
    public Map<String, Object> dialogue(Long memberId, NpcDialogueReq req)
    {
        if (memberId == null || memberId <= 0L)
        {
            throw new ServiceException("请先登录", HttpStatus.UNAUTHORIZED);
        }
        if (req == null || req.getNpcId() == null || req.getNpcId() <= 0L)
        {
            throw new ServiceException("npcId 无效", HttpStatus.BAD_REQUEST);
        }
        String utterance = req.getUtterance() == null ? "" : req.getUtterance().trim();
        if (utterance.isEmpty() || utterance.length() > MAX_UTTERANCE_LENGTH)
        {
            throw new ServiceException("对话内容不能为空且不超过500字", HttpStatus.BAD_REQUEST);
        }
        String routeActionId = StringUtils.hasText(req.getRouteActionId())
                ? req.getRouteActionId().trim() : UUID.randomUUID().toString();
        if (routeActionId.length() > 64 || !routeActionId.matches("[A-Za-z0-9._:-]{1,64}"))
        {
            throw new ServiceException("routeActionId 格式无效", HttpStatus.BAD_REQUEST);
        }
        String actionId = "dlg:" + req.getNpcId() + ":" + routeActionId;
        if (actionId.length() > 96)
        {
            throw new ServiceException("routeActionId 过长", HttpStatus.BAD_REQUEST);
        }

        TopicRouteSession session = topicRouteRuntimeService.requireNpcDialogueSession(
                memberId, req.getTopicId(), req.getActivityId(), req.getRunId());

        Map<String, Object> replayed = topicRouteRuntimeService.findNpcDialogueReplay(session, memberId, actionId);
        if (replayed != null)
        {
            return replayed;
        }

        NpcProfile profile = npcProfileMapper.selectThemeNpcById(req.getNpcId());
        if (profile == null || !Integer.valueOf(1).equals(profile.getEnabled())
                || !Integer.valueOf(1).equals(profile.getAuditStatus())
                || !session.getTopicId().equals(profile.getScopeId()))
        {
            throw new ServiceException("主题 NPC 不存在或未启用", HttpStatus.NOT_FOUND);
        }

        List<ThemeStateKey> allowedKeys = topicRouteRuntimeService.allowedNpcDialogueKeys(session);
        String rawModelOutput = invokeModel(memberId, profile, utterance, allowedKeys);
        ParsedDialogue parsed = parseDialogueOutput(rawModelOutput);

        return topicRouteRuntimeService.commitNpcDialogue(
                session, memberId, profile.getId(), profile.getName(), profile.getRelationKey(),
                actionId, routeActionId, parsed.reply, parsed.effects);
    }

    private String invokeModel(Long memberId, NpcProfile profile, String utterance, List<ThemeStateKey> allowedKeys)
    {
        String systemPrompt = buildSystemPrompt(profile, allowedKeys);
        try
        {
            AiGatewayResult result = aiGatewayService.generate(memberId, "player", "npc_dialogue", systemPrompt, utterance);
            if (result != null && !result.isSafetyOk())
            {
                return "{\"reply\":\"这个话题我们还是先跳过吧。\",\"effects\":[]}";
            }
            return result != null && result.getText() != null ? result.getText() : DEFAULT_REPLY;
        }
        catch (AiServiceException ex)
        {
            if (ex.getReason() == AiServiceException.Reason.UPSTREAM_UNAVAILABLE && e2eTestNpcDialogueResponder != null)
            {
                log.info("[npc/dialogue] upstream disabled in e2e-test profile; using TEST dialogue responder");
                return e2eTestNpcDialogueResponder.generateJson(profile, utterance, allowedKeys);
            }
            throw ex;
        }
    }

    private static String buildSystemPrompt(NpcProfile profile, List<ThemeStateKey> allowedKeys)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("[BIZ:npc_dialogue]\n");
        sb.append("你是剧本角色「").append(profile.getName() == null ? "NPC" : profile.getName()).append("」。\n");
        if (StringUtils.hasText(profile.getPersona()))
        {
            sb.append("人设：").append(profile.getPersona()).append("\n");
        }
        if (StringUtils.hasText(profile.getRelationKey()))
        {
            sb.append("优先关系键：").append(profile.getRelationKey()).append("\n");
        }
        sb.append("安全规则：绝对不要发放任何奖励/道具/徽章/积分，绝对不要直接泄露关卡密码、答案或谜底。\n");
        sb.append("允许输出的效果状态键白名单（只能从中选择）：\n");
        if (allowedKeys.isEmpty())
        {
            sb.append("- 无可写状态键，请将 effects 保持为空数组 []\n");
        }
        else
        {
            for (ThemeStateKey item : allowedKeys)
            {
                sb.append("- ").append(item.getStateKey());
                if (StringUtils.hasText(item.getLabel())) sb.append(" (").append(item.getLabel()).append(')');
                sb.append("\n");
            }
        }
        sb.append("请严格只输出一个 JSON 对象，格式：{\"reply\":\"角色自然回复\",\"effects\":[{\"type\":\"INC\",\"key\":\"relation.xxx\",\"delta\":1},{\"type\":\"ADD_TAG\",\"key\":\"tag.xxx\"}]}");
        return sb.toString();
    }

    static ParsedDialogue parseDialogueOutput(String raw)
    {
        if (!StringUtils.hasText(raw))
        {
            return new ParsedDialogue(DEFAULT_REPLY, Collections.<Map<String, Object>>emptyList());
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("```"))
        {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline)
            {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start)
        {
            String jsonCandidate = trimmed.substring(start, end + 1);
            try
            {
                JsonNode root = JSON.readTree(jsonCandidate);
                if (root != null && root.isObject())
                {
                    String reply = root.path("reply").asText("").trim();
                    if (reply.isEmpty()) reply = DEFAULT_REPLY;
                    List<Map<String, Object>> effects = new ArrayList<Map<String, Object>>();
                    JsonNode effNode = root.get("effects");
                    if (effNode != null && effNode.isArray())
                    {
                        for (JsonNode item : effNode)
                        {
                            if (item == null || !item.isObject()) continue;
                            Map<String, Object> row = new LinkedHashMap<String, Object>();
                            if (item.has("type")) row.put("type", item.get("type").asText(""));
                            else if (item.has("op")) row.put("type", item.get("op").asText(""));
                            if (item.has("key")) row.put("key", item.get("key").asText(""));
                            else if (item.has("var")) row.put("key", item.get("var").asText(""));
                            if (item.has("delta") && item.get("delta").isNumber()) row.put("delta", item.get("delta").asInt());
                            else if (item.has("value") && item.get("value").isNumber()) row.put("delta", item.get("value").asInt());
                            effects.add(row);
                        }
                    }
                    return new ParsedDialogue(reply, effects);
                }
            }
            catch (Exception ignored)
            {
                // Fall through to plain text reply without effects.
            }
        }
        return new ParsedDialogue(trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed,
                Collections.<Map<String, Object>>emptyList());
    }

    static final class ParsedDialogue
    {
        final String reply;
        final List<Map<String, Object>> effects;
        ParsedDialogue(String reply, List<Map<String, Object>> effects)
        {
            this.reply = reply;
            this.effects = effects;
        }
    }
}
