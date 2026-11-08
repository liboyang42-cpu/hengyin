package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.RevenueRuleDraftRequest;
import com.chengyinhub.business.domain.dto.RevenueRuleView;
import com.chengyinhub.business.domain.dto.RevenueTrialRequest;
import com.chengyinhub.business.domain.dto.RevenueTrialResult;
import com.chengyinhub.business.domain.marketing.MarketingRevenueRule;
import com.chengyinhub.business.exception.MarketingRevenueException;
import com.chengyinhub.business.mapper.MarketingRevenueRuleMapper;
import com.chengyinhub.business.service.IMarketingRevenueRuleService;
import com.chengyinhub.business.service.support.RevenueMoney;
import com.chengyinhub.business.service.support.RevenueRuleKeys;
import com.chengyinhub.business.service.support.RevenueSplitCalculator;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 分配规则实现。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li>费率必须显式配置；本类<b>不含</b>任何默认比例常量(50/75/20 只是竞品样本)。</li>
 *   <li>发布是「退役旧版 → 激活草稿」的顺序动作，靠 {@code uk_revenue_rule_single_active} 兜底，
 *       并发发布时第二个会撞唯一键而不是造出两个 ACTIVE。</li>
 *   <li>已应计的分配冻结了 rule_version；改规则只会影响新单。</li>
 * </ol>
 */
@Service
public class MarketingRevenueRuleServiceImpl implements IMarketingRevenueRuleService {

    /** 当前结算体系只有人民币(微信支付事实 CNY)；其它币种需要真实多币种合同后再开放。 */
    static final String SUPPORTED_CURRENCY = "CNY";

    private static final int DEFAULT_HOLD_DAYS = 14;
    private static final int MAX_HOLD_DAYS = 365;

    private final MarketingRevenueRuleMapper ruleMapper;

    public MarketingRevenueRuleServiceImpl(MarketingRevenueRuleMapper ruleMapper) {
        this.ruleMapper = ruleMapper;
    }

    @Override
    @Transactional
    public RevenueRuleView saveDraft(Long operatorId, RevenueRuleDraftRequest request) {
        if (request == null) {
            throw MarketingRevenueException.badRequest("RULE_REQUIRED", "缺少规则内容");
        }
        String ruleKey = normalizeRuleKey(request);
        validateRates(request);

        MarketingRevenueRule draft = ruleMapper.selectDraftByRuleKey(ruleKey);
        Date now = DateUtils.getNowDate();
        if (draft == null) {
            MarketingRevenueRule rule = new MarketingRevenueRule();
            rule.setRuleKey(ruleKey);
            Integer maxVersion = ruleMapper.selectMaxVersion(ruleKey);
            rule.setVersion(maxVersion == null ? 1 : maxVersion + 1);
            rule.setStatus(MarketingRevenueRule.STATUS_DRAFT);
            applyRequest(rule, request);
            rule.setCreateBy(operatorId);
            rule.setCreateTime(now);
            if (ruleMapper.insertRule(rule) != 1) {
                throw new IllegalStateException("分配规则草稿写入失败");
            }
            return toView(ruleMapper.selectById(rule.getId()));
        }
        if (draft.getScenario() != null && request.getScenario() != null
                && draft.getScenario().intValue() != request.getScenario().intValue()) {
            // rule_key 已含场景；换场景必须换键，避免同键在版本间静默改变语义。
            throw MarketingRevenueException.conflict("SCENARIO_IMMUTABLE",
                    "同一规则键的版本不能更换场景，请使用新的规则键");
        }
        applyRequest(draft, request);
        if (ruleMapper.updateRule(draft) != 1) {
            throw MarketingRevenueException.conflict("DRAFT_CHANGED", "草稿状态已变化，请刷新后重试");
        }
        return toView(ruleMapper.selectById(draft.getId()));
    }

