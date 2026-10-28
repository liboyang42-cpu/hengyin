package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ThemeAttributeMark;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.mapper.ThemeAttributeMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档二 L8:作者只写名字,键名由系统生成(原型 A e-keys / e-init / e-route)。
 *
 * <p>线索 clue.k1…、标签 tag.k1…、数值 counter.v1…,按主题内现有最大编号递增;同一主题同一种类不许重名。
 * 改名只改 label,键名不变。「用在」= 发布闸同一份引用收集({@link TopicStateKeyRefService})
 * + 初始状态(开局带着)+ 进度钟;读不懂的旧配置 fail-closed,不能拿「没查到」放行删除。
 * 属性(is_attribute=1)不在这里列,只在 {@link ThemeAttributeService}。
 */
@Service
public class ThemeKeyNamingService {

    public static final String KIND_CLUE = "clue";
    public static final String KIND_TAG = "tag";
    public static final String KIND_COUNTER = "counter";

    public static final int MAX_LABEL = 20;

    public static final String ERR_INVALID = ThemeStateKeyService.ERR_INVALID;
    public static final String ERR_IN_USE = "STATE_KEY_IN_USE";

    static final String MSG_NAME_EMPTY = "请填写名称。";
    static final String MSG_NAME_LONG = "名称最多 20 字。";
    static final String MSG_KIND = "请选择种类。";
    static final String MSG_NOT_FOUND = "这一项不存在。";
    static final String MSG_UNREADABLE = "主题里有无法读取的配置，暂时不能查看用在哪里。";
    static final String POSITION_INITIAL = "初始状态";
    static final String POSITION_CLOCK = "进度钟";

