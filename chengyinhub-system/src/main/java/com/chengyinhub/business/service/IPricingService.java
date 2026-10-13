package com.chengyinhub.business.service;

import java.math.BigDecimal;
import com.chengyinhub.business.domain.dto.PricingResult;

/**
 * 定价引擎 Service 接口(报告§5.4 · B2)。
 * 原则:系统算底价 P_min,发布者定终价(≥P_min)。
 */
public interface IPricingService {

    /**
     * 纯计算:给定成本项与分成/保底/佣金,算每人成本 C 与最低售价 P_min。
     * P_min = C / (1 − platformRate − guaranteeRate − sumShareRate),分母 ≤0 抛 IllegalStateException(分成+保底太高)。
     * guaranteeRate 为发起人保底率(§3.4:须进地板,否则结算 margin 可为负、守恒拒结、款项悬空)。抽成纯方法便于单测。
     */
    PricingResult compute(BigDecimal sumShareRate, BigDecimal guaranteeRate, BigDecimal sumFixedFee,
                          BigDecimal leadCostPerHead, BigDecimal perkCost, BigDecimal platformRate);

    /**
     * 主题定价预览:读该主题生效合作单算阵容与 P_min。
     * 权益成本(perkCost)由服务端从已申报权益汇总,不由前端传入。
     * @param leadCost 带队总成本(guided 票用,self 传0/null)
     * @param teamSize 预计成团人数(guided 摊薄用)
     * @param subType  guided/self
     */
    PricingResult previewTopic(Long topicId, BigDecimal leadCost, Integer teamSize, String subType);

    /**
     * 确认终价并生成双票(报告§5.4 · B2-4)。校验主题在定价态(lifecycle=2)、各票价≥各自底价,
     * 由 finalizeForSale 终检并原子迁移至 lifecycle=3(售票);self 复用现有自玩票(写 cms_topic.selfPlayPrice,不收编),
     * guided 新建/更新 sub_type=guided 的 oms_ticket。不满足抛 IllegalStateException。
     * @param guidedInventory 有人带票库存(null 默认 100);self 走自玩不限
     * @return 1
     */
    int confirmPrice(Long topicId, BigDecimal guidedPrice, BigDecimal selfPrice,
                     Integer guidedInventory, BigDecimal leadCost, Integer teamSize);

    /**
     * 冻结平台侧费率快照(幂等:已冻结则原样返回 0,绝不覆盖)。
     *
     * <p>给**零现金自动开售**路径用。confirmPrice 是 {@code cms_topic.fee_platform_rate} 原本的
     * 唯一写入点,而全 PERK/TRAFFIC 的主题在 {@code processRecruitDeadlines} 里是同一事务内
     * 直接调 finalizeForSale 的 —— 中间没有任何时机能走到 confirmPrice ⇒ 不在这里补冻,
     * finalizeForSale 的 {@code assertFrozenCommercialFacts} 会把**所有收费票**一律拒死,
     * 且异常冲出批处理循环、整轮招募到期扫描中止(只落一行 error 日志)。
     *
     * <p>只冻费率、不做地板价/必亏校验是**成立的**:零现金义务 ⇒ 平台侧成本 C=0 ⇒ 地板价恒为 0,
     * 而必亏闸只对 REVSHARE 有意义,零现金局里恒空转。要收现金义务的局仍必须走 confirmPrice。
     *
     * @return 实际写入行数;已有快照返回 0
     */
    int freezeFeeSnapshot(Long topicId);

    /** 平台佣金率(报告§十待拍板#1 默认 10%) */
    BigDecimal platformRate();

    /** 发起人保底率 = 5% + 2%×min(有真实核销的被邀商家数, 5),封顶 15%。乙-2 定价/结算共用。 */
    BigDecimal initiatorGuaranteeRate(int verifiedMerchantCount);

    /**
     * [P0-28 批1] 按主题**锁价时冻结的快照**取平台抽成率;快照为空(未锁价/存量)回落 live 值。
     * <p><b>结算路径只能用这个,不能用 {@link #platformRate()}</b> —— 后者是「此刻该按多少定价」,
     * 拿它结算 = 调一次费率追溯改写所有已售未结订单 → margin&lt;0 → 拒结悬空。
     */
    BigDecimal platformRateOf(com.chengyinhub.business.domain.CmsTopic topic);

    /**
     * [P0-28 批1] 按主题快照取发起人保底率;快照为空回落 live 值。
     * <p>⚠️ 冻的是公式参数(base/step/max)不是算好的率 —— 已核销商家数只有结算时才知道。
     */
    BigDecimal initiatorGuaranteeRateOf(com.chengyinhub.business.domain.CmsTopic topic, int verifiedMerchantCount);
}
