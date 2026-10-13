package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private static final Set<String> SCOPES = new HashSet<String>(Arrays.asList(
            ThemeStateKey.SCOPE_RUN, ThemeStateKey.SCOPE_PARTY, ThemeStateKey.SCOPE_DEPLOYMENT));

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
                    + "(仅支持 run/party/deployment)");
        }
        String prefix = stateKey.substring(0, stateKey.indexOf('.') + 1);
        String expectedType = PREFIX_KEY_TYPES.get(prefix);
        if (expectedType != null && !expectedType.equals(key.getKeyType())) {
            throw new ServiceException(prefix.substring(0, prefix.length() - 1)
                    + " 前缀的状态键类型必须是 " + expectedType + ":" + stateKey);
        }
    }
}
