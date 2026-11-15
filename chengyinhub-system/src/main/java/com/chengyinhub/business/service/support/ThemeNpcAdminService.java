package com.chengyinhub.business.service.support;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.service.ai.VisionModelConfig;
import com.chengyinhub.common.config.AiModelConfig;
import com.chengyinhub.common.config.AiProviderProperties;
import com.chengyinhub.common.config.AiVoiceConfig;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;

/**
 * E1 主题人物表(scope_type=1)、3D形象生成任务审核、声音克隆授权审计与AI提供方环境变量状态只读服务。
 */
@Service
public class ThemeNpcAdminService
{
    private static final int NAME_MAX = 32;
    private static final int AVATAR_MAX = 255;
    private static final int GREETING_MAX = 255;
    private static final int PERSONA_MAX = 2000;

    private final NpcProfileMapper npcProfileMapper;
    private final CmsTopicMapper cmsTopicMapper;
    private final ThemeStateKeyMapper themeStateKeyMapper;
    private final JdbcTemplate jdbc;

    @Autowired(required = false)
    private AiModelConfig aiModelConfig;

    @Autowired(required = false)
    private VisionModelConfig visionModelConfig;

    @Autowired(required = false)
    private AiVoiceConfig aiVoiceConfig;

    @Autowired(required = false)
    private AiProviderProperties aiProviderProperties;

    @Value("${merchant.npc.avatar.provider:disabled}")
    private String avatarProvider;

    @Value("${merchant.npc.avatar.baseUrl:}")
    private String avatarBaseUrl;

    @Value("${merchant.npc.avatar.apiKey:}")
    private String avatarApiKey;

    @Autowired
    public ThemeNpcAdminService(NpcProfileMapper npcProfileMapper,
            CmsTopicMapper cmsTopicMapper,
            ThemeStateKeyMapper themeStateKeyMapper,
            JdbcTemplate jdbc)
    {
        this.npcProfileMapper = npcProfileMapper;
        this.cmsTopicMapper = cmsTopicMapper;
        this.themeStateKeyMapper = themeStateKeyMapper;
        this.jdbc = jdbc;
    }

    public List<NpcProfile> listThemeNpcs(Long topicId, String name)
    {
        List<NpcProfile> rows = npcProfileMapper.selectThemeNpcList(topicId, StringUtils.isEmpty(name) ? null : name.trim());
        if (rows == null || rows.isEmpty()) return Collections.emptyList();
        for (NpcProfile row : rows)
        {
            enrichInitialRelation(row);
        }
        return rows;
    }

    public NpcProfile getThemeNpc(Long id)
    {
        if (id == null || id <= 0L) throw new ServiceException("参数有误", HttpStatus.BAD_REQUEST);
        NpcProfile row = npcProfileMapper.selectThemeNpcById(id);
        if (row == null) throw new ServiceException("主题人物不存在", HttpStatus.NOT_FOUND);
        enrichInitialRelation(row);
        return row;
    }

