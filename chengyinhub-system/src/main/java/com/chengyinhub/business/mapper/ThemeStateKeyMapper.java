package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ThemeStateKey;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 主题具名状态键注册表(B1)。
 *
 * <p>写入固定走 upsert:同 topic 同 state_key 再注册 = 更新 label/initial_value,不重复插
 * (唯一键 uk_theme_state_key 兜底)。
 */
public interface ThemeStateKeyMapper {

    /** 全部属性写侧统一先锁主题，再锁注册版本；调用者必须持有事务。 */
    Long lockTopic(@Param("topicId") Long topicId);
    int ensureRegistryVersion(@Param("topicId") Long topicId);
    Long lockRegistryVersion(@Param("topicId") Long topicId);
    int advanceRegistryVersion(@Param("topicId") Long topicId);

    /** 某主题已注册的全部状态键;没注册过返回空列表。 */
    List<ThemeStateKey> selectByTopicId(@Param("topicId") Long topicId);

    /** Same cms_topic parent fence as publication; callers hold it until their transaction ends. */
    Long lockTopicForStateKeyWrite(@Param("topicId") Long topicId);

    /** Current author declarations, never runtime state. Lock topic first, then keys in id order.
     * The publication owner must reuse this one result for hash, validation and freezing. */
    List<ThemeStateKey> selectByTopicIdForUpdate(@Param("topicId") Long topicId);

    /**
     * 注册一个键;唯一键冲突时覆盖 label/initial_value/update_time,F2 扩展列只在本次带值时覆盖
     * (老调用方不带扩展列 = 保留作者在定义接口里设过的值)。
     */
    int upsertThemeStateKey(ThemeStateKey key);

    /** 某主题的一个键;不在返回 null。 */
    ThemeStateKey selectByTopicIdAndStateKey(@Param("topicId") Long topicId, @Param("stateKey") String stateKey);

    /** Current definition for edits after acquiring the same topic parent fence. */
    ThemeStateKey selectByTopicIdAndStateKeyForUpdate(@Param("topicId") Long topicId, @Param("stateKey") String stateKey);

    /** 整体覆盖一个键的定义元数据(F2 T01);键名 / 类型 / 作用域不变。返回受影响行数。 */
    int updateDefinition(ThemeStateKey key);

    /** 删除某主题的一个键;返回受影响行数(键不在 = 0)。 */
    int deleteByTopicIdAndStateKey(@Param("topicId") Long topicId, @Param("stateKey") String stateKey);
}
