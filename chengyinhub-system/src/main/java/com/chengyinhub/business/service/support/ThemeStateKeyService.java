package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 主题具名状态键注册(B1 旅程体验模组化)。
 *
 * <p>键名 / 类型 / 作用域口径见 {@link ThemeStateKey};同 topic 同 state_key 重复注册 =
 * 更新 label/initial_value,不重复插(唯一键 + upsert 兜底)。
 */
@Service
public class ThemeStateKeyService {

    private static final Set<String> KEY_TYPES = new HashSet<String>(Arrays.asList(
            ThemeStateKey.TYPE_FLAG, ThemeStateKey.TYPE_COUNTER,
            ThemeStateKey.TYPE_RELATION, ThemeStateKey.TYPE_TAG));

    /**
     * 运行期只按 run 读写;party/deployment 只有常量没有实现,收下来作者会以为状态跨队/跨场共享,
     * 实际每局各算各的。实现之前在注册口拒掉,不静默降级成 run。
     */
    private static final Set<String> SCOPES = new HashSet<String>(Arrays.asList(ThemeStateKey.SCOPE_RUN));

    /** 命名空间 → key_type 一一对应:前缀就是类型,不允许交叉(否则运行期按前缀读写会与声明对不上)。 */
    private static final Map<String, String> PREFIX_KEY_TYPES;

    static {
        Map<String, String> prefixTypes = new LinkedHashMap<String, String>();
        prefixTypes.put("clue.", ThemeStateKey.TYPE_FLAG);
        prefixTypes.put("counter.", ThemeStateKey.TYPE_COUNTER);
        prefixTypes.put("relation.", ThemeStateKey.TYPE_RELATION);
        prefixTypes.put("tag.", ThemeStateKey.TYPE_TAG);
        PREFIX_KEY_TYPES = Collections.unmodifiableMap(prefixTypes);
    }

    /** F2 T01 错误码:随 AjaxResult 的 errorCode 字段下发,客户端按码提示,不解析文案。 */
    public static final String ERR_INVALID = "STATE_KEY_INVALID";
    public static final String ERR_RANGE = "STATE_KEY_RANGE";
    public static final String ERR_NOT_FOUND = "STATE_KEY_NOT_FOUND";
    public static final String ERR_RENAME = "STATE_KEY_RENAME_FORBIDDEN";

