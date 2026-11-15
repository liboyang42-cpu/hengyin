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

    /** R10 后台规则列表：含停用，按条件筛，id 倒序，最多 500 行。 */
    List<RewardDropRule> selectAdminList(RewardDropRule filter);

    RewardDropRule selectById(@Param("id") Long id);

    int insertRule(RewardDropRule rule);

    /** 整体覆盖可编辑字段；状态只经 updateStatus 改。 */
    int updateRule(RewardDropRule rule);

    int updateStatus(@Param("id") Long id, @Param("status") Integer status);
}
