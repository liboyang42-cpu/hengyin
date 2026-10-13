package com.chengyinhub.business.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.MerchantPayableBatch;

/**
 * ③ 计酬档应付月结批次 Mapper。
 *
 * <p>与 {@link MerchantPayableMapper} 的分工:那张表是**不可变事件流水**,本表是**可变生命周期**
 * (待结 → 对账确认 → 已打款)。所以 update 只出现在这里,且都是**条件置位(CAS)**,不是无条件改。
 */
public interface MerchantPayableBatchMapper {

    /**
     * 生成批次。撞 {@code uk_batch_merchant_period} 报 1062 = 该商家该月批次已生成
     * ⇒ 重复生成在库层被拦住,不会把同一个月结出两张单。
     */
    public int insertBatch(MerchantPayableBatch b);

    public MerchantPayableBatch selectById(@Param("id") Long id);

    public MerchantPayableBatch selectByMerchantAndPeriod(@Param("merchantMemberId") Long merchantMemberId,
                                                          @Param("periodYm") String periodYm);

    /** 资金读模型按商家读取批次；归属条件必须在 SQL 内。 */
    public List<MerchantPayableBatch> selectByMerchant(@Param("merchantMemberId") Long merchantMemberId);

    /** 批次详情的归属检查必须与读取合并为一条 SQL，避免先列举后挑选。 */
    public MerchantPayableBatch selectByMerchantAndId(@Param("merchantMemberId") Long merchantMemberId,
                                                      @Param("id") Long id);

    /**
     * 按被冲那笔核销的 {@code create_time} 所属自然月定位批次 —— period 用 SQL {@code date_format} 算,
     * 与 {@code MerchantPayableMapper.selectMerchantIdsByPeriod} / {@code sumNetByMerchantAndPeriod}
     * 的分桶口径<b>同源</b>,规避 Java(SimpleDateFormat + JVM 时区)对同一时刻算出与 SQL(MySQL 会话
     * 时区)不同的月、进而漏掉已开票批次的红字发票提醒。
     */
    public MerchantPayableBatch selectByMerchantAndAccrualTime(@Param("merchantMemberId") Long merchantMemberId,
                                                               @Param("createTime") java.util.Date createTime);

    public List<MerchantPayableBatch> selectByPeriod(@Param("periodYm") String periodYm);

    /**
     * 对账确认:PENDING → CONFIRMED。
     *
     * <p>⚠️ <b>带 status='PENDING' 条件</b>,返回 0 = 该批次已不是待结态(已确认过 / 已打款)。
     * 无条件 update 会让「已打款」被打回「已确认」,把打款留痕冲掉。
     */
    public int confirm(@Param("id") Long id, @Param("confirmedBy") String confirmedBy);

    /**
     * 打款置位:CONFIRMED → PAID。
     *
     * <p>⚠️ <b>带 status='CONFIRMED' 条件</b>,返回 0 = 没对完账就想打款,或已经打过了。
     * 这道 CAS 是「先对账再打款」的唯一强制点 —— 拆了它,PENDING 可以直接跳到 PAID。
     */
    public int markPaid(@Param("id") Long id, @Param("paidBy") String paidBy,
                        @Param("payVoucherNo") String payVoucherNo);

    /**
     * 开蓝票:NONE → ISSUED。
     *
     * <p>⚠️ 带 invoice_status='NONE' 条件,返回 0 = 已开过票(或已红冲)。
     * 发票轴与 {@code status} 轴独立 —— 开票不要求已打款,打款也不要求已开票。
     */
    public int markInvoiceIssued(@Param("id") Long id, @Param("invoiceNo") String invoiceNo);

    /**
     * 红冲:ISSUED → RED。
     *
     * <p>⚠️ 带 invoice_status='ISSUED' 条件:没开过蓝票的批次谈不上红冲。
     * 触发时机见 {@code MerchantPayable} 的冲正路径 —— 已开票批次里的核销被撤销时,
     * 光追加 REVERSAL 台账行不够,税务上必须有红字发票对应。
     */
    public int markInvoiceRed(@Param("id") Long id, @Param("redInvoiceNo") String redInvoiceNo);

    /**
     * M块违规倒扣:该商家「下一个未打款批次」—— status 为 PENDING/CONFIRMED 中
     * period_ym 最小(最近将要打款)的一个。{@code uk_batch_merchant_period}
     * 保证同一商家同一自然月只有一个批次,故按 period_ym 排序天然无歧义。
     *
     * @return 无未来可冲抵批次时返回 null(调用方应转走 BALANCE 兜底路径)
     */
    public MerchantPayableBatch selectNextUnpaidBatch(@Param("merchantMemberId") Long merchantMemberId);

    /**
     * M块违规倒扣:直接从目标批次的 amount_total 里扣减(CAS)。
     *
     * <p>⚠️ 扣减后金额可能变负(留痕说明扣了什么),不做同步防负校验 —— 与
     * {@code MerchantReferralBountyService.clawbackBalance} 同一处理原则,
     * 由对账脚本兜底发现。
     *
     * @return 0 = 该批次已不是 PENDING/CONFIRMED(执行前已被打款/流转,须转走人工复核别的路径)
     */
    public int deductForClawback(@Param("id") Long id, @Param("amount") java.math.BigDecimal amount);

    /**
     * P块冻结:仅未冻结的批次可冻结(CAS)。
     *
     * @return 0 = 已在冻结中(重复点击)
     */
    public int freeze(MerchantPayableBatch b);

    /**
     * P块解冻:仅冻结中的批次可解冻(CAS)。
     *
     * @return 0 = 本就未冻结
     */
    public int unfreeze(@Param("id") Long id);
}
