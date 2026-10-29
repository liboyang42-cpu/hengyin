package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.ThemeAttributeMark;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.mapper.ThemeAttributeMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文档二 L8 d20 属性(原型 A e-d20;A1 / A2 / A3 / X4)。
 *
 * <p>属性只在「d20 与角色设定」里建,存进状态键注册表(C-1 唯一存处),is_attribute=1 单独标记(A2);
 * 显示方式、颜色、图标、可见、保留范围跟着类型预设(A3,作者看不到);每个主题最多 6 项。
 * A1:老主题 journeyRules.attributes 里的 counter.*(编辑器原「自定义整数值」,进度钟的键除外)
 * 由迁移一次性登记,迁移后才出现的同类行在作者第一次读取时补登记(同一套规则,幂等)。
 *
 * <p>发布冻结不另造:读接口返回 {@code binding} / {@code projection},前端保存主题时原样放进
 * journeyRules.attributeBinding / attributes,走 #625 已有的 registry → journeyRules 冻结。
 */
@Service
public class ThemeAttributeService {

    public static final int MAX_ATTRIBUTES = 6;
    public static final int MAX_LABEL = ThemeKeyNamingService.MAX_ATTRIBUTE_LABEL;
    public static final int RANGE_MIN = 0;
    public static final int RANGE_MAX = 20;
    public static final String PRESET_CUSTOM = "custom";

    static final String MSG_FULL = "最多 6 项属性。";
    static final String MSG_DUPLICATE = ThemeKeyNamingService.MSG_ATTRIBUTE_DUPLICATE;
    static final String MSG_NAME_LONG = ThemeKeyNamingService.MSG_ATTRIBUTE_NAME_LONG;
    static final String MSG_PRESET = "请选择属性。";
    static final String MSG_MIN_MAX = "最小值要小于最大值。";
    static final String MSG_INITIAL = "开局值要在最小值和最大值之间。";
    static final String MSG_RANGE = "范围只能在 0 到 20 之间。";
    static final String MSG_ORDER = "属性已变化，请重新读取。";
    static final String MSG_NOT_FOUND = "属性不存在。";

    /** A3 类型预设:名称、范围 / 开局、显示方式、色值、图标键。顺序即药丸顺序。 */
    static final Map<String, Preset> PRESETS;

    static {
        Map<String, Preset> presets = new LinkedHashMap<String, Preset>();
        presets.put("observe", new Preset("observe", "观察", 0, 5, 2, ThemeStateKey.DISPLAY_STARS, "#5B8DEF"));
        presets.put("nerve", new Preset("nerve", "胆量", 0, 10, 6, ThemeStateKey.DISPLAY_BAR, "#E5484D"));
        presets.put("body", new Preset("body", "体魄", 0, 8, 4, ThemeStateKey.DISPLAY_BAR, "#30A46C"));
        presets.put("insight", new Preset("insight", "灵感", 0, 5, 1, ThemeStateKey.DISPLAY_NUMBER, "#8E4EC6"));
        presets.put("agility", new Preset("agility", "敏捷", 0, 5, 2, ThemeStateKey.DISPLAY_NUMBER, "#F76B15"));
        presets.put("charm", new Preset("charm", "魅力", 0, 5, 2, ThemeStateKey.DISPLAY_STARS, "#D6409F"));
        presets.put("fortune", new Preset("fortune", "运气", 0, 3, 1, ThemeStateKey.DISPLAY_STARS, "#FFB224"));
        presets.put(PRESET_CUSTOM, new Preset(PRESET_CUSTOM, null, 0, 5, 0, ThemeStateKey.DISPLAY_NUMBER, "#8B8D98"));
        PRESETS = Collections.unmodifiableMap(presets);
    }

    private final ThemeStateKeyMapper themeStateKeyMapper;
    private final ThemeAttributeMapper themeAttributeMapper;
    private final ThemeStateKeyService themeStateKeyService;
    private final ThemeKeyNamingService themeKeyNamingService;

