package com.chengyinhub.business.opsconsole;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.mapper.opsconsole.JourneyRulesAdminMapper;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.support.JourneyRulesValidator;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.business.service.support.TopicStateKeyRefService;
import com.chengyinhub.business.util.TopicEditWindow;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * R10 BE-24 主题数值规则（journeyRules）与具名状态键的后台结构化编辑器。
 *
 * <p>校验一律复用作者端同一套：规则走 {@link JourneyRulesValidator}，状态键走 {@link ThemeStateKeyService}，
 * 删除前查 {@link TopicStateKeyRefService#referencesOf} 引用位置。预览返回规范化后的 JSON 与「引用了但没注册」的键，
 * 不写库。
 *
 * <p>守门（保守口径）：
 * <ul>
 *   <li>规则只在编辑窗口为 FULL（草稿 / 未过审）时可保存——已过审主题的数值规则会影响正在进行的局；</li>
 *   <li>状态键与作者端一致：SELLING 后不可改；</li>
 *   <li>attributeBinding / attributeDefinition 是发布时冻结的派生块，后台编辑一律沿用库里原值，不接受覆盖。</li>
 * </ul>
 */
@Service
public class JourneyRulesAdminService
{
    private static final ObjectMapper JSON = new ObjectMapper();
    static final Set<String> FROZEN_BLOCKS = Set.of("attributeBinding", "attributeDefinition");

    @Autowired
    private JourneyRulesAdminMapper mapper;
    @Autowired
    private ICmsTopicService topics;
    @Autowired
    private ThemeStateKeyService stateKeys;
    @Autowired
    private TopicStateKeyRefService refs;
    @Autowired
    private OpsConsoleAudit audit;

    private final JourneyRulesValidator validator = new JourneyRulesValidator();

    public List<Map<String, Object>> topics(String keyword, Long topicId)
    {
        String k = keyword == null ? null : keyword.trim();
        return mapper.searchTopics(k == null || k.isEmpty() ? null : (k.length() > 64 ? k.substring(0, 64) : k), topicId);
    }

    public Map<String, Object> read(Long topicId)
    {
        CmsTopic topic = topic(topicId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topic", topicView(topic));
        body.put("editScope", scope(topic).name());
        body.put("rulesJson", topic.getJourneyRulesJson());
        try
        {
            String normalized = validator.validateAndNormalize(topic.getJourneyRulesJson());
            body.put("normalized", normalized);
            body.put("problem", null);
        }
        catch (IllegalArgumentException e)
        {
            body.put("normalized", null);
            body.put("problem", e.getMessage());
        }
        body.put("stateKeys", stateKeys.listByTopic(topic.getId()));
        return body;
    }

    /** 只读试算：返回规范化 JSON、摘要与未注册的状态键引用；校验不过返回 valid=false 与原因，不抛。 */
    public Map<String, Object> preview(Long topicId, String rawJson)
    {
        CmsTopic topic = topic(topicId);
        Map<String, Object> body = new LinkedHashMap<>();
        String normalized;
        try
        {
            normalized = normalize(topic, rawJson);
        }
        catch (IllegalArgumentException e)
        {
            body.put("valid", false);
            body.put("problem", e.getMessage());
            return body;
        }
        body.put("valid", true);
        body.put("normalized", normalized);
        body.put("pretty", pretty(normalized));
        body.put("summary", summary(normalized));
        body.put("unregisteredKeys", unregisteredKeys(topic, normalized));
        return body;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(Long sysUserId, Long topicId, String rawJson, String note)
    {
        if (note == null || note.isBlank() || note.length() > 500) throw bad("请填写 500 字内的修改说明");
        CmsTopic topic = topic(topicId);
        if (scope(topic) != TopicEditWindow.Scope.FULL)
            throw new ServiceException("主题已过审或在售，数值规则会影响进行中的局，后台不能直接改；请让创作者在草稿版本里修改", HttpStatus.CONFLICT);
        String normalized;
        try { normalized = normalize(topic, rawJson); }
        catch (IllegalArgumentException e) { throw bad(e.getMessage()); }
        String before = topic.getJourneyRulesJson();
        if (mapper.updateJourneyRules(topic.getId(), before, normalized) != 1)
            throw new ServiceException("规则已被修改，请刷新后再保存", HttpStatus.CONFLICT);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("topicId", topic.getId());
        b.put("journeyRules", before == null ? null : readTree(before));
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("topicId", topic.getId());
        a.put("journeyRules", normalized == null ? null : readTree(normalized));
        audit.record(sysUserId, "JOURNEY_RULES_SAVE", "CMS_TOPIC", topic.getId(), b, a, note.trim());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("normalized", normalized);
        body.put("unregisteredKeys", unregisteredKeys(topic, normalized));
        return body;
    }

    public List<String> checkStateKeys(List<ThemeStateKey> keys)
    {
        return stateKeys.check(keys);
    }

    @Transactional(rollbackFor = Exception.class)
    public void registerStateKey(Long sysUserId, Long topicId, ThemeStateKey key)
    {
        CmsTopic topic = keyWritable(topicId);
        if (key == null) throw bad("状态键不能为空");
        List<String> problems = stateKeys.check(List.of(key));
        if (!problems.isEmpty()) throw bad(problems.get(0));
        if (stateKeyExists(topic.getId(), key.getStateKey())) throw new ServiceException("状态键已存在，请用编辑", HttpStatus.CONFLICT);
        stateKeys.register(topic.getId(), List.of(key));
        audit.record(sysUserId, "STATE_KEY_CREATE", "CMS_TOPIC", topic.getId(), null, keyView(key), null);
    }

    @Transactional(rollbackFor = Exception.class)
    public ThemeStateKey updateStateKey(Long sysUserId, Long topicId, String stateKey, ThemeStateKey patch)
    {
        CmsTopic topic = keyWritable(topicId);
        ThemeStateKey before = find(topic.getId(), stateKey);
        ThemeStateKey after = stateKeys.updateDefinition(topic.getId(), stateKey, patch);
        audit.record(sysUserId, "STATE_KEY_UPDATE", "CMS_TOPIC", topic.getId(), before == null ? null : keyView(before), keyView(after), null);
        return after;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteStateKey(Long sysUserId, Long topicId, String stateKey, String note)
    {
        if (note == null || note.isBlank() || note.length() > 500) throw bad("请填写 500 字内的删除说明");
        CmsTopic topic = keyWritable(topicId);
        List<String> positions = refs.referencesOf(topic, stateKey);
        if (!positions.isEmpty())
            throw new ServiceException("状态键 " + stateKey + " 已被引用，不能删除。引用位置：" + String.join("、", positions), HttpStatus.CONFLICT);
        ThemeStateKey before = find(topic.getId(), stateKey);
        if (!stateKeys.delete(topic.getId(), stateKey)) throw new ServiceException("状态键不存在", HttpStatus.NOT_FOUND);
        audit.record(sysUserId, "STATE_KEY_DELETE", "CMS_TOPIC", topic.getId(), before == null ? null : keyView(before), null, note.trim());
    }

    /** 合并：沿用库里冻结的派生块，再走作者端同一个校验器。空白 = 清空规则（返回 null）。 */
    String normalize(CmsTopic topic, String rawJson)
    {
        if (rawJson == null || rawJson.isBlank()) return null;
        JsonNode submitted;
        try { submitted = JSON.readTree(rawJson); }
        catch (Exception e) { throw new IllegalArgumentException("规则不是合法 JSON"); }
        if (submitted == null || !submitted.isObject()) throw new IllegalArgumentException("规则必须是 JSON 对象");
        ObjectNode merged = ((ObjectNode) submitted).deepCopy();
        for (String block : FROZEN_BLOCKS) merged.remove(block);
        JsonNode stored = topic.getJourneyRulesJson() == null ? null : readTreeQuiet(topic.getJourneyRulesJson());
        if (stored != null && stored.isObject())
            for (String block : FROZEN_BLOCKS) if (stored.has(block)) merged.set(block, stored.get(block));
        return validator.validateAndNormalize(merged.toString());
    }

    private List<String> unregisteredKeys(CmsTopic topic, String normalizedRules)
    {
        CmsTopic candidate = new CmsTopic();
        candidate.setId(topic.getId());
        candidate.setJourneyRulesJson(normalizedRules);
        candidate.setJourneyStoryJson(topic.getJourneyStoryJson());
        Set<String> registered = new HashSet<>();
        for (ThemeStateKey k : stateKeys.listByTopic(topic.getId())) registered.add(k.getStateKey());
        List<String> missing = new ArrayList<>();
        try
        {
            for (String key : refs.collect(candidate).positionsByKey().keySet())
                if (!registered.contains(key)) missing.add(key);
        }
        catch (RuntimeException e)
        {
            missing.add("（引用核对失败：" + e.getMessage() + "）");
        }
        return missing;
    }

    private static Map<String, Object> summary(String normalized)
    {
        Map<String, Object> s = new LinkedHashMap<>();
        if (normalized == null) { s.put("enabled", false); return s; }
        JourneyRules r = JourneyRules.parse(normalized);
        s.put("enabled", r.hasRuntimeState());
        s.put("checkEnabled", r.isCheckEnabled());
        s.put("stateEnabled", r.isStateEnabled());
        s.put("difficulty", r.getDifficulty());
        s.put("attributes", r.getAttributes() == null ? 0 : r.getAttributes().size());
        s.put("thoughts", r.getThoughts().size());
        return s;
    }

    private CmsTopic topic(Long topicId)
    {
        CmsTopic topic = topicId == null || topicId <= 0 ? null : topics.selectCmsTopicById(topicId);
        if (topic == null || (topic.getDelFlag() != null && topic.getDelFlag() != 0)) throw new ServiceException("主题不存在", HttpStatus.NOT_FOUND);
        return topic;
    }

    private CmsTopic keyWritable(Long topicId)
    {
        CmsTopic topic = topic(topicId);
        if (scope(topic) == TopicEditWindow.Scope.SELLING_FROZEN)
            throw new ServiceException("主题已进入售卖，状态键注册表不能修改", HttpStatus.CONFLICT);
        return topic;
    }

    private TopicEditWindow.Scope scope(CmsTopic topic)
    {
        try { return TopicEditWindow.resolve(topic, topics.selectTopicIsDraft(topic.getId()), new Date()); }
        catch (IllegalStateException e) { throw new ServiceException(e.getMessage(), HttpStatus.CONFLICT); }
    }

    private boolean stateKeyExists(Long topicId, String key)
    {
        return find(topicId, key) != null;
    }

    private ThemeStateKey find(Long topicId, String key)
    {
        for (ThemeStateKey k : stateKeys.listByTopic(topicId)) if (k.getStateKey() != null && k.getStateKey().equals(key)) return k;
        return null;
    }

    private static Map<String, Object> topicView(CmsTopic t)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("status", t.getStatus());
        m.put("userStatus", t.getUserStatus());
        m.put("lifecycle", t.getLifecycle());
        return m;
    }

    private static Map<String, Object> keyView(ThemeStateKey k)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stateKey", k.getStateKey());
        m.put("keyType", k.getKeyType());
        m.put("scope", k.getScope());
        m.put("label", k.getLabel());
        m.put("initialValue", k.getInitialValue());
        m.put("minValue", k.getMinValue());
        m.put("maxValue", k.getMaxValue());
        m.put("visible", k.getVisible());
        m.put("persistScope", k.getPersistScope());
        return m;
    }

    private static String pretty(String json)
    {
        if (json == null) return null;
        try { return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(JSON.readTree(json)); }
        catch (Exception e) { return json; }
    }

    private static JsonNode readTree(String json)
    {
        try { return JSON.readTree(json); }
        catch (Exception e) { throw new IllegalStateException("规则 JSON 读取失败", e); }
    }

    private static JsonNode readTreeQuiet(String json)
    {
        try { return JSON.readTree(json); }
        catch (Exception e) { return null; }
    }

    private static ServiceException bad(String m) { return new ServiceException(m, HttpStatus.BAD_REQUEST); }
}
