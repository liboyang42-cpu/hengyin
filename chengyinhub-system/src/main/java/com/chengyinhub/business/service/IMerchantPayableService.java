package com.chengyinhub.business.service;

import java.math.BigDecimal;
import com.chengyinhub.business.domain.MerchantPayableBatch;

import java.util.List;

/**
 * ③ 计酬档平台应付台账(施工图 §5)。
 *
 * <p><b>它解决什么</b>:③-自营 的票款全额归平台,平台按核销给商家付固定人头费 ¥X/人。
 * 在此之前,核销成功、{@code redemption} 有流水,但**没有任何地方记着平台欠这家店多少钱**,且零告警。
 *
 * <p><b>三条不能碰的边界</b>(每条都是本项目真栽过的坑):
 * <ul>
 *   <li><b>别新造第三个打款方</b> —— PR#78 刚把打款能力收口到系统1。本服务**只记账,不打款**,
 *       更不许碰 {@code addBalance}:商家在 ③ 里是**供应商**,走对公打款 + 开票,不是余额。</li>
 *   <li><b>别复用 {@code settlement_detail}</b> —— 它的模型是 {@code Σ明细 == gross},
 *       而 per_head_fee 与票价无关且可能 > gross,硬塞会让 merchantShare 被兜底夹到 0 = 账算错。</li>
 *   <li><b>别现算费率</b> —— 必须用核销那一刻的快照。商家改价会追溯改写历史应付(P0-28 同型病)。</li>
 * </ul>
 */
public interface IMerchantPayableService {

    /**
     * 核销 → 计提一笔平台应付(2026-07-17 拍板:核销即计提,无冷静期)。
     *
     * <p><b>两道闸,缺一不可</b>(施工图 §5.3):
     * <ul>
     *   <li>{@code TermsMode.platformPays(offer.termsMode)} —— 只有计酬档要平台掏钱;
     *       权益档零现金、引流档零结算,它们**正常不入账**,不是漏了;</li>
     *   <li>{@code isPlatformOwned(topicId)} —— D21:计酬档 MVP **仅 ③-自营**;
     *       ③-第三方 的计酬 = 第三方间分钱 = 二清,必须等收付通(P0-43)。</li>
     * </ul>
     *
     * <p>⚠️ <b>必须在核销的同一事务内调用</b>。分开写 = 核销提交了、应付没落 = 又一个丢账口。
     * 本方法是 {@code @Transactional(REQUIRED)},由调用方的事务带着走。
     *
     * <p>⚠️ <b>幂等</b>:同一 redemptionId 重复计提直接返回。这条不是可选的 ——
     * {@code redeemChapter} 的请求级幂等命中时会**带着原 redemptionId 早返回**,
     * 于是重试会拿着同一个 id 走到这里;没有这层就会撞 uk_payable_idem 报 1062 ⇒
     * 整个事务回滚 ⇒ 把本该成功的幂等重试变成 500。
     *
     * @param redemptionId {@code redemption.id};null 直接返回
     */
    void accrueOnRedemption(Long redemptionId);

    /**
     * 撤销核销 → 冲正(施工图 §5.4)。
     *
     * <p><b>追加一条 {@code entry_type='REVERSAL'} 的新行,金额用被冲那笔的快照值</b>
     * (不是 live 值 —— 商家中途改价不该影响冲正的金额)。⚠️ <b>绝不改原行</b>:
     * 原行记的是「这笔核销当时发生过、费率是 X」,那是历史事实。
     *
     * <p>⚠️ 已开票批次的冲正在税务上必须走**红字发票**。本方法只负责把账冲平并把这件事
     * 记成 warn,**不实现税务对接** —— 红字发票由运营人工处理并回填。
     *
     * @param redemptionId 被撤销的 {@code redemption.id};null 直接返回
     */
    void reverseOnRedemption(Long redemptionId);

    /**
     * 商家某自然月的应付净额。
     *
     * <p>⚠️ 净额 = ΣACCRUAL − ΣREVERSAL,<b>可能为负</b>(本月冲正了上月已付的账)。
     * 别夹到 0 —— 那会把账做平成假的。
     *
     * @param periodYm 自然月 {@code YYYY-MM}
     * @return 净额;该月无台账时返 {@link BigDecimal#ZERO}
     */
    BigDecimal netPayable(Long merchantMemberId, String periodYm);

    /**
     * 生成某自然月的月结批次(每个有应付发生的商家一张),返回生成的批次。
     *
     * <p><b>由运营在后台发起,不挂定时任务</b>:打款本来就是人工动作(对公打款 + 商家开票),
     * 定时生成一批没人处置的批次没有意义;且本项目反复栽在「任务没注册 = 静默不跑、零告警」上。
     *
     * <p>⚠️ <b>只能结已经过完的月份</b>。批次成员是由 (商家, create_time 所在自然月) 推导的,
     * 月中生成会让之后落进该月的台账行**无处可去**(它们不属于任何批次,永远不会被打款,且零告警)。
     * 本方法对「结当月或未来月」直接拒。
     *
     * <p>⚠️ 重复生成由 {@code uk_batch_merchant_period} 在库层拦住,已生成的商家会被跳过。
     *
     * @param periodYm 自然月 {@code YYYY-MM}
     * @return 本次新生成的批次;已存在的不在其中
     */
    List<MerchantPayableBatch> generateMonthlyBatches(String periodYm);

    /** 某自然月的全部批次 */
    List<MerchantPayableBatch> listBatches(String periodYm);

    /**
     * 对账确认:PENDING → CONFIRMED。
     *
     * @return false = 该批次已不是待结态(已确认过 / 已打款)。⚠️ <b>不是「没事发生」,
     *         是「你以为的前置状态不成立」</b> —— 调用方必须当失败报给用户,别静默当成功。
     */
    boolean confirmBatch(Long batchId, String operator);

    /**
     * 记录**打款事实**:CONFIRMED → PAID。
     *
     * <p>⚠️ <b>这不是「打款」,是「记录钱已经打出去了」</b>。转账在对公网银;本服务只记账。
     * 在这里加 addBalance 就是新造第三个打款方 —— PR#78 刚根治掉的病。
     *
     * <p>★双人复核:打款人必须≠对账确认人(maker≠checker,与 RefundServiceImpl/
     * CashWithdrawServiceImpl 的既有模式同型),否则单个后台账号可以自审自批把钱打出去。
     *
     * @return false = 没对账就想记打款,或已经记过了,或该批次已被冻结
     * @throws IllegalStateException 打款人与对账确认人为同一人
     */
    boolean markBatchPaid(Long batchId, String operator, String payVoucherNo);

    /** 记录已开蓝票:NONE → ISSUED。发票轴与打款轴独立。@return false = 已开过票或已红冲 */
    boolean markBatchInvoiceIssued(Long batchId, String invoiceNo);

    /** 记录已红冲:ISSUED → RED。@return false = 没开过蓝票(谈不上红冲) */
    boolean markBatchInvoiceRed(Long batchId, String redInvoiceNo);

    /**
     * P块冻结:暂停该批次的对账确认/打款,不移动任何资金。
     * @return false = 已在冻结中(重复操作)
     * @throws IllegalStateException 批次不存在
     */
    boolean freezeBatch(Long batchId, String operator, String reason);

    /**
     * P块解冻:恢复该批次的对账确认/打款。
     * @return false = 本就未冻结
     */
    boolean unfreezeBatch(Long batchId, String operator);
}