    @Override
    @Transactional
    public RevenueRuleView publish(Long operatorId, String ruleKey) {
        String key = requireNonBlank(ruleKey, "RULE_KEY_REQUIRED", "缺少规则键");
        MarketingRevenueRule draft = ruleMapper.selectDraftByRuleKey(key);
        if (draft == null) {
            throw MarketingRevenueException.notFound("DRAFT_NOT_FOUND", "该规则键没有可发布的草稿");
        }
        requirePublishable(draft);
        Date now = DateUtils.getNowDate();
        // 先退役再激活；不同事务并发时靠唯一键兜底，不让两个 ACTIVE 同时存在。
        ruleMapper.retireActiveByRuleKey(key, now);
        if (ruleMapper.activate(draft.getId(), now, now) != 1) {
            throw MarketingRevenueException.conflict("PUBLISH_RACE", "草稿已被发布或状态已变化");
        }
        return toView(ruleMapper.selectById(draft.getId()));
    }

    @Override
    @Transactional
    public int retire(Long operatorId, String ruleKey) {
        String key = requireNonBlank(ruleKey, "RULE_KEY_REQUIRED", "缺少规则键");
        return ruleMapper.retireActiveByRuleKey(key, DateUtils.getNowDate());
    }

    @Override
    public List<RevenueRuleView> list(String ruleKey, Integer scenario, Integer status) {
        List<MarketingRevenueRule> rules = ruleMapper.selectList(ruleKey, scenario, status);
        if (rules == null || rules.isEmpty()) {
            return Collections.emptyList();
        }
        List<RevenueRuleView> views = new ArrayList<>(rules.size());
        for (MarketingRevenueRule rule : rules) {
            views.add(toView(rule));
        }
        return views;
    }

    @Override
    public RevenueRuleView get(Long ruleId) {
        if (ruleId == null || ruleId <= 0) {
            throw MarketingRevenueException.badRequest("RULE_ID_REQUIRED", "缺少规则 id");
        }
        MarketingRevenueRule rule = ruleMapper.selectById(ruleId);
        if (rule == null) {
            throw MarketingRevenueException.notFound("RULE_NOT_FOUND", "规则不存在");
        }
        return toView(rule);
    }

    @Override
    public MarketingRevenueRule resolveActive(Integer ownerType, Long ownerId, Integer scenario, String currency) {
        String required = RevenueSplitCalculator.requireCurrency(currency);
        if (!SUPPORTED_CURRENCY.equals(required)) {
            throw MarketingRevenueException.badRequest("CURRENCY_UNSUPPORTED",
                    "当前结算体系只支持 " + SUPPORTED_CURRENCY + "，其它币种需先确认真实多币种合同");
        }
        for (String key : RevenueRuleKeys.resolutionChain(ownerType, ownerId, scenario)) {
            MarketingRevenueRule rule = ruleMapper.selectActiveByRuleKey(key);
            if (rule == null) {
                continue;
            }
            if (!required.equals(rule.getCurrency())) {
                throw MarketingRevenueException.conflict("RULE_CURRENCY_MISMATCH",
                        "命中规则的币种与订单币种不一致，拒绝分配");
            }
            requireUsable(rule);
            return rule;
        }
        throw MarketingRevenueException.notFound("RULE_NOT_FOUND",
                "没有已发布的可用分配规则，未配置即不分配");
    }

