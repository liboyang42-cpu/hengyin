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
 * <p>DEC02完整版本局读取attributeDefinition；旧局保持既有partial兼容，模板读取启用的注册定义。
 * 属性值的唯一真源仍是局状态 {@code topic_route_session.state_json.variables} —— 本服务不新增
 * 任何客户端写入口。{@code persist_scope=topic} 的值在建新一局时从该玩家最近一局结转进
 * theme_character,再灌回新局;仅新旧冻结定义均为topic时继承，其余从新局初值开始。
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
        String capturedRules = NodeJourneySnapshot.journeyRules(snapshot);
        if (definitions != null) capturedRules = JourneyInitialState.frozenDefinitionRules(snapshot);
        String rulesJson = capturedRules == null ? topic.getJourneyRulesJson() : capturedRules;
        ThemeCharacter character = themeCharacterMapper.selectByMemberAndTopic(memberId, topicId);
        ThemeAttributeDefinition.Snapshot definition = latest == null ? null
                : ThemeAttributeDefinition.read(latest.getConfigSnapshotJson());
        // DEC02 complete definitions retain their versioned card/carry contract.
        if (matchesVersion(definition, latest)) return versionedCard(topicId, latest, definition);
        if (latest == null && character == null) return templateCard(topic, rulesJson, definitions);
        boolean running = latest != null && Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(latest.getStatus());
        Map<String, JsonNode> attributes = journeyAttributes(rulesJson);
        List<ThemeStateKey> keys = cardKeys(topicId, attributes, definitions);
        Map<String, Integer> carried = carriedValues(character, latest, keys);
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
        card.put("topicId", topicId.toString());
        card.put("created", Boolean.valueOf(character != null || latest != null));
        card.put("running", Boolean.valueOf(running));
        card.put("definitionSource", "LEGACY_LIVE");
        card.put("configVersion", null);
        card.put("attributes", rows);
        // F6 T12:主题开了 journeyRules.rpg 才多一个 rpg 块(动量 / 侧面 / 特质 / 进度钟);老主题响应不变。
        JourneyRules rpgRules = rpgRules(rulesJson);
        if (rpgRules != null) card.put("rpg", JourneyRpg.view(rpgRules, running ? engineState(latest.getStateJson()) : null));
        return card;
    }

    /** 运行局冻结的 journeyRules(rpg 块用);旧快照没有该键返回 null，损坏时返回空对象。 */
    private static JsonNode frozenRules(String snapshotJson) {
        if (StringUtils.isBlank(snapshotJson)) return null;
        try {
            JsonNode snapshot = JSON.readTree(snapshotJson);
            if (snapshot != null && snapshot.isObject() && !snapshot.has(NodeJourneySnapshot.JOURNEY_RULES_KEY)) return null;
            JsonNode rules = snapshot == null ? null : snapshot.get(NodeJourneySnapshot.JOURNEY_RULES_KEY);
            if (rules != null && rules.isObject() && !rules.has("attributes")) return null;
            return rules != null && rules.isObject() ? rules : JSON.createObjectNode();
        } catch (Exception unusableSnapshot) {
            return JSON.createObjectNode();
        }
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
     * 保留旧调用签名供04逐点接线。没有完整新局定义不能推断持久范围，保留调用前初始化值。
     *
     * <p>实际结转使用下方接收newSnapshotJson的重载；不会从实时注册表替旧快照补字段。
     */
    public void seedRun(Long memberId, Long topicId, JourneyRules rules, TopicRouteEngine.State state) {
        // 兼容调用签名不推断持久范围；04接入完整快照重载后才能继承。
        seedRun(memberId, topicId, rules, state, null);
    }

    /** DEC02 A：仅新旧完整冻结定义均为topic的同key继承；旧partial没有继承资格。 */
    public void seedRun(Long memberId, Long topicId, JourneyRules rules, TopicRouteEngine.State state,
                        String newSnapshotJson) {
        if (memberId == null || memberId <= 0L || topicId == null || topicId <= 0L || state == null) return;
        ThemeAttributeDefinition.Snapshot next = ThemeAttributeDefinition.read(newSnapshotJson);
        if (next == null || !next.complete) return;
        // 只重置已经由当前运行规则初始化的变量，不自动启用注册表里的其他键。
        boolean hasTopic = false;
        for (ThemeStateKey key : next.keys) {
            hasTopic |= key.persistsAcrossRuns();
            if (ThemeStateKey.TYPE_COUNTER.equals(key.getKeyType()) && state.getVariables().containsKey(key.getStateKey()))
                state.getVariables().put(key.getStateKey(), key.getInitialValue());
        }
        if (!hasTopic) return;
        TopicRouteSession latest = latestSession(memberId, topicId);
        if (latest == null) return;
        ThemeAttributeDefinition.Snapshot previous = ThemeAttributeDefinition.read(latest.getConfigSnapshotJson());
        if (!matchesVersion(previous, latest)) return;
        Set<String> previousTopic = new HashSet<>();
        for (ThemeStateKey key : previous.keys) if (key.persistsAcrossRuns()) previousTopic.add(key.getStateKey());
        Map<String, Integer> source = variables(latest.getStateJson());
        Map<String, Integer> carried = new HashMap<>();
        for (ThemeStateKey key : next.keys) {
            String name = key.getStateKey();
            if (!key.persistsAcrossRuns() || !previousTopic.contains(name)
                    || !state.getVariables().containsKey(name) || !source.containsKey(name)) continue;
            // 台账保留原局事实，仅新局值按新范围夹取。
            carried.put(name, source.get(name));
            state.getVariables().put(name, Math.max(key.effectiveMin(), Math.min(key.effectiveMax(), source.get(name))));
        }
        ThemeCharacter character = themeCharacterMapper.selectByMemberAndTopic(memberId, topicId);
        if (!carried.isEmpty() && latest.getId() != null && newer(latest, character)) record(memberId, topicId, latest, carried);
    }

    private static boolean matchesVersion(ThemeAttributeDefinition.Snapshot definition, TopicRouteSession session) {
        return definition != null && definition.complete && session.getConfigVersion() != null
                && definition.configVersion.equals(session.getConfigVersion().toString());
    }

    private Map<String, Object> versionedCard(Long topicId, TopicRouteSession session, ThemeAttributeDefinition.Snapshot definition) {
        Map<String, Object> card = definitionCard(topicId, definition.keys,
                variables(session.getStateJson()), true, Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus()));
        card.put("definitionSource", "SESSION_SNAPSHOT_V1");
        card.put("configVersion", definition.configVersion);
        card.put("registryVersion", definition.registryVersion);
        card.put("definitionMissingFields", java.util.Collections.emptyList());
        JsonNode frozen = frozenRules(session.getConfigSnapshotJson());
        JourneyRules rpg = frozen == null ? null : rpgRules(frozen.toString());
        if (rpg != null) card.put("rpg", JourneyRpg.view(rpg, engineState(session.getStateJson())));
        return card;
    }

    private Map<String, Object> templateCard(CmsTopic topic, String rulesJson, List<ThemeStateKey> definitions) {
        JourneyRules templateRules;
        try {
            templateRules = JourneyRules.parse(rulesJson);
        } catch (IllegalArgumentException invalidRules) { templateRules = new JourneyRules(); }
        Map<String, JsonNode> enabled = new HashMap<>();
        if (templateRules.isStateEnabled()) for (JsonNode row : templateRules.getAttributes()) enabled.put(row.path("key").asText(), row);
        List<ThemeStateKey> selected = new ArrayList<>();
        for (ThemeStateKey key : cardKeys(topic.getId(), java.util.Collections.emptyMap(), definitions)) {
            if (enabled.containsKey(key.getStateKey())) selected.add(key);
        }
        Map<String, Object> card = definitionCard(topic.getId(), selected, java.util.Collections.emptyMap(), false, false);
        card.put("definitionSource", "REGISTRY_TEMPLATE"); card.put("configVersion", null);
        if (templateRules.getRpg() != null) card.put("rpg", JourneyRpg.view(templateRules, null));
        return card;
    }

    private static Map<String, Object> definitionCard(Long topicId, List<ThemeStateKey> keys, Map<String, Integer> values,
                                                       boolean created, boolean running) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ThemeStateKey key : keys) {
            if (!ThemeStateKey.TYPE_COUNTER.equals(key.getKeyType()) || !key.shownOnCard()) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stateKey", key.getStateKey()); row.put("label", key.displayLabel());
            // 已有局的运行值不在读取时按后来定义重算；缺值仅用同一冻结初值。
            row.put("value", values.getOrDefault(key.getStateKey(), key.getInitialValue() == null ? 0 : key.getInitialValue()));
            row.put("min", key.effectiveMin()); row.put("max", key.effectiveMax());
            row.put("displayType", key.getDisplayType() == null ? ThemeStateKey.DISPLAY_NUMBER : key.getDisplayType());
            row.put("persistScope", key.persistsAcrossRuns() ? ThemeStateKey.PERSIST_TOPIC : ThemeStateKey.PERSIST_RUN);
            row.put("color", key.getColor()); row.put("icon", key.getIcon()); row.put("rules", rules(key.getRulesJson()));
            rows.add(row);
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("topicId", topicId.toString()); card.put("created", created); card.put("running", running); card.put("attributes", rows);
        return card;
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
            // 首次建档可能被旧来源抢先插入；无论驱动如何报告 no-op 行数，都再按来源版本校验。
            // 自己刚插入或竞争者来源更新时仍是 no-op，绝不覆盖更新的台账。
            themeCharacterMapper.updateIfNewer(row);
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
