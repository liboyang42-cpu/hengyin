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

    /** 某主题已注册的全部状态键;没注册过返回空列表。 */
    List<ThemeStateKey> selectByTopicId(@Param("topicId") Long topicId);

    /** 注册一个键;唯一键冲突时只覆盖 label/initial_value/update_time。 */
    int upsertThemeStateKey(ThemeStateKey key);

    /** 删除某主题的一个键;返回受影响行数(键不在 = 0)。 */
    int deleteByTopicIdAndStateKey(@Param("topicId") Long topicId, @Param("stateKey") String stateKey);
}