    @Override
    public RevenueTrialResult trial(RevenueTrialRequest request) {
        if (request == null) {
            throw MarketingRevenueException.badRequest("TRIAL_REQUIRED", "缺少试算参数");
        }
        MarketingRevenueRule rule;
        if (request.getRuleKey() != null && !request.getRuleKey().trim().isEmpty()) {
            rule = ruleMapper.selectActiveByRuleKey(request.getRuleKey().trim());
            if (rule == null) {
                throw MarketingRevenueException.notFound("RULE_NOT_FOUND", "该规则键没有已发布版本");
            }
            requireUsable(rule);
        } else {
            rule = resolveActive(request.getTargetType(), request.getTargetId(),
                    request.getScenario(), request.getCurrency());
        }

        boolean hasReferrer = Boolean.TRUE.equals(request.getHasReferrer());
        long paidFen = RevenueMoney.toFen(request.getPaidAmount(), "PAID_REQUIRED", "折后实付");
        long taxFen = request.getTaxAmount() == null ? 0L
                : RevenueMoney.toFen(request.getTaxAmount(), "TAX_INVALID", "税费");
        long feeFen = request.getPaymentFeeAmount() == null ? 0L
                : RevenueMoney.toFen(request.getPaymentFeeAmount(), "PAYMENT_FEE_INVALID", "支付手续费");
        long channelFen = request.getChannelCommissionAmount() == null ? 0L
                : RevenueMoney.toFen(request.getChannelCommissionAmount(), "CHANNEL_COMMISSION_INVALID", "渠道佣金");
        RevenueSplitCalculator.Output output = RevenueSplitCalculator.split(new RevenueSplitCalculator.Input()
                .paidFen(paidFen)
                .taxFen(taxFen)
                .paymentFeeFen(feeFen)
                .channelCommissionFen(channelFen)
                .authorBaseRate(rule.getAuthorBaseRate())
                .platformBaseRate(rule.getPlatformBaseRate())
                .referralRate(hasReferrer ? rule.getReferralRate() : BigDecimal.ZERO)
                .referralFundingSide(hasReferrer ? rule.getReferralFundingSide() : MarketingRevenueRule.FUNDING_NONE)
                .currency(rule.getCurrency()));

        RevenueTrialResult result = new RevenueTrialResult();
        result.setCurrency(output.getCurrency());
        result.setPaidAmount(RevenueMoney.toAmount(paidFen));
        result.setTaxAmount(RevenueMoney.toAmount(taxFen));
        result.setPaymentFeeAmount(RevenueMoney.toAmount(feeFen));
        result.setChannelCommissionAmount(RevenueMoney.toAmount(channelFen));
        result.setNetAmount(output.getNetAmount());
        result.setAuthorAmount(output.getAuthorAmount());
        result.setReferrerAmount(output.getReferrerAmount());
        result.setPlatformAmount(output.getPlatformAmount());
        result.setAuthorBaseRate(output.getAuthorBaseRate());
        result.setPlatformBaseRate(output.getPlatformBaseRate());
        result.setReferralRate(output.getReferralRate());
        result.setReferralFundingSide(output.getReferralFundingSide());
        result.setRuleId(rule.getId());
        result.setRuleKey(rule.getRuleKey());
        result.setRuleVersion(rule.getVersion());
        result.setScenario(rule.getScenario());
        result.setContractRef(rule.getContractRef());
        result.setConserved(output.isConserved());
        return result;
    }

    // ------------------------------------------------------------------ internals

    private void applyRequest(MarketingRevenueRule rule, RevenueRuleDraftRequest request) {
        rule.setScenario(request.getScenario());
        rule.setCurrency(RevenueSplitCalculator.requireCurrency(request.getCurrency()));
        rule.setAuthorBaseRate(request.getAuthorBaseRate());
        rule.setPlatformBaseRate(request.getPlatformBaseRate());
        rule.setReferralRate(request.getReferralRate() == null ? BigDecimal.ZERO : request.getReferralRate());
        rule.setReferralFundingSide(request.getReferralFundingSide() == null
                ? MarketingRevenueRule.FUNDING_NONE : request.getReferralFundingSide());
        int holdDays = request.getHoldDays() == null ? DEFAULT_HOLD_DAYS : request.getHoldDays();
        if (holdDays < 0 || holdDays > MAX_HOLD_DAYS) {
            throw MarketingRevenueException.badRequest("HOLD_DAYS_INVALID", "等待期必须在 0.." + MAX_HOLD_DAYS + " 天");
        }
        rule.setHoldDays(holdDays);
        rule.setContractRef(blankToNull(request.getContractRef()));
        rule.setPayeeMemberId(request.getPayeeMemberId());
        rule.setNote(request.getNote());
    }

