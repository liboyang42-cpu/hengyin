package com.chengyinhub.business.mapper;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.MerchantPayable;

/**
 * ③ 计酬档平台应付台账 Mapper(施工图 §5 · **append-only 事件流水**)。
 *
 * <p>⚠️ <b>本接口刻意没有 update / delete</b>。台账行记的是「这笔核销发生了,当时费率是 X」——
 * 那是历史事实,永不改。冲正靠 {@link #insertPayable} 追加一条 {@code entry_type='REVERSAL'} 的新行;
 * 生命周期状态机挂在 {@code merchant_payable_batch} 上,不在本表。
 * <b>要在这里加 update 之前,先读 MerchantPayable 的类注释,想清楚你要改的到底是不是历史。</b>
 */
public interface MerchantPayableMapper {

    /**
     * 插一条台账事件(ACCRUAL 计提 / REVERSAL 冲正)。
     *
     * <p>撞 {@code uk_payable_idem} 报 1062 = 这笔核销已经入过账 —— 幂等在库层兜底,
     * 应用层不必先查后insert(那是读-判-写竞态)。
     */
    public int insertPayable(MerchantPayable p);

    /** 按幂等键查;命中 = 这笔已入过账。⚠️ 仅供展示/预判,防重的真防线是 uk_payable_idem */
    public MerchantPayable selectByIdempotencyKey(@Param("key") String key);

    /** 某笔核销产生的所有台账行(计提 + 可能的冲正),按 id 升序 */
    public List<MerchantPayable> selectByRedemption(@Param("redemptionId") Long redemptionId);

    /** 资金读模型按收款商家读取全部不可变账务事实。 */
    public List<MerchantPayable> selectByMerchant(@Param("merchantMemberId") Long merchantMemberId);

    /**
     * 商家某个自然月的应付**净额**。
     *
     * <p>⚠️ <b>SQL 里写的是 SUM(CASE entry_type WHEN 'ACCRUAL' THEN amount ELSE -amount END),
     * 不是 SUM(amount)</b> —— amount 是绝对值,方向由 entry_type 承载。
     * 写成 SUM(amount) 会把冲正算成又欠一笔 = 翻倍多付。
     *
     * @param periodYm 自然月 {@code YYYY-MM}
     * @return 净额;<b>可能为负</b>(本月大量冲正上月已付的账)。null = 该月一行都没有
     */
    public BigDecimal sumNetByMerchantAndPeriod(@Param("merchantMemberId") Long merchantMemberId,
                                                @Param("periodYm") String periodYm);

    /**
     * 某个自然月里有应付发生的所有商家 —— 生成月结批次时用它拿名单。
     *
     * @param periodYm 自然月 {@code YYYY-MM}
     */
    public List<Long> selectMerchantIdsByPeriod(@Param("periodYm") String periodYm);
}
