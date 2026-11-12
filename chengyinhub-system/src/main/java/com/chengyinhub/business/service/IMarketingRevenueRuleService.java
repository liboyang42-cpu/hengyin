package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.RevenueRuleDraftRequest;
import com.chengyinhub.business.domain.dto.RevenueRuleView;
import com.chengyinhub.business.domain.dto.RevenueTrialRequest;
import com.chengyinhub.business.domain.dto.RevenueTrialResult;
import com.chengyinhub.business.domain.marketing.MarketingRevenueRule;

import java.util.List;

/**
 * 创作者/推荐分配规则(CO-10 规则半边)。
 *
 * <p>只管理「规则怎么配、怎么算」，<b>不</b>写任何钱、余额、支付或既有 settlement 表；
 * 也不改 {@code SettlementRule} 的费率语义，H8「topic/topic_club/activity 的 creatorRate 必须为 0」
 * 这条既有闸继续独立生效。本域规则要真正付款，必须由 root 批准后接入既有受控结算通道。
 */
public interface IMarketingRevenueRuleService {

    /** 保存草稿(新建或覆盖同 rule_key 的草稿)。发布后不可改，改配置须新建版本。 */
    RevenueRuleView saveDraft(Long operatorId, RevenueRuleDraftRequest request);

    /** 发布草稿：先退役同键的现行版本，再激活该草稿。同一 rule_key 永远只有一个 ACTIVE。 */
    RevenueRuleView publish(Long operatorId, String ruleKey);

    /** 退役某 rule_key 的现行版本；已应计的原单不受影响(冻结版本继续可追溯)。 */
    int retire(Long operatorId, String ruleKey);

    List<RevenueRuleView> list(String ruleKey, Integer scenario, Integer status);

    RevenueRuleView get(Long ruleId);

    /**
     * 解析生效规则：具体目标优先，其次全局默认；币种必须一致。
     * 找不到可直接用的规则时抛 {@code RULE_NOT_FOUND} —— 宁可不分配，也不套用未授权比例。
     */
    MarketingRevenueRule resolveActive(Integer ownerType, Long ownerId, Integer scenario, String currency);

    /** 试算：只算数不落库，返回净额分项与三方金额，并声明是否守恒。 */
    RevenueTrialResult trial(RevenueTrialRequest request);
}