    private void validateRates(RevenueRuleDraftRequest request) {
        Integer scenario = request.getScenario();
        if (scenario == null
                || (scenario != MarketingRevenueRule.SCENARIO_PLATFORM_SELLS_AUTHOR_CONTENT
                && scenario != MarketingRevenueRule.SCENARIO_AUTHOR_OWN_CODE
                && scenario != MarketingRevenueRule.SCENARIO_REFERRER_RECOMMENDS)) {
            throw MarketingRevenueException.badRequest("SCENARIO_INVALID", "场景必须是 1/2/3");
        }
        BigDecimal author = RevenueSplitCalculator.requireRate(request.getAuthorBaseRate(),
                "AUTHOR_RATE_INVALID", "作者基础比例");
        BigDecimal platform = RevenueSplitCalculator.requireRate(request.getPlatformBaseRate(),
                "PLATFORM_RATE_INVALID", "平台基础比例");
        if (author.add(platform).compareTo(BigDecimal.ONE) != 0) {
            throw MarketingRevenueException.badRequest("RATE_NOT_CONSERVED",
                    "作者基础比例 + 平台基础比例必须等于 100%");
        }
        BigDecimal referral = request.getReferralRate() == null ? BigDecimal.ZERO : request.getReferralRate();
        if (referral.compareTo(BigDecimal.ZERO) < 0 || referral.compareTo(BigDecimal.ONE) > 0) {
            throw MarketingRevenueException.badRequest("REFERRAL_RATE_INVALID", "推荐佣金比例必须在 0 到 1 之间");
        }
        int side = request.getReferralFundingSide() == null
                ? MarketingRevenueRule.FUNDING_NONE : request.getReferralFundingSide();
        if (side != MarketingRevenueRule.FUNDING_NONE
                && side != MarketingRevenueRule.FUNDING_PLATFORM
                && side != MarketingRevenueRule.FUNDING_AUTHOR) {
            throw MarketingRevenueException.badRequest("FUNDING_SIDE_INVALID", "推荐佣金出资侧不合法");
        }
        if (side == MarketingRevenueRule.FUNDING_NONE && referral.compareTo(BigDecimal.ZERO) != 0) {
            throw MarketingRevenueException.badRequest("REFERRAL_WITHOUT_FUNDING",
                    "配置了推荐佣金比例但未声明出资侧");
        }
        if (side != MarketingRevenueRule.FUNDING_NONE) {
            if (referral.compareTo(BigDecimal.ZERO) <= 0) {
                throw MarketingRevenueException.badRequest("REFERRAL_RATE_REQUIRED",
                        "声明了推荐出资侧但佣金比例必须大于 0");
            }
            BigDecimal fundingBase = side == MarketingRevenueRule.FUNDING_PLATFORM ? platform : author;
            if (referral.compareTo(fundingBase) > 0) {
                throw MarketingRevenueException.badRequest("REFERRAL_EXCEEDS_FUNDING",
                        "推荐佣金不能超过出资侧基础份额");
            }
        }
    }

    /** 场景与出资侧必须自洽：否则发布出的是算不出钱的规则。 */
    private void requireUsable(MarketingRevenueRule rule) {
        if (rule.getAuthorBaseRate() == null || rule.getPlatformBaseRate() == null) {
            throw MarketingRevenueException.conflict("RULE_INCOMPLETE", "规则缺少基础比例");
        }
        if (rule.getContractRef() == null || rule.getContractRef().trim().isEmpty()
                || rule.getPayeeMemberId() == null || rule.getPayeeMemberId() <= 0) {
            // 没有受审合同与合同收款人，就不能给真实订单授钱：内容 owner 不等于收款权利人。
            throw MarketingRevenueException.conflict("AUTHOR_CONTRACT_MISSING",
                    "规则缺少受审交易合同或合同收款人");
        }
        int side = rule.getReferralFundingSide() == null
                ? MarketingRevenueRule.FUNDING_NONE : rule.getReferralFundingSide();
        boolean referralScenario = rule.getScenario() != null
                && rule.getScenario() == MarketingRevenueRule.SCENARIO_REFERRER_RECOMMENDS;
        if (referralScenario && side == MarketingRevenueRule.FUNDING_NONE) {
            throw MarketingRevenueException.conflict("RULE_INCOMPLETE",
                    "推荐场景规则必须声明推荐佣金出资侧");
        }
        if (!referralScenario && side != MarketingRevenueRule.FUNDING_NONE) {
            throw MarketingRevenueException.conflict("RULE_INCOMPLETE",
                    "非推荐场景规则不应声明推荐佣金出资侧");
        }
    }

