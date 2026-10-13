package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.RewardDropRule;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * M6 随机掉落规则 Mapper。
 */
public interface RewardDropRuleMapper {
    /** 适用规则:全局 + 指定主题 + 与 sceneKey 精确匹配的场景规则，仅 status=1。 */
    List<RewardDropRule> selectApplicableRules(@Param("topicId") Long topicId,
                                                @Param("sceneKey") String sceneKey);
}
