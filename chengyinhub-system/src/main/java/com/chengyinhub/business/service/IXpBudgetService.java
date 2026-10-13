package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopicNode;

import java.util.List;
import java.util.Map;

/**
 * M7 XP 预算校验与节点探索值解析。
 */
public interface IXpBudgetService {

    /**
     * 校验主题 XP 预算:Σ节点xp ≤ xp_budget。
     * @return {budget, totalXp, over(bool), overBy, remain(未分配=通关奖励), perNode:[{nodeId,name,xp}]}
     */
    Map<String, Object> computeBudget(Long topicId);

    /**
     * 解析单节点探索值:节点 xp_value(纯停靠) → 模板 xp_value(游戏节点) → 0(交由全局兜底)。
     */
    int resolveNodeXp(CmsTopicNode node);

    /** 批量解析(避免逐节点查模板) */
    int resolveNodeXp(CmsTopicNode node, Map<Long, Integer> templateXpCache);

    List<CmsTopicNode> listTopicNodes(Long topicId);

    /**
     * M7 XP 自动分配(奖励契约:XP 全自动,创作者零配置)。发布/过审时调用一次:
     * 按验证方式加权(拍照3/答题3/扫码2/到点·GPS 1)把 xp_budget×0.6 拆到各节点写 node.xp_value,
     * 余额(≥40%)= 通关 XP(读取时由 computeBudget.remain 表达)。
     * @return 已分配到节点的 XP 总和(0=无节点/无预算)
     */
    int autoAllocate(Long topicId);
}
