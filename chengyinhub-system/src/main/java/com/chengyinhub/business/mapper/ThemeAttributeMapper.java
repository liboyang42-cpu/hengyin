package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ThemeAttributeMark;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 文档二 L8 d20 属性(A2 / A3):theme_state_key 的 is_attribute / attribute_preset 两列。
 * 只读写这两列;键的定义仍由 {@link ThemeStateKeyMapper} 与 ThemeStateKeyService 维护。
 */
public interface ThemeAttributeMapper {

    /** 本主题已标成属性的键与预设,按 id 升序。 */
    List<ThemeAttributeMark> selectAttributeMarks(@Param("topicId") Long topicId);

    int markAttribute(@Param("topicId") Long topicId, @Param("stateKey") String stateKey,
                      @Param("preset") String preset);
}