    public ThemeAttributeService(ThemeStateKeyMapper themeStateKeyMapper, ThemeAttributeMapper themeAttributeMapper,
                                 ThemeStateKeyService themeStateKeyService, ThemeKeyNamingService themeKeyNamingService) {
        this.themeStateKeyMapper = themeStateKeyMapper;
        this.themeAttributeMapper = themeAttributeMapper;
        this.themeStateKeyService = themeStateKeyService;
        this.themeKeyNamingService = themeKeyNamingService;
    }

    // ==================== 读 ====================

    /**
     * 作者读。{@code adopt=true}(主题可编辑)时先做 A1 补登记;冻结的主题只读、不写库。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> view(CmsTopic topic, boolean adopt) {
        if (adopt) adoptLegacy(topic);
        List<ThemeStateKey> rows = themeStateKeyService.listByTopic(topic.getId());
        Map<String, String> marks = marks(topic.getId());
        List<ThemeStateKey> attributes = attributes(rows, marks);
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("difficulty", JourneyDifficulty.resolve(parseRules(topic)));
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        Set<String> usedPresets = new HashSet<String>();
        for (ThemeStateKey row : attributes) {
            list.add(row(row, marks.get(row.getStateKey())));
            usedPresets.add(marks.get(row.getStateKey()));
        }
        view.put("attributes", list);
        List<String> left = new ArrayList<String>();
        if (attributes.size() < MAX_ATTRIBUTES) {
            for (String preset : PRESETS.keySet()) {
                if (!PRESET_CUSTOM.equals(preset) && !usedPresets.contains(preset)) left.add(preset);
            }
        }
        view.put("presetsLeft", left);
        view.put("max", Integer.valueOf(MAX_ATTRIBUTES));
        List<ThemeStateKey> enabled = enabledRows(topic, rows, attributes);
        List<String> keys = new ArrayList<String>();
        List<Map<String, Object>> projection = new ArrayList<Map<String, Object>>();
        for (ThemeStateKey row : enabled) {
            keys.add(row.getStateKey());
            projection.add(projection(row));
        }
        view.put("binding", themeStateKeyService.readBinding(topic.getId(), keys));
        view.put("projection", projection);
        return view;
    }

    /**
     * 保存主题时 journeyRules.attributes 必须正好是注册表里这些键(#625 冻结口径):
     * 属性(按排序)→ 数值(非属性、非进度钟的 counter)→ 开局带着的线索 / 标签(初值 1)。
     */
    static List<ThemeStateKey> enabledRows(CmsTopic topic, List<ThemeStateKey> rows, List<ThemeStateKey> attributes) {
        Set<String> attributeKeys = new HashSet<String>();
        for (ThemeStateKey row : attributes) attributeKeys.add(row.getStateKey());
        Set<String> clocks = ThemeKeyNamingService.clockVars(topic);
        List<ThemeStateKey> enabled = new ArrayList<ThemeStateKey>(attributes);
        for (ThemeStateKey row : rows) {
            if (attributeKeys.contains(row.getStateKey())) continue;
            String kind = ThemeKeyNamingService.kindOf(row);
            if (ThemeKeyNamingService.KIND_COUNTER.equals(kind) && !clocks.contains(row.getStateKey())) enabled.add(row);
        }
        for (ThemeStateKey row : rows) {
            String kind = ThemeKeyNamingService.kindOf(row);
            if ((ThemeKeyNamingService.KIND_CLUE.equals(kind) || ThemeKeyNamingService.KIND_TAG.equals(kind))
                    && row.getInitialValue() != null && row.getInitialValue().intValue() == 1) {
                enabled.add(row);
            }
        }
        if (enabled.size() > 32) throw new ServiceException("开局状态最多 32 项", HttpStatus.BAD_REQUEST)
                .setDetailMessage(ThemeStateKeyService.ERR_INVALID);
        return enabled;
    }