    private static final Pattern GENERATED = Pattern.compile("^(clue|tag)\\.k([0-9]{1,9})$|^counter\\.v([0-9]{1,9})$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ThemeStateKeyMapper themeStateKeyMapper;
    private final ThemeAttributeMapper themeAttributeMapper;
    private final ThemeStateKeyService themeStateKeyService;
    private final TopicStateKeyRefService topicStateKeyRefService;

    public ThemeKeyNamingService(ThemeStateKeyMapper themeStateKeyMapper, ThemeAttributeMapper themeAttributeMapper,
                                 ThemeStateKeyService themeStateKeyService,
                                 TopicStateKeyRefService topicStateKeyRefService) {
        this.themeStateKeyMapper = themeStateKeyMapper;
        this.themeAttributeMapper = themeAttributeMapper;
        this.themeStateKeyService = themeStateKeyService;
        this.topicStateKeyRefService = topicStateKeyRefService;
    }

    // ==================== 新建 / 改名 ====================

    /** 按名字新建一个键;返回 {key, kind, label}。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(CmsTopic topic, String kind, String label) {
        String type = keyType(kind);
        String name = requireLabel(label, MAX_LABEL, MSG_NAME_LONG);
        themeStateKeyMapper.lockTopic(topic.getId());
        List<ThemeStateKey> rows = themeStateKeyService.listByTopic(topic.getId());
        Set<String> attributes = attributeKeys(topic.getId());
        requireUniqueLabel(rows, attributes, type, false, name, null, duplicateMessage(kind));
        ThemeStateKey row = new ThemeStateKey();
        row.setStateKey(nextKey(kind, rows));
        row.setKeyType(type);
        row.setScope(ThemeStateKey.SCOPE_RUN);
        row.setLabel(name);
        row.setInitialValue(Integer.valueOf(0));
        themeStateKeyService.register(topic.getId(), Collections.singletonList(row));
        return item(row, kind);
    }

    /** 只改 label;与本种类其它项重名拒。属性改名(自定义属性)也走这里,重名话术按属性。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> rename(CmsTopic topic, String key, String label) {
        themeStateKeyMapper.lockTopic(topic.getId());
        ThemeStateKey existing = key == null ? null : themeStateKeyMapper.selectByTopicIdAndStateKey(topic.getId(), key);
        if (existing == null) throw new ServiceException(MSG_NOT_FOUND, HttpStatus.NOT_FOUND)
                .setDetailMessage(ThemeStateKeyService.ERR_NOT_FOUND);
        Set<String> attributes = attributeKeys(topic.getId());
        boolean attribute = attributes.contains(key);
        String name = attribute ? requireLabel(label, ThemeAttributeService.MAX_LABEL, ThemeAttributeService.MSG_NAME_LONG)
                : requireLabel(label, MAX_LABEL, MSG_NAME_LONG);
        String kind = kindOf(existing);
        requireUniqueLabel(themeStateKeyService.listByTopic(topic.getId()), attributes, existing.getKeyType(), attribute,
                name, key, attribute ? ThemeAttributeService.MSG_DUPLICATE : duplicateMessage(kind));
        ThemeStateKey patch = copy(existing);
        patch.setLabel(name);
        themeStateKeyService.updateDefinition(topic.getId(), key, patch);
        existing.setLabel(name);
        return item(existing, kind);
    }

    /** 开局带着(初始状态):列表里的线索 / 标签开局为 1,其余线索 / 标签为 0。返回 {@link #usage}。 */
    @Transactional(rollbackFor = Exception.class)
    public List<Map<String, Object>> setStartWith(CmsTopic topic, Collection<String> keys) {
        Set<String> wanted = keys == null ? Collections.<String>emptySet() : new HashSet<String>(keys);
        themeStateKeyMapper.lockTopic(topic.getId());
        Map<String, ThemeStateKey> binary = new LinkedHashMap<String, ThemeStateKey>();
        for (ThemeStateKey row : themeStateKeyService.listByTopic(topic.getId())) {
            if (KIND_CLUE.equals(kindOf(row)) || KIND_TAG.equals(kindOf(row))) binary.put(row.getStateKey(), row);
        }
        for (String key : wanted) {
            if (!binary.containsKey(key)) throw new ServiceException(MSG_NOT_FOUND, HttpStatus.NOT_FOUND)
                    .setDetailMessage(ThemeStateKeyService.ERR_NOT_FOUND);
        }
        for (ThemeStateKey row : binary.values()) {
            int target = wanted.contains(row.getStateKey()) ? 1 : 0;
            int current = row.getInitialValue() == null ? 0 : row.getInitialValue().intValue();
            if (target == current) continue;
            ThemeStateKey patch = copy(row);
            patch.setInitialValue(Integer.valueOf(target));
            themeStateKeyService.updateDefinition(topic.getId(), row.getStateKey(), patch);
        }
        return usage(topic);
    }

    // ==================== 用在 / 删除 ====================

    /** 线索、标签、数值的列表(不含属性与进度钟),每项带「用在」。 */
    public List<Map<String, Object>> usage(CmsTopic topic) {
        Map<String, Set<String>> positions = positions(topic);
        Set<String> attributes = attributeKeys(topic.getId());
        Set<String> clocks = clockVars(topic);
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (ThemeStateKey row : themeStateKeyService.listByTopic(topic.getId())) {
            String kind = kindOf(row);
            if (kind == null || attributes.contains(row.getStateKey()) || clocks.contains(row.getStateKey())) continue;
            Map<String, Object> item = item(row, kind);
            item.put("sortOrder", row.getSortOrder());
            item.put("startWith", Boolean.valueOf(row.getInitialValue() != null && row.getInitialValue().intValue() == 1
                    && !KIND_COUNTER.equals(kind)));
            Set<String> used = positions.get(row.getStateKey());
            item.put("usedIn", used == null ? new ArrayList<String>() : new ArrayList<String>(used));
            items.add(item);
        }
        return items;
    }

    /** 某个键被用到的位置;空 = 可以删。 */
    public List<String> usedIn(CmsTopic topic, String key) {
        Set<String> used = positions(topic).get(key);
        return used == null ? new ArrayList<String>() : new ArrayList<String>(used);
    }

    /**
     * 删除:被用到时抛 409「不能删除「名字」」,data.usedIn 列出位置;不存在抛 404。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(CmsTopic topic, String key) {
        themeStateKeyMapper.lockTopic(topic.getId());
        ThemeStateKey existing = key == null ? null : themeStateKeyMapper.selectByTopicIdAndStateKey(topic.getId(), key);
        if (existing == null) throw new ServiceException(MSG_NOT_FOUND, HttpStatus.NOT_FOUND)
                .setDetailMessage(ThemeStateKeyService.ERR_NOT_FOUND);
        requireUnused(topic, existing);
        themeStateKeyService.delete(topic.getId(), key);
    }

    void requireUnused(CmsTopic topic, ThemeStateKey existing) {
        List<String> used = usedIn(topic, existing.getStateKey());
        if (!used.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("usedIn", used);
            throw new ServiceException("不能删除「" + existing.displayLabel() + "」", HttpStatus.CONFLICT)
                    .setDetailMessage(ERR_IN_USE).setData(data);
        }
    }

    private Map<String, Set<String>> positions(CmsTopic topic) {
        TopicStateKeyRefService.RefCollection refs = topicStateKeyRefService.collect(topic);
        if (!refs.getParseErrors().isEmpty()) throw new ServiceException(MSG_UNREADABLE);
        Map<String, Set<String>> positions = refs.positionsByKey();
        for (ThemeStateKey row : themeStateKeyService.listByTopic(topic.getId())) {
            String kind = kindOf(row);
            if ((KIND_CLUE.equals(kind) || KIND_TAG.equals(kind))
                    && row.getInitialValue() != null && row.getInitialValue().intValue() == 1) {
                add(positions, row.getStateKey(), POSITION_INITIAL);
            }
        }
        JsonNode rules = rules(topic);
        for (JsonNode row : rules.path("attributes")) {
            String key = row.path("key").asText();
            if ((key.startsWith("clue.") || key.startsWith("tag.")) && row.path("initial").asInt() == 1) {
                add(positions, key, POSITION_INITIAL);
            }
        }
        for (String var : clockVars(topic)) add(positions, var, POSITION_CLOCK);
        return positions;
    }

    // ==================== 小工具 ====================

    Set<String> attributeKeys(Long topicId) {
        Set<String> keys = new LinkedHashSet<String>();
        List<ThemeAttributeMark> marks = themeAttributeMapper.selectAttributeMarks(topicId);
        if (marks != null) for (ThemeAttributeMark mark : marks) keys.add(mark.getStateKey());
        return keys;
    }

    static Set<String> clockVars(CmsTopic topic) {
        Set<String> vars = new LinkedHashSet<String>();
        for (JsonNode clock : rules(topic).path("rpg").path("clocks")) {
            String var = clock.path("var").asText("");
            if (!var.isEmpty()) vars.add(var);
        }
        return vars;
    }

    /** 主题草稿上的 journeyRules 原文;读不懂按 fail-closed 拒。 */
    static JsonNode rules(CmsTopic topic) {
        String raw = topic == null ? null : topic.getJourneyRulesJson();
        if (raw == null || raw.trim().isEmpty()) return JSON.createObjectNode();
        try {
            JsonNode node = JSON.readTree(raw);
            return node == null ? JSON.createObjectNode() : node;
        } catch (Exception unreadable) {
            throw new ServiceException(MSG_UNREADABLE);
        }
    }

    static String kindOf(ThemeStateKey row) {
        if (row == null || row.getKeyType() == null) return null;
        if (ThemeStateKey.TYPE_FLAG.equals(row.getKeyType())) return KIND_CLUE;
        if (ThemeStateKey.TYPE_TAG.equals(row.getKeyType())) return KIND_TAG;
        if (ThemeStateKey.TYPE_COUNTER.equals(row.getKeyType())) return KIND_COUNTER;
        return null;
    }

    private static String keyType(String kind) {
        if (KIND_CLUE.equals(kind)) return ThemeStateKey.TYPE_FLAG;
        if (KIND_TAG.equals(kind)) return ThemeStateKey.TYPE_TAG;
        if (KIND_COUNTER.equals(kind)) return ThemeStateKey.TYPE_COUNTER;
        throw invalid(MSG_KIND);
    }

    static String duplicateMessage(String kind) {
        if (KIND_CLUE.equals(kind)) return "已有同名的线索。";
        if (KIND_TAG.equals(kind)) return "已有同名的标签。";
        return "已有同名的数值。";
    }

    /** clue.k{n} / tag.k{n} / counter.v{n},n = 本主题同前缀现有最大编号 + 1。 */
    static String nextKey(String kind, List<ThemeStateKey> rows) {
        String prefix = KIND_COUNTER.equals(kind) ? "counter.v" : kind + ".k";
        long max = 0L;
        for (ThemeStateKey row : rows) {
            String key = row.getStateKey();
            if (key == null || !key.startsWith(prefix)) continue;
            Matcher matcher = GENERATED.matcher(key);
            if (!matcher.matches()) continue;
            String digits = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            max = Math.max(max, Long.parseLong(digits));
        }
        return prefix + (max + 1L);
    }

    /**
     * 同名判定:去首尾空白后完全相同。属性只和属性比;线索 / 标签 / 数值各自只和同种类的非属性项比。
     */
    static void requireUniqueLabel(List<ThemeStateKey> rows, Set<String> attributes, String keyType, boolean attribute,
                                   String label, String self, String message) {
        for (ThemeStateKey row : rows) {
            if (row.getStateKey() == null || row.getStateKey().equals(self)) continue;
            boolean rowAttribute = attributes.contains(row.getStateKey());
            if (attribute != rowAttribute) continue;
            if (!attribute && !keyType.equals(row.getKeyType())) continue;
            if (row.getLabel() != null && row.getLabel().trim().equals(label)) {
                throw invalid(message);
            }
        }
    }

    static String requireLabel(String label, int max, String tooLong) {
        String name = label == null ? "" : label.trim();
        if (name.isEmpty()) throw invalid(MSG_NAME_EMPTY);
        if (name.codePointCount(0, name.length()) > max) throw invalid(tooLong);
        return name;
    }

    static ThemeStateKey copy(ThemeStateKey source) {
        ThemeStateKey patch = new ThemeStateKey();
        patch.setStateKey(source.getStateKey());
        patch.setKeyType(source.getKeyType());
        patch.setScope(source.getScope());
        patch.setLabel(source.getLabel());
        patch.setInitialValue(source.getInitialValue());
        patch.setMinValue(source.getMinValue());
        patch.setMaxValue(source.getMaxValue());
        patch.setDisplayType(source.getDisplayType());
        patch.setColor(source.getColor());
        patch.setIcon(source.getIcon());
        patch.setSortOrder(source.getSortOrder());
        patch.setVisible(source.getVisible());
        patch.setPersistScope(source.getPersistScope());
        patch.setRulesJson(source.getRulesJson());
        return patch;
    }

    private static Map<String, Object> item(ThemeStateKey row, String kind) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("key", row.getStateKey());
        item.put("kind", kind);
        item.put("label", row.displayLabel());
        return item;
    }

    private static void add(Map<String, Set<String>> positions, String key, String position) {
        Set<String> values = positions.get(key);
        if (values == null) {
            values = new LinkedHashSet<String>();
            positions.put(key, values);
        }
        values.add(position);
    }

    static ServiceException invalid(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST).setDetailMessage(ERR_INVALID);
    }
}
