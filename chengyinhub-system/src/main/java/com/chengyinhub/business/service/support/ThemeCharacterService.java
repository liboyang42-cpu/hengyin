package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.ThemeCharacter;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.ThemeCharacterMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.OwnershipUtils;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 动态角色卡(F2 RPG P0 / T02)。
 *
 * <p>属性定义来自 theme_state_key 的 counter 键(T01 扩展列给范围 / 展示 / 持久化);
 * 属性值的唯一真源仍是局状态 {@code topic_route_session.state_json.variables} —— 本服务不新增
 * 任何客户端写入口。{@code persist_scope=topic} 的值在建新一局时从该玩家最近一局结转进
 * theme_character,再灌回新局;run 作用域每局从初值重置。没有任何 topic 作用域键的主题
 * (全部老主题)建局时只多一次注册表查询,行为不变。
 */
@Service
public class ThemeCharacterService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ThemeStateKeyMapper themeStateKeyMapper;
    private final ThemeCharacterMapper themeCharacterMapper;
    private final TopicRouteSessionMapper topicRouteSessionMapper;
    private final CmsTopicMapper cmsTopicMapper;

    public ThemeCharacterService(ThemeStateKeyMapper themeStateKeyMapper, ThemeCharacterMapper themeCharacterMapper,
                                 TopicRouteSessionMapper topicRouteSessionMapper, CmsTopicMapper cmsTopicMapper) {
        this.themeStateKeyMapper = themeStateKeyMapper;
        this.themeCharacterMapper = themeCharacterMapper;
        this.topicRouteSessionMapper = topicRouteSessionMapper;
        this.cmsTopicMapper = cmsTopicMapper;
    }

    /**
     * 玩家本人在某主题下的角色卡。还没建过角色(无台账、无任何一局)时返回模板:各属性初值,
     * {@code created=false}。只读。
     */
    public Map<String, Object> card(Long memberId, Long topicId) {
        if (memberId == null || memberId <= 0L) {
            throw new ServiceException("请先登录", HttpStatus.UNAUTHORIZED).setDetailMessage("CHARACTER_LOGIN_REQUIRED");
        }
        if (topicId == null || topicId <= 0L) {
            throw new ServiceException("参数不合规", HttpStatus.BAD_REQUEST).setDetailMessage("CHARACTER_BAD_REQUEST");
        }
        CmsTopic topic = cmsTopicMapper.selectCmsTopicById(topicId);
        boolean deleted = topic == null || (topic.getDelFlag() != null && topic.getDelFlag() == 2);
        boolean published = !deleted && topic.getPublishedConfigVersion() != null && topic.getPublishedConfigVersion() > 0L;
        if (deleted || (!published && OwnershipUtils.notOwner(topic.getMemberId(), memberId))) {
            throw new ServiceException("主题不存在", HttpStatus.NOT_FOUND).setDetailMessage("CHARACTER_TOPIC_NOT_FOUND");
        }
        TopicRouteSession latest = latestSession(memberId, topicId);
        String snapshot = latest == null ? topic.getPublishedRouteGraphJson() : latest.getConfigSnapshotJson();
        List<ThemeStateKey> definitions = JourneyInitialState.frozenDefinitions(snapshot);
        String frozenRules = NodeJourneySnapshot.journeyRules(snapshot);
        if (definitions != null) frozenRules = JourneyInitialState.frozenDefinitionRules(snapshot);
        String rulesJson = frozenRules == null ? topic.getJourneyRulesJson() : frozenRules;
        Map<String, JsonNode> attributes = journeyAttributes(rulesJson);
        List<ThemeStateKey> keys = cardKeys(topicId, attributes, definitions);

        ThemeCharacter character = themeCharacterMapper.selectByMemberAndTopic(memberId, topicId);
        Map<String, Integer> carried = carriedValues(character, latest, keys);
        boolean running = latest != null && Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(latest.getStatus());
        Map<String, Integer> live = running ? variables(latest.getStateJson()) : new HashMap<String, Integer>();

        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (ThemeStateKey key : keys) {
            Integer value = live.get(key.getStateKey());
            if (value == null && key.persistsAcrossRuns()) value = carried.get(key.getStateKey());
            if (value == null) value = Integer.valueOf(initialOf(key, attributes));
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("stateKey", key.getStateKey());
            row.put("label", key.displayLabel());
            row.put("value", Integer.valueOf(clamp(value.intValue(), key, attributes)));
            row.put("min", Integer.valueOf(key.effectiveMin()));
            row.put("max", Integer.valueOf(key.effectiveMax()));
            row.put("displayType", key.getDisplayType() == null ? ThemeStateKey.DISPLAY_NUMBER : key.getDisplayType());
            row.put("color", key.getColor());
            row.put("icon", key.getIcon());
            row.put("persistScope", key.persistsAcrossRuns() ? ThemeStateKey.PERSIST_TOPIC : ThemeStateKey.PERSIST_RUN);
            row.put("rules", rules(key.getRulesJson()));
            rows.add(row);
        }
        Map<String, Object> card = new LinkedHashMap<String, Object>();
        card.put("topicId", topicId);
        card.put("created", Boolean.valueOf(character != null || latest != null));
        card.put("running", Boolean.valueOf(running));
        card.put("attributes", rows);
        // F6 T12:主题开了 journeyRules.rpg 才多一个 rpg 块(动量 / 侧面 / 特质 / 进度钟);老主题响应不变。
        JourneyRules rpgRules = rpgRules(rulesJson);
        if (rpgRules != null) card.put("rpg", JourneyRpg.view(rpgRules, running ? engineState(latest.getStateJson()) : null));
        return card;
    }

    private static JourneyRules rpgRules(String journeyRulesJson) {
        if (StringUtils.isBlank(journeyRulesJson)) return null;
        try {
            JourneyRules rules = JourneyRules.parse(journeyRulesJson);
            return rules.getRpg() == null ? null : rules;
        } catch (IllegalArgumentException unusable) {
            return null;     // 与 journeyAttributes 同口径:规则坏掉不替发布闸报错
        }
    }

    private static TopicRouteEngine.State engineState(String stateJson) {
        if (StringUtils.isBlank(stateJson)) return null;
        try {
            return JSON.readValue(stateJson, TopicRouteEngine.State.class);
        } catch (Exception unusable) {
            return null;
        }
    }

    /**
     * 建新一局时调用(已完成 journeyRules 初值之后):先把该玩家最近一局的 topic 作用域属性结转进台账,
     * 再把台账值灌进新局。没有 topic 作用域键 → 直接返回,不碰台账。
     *
     * <p>幂等:台账按来源 (sessionId, version) 只增不减;同一来源重放 = 不写。新局里没初始化的键
     * (该属性没在 journeyRules.attributes 里启用)不凭空创建 —— 运行期变量表只由规则决定。
     */
    public void seedRun(Long memberId, Long topicId, JourneyRules rules, TopicRouteEngine.State state) {
        seedRun(memberId, topicId, rules, state, null);
    }

    /** Original carry ledger/value policy; only author definitions come from this new run's publication. */
    public void seedRun(Long memberId, Long topicId, JourneyRules rules, TopicRouteEngine.State state, String snapshot) {
        if (memberId == null || memberId <= 0L || topicId == null || topicId <= 0L || state == null) return;
        List<ThemeStateKey> persisted = new ArrayList<ThemeStateKey>();
        List<ThemeStateKey> all = JourneyInitialState.frozenDefinitions(snapshot);
        if (all == null) all = themeStateKeyMapper.selectByTopicId(topicId);
        else rules = JourneyRules.parse(JourneyInitialState.frozenDefinitionRules(snapshot));
        if (all != null) {
            for (ThemeStateKey key : all) {
                if (key != null && ThemeStateKey.TYPE_COUNTER.equals(key.getKeyType()) && key.persistsAcrossRuns()) {
                    persisted.add(key);
                }
            }
        }
        if (persisted.isEmpty()) return;
        Map<String, JsonNode> attributes = new HashMap<String, JsonNode>();
        if (rules != null) {
            for (JsonNode row : rules.getAttributes()) attributes.put(row.path("key").asText(), row);
        }
        ThemeCharacter character = themeCharacterMapper.selectByMemberAndTopic(memberId, topicId);
        TopicRouteSession latest = latestSession(memberId, topicId);
        Map<String, Integer> carried = carriedValues(character, latest, persisted);
        if (latest != null && latest.getId() != null && newer(latest, character)) {
            record(memberId, topicId, latest, carried);
        }
        for (ThemeStateKey key : persisted) {
            Integer value = carried.get(key.getStateKey());
            if (value == null || !state.getVariables().containsKey(key.getStateKey())) continue;
            state.getVariables().put(key.getStateKey(), Integer.valueOf(clamp(value.intValue(), key, attributes)));
        }
    }

    private void record(Long memberId, Long topicId, TopicRouteSession source, Map<String, Integer> values) {
        ThemeCharacter row = new ThemeCharacter();
        row.setMemberId(memberId);
        row.setTopicId(topicId);
        try {
            row.setValuesJson(JSON.writeValueAsString(new java.util.TreeMap<String, Integer>(values)));
        } catch (Exception e) {
            throw new IllegalStateException("角色卡台账序列化失败", e);
        }
        row.setSourceSessionId(source.getId());
        row.setSourceVersion(source.getVersion() == null ? Integer.valueOf(0) : source.getVersion());
        Date now = DateUtils.getNowDate();
        row.setCreateTime(now);
        row.setUpdateTime(now);
        if (themeCharacterMapper.updateIfNewer(row) == 0) {
            themeCharacterMapper.insertIfAbsent(row);
        }
    }

    /** 台账值叠上最近一局(比台账来源新时)的权威变量;只取给定键。 */
    private Map<String, Integer> carriedValues(ThemeCharacter character, TopicRouteSession latest, List<ThemeStateKey> keys) {
        Set<String> wanted = new HashSet<String>();
        for (ThemeStateKey key : keys) if (key.persistsAcrossRuns()) wanted.add(key.getStateKey());
        Map<String, Integer> values = new HashMap<String, Integer>();
        if (character != null) {
            for (Map.Entry<String, Integer> entry : integers(character.getValuesJson()).entrySet()) {
                if (wanted.contains(entry.getKey())) values.put(entry.getKey(), entry.getValue());
            }
        }
        if (latest != null && newer(latest, character)) {
            for (Map.Entry<String, Integer> entry : variables(latest.getStateJson()).entrySet()) {
                if (wanted.contains(entry.getKey())) values.put(entry.getKey(), entry.getValue());
            }
        }
        return values;
    }

    private static boolean newer(TopicRouteSession session, ThemeCharacter character) {
        if (character == null || character.getSourceSessionId() == null) return true;
        long sid = session.getId() == null ? 0L : session.getId();
        int version = session.getVersion() == null ? 0 : session.getVersion();
        long recorded = character.getSourceSessionId();
        int recordedVersion = character.getSourceVersion() == null ? 0 : character.getSourceVersion();
        return sid > recorded || (sid == recorded && version > recordedVersion);
    }

    private TopicRouteSession latestSession(Long memberId, Long topicId) {
        List<TopicRouteSession> rows = topicRouteSessionMapper.selectHistoryPage(memberId, topicId, null, 1);
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    /** 角色卡展示的键:counter、可见,且没被 journeyRules 标成隐藏属性;按 sortOrder 升序,null 排最后。 */
    private List<ThemeStateKey> cardKeys(Long topicId, Map<String, JsonNode> attributes, List<ThemeStateKey> definitions) {
        List<ThemeStateKey> keys = new ArrayList<ThemeStateKey>();
        List<ThemeStateKey> all = definitions == null ? themeStateKeyMapper.selectByTopicId(topicId) : definitions;
        if (all == null) return keys;
        for (ThemeStateKey key : all) {
            if (key == null || !ThemeStateKey.TYPE_COUNTER.equals(key.getKeyType()) || !key.shownOnCard()) continue;
            JsonNode attribute = attributes.get(key.getStateKey());
            if (attribute != null && !attribute.path("visible").asBoolean(true)) continue;
            keys.add(key);
        }
        keys.sort(Comparator.comparing((ThemeStateKey k) -> k.getSortOrder() == null ? Integer.MAX_VALUE : k.getSortOrder())
                .thenComparing(k -> k.getId() == null ? Long.MAX_VALUE : k.getId()));
        return keys;
    }

    /** 运行期初值以 journeyRules.attributes 为准(建局真用的那份);没在规则里的键退回注册表 initial_value。 */
    private static int initialOf(ThemeStateKey key, Map<String, JsonNode> attributes) {
        JsonNode attribute = attributes.get(key.getStateKey());
        if (attribute != null && attribute.has("initial")) return attribute.path("initial").asInt();
        return key.getInitialValue() == null ? 0 : key.getInitialValue();
    }

    /** 同时夹进注册表范围与 journeyRules 属性范围(后者是运行期 clamp 的口径)。 */
    private static int clamp(int value, ThemeStateKey key, Map<String, JsonNode> attributes) {
        int min = key.effectiveMin();
        int max = key.effectiveMax();
        JsonNode attribute = attributes.get(key.getStateKey());
        if (attribute != null) {
            if (attribute.has("min")) min = Math.max(min, attribute.path("min").asInt());
            if (attribute.has("max")) max = Math.min(max, attribute.path("max").asInt());
        }
        if (min > max) return value;
        return Math.max(min, Math.min(max, value));
    }

    private static Map<String, JsonNode> journeyAttributes(String journeyRulesJson) {
        Map<String, JsonNode> attributes = new HashMap<String, JsonNode>();
        if (StringUtils.isBlank(journeyRulesJson)) return attributes;
        try {
            for (JsonNode row : JourneyRules.parse(journeyRulesJson).getAttributes()) {
                attributes.put(row.path("key").asText(), row);
            }
        } catch (IllegalArgumentException unusable) {
            // 规则坏掉时角色卡退回注册表口径;发布闸另有拦截,这里不替它报错。
        }
        return attributes;
    }

    private static Map<String, Integer> variables(String stateJson) {
        if (StringUtils.isBlank(stateJson)) return new HashMap<String, Integer>();
        try {
            return integerFields(JSON.readTree(stateJson).path("variables"));
        } catch (Exception e) {
            return new HashMap<String, Integer>();
        }
    }

    private static Map<String, Integer> integers(String json) {
        if (StringUtils.isBlank(json)) return new HashMap<String, Integer>();
        try {
            return integerFields(JSON.readTree(json));
        } catch (Exception e) {
            return new HashMap<String, Integer>();
        }
    }

    private static Map<String, Integer> integerFields(JsonNode node) {
        Map<String, Integer> values = new HashMap<String, Integer>();
        if (node == null || !node.isObject()) return values;
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            if (value.isNumber() && value.canConvertToInt() && value.asDouble() == value.asInt()) {
                values.put(field.getKey(), Integer.valueOf(value.asInt()));
            }
        }
        return values;
    }

    private static JsonNode rules(String rulesJson) {
        if (StringUtils.isBlank(rulesJson)) return null;
        try {
            JsonNode node = JSON.readTree(rulesJson);
            return node instanceof ObjectNode ? node : null;
        } catch (Exception e) {
            return null;
        }
    }
}
