package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ThemeCharacter;
import org.apache.ibatis.annotations.Param;

/**
 * 主题角色卡跨局属性台账(F2 T02)。写入只走「来源更新才覆盖」的条件更新 + 冲突即不动的插入,
 * 同一来源重放是 no-op。
 */
public interface ThemeCharacterMapper {

    /** 一位玩家在一个主题下的台账;没有返回 null。 */
    ThemeCharacter selectByMemberAndTopic(@Param("memberId") Long memberId, @Param("topicId") Long topicId);

    /** 来源 (sourceSessionId, sourceVersion) 严格更新时才覆盖;返回受影响行数。 */
    int updateIfNewer(ThemeCharacter character);

    /** 首次建档;唯一键冲突(并发或已有行)时什么都不改。 */
    int insertIfAbsent(ThemeCharacter character);
}