    // ==================== 写 ====================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> add(CmsTopic topic, String presetName, String label) {
        Preset preset = presetName == null ? null : PRESETS.get(presetName);
        if (preset == null) throw ThemeKeyNamingService.invalid(MSG_PRESET);
        String name = PRESET_CUSTOM.equals(preset.id)
                ? ThemeKeyNamingService.requireLabel(label, MAX_LABEL, MSG_NAME_LONG) : preset.label;
        themeStateKeyMapper.lockTopic(topic.getId());
        List<ThemeStateKey> rows = themeStateKeyService.listByTopic(topic.getId());
        Map<String, String> marks = marks(topic.getId());
        List<ThemeStateKey> attributes = attributes(rows, marks);
        if (attributes.size() >= MAX_ATTRIBUTES) throw ThemeKeyNamingService.invalid(MSG_FULL);
        for (ThemeStateKey row : attributes) {
            if (row.getLabel() != null && row.getLabel().trim().equals(name)) throw ThemeKeyNamingService.invalid(MSG_DUPLICATE);
        }
        ThemeStateKey row = new ThemeStateKey();
        row.setStateKey(nextKey(preset.id, rows));
        row.setKeyType(ThemeStateKey.TYPE_COUNTER);
        row.setScope(ThemeStateKey.SCOPE_RUN);
        row.setLabel(name);
        row.setMinValue(Integer.valueOf(preset.min));
        row.setMaxValue(Integer.valueOf(preset.max));
        row.setInitialValue(Integer.valueOf(preset.initial));
        row.setDisplayType(preset.displayType);
        row.setColor(preset.color);
        row.setIcon(preset.icon());
        row.setVisible(Boolean.TRUE);
        row.setPersistScope(ThemeStateKey.PERSIST_RUN);
        int order = 0;
        for (ThemeStateKey existing : attributes) {
            if (existing.getSortOrder() != null) order = Math.max(order, existing.getSortOrder().intValue());
        }
        row.setSortOrder(Integer.valueOf(order + 1));
        themeStateKeyService.register(topic.getId(), Collections.singletonList(row));
        themeAttributeMapper.markAttribute(topic.getId(), row.getStateKey(), preset.id);
        return row(row, preset.id);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateRange(CmsTopic topic, String key, Integer min, Integer max, Integer initial) {
        ThemeStateKey existing = requireAttribute(topic, key);
        if (min == null || max == null || initial == null) throw ThemeKeyNamingService.invalid(MSG_RANGE);
        validateRange(min.intValue(), max.intValue(), initial.intValue());
        ThemeStateKey patch = ThemeKeyNamingService.copy(existing);
        patch.setMinValue(min);
        patch.setMaxValue(max);
        patch.setInitialValue(initial);
        ThemeStateKey saved = themeStateKeyService.updateDefinition(topic.getId(), key, patch);
        return row(saved, marks(topic.getId()).get(key));
    }

    static void validateRange(int min, int max, int initial) {
        if (min < RANGE_MIN || max > RANGE_MAX || min > RANGE_MAX || max < RANGE_MIN) throw ThemeKeyNamingService.invalid(MSG_RANGE);
        if (min >= max) throw ThemeKeyNamingService.invalid(MSG_MIN_MAX);
        if (initial < min || initial > max) throw ThemeKeyNamingService.invalid(MSG_INITIAL);
    }