    /** 属性范围绝对边界:与 JourneyInitialState 的属性上下限同一档,不另起口径。 */
    private static final int ABS_MIN = -10000;
    private static final int ABS_MAX = 10000;
    private static final int MAX_LABEL = 128;
    private static final int MAX_RULES_JSON = 1024;
    private static final int MAX_LEVELS = 10;
    private static final int MAX_LEVEL_LABEL = 12;
    private static final Set<String> DISPLAY_TYPES = new HashSet<String>(Arrays.asList(
            ThemeStateKey.DISPLAY_NUMBER, ThemeStateKey.DISPLAY_BAR, ThemeStateKey.DISPLAY_STARS));
    private static final Set<String> PERSIST_SCOPES = new HashSet<String>(Arrays.asList(
            ThemeStateKey.PERSIST_RUN, ThemeStateKey.PERSIST_TOPIC));
    private static final Pattern COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    /** 图标是客户端内置图标键,不是 URL:不走媒体审核,也不许借这里塞外链。 */
    private static final Pattern ICON = Pattern.compile("^[a-z0-9][a-z0-9-]{0,31}$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ThemeStateKeyMapper themeStateKeyMapper;

    public ThemeStateKeyService(ThemeStateKeyMapper themeStateKeyMapper) {
        this.themeStateKeyMapper = themeStateKeyMapper;
    }

    /** 逐条校验后写入;任一条非法则整批不写。 */
    @Transactional(rollbackFor = Exception.class)
    public void register(Long topicId, List<ThemeStateKey> keys) {
        if (topicId == null || topicId <= 0L) {
            throw new ServiceException("主题编号无效,无法注册状态键");
        }
        if (keys == null || keys.isEmpty()) {
            return;
        }
        for (ThemeStateKey key : keys) {
            validate(key);
            validateDefinition(key, false);
        }
        Date now = DateUtils.getNowDate();
        for (ThemeStateKey key : keys) {
            key.setTopicId(topicId);
            if (key.getInitialValue() == null) {
                key.setInitialValue(Integer.valueOf(0));
            }
            key.setCreateTime(now);
            key.setUpdateTime(now);
            themeStateKeyMapper.upsertThemeStateKey(key);
        }
    }

    /**
     * F2 T01:按键名整体覆盖一个已注册键的定义(label / 初值 / 范围 / 展示 / 持久化 / 规则)。
     *
     * <p>键名取自路径,body 里带了不同的 stateKey 直接拒 —— 改 label 永远不改键名,
     * 运行期状态、玩法配置引用、跨局台账都按键名对账。类型与作用域同样不可经此修改。
     * 同一份 body 重放结果相同(整体覆盖,天然幂等)。
     */
    @Transactional(rollbackFor = Exception.class)
    public ThemeStateKey updateDefinition(Long topicId, String stateKey, ThemeStateKey patch) {
        if (topicId == null || topicId <= 0L) {
            throw invalid(ERR_INVALID, "主题编号无效,无法修改状态键");
        }
        if (patch == null) {
            throw invalid(ERR_INVALID, "状态键定义不能为空");
        }
        if (patch.getStateKey() != null && !patch.getStateKey().equals(stateKey)) {
            throw invalid(ERR_RENAME, "状态键名不可修改:" + stateKey + "(改展示名请只改 label)");
        }
        ThemeStateKey existing = StringUtils.isEmpty(stateKey) ? null
                : themeStateKeyMapper.selectByTopicIdAndStateKey(topicId, stateKey);
        if (existing == null) {
            throw new ServiceException("状态键不存在:" + stateKey, HttpStatus.NOT_FOUND).setDetailMessage(ERR_NOT_FOUND);
        }
        if ((patch.getKeyType() != null && !patch.getKeyType().equals(existing.getKeyType()))
                || (patch.getScope() != null && !patch.getScope().equals(existing.getScope()))) {
            throw invalid(ERR_INVALID, "状态键的类型与作用域不可修改:" + stateKey);
        }
        ThemeStateKey next = copyDefinition(patch);
        next.setTopicId(topicId);
        next.setStateKey(existing.getStateKey());
        next.setKeyType(existing.getKeyType());
        next.setScope(existing.getScope());
        if (next.getInitialValue() == null) next.setInitialValue(Integer.valueOf(0));
        validateDefinition(next, true);
        next.setUpdateTime(DateUtils.getNowDate());
        themeStateKeyMapper.updateDefinition(next);
        next.setId(existing.getId());
        next.setCreateTime(existing.getCreateTime());
        return next;
    }

    /**
     * F2 T01 试校验:按 {@link #updateDefinition} 的严格口径逐条检查,不写库。
     * 返回问题列表(空 = 全部通过),每条带键名,作者一次看全。
     */
    public List<String> check(List<ThemeStateKey> keys) {
        List<String> problems = new ArrayList<String>();
        if (keys == null) return problems;
        Set<String> seen = new HashSet<String>();
        for (ThemeStateKey key : keys) {
            try {
                validate(key);
                if (!seen.add(key.getStateKey())) {
                    throw invalid(ERR_INVALID, "状态键重复:" + key.getStateKey());
                }
                ThemeStateKey probe = copyDefinition(key);
                probe.setStateKey(key.getStateKey());
                probe.setKeyType(key.getKeyType());
                if (probe.getInitialValue() == null) probe.setInitialValue(Integer.valueOf(0));
                validateDefinition(probe, true);
            } catch (ServiceException e) {
                problems.add(e.getMessage());
            }
        }
        return problems;
    }

    /** 某主题已注册的全部状态键;没注册过返回空列表。 */
    public List<ThemeStateKey> listByTopic(Long topicId) {
        if (topicId == null || topicId <= 0L) {
            return new ArrayList<ThemeStateKey>();
        }
        List<ThemeStateKey> rows = themeStateKeyMapper.selectByTopicId(topicId);
        return rows == null ? new ArrayList<ThemeStateKey>() : rows;
    }

    /**
     * 删除某主题的一个键;该键本来就不在时返回 false(不报错)。
     *
     * <p>「是否被引用」的闸不在这里:引用收集在 {@link TopicStateKeyRefService},由调用方
     * (创作者接口)先查引用位置再删 —— 这不是本表的仓储语义,不该塞进注册服务。
     */
    public boolean delete(Long topicId, String stateKey) {
        if (topicId == null || topicId <= 0L) {
            throw new ServiceException("主题编号无效,无法删除状态键");
        }
        if (StringUtils.isEmpty(stateKey)) {
            throw new ServiceException("状态键名不能为空");
        }
        return themeStateKeyMapper.deleteByTopicIdAndStateKey(topicId, stateKey) > 0;
    }

    /**
     * 扩展列校验。{@code strict=true}(定义接口 / 试校验):初值必须落在生效范围内(缺省 0..100);
     * {@code strict=false}(老注册路径):只在本次显式带了范围时才校验初值 —— 老调用方
     * (发布期同步 journeyRules.attributes、选项标签自动注册)从没有范围概念,不能因此被拦。
     */
    private void validateDefinition(ThemeStateKey key, boolean strict) {
        String name = key.getStateKey();
        if (key.getLabel() != null && key.getLabel().length() > MAX_LABEL) {
            throw invalid(ERR_INVALID, "状态键展示名不能超过 " + MAX_LABEL + " 字:" + name);
        }
        Integer min = key.getMinValue();
        Integer max = key.getMaxValue();
        boolean binary = ThemeStateKey.TYPE_FLAG.equals(key.getKeyType()) || ThemeStateKey.TYPE_TAG.equals(key.getKeyType());
        if (binary && ((min != null && min.intValue() != 0) || (max != null && max.intValue() != 1))) {
            throw invalid(ERR_RANGE, "flag/tag 状态键的范围固定为 0..1:" + name);
        }
        if ((min != null && (min < ABS_MIN || min > ABS_MAX)) || (max != null && (max < ABS_MIN || max > ABS_MAX))) {
            throw invalid(ERR_RANGE, "状态键范围须在 " + ABS_MIN + " 至 " + ABS_MAX + " 之间:" + name);
        }
        if (key.effectiveMin() > key.effectiveMax()) {
            throw invalid(ERR_RANGE, "状态键下限不能大于上限:" + name);
        }
        boolean checkInitial = strict || min != null || max != null;
        Integer initial = key.getInitialValue();
        if (checkInitial && !binary && initial != null
                && (initial < key.effectiveMin() || initial > key.effectiveMax())) {
            throw invalid(ERR_RANGE, "状态键初值 " + initial + " 超出范围 " + key.effectiveMin() + ".."
                    + key.effectiveMax() + ":" + name);
        }
        if (key.getDisplayType() != null && !DISPLAY_TYPES.contains(key.getDisplayType())) {
            throw invalid(ERR_INVALID, "状态键展示方式只能是 number/bar/stars:" + name);
        }
        if (key.getColor() != null && !COLOR.matcher(key.getColor()).matches()) {
            throw invalid(ERR_INVALID, "状态键颜色须为 #RRGGBB:" + name);
        }
        if (key.getIcon() != null && !ICON.matcher(key.getIcon()).matches()) {
            throw invalid(ERR_INVALID, "状态键图标须为内置图标键(小写字母、数字、短横线,不超过 32 位):" + name);
        }
        if (key.getPersistScope() != null && !PERSIST_SCOPES.contains(key.getPersistScope())) {
            throw invalid(ERR_INVALID, "状态键持久化作用域只能是 run/topic:" + name);
        }
        if (key.persistsAcrossRuns() && !ThemeStateKey.TYPE_COUNTER.equals(key.getKeyType())) {
            throw invalid(ERR_INVALID, "只有 counter 属性可以跨局保留:" + name);
        }
        if (key.getRulesJson() != null) {
            key.setRulesJson(normalizeRules(key.getRulesJson(), key, name));
        }
    }

    /** rules 只认 {@code levels}:[{at:int,label:str}],at 严格递增且落在范围内;不认识的字段一律拒。 */
    private String normalizeRules(String raw, ThemeStateKey key, String name) {
        if (raw.trim().isEmpty()) return null;
        if (raw.length() > MAX_RULES_JSON) {
            throw invalid(ERR_INVALID, "状态键规则不能超过 " + MAX_RULES_JSON + " 字符:" + name);
        }
        JsonNode root;
        try {
            root = JSON.readTree(raw);
        } catch (Exception e) {
            throw invalid(ERR_INVALID, "状态键规则不是合法 JSON:" + name);
        }
        if (root == null || !root.isObject()) {
            throw invalid(ERR_INVALID, "状态键规则必须是对象:" + name);
        }
        java.util.Iterator<String> fields = root.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!"levels".equals(field)) {
                throw invalid(ERR_INVALID, "状态键规则不支持字段 " + field + ":" + name);
            }
        }
        JsonNode levels = root.get("levels");
        if (levels != null && !levels.isNull()) {
            if (!levels.isArray() || levels.size() > MAX_LEVELS) {
                throw invalid(ERR_INVALID, "状态键规则 levels 须为不超过 " + MAX_LEVELS + " 项的数组:" + name);
            }
            Integer previous = null;
            for (JsonNode level : levels) {
                JsonNode at = level.get("at");
                JsonNode label = level.get("label");
                if (at == null || !at.isIntegralNumber() || !at.canConvertToInt()
                        || label == null || !label.isTextual() || label.asText().trim().isEmpty()
                        || label.asText().trim().length() > MAX_LEVEL_LABEL || level.size() != 2) {
                    throw invalid(ERR_INVALID, "状态键规则 levels 每项须为 {at:整数,label:1-" + MAX_LEVEL_LABEL + " 字}:" + name);
                }
                int value = at.asInt();
                if (value < key.effectiveMin() || value > key.effectiveMax() || (previous != null && value <= previous)) {
                    throw invalid(ERR_RANGE, "状态键规则 levels 的 at 须在范围内且严格递增:" + name);
                }
                previous = Integer.valueOf(value);
            }
        }
        return root.toString();
    }

    private static ThemeStateKey copyDefinition(ThemeStateKey source) {
        ThemeStateKey copy = new ThemeStateKey();
        copy.setLabel(source.getLabel());
        copy.setInitialValue(source.getInitialValue());
        copy.setMinValue(source.getMinValue());
        copy.setMaxValue(source.getMaxValue());
        copy.setDisplayType(source.getDisplayType());
        copy.setColor(source.getColor());
        copy.setIcon(source.getIcon());
        copy.setSortOrder(source.getSortOrder());
        copy.setVisible(source.getVisible());
        copy.setPersistScope(source.getPersistScope());
        copy.setRulesJson(source.getRulesJson());
        return copy;
    }

    private static ServiceException invalid(String code, String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST).setDetailMessage(code);
    }

    private void validate(ThemeStateKey key) {
        if (key == null) {
            throw new ServiceException("状态键不能为空");
        }
        String stateKey = key.getStateKey();
        if (stateKey == null || !ThemeStateKey.KEY_NAME.matcher(stateKey).matches()) {
            throw new ServiceException("状态键名不合法:" + stateKey
                    + "(须为 clue./relation./tag./counter. 前缀 + 小写下划线,如 clue.ledger_page)");
        }
        if (key.getKeyType() == null || !KEY_TYPES.contains(key.getKeyType())) {
            throw new ServiceException("状态键类型不合法:" + key.getKeyType()
                    + "(仅支持 flag/counter/relation/tag)");
        }
        if (key.getScope() == null || !SCOPES.contains(key.getScope())) {
            throw new ServiceException("状态键作用域不合法:" + key.getScope()
                    + "(目前仅支持 run;party/deployment 尚未实现)");
        }
        String prefix = stateKey.substring(0, stateKey.indexOf('.') + 1);
        String expectedType = PREFIX_KEY_TYPES.get(prefix);
        if (expectedType != null && !expectedType.equals(key.getKeyType())) {
            throw new ServiceException(prefix.substring(0, prefix.length() - 1)
                    + " 前缀的状态键类型必须是 " + expectedType + ":" + stateKey);
        }
    }
}
