package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsSetting;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.vo.PlatformSettlementAccountVO;
import com.chengyinhub.business.mapper.CmsSettingMapper;
import com.chengyinhub.business.mapper.SettlementRuleMapper;
import com.chengyinhub.business.service.ISettlementRuleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class SettlementRuleServiceImpl implements ISettlementRuleService {
    private static final Long SETTLEMENT_CONFIG_ID = 1L;

    @Autowired
    private SettlementRuleMapper settlementRuleMapper;

    @Autowired
    private CmsSettingMapper cmsSettingMapper;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Override
    public List<SettlementRule> selectRuleList(SettlementRule query) {
        return settlementRuleMapper.selectRuleList(query);
    }

    @Override
    public SettlementRule getActiveRule(Long merchantId, String bizType) {
        requireBizType(bizType);
        Long effectiveMerchantId = merchantId == null ? 0L : merchantId;
        SettlementRule rule = settlementRuleMapper.selectActiveRule(effectiveMerchantId, bizType);
        if (rule == null && !Long.valueOf(0L).equals(effectiveMerchantId)) {
            rule = settlementRuleMapper.selectActiveRule(0L, bizType);
        }
        return rule;
    }

    @Override
    public int saveRule(SettlementRule rule) {
        validateRule(rule);
        if (rule.getId() == null) {
            if (rule.getMerchantId() == null) {
                rule.setMerchantId(0L);
            }
            if (rule.getStatus() == null) {
                rule.setStatus(1);
            }
            if (rule.getDelFlag() == null) {
                rule.setDelFlag(0);
            }
            return settlementRuleMapper.insertSettlementRule(rule);
        }
        return settlementRuleMapper.updateSettlementRule(rule);
    }

    @Override
    public PlatformSettlementAccountVO getPlatformSettlementAccount() {
        PlatformSettlementAccountVO account = new PlatformSettlementAccountVO();
        CmsSetting setting = cmsSettingMapper.selectCmsSettingById(SETTLEMENT_CONFIG_ID);
        if (setting == null || setting.getPlatformMemberId() == null) {
            return account;
        }
        account.setMemberId(setting.getPlatformMemberId());
        account.setConfigured(true);
        account.setUsable(isUsableMember(umsMemberService.selectUmsMemberById(setting.getPlatformMemberId())));
        return account;
    }

    @Override
    public int updatePlatformSettlementAccount(Long memberId) {
        if (memberId == null || memberId <= 0) {
            throw new ServiceException("请选择有效的平台收款会员账户");
        }
        UmsMember member = umsMemberService.selectUmsMemberById(memberId);
        if (!isUsableMember(member)) {
            throw new ServiceException("平台收款账户不存在或不可用");
        }
        if (cmsSettingMapper.selectCmsSettingById(SETTLEMENT_CONFIG_ID) == null) {
            throw new ServiceException("活动结算配置不存在，不能更新平台收款账户");
        }
        CmsSetting patch = new CmsSetting();
        patch.setId(SETTLEMENT_CONFIG_ID);
        patch.setPlatformMemberId(memberId);
        patch.setUpdateTime(DateUtils.getNowDate());
        return cmsSettingMapper.updateCmsSetting(patch);
    }

    private void validateRule(SettlementRule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("rule is required");
        }
        requireBizType(rule.getBizType());
        requireNonNegativeRate(rule.getPlatformCommissionRate(), "platformCommissionRate");
        requireNonNegativeRate(rule.getMerchantRate(), "merchantRate");
        requireNonNegativeRate(rule.getCreatorRate(), "creatorRate");
        requireNonNegativeRate(rule.getInviterRate(), "inviterRate");
        requireSumWithinOne(rule);
        requireZeroCreatorRateForH8BizTypes(rule);
    }

    /**
     * H8(2026-08-14 裁决):删除发布者分润，creatorRate 全并 merchant。这是**持久属性**，
     * 不是一次性数据动作 —— 迁移收敛的是它跑那一刻的存量(含 per-merchant 覆盖规则)，
     * 之后后台再建/改一条规则就能重新造出发布者份额，
     * 而四方和 &lt;= 1.0 完全过得了 requireSumWithinOne，不会被上面那道闸接住。
     * 只管 H8 裁决点名的三类 biz_type；coupon 等其余玩法不在裁决范围内，不扩大保护半径。
     */
    private void requireZeroCreatorRateForH8BizTypes(SettlementRule rule) {
        String bizType = rule.getBizType().trim();
        if (!"topic".equals(bizType) && !"topic_club".equals(bizType) && !"activity".equals(bizType)) {
            return;
        }
        if (nz(rule.getCreatorRate()).compareTo(BigDecimal.ZERO) != 0) {
            throw new IllegalArgumentException("H8 已取消发布者分润，" + bizType
                    + " 的 creatorRate 必须为 0(份额全并商家)，当前 " + rule.getCreatorRate());
        }
    }

    /**
     * HF-01(A-30): 四方分成比例之和不得超过 1.0(gross 的 100%),防止运营配错导致超分资损。
     * 注:rate 为小数(DDL 默认 platform 0.1 + merchant 0.9 = 1.0)。允许 &lt;1.0(平台留存差额)。
     */
    private void requireSumWithinOne(SettlementRule rule) {
        BigDecimal sum = nz(rule.getPlatformCommissionRate())
                .add(nz(rule.getMerchantRate()))
                .add(nz(rule.getCreatorRate()))
                .add(nz(rule.getInviterRate()));
        if (sum.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("分成比例之和不得超过 100%(当前 " + sum + ")");
        }
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private boolean isUsableMember(UmsMember member) {
        return member != null
                && Long.valueOf(1L).equals(member.getStatus())
                && Integer.valueOf(0).equals(member.getDelFlag());
    }

    private void requireBizType(String bizType) {
        if (bizType == null || bizType.trim().isEmpty()) {
            throw new IllegalArgumentException("bizType is required");
        }
    }

    private void requireNonNegativeRate(BigDecimal value, String field) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