    /** keys 必须正好是当前全部属性;按给定顺序写 sortOrder 1..n。 */
    @Transactional(rollbackFor = Exception.class)
    public List<Map<String, Object>> reorder(CmsTopic topic, List<String> keys) {
        themeStateKeyMapper.lockTopic(topic.getId());
        List<ThemeStateKey> rows = themeStateKeyService.listByTopic(topic.getId());
        Map<String, String> marks = marks(topic.getId());
        Map<String, ThemeStateKey> current = new LinkedHashMap<String, ThemeStateKey>();
        for (ThemeStateKey row : attributes(rows, marks)) current.put(row.getStateKey(), row);
        if (keys == null || keys.size() != current.size() || !current.keySet().equals(new HashSet<String>(keys))) {
            throw new ServiceException(MSG_ORDER, HttpStatus.CONFLICT).setDetailMessage("ATTRIBUTE_ORDER_CONFLICT");
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        int order = 1;
        for (String key : keys) {
            ThemeStateKey row = current.get(key);
            if (row.getSortOrder() == null || row.getSortOrder().intValue() != order) {
                ThemeStateKey patch = ThemeKeyNamingService.copy(row);
                patch.setSortOrder(Integer.valueOf(order));
                row = themeStateKeyService.updateDefinition(topic.getId(), key, patch);
            }
            result.add(row(row, marks.get(key)));
            order++;
        }
        return result;
    }

    /** 被用到时 409「不能删除「观察」」+ data.usedIn;不存在 404。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(CmsTopic topic, String key) {
        ThemeStateKey existing = requireAttribute(topic, key);
        themeKeyNamingService.requireUnused(topic, existing);
        themeStateKeyService.delete(topic.getId(), key);
    }

    // ==================== A1 补登记 ====================

    /**
     * journeyRules.attributes 里的 counter.*(进度钟的键除外)未登记或未标成属性的,登记并标成属性。
     * 已登记的只补标记,不改它的定义;标签非法(空)或范围不合注册表口径的行跳过,原样留给作者。
     */
    void adoptLegacy(CmsTopic topic) {
        JsonNode rules = ThemeKeyNamingService.rules(topic);
        if (!rules.path("attributes").isArray() || rules.path("attributes").isEmpty()) return;
        Set<String> clocks = ThemeKeyNamingService.clockVars(topic);
        Map<String, String> marks = null;
        Map<String, ThemeStateKey> registry = null;
        for (JsonNode attribute : rules.path("attributes")) {
            String key = attribute.path("key").asText("");
            String label = attribute.path("label").asText("").trim();
            if (!key.startsWith("counter.") || !ThemeStateKey.KEY_NAME.matcher(key).matches()
                    || clocks.contains(key) || label.isEmpty()) continue;
            if (marks == null) {
                themeStateKeyMapper.lockTopic(topic.getId());
                marks = marks(topic.getId());
                registry = new LinkedHashMap<String, ThemeStateKey>();
                for (ThemeStateKey row : themeStateKeyService.listByTopic(topic.getId())) registry.put(row.getStateKey(), row);
            }
            if (marks.containsKey(key)) continue;
            if (!registry.containsKey(key)) {
                ThemeStateKey row = new ThemeStateKey();
                row.setStateKey(key);
                row.setKeyType(ThemeStateKey.TYPE_COUNTER);
                row.setScope(ThemeStateKey.SCOPE_RUN);
                row.setLabel(label.length() > 128 ? label.substring(0, 128) : label);
                row.setInitialValue(Integer.valueOf(attribute.path("initial").asInt(0)));
                if (attribute.path("min").isIntegralNumber()) row.setMinValue(Integer.valueOf(attribute.path("min").asInt()));
                if (attribute.path("max").isIntegralNumber()) row.setMaxValue(Integer.valueOf(attribute.path("max").asInt()));
                row.setDisplayType(ThemeStateKey.DISPLAY_NUMBER);
                row.setVisible(Boolean.valueOf(attribute.path("visible").asBoolean(true)));
                row.setPersistScope(ThemeStateKey.PERSIST_RUN);
                if (!themeStateKeyService.check(Collections.singletonList(row)).isEmpty()) continue;
                themeStateKeyService.register(topic.getId(), Collections.singletonList(row));
                registry.put(key, row);
            }
            themeAttributeMapper.markAttribute(topic.getId(), key, PRESET_CUSTOM);
            marks.put(key, PRESET_CUSTOM);
        }
    }

    // ==================== 小工具 ====================

    private ThemeStateKey requireAttribute(CmsTopic topic, String key) {
        themeStateKeyMapper.lockTopic(topic.getId());
        if (key == null || !marks(topic.getId()).containsKey(key)) {
            throw new ServiceException(MSG_NOT_FOUND, HttpStatus.NOT_FOUND).setDetailMessage(ThemeStateKeyService.ERR_NOT_FOUND);
        }
        ThemeStateKey row = themeStateKeyMapper.selectByTopicIdAndStateKey(topic.getId(), key);
        if (row == null) {
            throw new ServiceException(MSG_NOT_FOUND, HttpStatus.NOT_FOUND).setDetailMessage(ThemeStateKeyService.ERR_NOT_FOUND);
        }
        return row;
    }

    private Map<String, String> marks(Long topicId) {
        Map<String, String> marks = new LinkedHashMap<String, String>();
        List<ThemeAttributeMark> rows = themeAttributeMapper.selectAttributeMarks(topicId);
        if (rows != null) for (ThemeAttributeMark mark : rows) {
            marks.put(mark.getStateKey(), mark.getPreset() == null ? PRESET_CUSTOM : mark.getPreset());
        }
        return marks;
    }

    /** 标成属性的行,按 sortOrder 升序(空排最后),同序按 id。 */
    static List<ThemeStateKey> attributes(List<ThemeStateKey> rows, Map<String, String> marks) {
        List<ThemeStateKey> attributes = new ArrayList<ThemeStateKey>();
        for (ThemeStateKey row : rows) if (marks.containsKey(row.getStateKey())) attributes.add(row);
        attributes.sort(Comparator.comparing((ThemeStateKey row) -> row.getSortOrder() == null
                ? Integer.MAX_VALUE : row.getSortOrder().intValue())
                .thenComparing(row -> row.getId() == null ? Long.MAX_VALUE : row.getId().longValue()));
        return attributes;
    }

    /** 预设 = counter.<preset>,被占用时 _2、_3…;自定义 = counter.attr_1、attr_2…。 */
    static String nextKey(String preset, List<ThemeStateKey> rows) {
        Set<String> taken = new HashSet<String>();
        for (ThemeStateKey row : rows) taken.add(row.getStateKey());
        if (!PRESET_CUSTOM.equals(preset)) {
            String base = "counter." + preset;
            if (!taken.contains(base)) return base;
            for (int i = 2; ; i++) if (!taken.contains(base + "_" + i)) return base + "_" + i;
        }
        for (int i = 1; ; i++) if (!taken.contains("counter.attr_" + i)) return "counter.attr_" + i;
    }

    private static JourneyRules parseRules(CmsTopic topic) {
        try {
            return JourneyRules.parse(topic.getJourneyRulesJson());
        } catch (IllegalArgumentException unreadable) {
            return null;
        }
    }

    static Map<String, Object> row(ThemeStateKey row, String preset) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("key", row.getStateKey());
        view.put("label", row.displayLabel());
        view.put("preset", preset == null ? PRESET_CUSTOM : preset);
        view.put("min", Integer.valueOf(row.effectiveMin()));
        view.put("max", Integer.valueOf(row.effectiveMax()));
        view.put("initial", Integer.valueOf(row.getInitialValue() == null ? 0 : row.getInitialValue().intValue()));
        view.put("displayType", row.getDisplayType() == null ? ThemeStateKey.DISPLAY_NUMBER : row.getDisplayType());
        view.put("color", row.getColor());
        view.put("icon", row.getIcon());
        view.put("sortOrder", row.getSortOrder());
        return view;
    }

    /** journeyRules.attributes 的一行:与 ThemeStateKeyService.freezeDefinition 的比对口径一致。 */
    static Map<String, Object> projection(ThemeStateKey row) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("key", row.getStateKey());
        view.put("label", row.displayLabel());
        view.put("initial", Integer.valueOf(row.getInitialValue() == null ? 0 : row.getInitialValue().intValue()));
        view.put("min", Integer.valueOf(row.effectiveMin()));
        view.put("max", Integer.valueOf(row.effectiveMax()));
        view.put("visible", Boolean.valueOf(row.shownOnCard()));
        return view;
    }

    static final class Preset {
        final String id;
        final String label;
        final int min;
        final int max;
        final int initial;
        final String displayType;
        final String color;

        Preset(String id, String label, int min, int max, int initial, String displayType, String color) {
            this.id = id;
            this.label = label;
            this.min = min;
            this.max = max;
            this.initial = initial;
            this.displayType = displayType;
            this.color = color;
        }

        String icon() {
            return "attr-" + id;
        }
    }
}