    public List<Map<String, Object>> listTopics(String title)
    {
        CmsTopic query = new CmsTopic();
        if (StringUtils.hasText(title)) query.setName(title.trim());
        List<CmsTopic> list = cmsTopicMapper.selectCmsTopicList(query);
        if (list == null) return Collections.emptyList();
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (CmsTopic topic : list)
        {
            if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())) continue;
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", topic.getId());
            item.put("title", topic.getName());
            item.put("status", topic.getStatus());
            out.add(item);
            if (out.size() >= 100) break;
        }
        return out;
    }

    public List<ThemeStateKey> listRelationKeys(Long topicId)
    {
        if (topicId == null || topicId <= 0L) return Collections.emptyList();
        List<ThemeStateKey> all = themeStateKeyMapper.selectByTopicId(topicId);
        if (all == null) return Collections.emptyList();
        List<ThemeStateKey> out = new ArrayList<ThemeStateKey>();
        for (ThemeStateKey k : all)
        {
            if (k != null && k.getStateKey() != null && k.getStateKey().startsWith("relation."))
            {
                out.add(k);
            }
        }
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public int addThemeNpc(NpcProfile body)
    {
        if (body == null || body.getScopeId() == null || body.getScopeId() <= 0L)
        {
            throw new ServiceException("请选择所属主题", HttpStatus.BAD_REQUEST);
        }
        CmsTopic topic = cmsTopicMapper.selectCmsTopicById(body.getScopeId());
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag()))
        {
            throw new ServiceException("所属主题不存在", HttpStatus.NOT_FOUND);
        }
        String invalid = validateFields(body);
        if (invalid != null) throw new ServiceException(invalid, HttpStatus.BAD_REQUEST);

        String relationKey = normalizeRelationKey(body.getRelationKey());
        NpcProfile profile = new NpcProfile();
        profile.setScopeType(1);
        profile.setScopeId(body.getScopeId());
        profile.setName(body.getName().trim());
        profile.setAvatar(trimToNull(body.getAvatar()));
        profile.setPersona(body.getPersona() == null ? "" : body.getPersona().trim());
        profile.setGreeting(trimToNull(body.getGreeting()));
        profile.setRelationKey(relationKey);
        profile.setSort(body.getSort() == null ? 0 : Math.max(0, Math.min(999, body.getSort())));
        profile.setAuditStatus(1);
        profile.setEnabled(Integer.valueOf(0).equals(body.getEnabled()) ? 0 : 1);

        int rows = npcProfileMapper.insertThemeNpc(profile);
        syncInitialRelation(body.getScopeId(), relationKey, profile.getName(), body.getInitialRelation());
        return rows;
    }

    @Transactional(rollbackFor = Exception.class)
    public int updateThemeNpc(NpcProfile body)
    {
        if (body == null || body.getId() == null || body.getId() <= 0L)
        {
            throw new ServiceException("参数有误", HttpStatus.BAD_REQUEST);
        }
        NpcProfile existing = npcProfileMapper.selectThemeNpcById(body.getId());
        if (existing == null) throw new ServiceException("主题人物不存在", HttpStatus.NOT_FOUND);

        String invalid = validateFields(body);
        if (invalid != null) throw new ServiceException(invalid, HttpStatus.BAD_REQUEST);

        String relationKey = normalizeRelationKey(body.getRelationKey());
        NpcProfile update = new NpcProfile();
        update.setId(existing.getId());
        update.setName(body.getName().trim());
        update.setAvatar(trimToNull(body.getAvatar()));
        update.setPersona(body.getPersona() == null ? "" : body.getPersona().trim());
        update.setGreeting(trimToNull(body.getGreeting()));
        update.setRelationKey(relationKey);
        update.setSort(body.getSort() == null ? existing.getSort() : Math.max(0, Math.min(999, body.getSort())));
        update.setEnabled(Integer.valueOf(0).equals(body.getEnabled()) ? 0 : 1);

        int rows = npcProfileMapper.updateThemeNpc(update);
        syncInitialRelation(existing.getScopeId(), relationKey, update.getName(), body.getInitialRelation());
        return rows;
    }

    @Transactional(rollbackFor = Exception.class)
    public int deleteThemeNpc(Long id)
    {
        if (id == null || id <= 0L) throw new ServiceException("参数有误", HttpStatus.BAD_REQUEST);
        if (npcProfileMapper.selectThemeNpcById(id) == null)
        {
            throw new ServiceException("主题人物不存在", HttpStatus.NOT_FOUND);
        }
        return npcProfileMapper.deleteThemeNpcById(id);
    }

    public List<Map<String, Object>> listAvatarJobs(String status, String reviewStatus, Long merchantId)
    {
        StringBuilder sql = new StringBuilder(
                "SELECT j.id, j.merchant_id, m.name AS merchant_name, j.member_id, j.image_url, j.style, "
              + "j.provider, j.status, j.model_url, j.thumb_url, j.fail_reason, "
              + "COALESCE(j.review_status, 'PENDING') AS review_status, j.review_reason, j.review_time, "
              + "j.create_time, j.update_time "
              + "FROM merchant_npc_avatar_job j "
              + "LEFT JOIN mms_merchant m ON m.id = j.merchant_id WHERE 1=1");
        List<Object> params = new ArrayList<Object>();
        if (merchantId != null && merchantId > 0L) { sql.append(" AND j.merchant_id = ?"); params.add(merchantId); }
        if (StringUtils.hasText(status)) { sql.append(" AND j.status = ?"); params.add(status.trim().toUpperCase()); }
        if (StringUtils.hasText(reviewStatus)) { sql.append(" AND COALESCE(j.review_status, 'PENDING') = ?"); params.add(reviewStatus.trim().toUpperCase()); }
        sql.append(" ORDER BY j.id DESC LIMIT 100");
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reviewAvatarJob(Long id, String decision, String reason)
    {
        if (id == null || id <= 0L) throw new ServiceException("任务ID无效", HttpStatus.BAD_REQUEST);
        String normalized = decision == null ? "" : decision.trim().toUpperCase();
        if (!"APPROVED".equals(normalized) && !"REJECTED".equals(normalized))
        {
            throw new ServiceException("审核决定仅支持 APPROVED 或 REJECTED", HttpStatus.BAD_REQUEST);
        }
        String trimmedReason = reason == null ? null : reason.trim();
        if ("REJECTED".equals(normalized) && StringUtils.isEmpty(trimmedReason))
        {
            throw new ServiceException("驳回必须填写原因", HttpStatus.BAD_REQUEST);
        }
        if (trimmedReason != null && trimmedReason.length() > 255)
        {
            throw new ServiceException("审核原因不超过255字", HttpStatus.BAD_REQUEST);
        }
        int updated = jdbc.update(
                "UPDATE merchant_npc_avatar_job SET review_status = ?, review_reason = ?, review_time = ? WHERE id = ?",
                normalized, trimmedReason, new Date(), id);
        if (updated == 0) throw new ServiceException("形象生成任务不存在", HttpStatus.NOT_FOUND);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, merchant_id, status, model_url, thumb_url, COALESCE(review_status,'PENDING') AS review_status, review_reason, review_time FROM merchant_npc_avatar_job WHERE id = ?",
                id);
        return rows.isEmpty() ? Collections.<String, Object>emptyMap() : rows.get(0);
    }

    public List<Map<String, Object>> listVoiceAudits(Integer scopeType, Long scopeId)
    {
        StringBuilder sql = new StringBuilder(
                "SELECT p.id, p.scope_type, p.scope_id, p.name, p.voice_id, p.voice_status, p.voice_version, p.voice_sample, "
              + "i.id AS intent_id, i.state AS intent_state, i.actor_member_id, i.sample_reference, i.created_at AS consent_time "
              + "FROM npc_profile p "
              + "LEFT JOIN npc_voice_enrollment_intent i ON i.id = ("
              + "  SELECT i2.id FROM npc_voice_enrollment_intent i2 WHERE i2.npc_id = p.id ORDER BY i2.id DESC LIMIT 1"
              + ") "
              + "WHERE (p.del_flag IS NULL OR p.del_flag = '0') "
              + "AND (p.voice_status > 0 OR p.voice_id IS NOT NULL OR i.id IS NOT NULL)");
        List<Object> params = new ArrayList<Object>();
        if (scopeType != null) { sql.append(" AND p.scope_type = ?"); params.add(scopeType); }
        if (scopeId != null && scopeId > 0L) { sql.append(" AND p.scope_id = ?"); params.add(scopeId); }
        sql.append(" ORDER BY p.update_time DESC, p.id DESC LIMIT 100");
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> revokeVoiceConsent(Long npcId, Long expectedVersion)
    {
        if (npcId == null || npcId <= 0L) throw new ServiceException("NPC ID 无效", HttpStatus.BAD_REQUEST);
        List<Map<String, Object>> current = jdbc.queryForList(
                "SELECT id, voice_version, voice_status FROM npc_profile WHERE id = ? AND (del_flag IS NULL OR del_flag = '0')",
                npcId);
        if (current.isEmpty()) throw new ServiceException("NPC不存在", HttpStatus.NOT_FOUND);
        Number verNum = (Number) current.get(0).get("voice_version");
        long currentVer = verNum == null ? 0L : verNum.longValue();
        long matchVer = expectedVersion != null ? expectedVersion : currentVer;
        int updated = jdbc.update(
                "UPDATE npc_profile SET voice_id = NULL, voice_status = 0, voice_sample = NULL, voice_version = voice_version + 1, update_time = NOW() WHERE id = ? AND voice_version = ?",
                npcId, matchVer);
        if (updated == 0) throw new ServiceException("声音状态已变更，请刷新后重试", HttpStatus.CONFLICT);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", npcId);
        out.put("voiceStatus", 0);
        out.put("voiceVersion", matchVer + 1);
        out.put("revoked", true);
        return out;
    }

    /** 只读返回各AI/形象/语音提供方的环境变量接入状态(绝不返回密钥明文或前后缀)。 */
    public List<Map<String, Object>> providerKeyStatuses()
    {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        list.add(providerStatus("llm_default", "默认对话模型 (DeepSeek/OpenAI兼容)",
                aiModelConfig != null ? aiModelConfig.getProvider() : "unconfigured",
                aiModelConfig != null ? aiModelConfig.getModel() : "",
                aiModelConfig != null ? hostOf(aiModelConfig.getBaseUrl()) : "",
                aiModelConfig != null && aiModelConfig.isEnabled(),
                aiModelConfig != null && StringUtils.hasText(aiModelConfig.getApiKey()),
                "CHENGYIN_AI_API_KEY / CHENGYIN_DEEPSEEK_API_KEY"));

        AiProviderProperties.ProviderConfig minimax = (aiProviderProperties != null && aiProviderProperties.getProviders() != null)
                ? aiProviderProperties.getProviders().get("minimax") : null;
        list.add(providerStatus("minimax", "MiniMax 文本备选模型",
                "minimax",
                minimax != null ? StringUtils.nvl(minimax.getModel(), "") : "",
                minimax != null ? hostOf(minimax.getBaseUrl()) : "",
                minimax != null && minimax.isEnabled(),
                minimax != null && StringUtils.hasText(minimax.getApiKey()),
                "CHENGYIN_MINIMAX_API_KEY"));

        list.add(providerStatus("vision", "视觉审核模型",
                "qwen-vl",
                visionModelConfig != null ? visionModelConfig.getModel() : "",
                visionModelConfig != null ? hostOf(visionModelConfig.getBaseUrl()) : "",
                visionModelConfig != null && visionModelConfig.isEnabled(),
                visionModelConfig != null && StringUtils.hasText(visionModelConfig.getApiKey()),
                "CHENGYIN_DEEPSEEK_API_KEY"));

        list.add(providerStatus("voice_dashscope", "百炼声音复刻与语音识别",
                "dashscope",
                aiVoiceConfig != null ? StringUtils.nvl(aiVoiceConfig.getEnrollModel(), "") : "",
                aiVoiceConfig != null ? hostOf(aiVoiceConfig.baseUrl()) : "",
                aiVoiceConfig != null && aiVoiceConfig.isEnabled(),
                aiVoiceConfig != null && StringUtils.hasText(aiVoiceConfig.getApiKey()),
                "DASHSCOPE_API_KEY / AI_VOICE_API_KEY"));

        list.add(providerStatus("merchant_avatar", "3D形象生成服务",
                avatarProvider,
                "",
                hostOf(avatarBaseUrl),
                !"disabled".equalsIgnoreCase(avatarProvider) && StringUtils.hasText(avatarBaseUrl),
                StringUtils.hasText(avatarApiKey),
                "MERCHANT_NPC_AVATAR_API_KEY"));
        return list;
    }

    private static Map<String, Object> providerStatus(String code, String label, String provider, String model,
            String baseUrlHost, boolean enabled, boolean keyConfigured, String envVarHint)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("code", code);
        m.put("label", label);
        m.put("provider", provider);
        m.put("model", model);
        m.put("baseUrlHost", baseUrlHost);
        m.put("enabled", enabled);
        m.put("keyConfigured", keyConfigured);
        m.put("envVarHint", envVarHint);
        return m;
    }

    private static String hostOf(String url)
    {
        if (!StringUtils.hasText(url)) return "";
        try { URI u = URI.create(url.trim()); return u.getHost() != null ? u.getHost() : ""; }
        catch (Exception e) { return ""; }
    }

    private void enrichInitialRelation(NpcProfile row)
    {
        if (row == null || row.getScopeId() == null || StringUtils.isEmpty(row.getRelationKey())) return;
        ThemeStateKey key = themeStateKeyMapper.selectByTopicIdAndStateKey(row.getScopeId(), row.getRelationKey());
        if (key != null) row.setInitialRelation(key.getInitialValue());
    }

    private void syncInitialRelation(Long topicId, String relationKey, String npcName, Integer initialRelation)
    {
        if (topicId == null || StringUtils.isEmpty(relationKey)) return;
        ThemeStateKey existing = themeStateKeyMapper.selectByTopicIdAndStateKey(topicId, relationKey);
        int boundedInitial = initialRelation == null ? (existing != null && existing.getInitialValue() != null ? existing.getInitialValue() : 0)
                : Math.max(-10, Math.min(10, initialRelation));
        if (existing == null)
        {
            ThemeStateKey key = new ThemeStateKey();
            key.setTopicId(topicId);
            key.setStateKey(relationKey);
            key.setKeyType(ThemeStateKey.TYPE_RELATION);
            key.setScope(ThemeStateKey.SCOPE_RUN);
            key.setLabel(npcName != null ? npcName : relationKey);
            key.setInitialValue(boundedInitial);
            key.setMinValue(-10);
            key.setMaxValue(10);
            themeStateKeyMapper.upsertThemeStateKey(key);
        }
        else if (initialRelation != null)
        {
            existing.setInitialValue(boundedInitial);
            themeStateKeyMapper.updateDefinition(existing);
        }
    }

    private static String normalizeRelationKey(String raw)
    {
        if (!StringUtils.hasText(raw)) return null;
        String trimmed = raw.trim();
        if (!trimmed.startsWith("relation.") || !ThemeStateKey.KEY_NAME.matcher(trimmed).matches())
        {
            throw new ServiceException("关系状态键须为 relation.<小写字母数字下划线>", HttpStatus.BAD_REQUEST);
        }
        return trimmed;
    }

    private static String validateFields(NpcProfile body)
    {
        if (body == null || StringUtils.isEmpty(body.getName()) || body.getName().trim().isEmpty()) return "角色名称不能为空";
        if (body.getName().trim().length() > NAME_MAX) return "角色名称不超过32字";
        if (body.getAvatar() != null && body.getAvatar().trim().length() > AVATAR_MAX) return "头像地址过长";
        if (body.getGreeting() != null && body.getGreeting().trim().length() > GREETING_MAX) return "开场白不超过255字";
        if (body.getPersona() != null && body.getPersona().trim().length() > PERSONA_MAX) return "人设描述不超过2000字";
        if (body.getInitialRelation() != null && (body.getInitialRelation() < -10 || body.getInitialRelation() > 10))
        {
            return "初始关系值范围为 -10 ~ 10";
        }
        return null;
    }

    private static String trimToNull(String value)
    {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
