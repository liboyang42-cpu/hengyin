package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * ③ 计酬档平台应付台账 merchant_payable(施工图 §5.2 · **不可变事件流水**)。
 *
 * <p><b>它记的是什么</b>:③-自营 的票款全额归平台,平台按核销给商家付固定人头费 ¥X/人
 * ⇒ 这笔钱是平台的**采购成本**,走「应付台账 → 对公打款 → 商家开票」,
 * <b>不是分账、不碰二清、不经 settlement_detail</b>(D21)。
 *
 * <p><b>为什么必须存费率快照,不能 join 现算</b>:redemption 表没有金额快照,它有 offer_id,
 * 看起来应付额可以 join {@code merchant_chapter_offer.per_head_fee} 现算 —— <b>不行</b>:
 * 商家把人头费从 ¥5 改成 ¥50,历史上所有已核销的应付被**追溯改写**。
 * 与 P0-28「拍板4:分账与定价同冻结,不被 live 费率追溯改写」是同一个病,本项目已经栽过一次。
 * <b>本表的核心价值就是「核销那一刻的费率快照」,不是存 join 结果。</b>
 *
 * <p>⚠️ <b>append-only:只 insert,永不 update</b>。冲正走 {@code entry_type='REVERSAL'} 的**新行**,
 * 绝不改原行。生命周期状态机(待结/对账确认/已打款)挂在 {@link MerchantPayableBatch} 上,
 * 不在本表 —— 同一行不可能既不可变又流转状态。
 *
 * <p>⚠️ <b>净额 = SUM(CASE entry_type WHEN 'ACCRUAL' THEN amount ELSE -amount END)</b>,
 * <b>不是 SUM(amount)</b>。{@link #amount} 是绝对值,方向由 {@link #entryType} 单独承载。
 * 写成 SUM(amount) 会把冲正算成又欠一笔 = 翻倍多付。
 *
 * <p>⚠️ <b>{@code createTime}(继承自 BaseEntity)兼作批次归属依据</b>:
 * 批次 = (merchantMemberId, createTime 所在自然月)。刻意不落 batch_id —— 落了就要在生成批次时
 * UPDATE 台账行,破坏 append-only。推导之所以稳:月结 = 次月结上月 ⇒ 结算时该月已过完
 * ⇒ 不会再有新行落进那个月。<b>若将来改成月中就能结,这个推导立刻不成立</b>,那时必须回头加 batch_id。
 */
public class MerchantPayable extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** 计提 —— 核销即计提(2026-07-17 拍板)。方向:平台欠商家 */
    public static final String ENTRY_ACCRUAL = "ACCRUAL";
    /** 冲正 —— 撤销核销。方向:反向抵消。⚠️ 追加新行,绝不改原行 */
    public static final String ENTRY_REVERSAL = "REVERSAL";

    private Long id;

    /** 合同四原子归属；ACCRUAL/REVERSAL 必须引用同一个 claim。 */
    private Long claimId;

    /** ORIGIN 或不可变业务调整事件 id。 */
    private String eventKey;

    /** 回指核销流水 redemption.id;REVERSAL 行指向**被冲的那笔核销**(不是新造一个 id) */
    private Long redemptionId;

    private Long topicId;

    /** 章节id。③ 必须非空 —— 一票跨 N 章,**章才是应付的粒度** */
    private Long chapterId;

    /** 收款商家 memberId(供应商)。⚠️是 memberId 不是 mms_merchant.id,与 redemption.merchantId 同口径 */
    private Long merchantMemberId;

    /** ★核销那一刻冻结的 ¥X/人。绝不 join merchant_chapter_offer 现算(见类注释) */
    private BigDecimal perHeadFeeSnapshot;

    /**
     * ★<b>绝对值,不带正负</b> —— 方向由 {@link #entryType} 承载(§4.1 竞品实证的可移植范式)。
     * MVP 形态下 == {@link #perHeadFeeSnapshot}(固定人头费 × 1 人次:一笔核销 = 一个玩家在一家店一章)。
     * 两列都留:快照是**费率**(审计留痕),amount 是**算出来的应付额**。
     */
    private BigDecimal amount;

    /** ACCRUAL 计提(核销即计提,2026-07-17 拍板)/ REVERSAL 冲正(撤销核销) */
    private String entryType;

    /** accrual:{redemptionId} / reversal:{redemptionId}。同一笔核销重复入账被 uk_payable_idem 拦住 */
    private String idempotencyKey;

    public Long getId() {
        return id;
    }

    public Long getClaimId() { return claimId; }
    public void setClaimId(Long claimId) { this.claimId = claimId; }
    public String getEventKey() { return eventKey; }
    public void setEventKey(String eventKey) { this.eventKey = eventKey; }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRedemptionId() {
        return redemptionId;
    }

    public void setRedemptionId(Long redemptionId) {
        this.redemptionId = redemptionId;
    }

    public Long getTopicId() {
        return topicId;
    }

    public void setTopicId(Long topicId) {
        this.topicId = topicId;
    }

    public Long getChapterId() {
        return chapterId;
    }

    public void setChapterId(Long chapterId) {
        this.chapterId = chapterId;
    }

    public Long getMerchantMemberId() {
        return merchantMemberId;
    }

    public void setMerchantMemberId(Long merchantMemberId) {
        this.merchantMemberId = merchantMemberId;
    }

    public BigDecimal getPerHeadFeeSnapshot() {
        return perHeadFeeSnapshot;
    }

    public void setPerHeadFeeSnapshot(BigDecimal perHeadFeeSnapshot) {
        this.perHeadFeeSnapshot = perHeadFeeSnapshot;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getEntryType() {
        return entryType;
    }

    public void setEntryType(String entryType) {
        this.entryType = entryType;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
