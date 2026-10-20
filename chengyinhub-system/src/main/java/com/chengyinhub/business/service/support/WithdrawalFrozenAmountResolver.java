package com.chengyinhub.business.service.support;

import com.chengyinhub.business.service.IPlayerWithdrawalCooldownService;
import com.chengyinhub.business.service.impl.MerchantReferralBountyService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 提现冻结额的唯一解析点：拓展赏金冻结 + 未到期的 PLAYER 投诉补偿冷静额。
 *
 * <p><b>为什么必须只有一处</b>：银行卡与微信零钱是两条提现腿，历史上就因为「只在一条腿刨冻结额」
 * 让冻结款从另一条口子提走过一次（见 CashWithdrawServiceImpl 里的那段注释）。
 * 两条腿都从这里取<b>同一个总额</b>、一次传给原子扣减，将来加第三条腿也不会再漏。
 *
 * <p><b>fail-closed</b>：两个来源都刻意不 try/catch。任一查询挂了就让提现整单失败；
 * 吞异常当 0 = 冻结静默失效、钱当场提走，正是它要防的那件事。
 *
 * <p>返回值带分项：原子扣减只用 {@link Frozen#total()} 一个数，但拒绝文案要能指明是哪道闸
 * —— 「余额不足」和「有 30 元在冷静期」对用户是两件事，对排障也是。
 */
@Component
public class WithdrawalFrozenAmountResolver {

    private final MerchantReferralBountyService referralBountyService;
    private final IPlayerWithdrawalCooldownService cooldownService;

    public WithdrawalFrozenAmountResolver(MerchantReferralBountyService referralBountyService,
                                          IPlayerWithdrawalCooldownService cooldownService) {
        this.referralBountyService = referralBountyService;
        this.cooldownService = cooldownService;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.mapper.MerchantReferralBountyMapper bountyMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.mapper.PlayerWithdrawalCooldownMapper cooldownMapper;

    /** Caller holds the member balance lock in its financial transaction. */
    public Frozen resolveForWithdrawal(Long memberId) {
        if (memberId == null) throw new IllegalArgumentException("member identity required");
        return new Frozen(sum(bountyMapper.selectFrozenAmountsForUpdate(memberId, new java.util.Date())),
                sum(cooldownMapper.selectUnexpiredAmountsForUpdate(memberId)));
    }

    private static BigDecimal sum(java.util.List<BigDecimal> amounts) {
        if (amounts == null) throw new IllegalStateException("Frozen current read unavailable");
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal amount : amounts) if (amount != null) total = total.add(amount);
        return total;
    }

    /** 该会员当前不可提现的冻结额分项，两项都 ≥0。 */
    public Frozen resolve(Long memberId) {
        if (memberId == null) {
            return new Frozen(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        return new Frozen(nz(referralBountyService.frozenAmount(memberId)),
                nz(cooldownService.frozenAmount(memberId)));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 冻结额分项。两条提现腿一次算完、一次传给 deductWithdrawableBalance，禁止分别扣。 */
    public static final class Frozen {
        /**
         * A3 锁定的业务错误码。中文文案能给用户看，但小程序与监控要按<b>稳定符号</b>分流 ——
         * 文案随时可能被改写，码不行。两条提现腿被冷静期挡住时都必须带它。
         */
        public static final String PLAYER_WITHDRAWAL_COOLDOWN = "PLAYER_WITHDRAWAL_COOLDOWN";

        private final BigDecimal referralBounty;
        private final BigDecimal playerComplaintCooldown;

        Frozen(BigDecimal referralBounty, BigDecimal playerComplaintCooldown) {
            this.referralBounty = referralBounty;
            this.playerComplaintCooldown = playerComplaintCooldown;
        }

        public BigDecimal getReferralBounty() { return referralBounty; }

        public BigDecimal getPlayerComplaintCooldown() { return playerComplaintCooldown; }

        public BigDecimal total() { return referralBounty.add(playerComplaintCooldown); }

        public boolean isEmpty() { return total().compareTo(BigDecimal.ZERO) <= 0; }

        /**
         * 提现被拒时抛出去的完整理由：命中冷静期就前缀固定机器码，人话原样留在后面。
         *
         * <p>前缀式在本仓库是既有体例（EDITION_FINAL_PENDING: / PAYEE_FIELD_REQUIRED: 同形）。
         * 两条提现腿都必须用它，不能一条带码一条不带。
         */
        public String rejectionMessage() {
            return playerComplaintCooldown.compareTo(BigDecimal.ZERO) > 0
                    ? PLAYER_WITHDRAWAL_COOLDOWN + ":" + describe()
                    : describe();
        }

        /** 拒绝文案：点名是哪一道闸挡的，别只说「余额不足」。 */
        public String describe() {
            boolean hasBounty = referralBounty.compareTo(BigDecimal.ZERO) > 0;
            boolean hasCooldown = playerComplaintCooldown.compareTo(BigDecimal.ZERO) > 0;
            if (hasBounty && hasCooldown) {
                return "可提现余额不足(其中 " + referralBounty + " 元为拓展赏金冻结、"
                        + playerComplaintCooldown + " 元为投诉补偿冷静期,到期后可提现)";
            }
            if (hasBounty) {
                return "可提现余额不足(其中 " + referralBounty + " 元为拓展赏金,入账 48 小时后可提现)";
            }
            if (hasCooldown) {
                return "可提现余额不足(其中 " + playerComplaintCooldown
                        + " 元为投诉补偿冷静期,到账 48 小时后可提现)";
            }
            return "用户余额不足";
        }
    }
}