    private String normalizeRuleKey(RevenueRuleDraftRequest request) {
        String encoded = RevenueRuleKeys.of(request.getTargetType(), request.getTargetId(), request.getScenario());
        String provided = request.getRuleKey();
        if (provided == null || provided.trim().isEmpty()) {
            return encoded;
        }
        String key = provided.trim();
        if (!key.matches("[a-z]{1,10}:[0-9]{1,19}:s[1-3]") && !key.matches("global:s[1-3]")) {
            throw MarketingRevenueException.badRequest("RULE_KEY_INVALID",
                    "规则键格式必须为 topic:<id>:s<1-3> / activity:<id>:s<1-3> / global:s<1-3>");
        }
        if (!key.equals(encoded)) {
            throw MarketingRevenueException.badRequest("RULE_KEY_MISMATCH",
                    "规则键与适用范围/场景不一致，应为 " + encoded);
        }
        return key;
    }

    private static String requireNonBlank(String value, String reasonCode, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw MarketingRevenueException.badRequest(reasonCode, message);
        }
        return value.trim();
    }

    /**
     * 发布闸：草稿可以随便存，但一旦 ACTIVE 就会给真实订单授钱，
     * 所以必须已有受审合同与合同收款人；费率也必须自洽。
     */
    private void requirePublishable(MarketingRevenueRule draft) {
        if (draft.getAuthorBaseRate() == null || draft.getPlatformBaseRate() == null
                || draft.getAuthorBaseRate().add(draft.getPlatformBaseRate()).compareTo(BigDecimal.ONE) != 0) {
            throw MarketingRevenueException.badRequest("RATE_NOT_CONSERVED",
                    "作者基础比例 + 平台基础比例必须等于 100%");
        }
        if (draft.getContractRef() == null || draft.getContractRef().trim().isEmpty()) {
            throw MarketingRevenueException.badRequest("CONTRACT_REQUIRED",
                    "发布规则必须绑定受审交易合同标识");
        }
        if (draft.getPayeeMemberId() == null || draft.getPayeeMemberId() <= 0) {
            throw MarketingRevenueException.badRequest("PAYEE_REQUIRED",
                    "发布规则必须声明合同收款人，不能默认取内容 owner");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private RevenueRuleView toView(MarketingRevenueRule rule) {
        if (rule == null) {
            return null;
        }
        RevenueRuleView view = new RevenueRuleView();
        view.setId(rule.getId());
        view.setRuleKey(rule.getRuleKey());
        view.setVersion(rule.getVersion());
        view.setScenario(rule.getScenario());
        view.setCurrency(rule.getCurrency());
        view.setAuthorBaseRate(rule.getAuthorBaseRate());
        view.setPlatformBaseRate(rule.getPlatformBaseRate());
        view.setReferralRate(rule.getReferralRate());
        view.setReferralFundingSide(rule.getReferralFundingSide());
        view.setContractRef(rule.getContractRef());
        view.setPayeeMemberId(rule.getPayeeMemberId());
        view.setHoldDays(rule.getHoldDays());
        view.setStatus(rule.getStatus());
        view.setEffectiveFrom(rule.getEffectiveFrom());
        view.setEffectiveTo(rule.getEffectiveTo());
        view.setNote(rule.getNote());
        view.setCreateTime(rule.getCreateTime());
        view.setPublishTime(rule.getPublishTime());
        view.setRetireTime(rule.getRetireTime());
        String[] scope = RevenueRuleKeys.parseScope(rule.getRuleKey());
        if (scope != null) {
            view.setTargetType(Integer.valueOf(scope[0]));
            view.setTargetId(Long.valueOf(scope[1]));
        }
        return view;
    }

}
