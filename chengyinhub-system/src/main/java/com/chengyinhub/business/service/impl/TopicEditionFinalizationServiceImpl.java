package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ClubEditionCompensationTerms;
import com.chengyinhub.business.domain.ClubPayable;
import com.chengyinhub.business.domain.ClubPayableBatch;
import com.chengyinhub.business.domain.TopicEditionFinalization;
import com.chengyinhub.business.domain.TopicSalePayeeSnapshot;
import com.chengyinhub.business.mapper.ClubPayableMapper;
import com.chengyinhub.business.service.IClubCorporatePayeeService;
import com.chengyinhub.business.service.IClubEditionCompensationService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ITopicEditionFinalizationService;
import com.chengyinhub.business.service.ITopicEditionFundsService;
import com.chengyinhub.business.service.TopicEditionFundsPhase;
import com.chengyinhub.business.service.support.EditionFinalFactGate;
import com.chengyinhub.business.service.support.FinalCompensationFacts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F3：FINAL 结算 + 俱乐部应付 + 付款批次。
 *
 * <p>FINAL 的事务顺序不可重排：锁 finalization 行 → <b>跑事实门</b>（期已结束 / 退款已落定 /
 * 无未结投诉）→ 读已确认补偿事实 → 读开售冻结的收款人快照 → 写 FINAL 头 → 按 component 追加应付。
 * 任一步失败整体回滚，绝不留下「有 ledger 没 payable」或「付了一半」的中间态。
 *
 * <p>⚠️ <b>仓库里有两个都叫 FINAL 的东西，别混</b>：
 * <ul>
 *   <li><b>本类的 FINAL</b> = {@code topic_edition_finalization}，<b>俱乐部补偿</b>
 *       （F/Q/EXTRA/HEADCOUNT）的终结，
 *       已实现，落 {@code club_payable}；</li>
 *   <li>{@code TopicEditionFundsServiceImpl} 的 FINAL = 期级<b>六量资金快照</b>的 FINAL，
 *       由本类在同一事务内先生产；补偿侧随后只读这份 FINAL（承接费 HEADCOUNT
 *       读它的逐票净票数归因；{@code hasCurrentFinalSnapshot} 靠它的 {@code g_earned} 判本期是否已结清），
 *       绝不拿 PROVISIONAL 猜算。</li>
 * </ul>
 * 两侧现在由同一个事务按「资金 FINAL → 补偿 FINAL」顺序收口；错误码前缀仍分开
 * （{@code EDITION_FINAL_*} vs {@code EDITION_FUNDS_FINAL_*}），便于定位是哪一侧事实不完整。
 */
@Service
public class TopicEditionFinalizationServiceImpl implements ITopicEditionFinalizationService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.mapper.CmsTopicMapper topicMapper;

    private final ClubPayableMapper mapper;
    private final IClubEditionCompensationService compensationService;
    private final IClubCorporatePayeeService payeeService;
    private final IClubService clubService;
    private final ITopicEditionFundsService fundsService;
    private final EditionFinalFactGate factGate;

    public TopicEditionFinalizationServiceImpl(ClubPayableMapper mapper,
                                               IClubEditionCompensationService compensationService,
                                               IClubCorporatePayeeService payeeService,
                                               IClubService clubService,
                                               ITopicEditionFundsService fundsService,
                                               EditionFinalFactGate factGate) {
        this.mapper = mapper;
        this.compensationService = compensationService;
        this.payeeService = payeeService;
        this.clubService = clubService;
        this.fundsService = fundsService;
        this.factGate = factGate;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TopicEditionFinalization finalizeEdition(Long topicId, Date now, Long operatorId) {
        if (topicId == null || now == null || operatorId == null) {
            throw new IllegalArgumentException("EDITION_FINAL_ARGUMENT_REQUIRED");
        }
        lockTopic(topicId);
        // 1. topic 在最外层；随后锁 finalization 行，重放与并发收敛到同一结果。
        TopicEditionFinalization existing = mapper.selectFinalizationForUpdate(topicId);
        if (existing != null) return existing;

        // 2. ★事实门先于任何应付生成：期还没结束、退款没落定、投诉工单没结，一律拒绝终结。
        //    FINAL 一期只能一次、红冲后又只能靠 reaccrue 补救 —— 定责结果晚到的代价太大，
        //    这道门必须在写台账之前（审计 2「先重算、跑事实门和守恒断言，再允许…应付生成」）。
        ClubEditionCompensationTerms terms = compensationService.requireFrozenTerms(topicId);
        factGate.assertFinalizable(topicId, now, terms.getFulfillmentEndAt());

        // 3. 事实门通过后先在同一事务生产不可变六量 FINAL 及逐票金额归因。
        // 这样 F/Q/EXTRA 不会再因为一个仓库内没有任何生产调用方的 FINAL 永久 PENDING。
        fundsService.recompute(topicId, now, "FINAL:" + topicId + ":FUNDS",
                "edition-final:" + operatorId, TopicEditionFundsPhase.FINAL);

        // 4. 已确认的补偿事实；任一维度未决就是 PENDING，不得 FINAL、更不得部分应付。
        FinalCompensationFacts facts = compensationService.computeFinalCompensationFacts(topicId, now);
        if (facts.isPending()) {
            throw new IllegalStateException("EDITION_FINAL_PENDING:" + facts.getPendingReason());
        }

        // 5. 开售冻结的收款人快照。用的是当初那一版，不是最新 profile。
        TopicSalePayeeSnapshot payee = payeeService.requireSnapshotForFinal(topicId, facts.getClubId());

        TopicEditionFinalization finalization = new TopicEditionFinalization();
        finalization.setTopicId(topicId);
        finalization.setClubId(facts.getClubId());
        finalization.setFinalStatus(TopicEditionFinalization.STATUS_FINAL);
        finalization.setFixedCompensation(facts.getFixedCompensation());
        finalization.setQualityAmount(facts.getQuality());
        finalization.setExtraAmount(facts.getExtraHours());
        finalization.setHeadcountAmount(facts.getHeadcount());
        finalization.setPayeeProfileId(payee.getProfileId());
        finalization.setPayeeProfileVersion(payee.getProfileVersion());
        finalization.setPayeeFactsHash(payee.getFactsHash());
        finalization.setIdempotencyKey(finalKey(topicId));
        finalization.setFinalizedBy(operatorId);
        // 6. 唯一键 uk_topic_edition_finalization(topic_id)：并发第二次在这里撞键回滚。
        if (mapper.insertFinalization(finalization) != 1) {
            throw new IllegalStateException("EDITION_FINAL_WRITE_FAILED");
        }

        // 7. 四个 component 各一笔，各自的幂等键；禁止合并成一个总额。
        appendAccrual(finalization, ClubPayable.COMPONENT_FIXED, facts.getFixedCompensation(), operatorId);
        appendAccrual(finalization, ClubPayable.COMPONENT_QUALITY, facts.getQuality(), operatorId);
        appendAccrual(finalization, ClubPayable.COMPONENT_EXTRA, facts.getExtraHours(), operatorId);
        appendAccrual(finalization, ClubPayable.COMPONENT_HEADCOUNT, facts.getHeadcount(), operatorId);
        return finalization;
    }

    /**
     * 红冲之后的 append-only 重计提。
     *
     * <p>为什么必须单独有这条路：{@code finalizeEdition} 见到 FINAL 头就读回原件、永不重跑
     * {@code appendAccrual}；就算绕过去，幂等键恒为 {@code FINAL:topicId} 也会撞
     * {@code uk_club_payable_idem}。所以红冲只能把钱抵成 0，<b>正确金额进不了台账</b> ——
     * 事后才发现的错（Q 被过错排除、EXTRA 更正、F 录错）只剩手工改库一条路。
     *
     * <p>做法：按 component 比「台账净额」与「当下重算出的应付」，差额追加<b>一条新行</b>。
     * FINAL 头是终结快照，不就地 UPDATE（审计 2：「绝不可直接 UPDATE 已终结数字」）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<ClubPayable> reaccrueAfterReversal(Long topicId, Date now, Long operatorId, String reason) {
        if (topicId == null || now == null || operatorId == null || isBlank(reason)) {
            throw new IllegalArgumentException("CLUB_PAYABLE_ARGUMENT_REQUIRED");
        }
        lockTopic(topicId);
        TopicEditionFinalization head = mapper.selectFinalizationForUpdate(topicId);
        if (head == null) throw new IllegalStateException("EDITION_FINAL_NOT_FOUND");

        // 重计提也要过同一道事实门：期没结束/退款没落定/工单没结时改金额，改的是还会再变的数。
        ClubEditionCompensationTerms terms = compensationService.requireFrozenTerms(topicId);
        factGate.assertFinalizable(topicId, now, terms.getFulfillmentEndAt());

        FinalCompensationFacts facts = compensationService.computeFinalCompensationFacts(topicId, now);
        if (facts.isPending()) {
            throw new IllegalStateException("EDITION_FINAL_PENDING:" + facts.getPendingReason());
        }
        if (!head.getClubId().equals(facts.getClubId())) {
            throw new IllegalStateException("EDITION_FINAL_CLUB_CHANGED");
        }
        List<ClubPayable> written = new ArrayList<ClubPayable>();
        appendDelta(head, ClubPayable.COMPONENT_FIXED, facts.getFixedCompensation(), operatorId, reason, written);
        appendDelta(head, ClubPayable.COMPONENT_QUALITY, facts.getQuality(), operatorId, reason, written);
        appendDelta(head, ClubPayable.COMPONENT_EXTRA, facts.getExtraHours(), operatorId, reason, written);
        appendDelta(head, ClubPayable.COMPONENT_HEADCOUNT, facts.getHeadcount(), operatorId, reason, written);
        if (written.isEmpty()) throw new IllegalStateException("CLUB_PAYABLE_NOTHING_TO_REACCRUE");
        return written;
    }

    private void appendDelta(TopicEditionFinalization head, String component, BigDecimal target,
                             Long operatorId, String reason, List<ClubPayable> written) {
        // ★锁定读，不是快照读 —— 与 reversePayable 逐字同一个理由。「读到这批行都还没进批次 →
        //   插差额行」两步之间，并发的 createBatch 能把它们整批挂进批次并提交（差额行是纯 INSERT，
        //   不与它的行锁冲突）⇒ 正额进了付款单、抵消它的负向差额行落在批次之外 ⇒ 多付且零告警。
        //   ⚠️ 上面那把 selectFinalizationForUpdate 的头锁挡不住它：createBatch 全程不碰
        //   topic_edition_finalization。行锁让 attachUnbatchedPayables 阻塞到本事务提交，届时
        //   它的命中数就对不上自己算的 pending.size() ⇒ CLUB_PAYABLE_BATCH_CONTENT_CHANGED 接住。
        List<ClubPayable> rows =
                mapper.selectPayablesByComponentForUpdate(head.getTopicId(), head.getClubId(), component);
        BigDecimal net = BigDecimal.ZERO;
        int revision = 0;
        if (rows != null) {
            for (ClubPayable row : rows) {
                // 已进批次的行不许再被重计提改动：那笔钱要么在付、要么已付，走退款/追偿不是改台账。
                if (row.getBatchId() != null) {
                    throw new IllegalStateException("CLUB_PAYABLE_ALREADY_BATCHED");
                }
                net = net.add(row.getAmount());
                revision++;
            }
        }
        BigDecimal delta = target.subtract(net);
        if (delta.signum() == 0) return;

        ClubPayable row = new ClubPayable();
        row.setTopicId(head.getTopicId());
        row.setClubId(head.getClubId());
        row.setComponent(component);
        row.setEntryType(delta.signum() > 0 ? ClubPayable.ENTRY_ACCRUAL : ClubPayable.ENTRY_REVERSAL);
        row.setAmount(delta);
        // 幂等键带修订序号：重放同一次重计提撞 uk_club_payable_idem，不会写两条。
        row.setIdempotencyKey(finalKey(head.getTopicId()) + "#R" + revision);
        row.setReason(reason);
        row.setCreatedBy(operatorId);
        if (mapper.insertPayable(row) != 1) throw new IllegalStateException("CLUB_PAYABLE_WRITE_FAILED");
        written.add(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubPayable reversePayable(Long topicId, Long payableId, Long operatorId, String reason) {
        if (topicId == null || payableId == null || operatorId == null || isBlank(reason)) {
            throw new IllegalArgumentException("CLUB_PAYABLE_ARGUMENT_REQUIRED");
        }
        lockTopic(topicId);
        // ★锁定读，不是快照读。「读到 batch_id 为空 → 插反向行」两步之间，并发的 createBatch
        //   能把这一行整条挂进批次并提交（反向行是纯 INSERT，不与它的行锁冲突）⇒ 正额进了付款单、
        //   抵消它的反向行落在批次之外 ⇒ 多付一笔且零告警。这与本改动在 upsertPlannedHours 与
        //   CoopComplaintServiceImpl.advance 两处亲自判过不合格的「check-then-act」是同一形状。
        ClubPayable original = mapper.selectPayableByIdForUpdate(payableId);
        if (original == null || !ClubPayable.ENTRY_ACCRUAL.equals(original.getEntryType())) {
            throw new IllegalStateException("CLUB_PAYABLE_ACCRUAL_NOT_FOUND");
        }
        // ★作用域回读：Controller 已用 topicId 过了 canEditTopic，这里再拿台账行自报的 topic_id 对一遍。
        // 少了这一句，一个裸 payableId 就能跨主题写出负向资金行 —— 而 canEditTopic 判的是另一个主题。
        if (!topicId.equals(original.getTopicId())) {
            throw new IllegalStateException("CLUB_PAYABLE_TOPIC_MISMATCH");
        }
        // 已进批次并付掉的行不许再红冲：那要走退款/追偿，不是改台账。
        if (original.getBatchId() != null) throw new IllegalStateException("CLUB_PAYABLE_ALREADY_BATCHED");

        ClubPayable reversal = new ClubPayable();
        reversal.setTopicId(original.getTopicId());
        reversal.setClubId(original.getClubId());
        reversal.setComponent(original.getComponent());
        reversal.setEntryType(ClubPayable.ENTRY_REVERSAL);
        reversal.setAmount(original.getAmount().negate());
        reversal.setReversalOf(payableId);
        reversal.setIdempotencyKey("REVERSAL:" + payableId);
        // ★理由落库，不指望 @Log：本仓 LogAspect 已加固到不写请求体（setOperParam/setJsonResult 全仓 0 命中），
        //   sys_oper_log 只有 title/method/url/操作人/耗时。不落这一列，台账里就只剩一条无因由的负向行，
        //   与审计 2「成本录入/更正 … 必要审计：类别、金额、说明」直接冲突。
        reversal.setReason(reason);
        reversal.setCreatedBy(operatorId);
        if (mapper.insertPayable(reversal) != 1) throw new IllegalStateException("CLUB_PAYABLE_WRITE_FAILED");
        return reversal;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> editionLedgerView(Long topicId) {
        if (topicId == null) throw new IllegalArgumentException("CLUB_PAYABLE_ARGUMENT_REQUIRED");
        List<ClubPayable> payables = mapper.selectPayablesByTopic(topicId);
        if (payables == null) payables = new ArrayList<ClubPayable>();
        List<ClubPayableBatch> batches = mapper.selectBatchesByTopic(topicId);
        if (batches == null) batches = new ArrayList<ClubPayableBatch>();
        // ★逐行回读：SQL 已按 topic_id 收窄，这里再核一遍它真的照做了。
        //   哪天谁把那个谓词改丢，别的期的资金行会出现在这一期的操作面上，而操作者据此挑 id 去动钱。
        for (ClubPayable row : payables) {
            if (!topicId.equals(row.getTopicId())) throw new IllegalStateException("CLUB_PAYABLE_EDITION_LEAK");
        }
        for (ClubPayableBatch batch : batches) {
            if (!topicId.equals(batch.getTopicId())) throw new IllegalStateException("CLUB_PAYABLE_EDITION_LEAK");
        }
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("finalization", mapper.selectFinalization(topicId));
        view.put("payables", payables);
        view.put("batches", batches);
        view.put("separation", separationReadiness());
        return view;
    }

    /** 确认动作的权限位。CAS/CHECK 认的是 user_id，这两个串只用来回答「谁按得动」。 */
    static final String PERM_CONFIRM = "business:clubpayable:confirm";
    /** 付款登记动作的权限位。 */
    static final String PERM_PAID = "business:clubpayable:paid";

    /**
     * 双人分离在<b>本环境</b>是否真按得动 —— 显式降级状态，不是放宽。
     *
     * <p>「确认人 ≠ 付款登记人」三层闸（DB CHECK / SQL CAS / Java）一步不动：它是审计 2 P0-9
     * 明令要的形状。但既有裁决是「后台只用超管单账号、四角色不建号」，两个动作只能同一个
     * {@code userId=1} 去点 ⇒ 第一笔真实对公打款登记必然被拒，而在此之前系统里<b>没有任何一处</b>
     * 说明「本环境降级为单人确认」——操作者看到的只是一个点不动的按钮。审计 2 逐字要求：
     * 「若环境尚无法提供第二账号，必须以配置化降级状态显式显示，不能偷偷放弃复核」。
     *
     * <p>判据是「存在两个不同的账号分别持这两条权限」，不是「两条权限各有人」——
     * 两条都落在同一个人身上时 CAS 照样命中 0 行。
     */
    private Map<String, Object> separationReadiness() {
        List<Long> confirmHolders = nonNull(mapper.selectPermHolderUserIds(PERM_CONFIRM));
        List<Long> paidHolders = nonNull(mapper.selectPermHolderUserIds(PERM_PAID));
        boolean separable = false;
        for (Long confirmer : confirmHolders) {
            for (Long payer : paidHolders) {
                if (confirmer != null && payer != null && !confirmer.equals(payer)) separable = true;
            }
        }
        Map<String, Object> separation = new LinkedHashMap<String, Object>();
        separation.put("degraded", !separable);
        separation.put("confirmPerm", PERM_CONFIRM);
        separation.put("paidPerm", PERM_PAID);
        separation.put("confirmHolders", confirmHolders.size());
        separation.put("paidHolders", paidHolders.size());
        separation.put("reason", separable ? "" : degradedReason(confirmHolders, paidHolders));
        separation.put("deploymentAction", separable ? ""
                : "上线前置动作：为 cy_finance 与 cy_operator 各建一个非超管 sys_user，"
                + "落库后回读 sys_user_role 确认两人分别持 " + PERM_CONFIRM + " 与 " + PERM_PAID
                + "。在此之前不要把「登记付款」当成坏按钮 —— 复核闸没有被放弃。");
        return separation;
    }

    private String degradedReason(List<Long> confirmHolders, List<Long> paidHolders) {
        if (confirmHolders.isEmpty() && paidHolders.isEmpty()) {
            return "本环境没有任何非超管账号持 " + PERM_CONFIRM + " 或 " + PERM_PAID
                    + "：两个动作只能由超管同一个人点，而确认人 ≠ 付款登记人是 DB 级约束。";
        }
        if (confirmHolders.isEmpty()) {
            return "没有非超管账号持 " + PERM_CONFIRM + "（" + PERM_PAID + " 有 " + paidHolders.size() + " 个）。";
        }
        if (paidHolders.isEmpty()) {
            return "没有非超管账号持 " + PERM_PAID + "（" + PERM_CONFIRM + " 有 " + confirmHolders.size() + " 个）。";
        }
        return "两条权限都落在同一个账号上：确认与付款登记必须是不同的两个人。";
    }

    private static <T> List<T> nonNull(List<T> rows) {
        return rows == null ? new ArrayList<T>() : rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubPayableBatch createBatch(Long topicId, Long clubId, Long operatorId) {
        if (topicId == null || clubId == null || operatorId == null) {
            throw new IllegalArgumentException("CLUB_PAYABLE_ARGUMENT_REQUIRED");
        }
        lockTopic(topicId);
        // 归属回读：clubId 必须是一个存在且未删除的俱乐部（selectClubById 自带 del_flag = 0）。
        // 拿一个不存在的 clubId 建批次 = 造一张没人认领的付款单据。
        if (clubService.selectClubById(clubId) == null) {
            throw new IllegalStateException("CLUB_PAYABLE_CLUB_UNKNOWN");
        }
        // ★一期一批次（E4）：只收这一期的行。少了 topic 维，两期两份合同的钱会并进同一张发票，
        //   而且给期 B 建批次会把期 A 的行一起挂上 batch_id ⇒ 期 A 的红冲能力被连带锁死
        //   （reversePayable 撞 CLUB_PAYABLE_ALREADY_BATCHED），操作者还没有任何提示。
        List<ClubPayable> pending = mapper.selectUnbatchedPayables(topicId, clubId);
        if (pending == null || pending.isEmpty()) throw new IllegalStateException("CLUB_PAYABLE_NOTHING_TO_BATCH");
        BigDecimal total = BigDecimal.ZERO;
        for (ClubPayable row : pending) {
            // ★作用域回读：SQL 里已经带了 topic_id/club_id 谓词，这里再核一遍它真的照做了。
            // 哪天谁把那两个 where 改丢，别的俱乐部/别的期的应付行会被卷进这张批次 —— 这句是那道负控。
            if (!clubId.equals(row.getClubId())) throw new IllegalStateException("CLUB_PAYABLE_SCOPE_LEAK");
            if (!topicId.equals(row.getTopicId())) throw new IllegalStateException("CLUB_PAYABLE_EDITION_LEAK");
            total = total.add(row.getAmount());
        }
        // 合计 ≤0 的批次不该存在：那是红冲把正向行抵消干净了，该走对账不是开付款单。
        if (total.signum() <= 0) throw new IllegalStateException("CLUB_PAYABLE_BATCH_TOTAL_NOT_POSITIVE");

        ClubPayableBatch batch = new ClubPayableBatch();
        batch.setBatchNo("CPB" + topicId + "-" + clubId + "-" + pending.get(pending.size() - 1).getId());
        batch.setClubId(clubId);
        batch.setTopicId(topicId);
        batch.setTotalAmount(total);
        batch.setBatchStatus(ClubPayableBatch.STATUS_PENDING);
        // 发票轴独立起步：新批次「一张票都还没开」，与付款状态互不影响。
        batch.setInvoiceStatus(ClubPayableBatch.INVOICE_NONE);
        if (mapper.insertBatch(batch) != 1) throw new IllegalStateException("CLUB_PAYABLE_BATCH_WRITE_FAILED");
        // 只挂仍未进批次的行；并发建批次时第二个批次挂到 0 行，总额与实际不符即回滚。
        if (mapper.attachUnbatchedPayables(batch.getId(), topicId, clubId) != pending.size()) {
            throw new IllegalStateException("CLUB_PAYABLE_BATCH_CONTENT_CHANGED");
        }
        return batch;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubPayableBatch markBatchInvoiceIssued(Long topicId, Long batchId, String invoiceNo, Long operatorId) {
        if (topicId == null || batchId == null || operatorId == null || isBlank(invoiceNo)) {
            throw new IllegalArgumentException("CLUB_PAYABLE_ARGUMENT_REQUIRED");
        }
        requireBatchOfEdition(topicId, batchId);
        // CAS 条件 invoice_status = 'NONE' 在 XML 里：重复开票拿不到行。
        // ★不看付款状态：「已打款但未开票」「已开票但没打款」都是合法状态，两条轴互不干涉。
        if (mapper.markBatchInvoiceIssued(batchId, invoiceNo) != 1) {
            throw new IllegalStateException("CLUB_PAYABLE_BATCH_INVOICE_CONFLICT");
        }
        return mapper.selectBatch(batchId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubPayableBatch markBatchInvoiceRed(Long topicId, Long batchId, String redInvoiceNo, Long operatorId) {
        if (topicId == null || batchId == null || operatorId == null || isBlank(redInvoiceNo)) {
            throw new IllegalArgumentException("CLUB_PAYABLE_ARGUMENT_REQUIRED");
        }
        requireBatchOfEdition(topicId, batchId);
        // CAS 条件 invoice_status = 'ISSUED'：没开过票就红冲拿不到行。
        if (mapper.markBatchInvoiceRed(batchId, redInvoiceNo) != 1) {
            throw new IllegalStateException("CLUB_PAYABLE_BATCH_RED_INVOICE_CONFLICT");
        }
        return mapper.selectBatch(batchId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubPayableBatch confirmBatch(Long topicId, Long batchId, Long operatorId) {
        if (topicId == null || batchId == null || operatorId == null) {
            throw new IllegalArgumentException("CLUB_PAYABLE_ARGUMENT_REQUIRED");
        }
        requireBatchOfEdition(topicId, batchId);
        if (mapper.confirmBatchCas(batchId, operatorId, new Date()) != 1) {
            throw new IllegalStateException("CLUB_PAYABLE_BATCH_CONFIRM_CONFLICT");
        }
        return mapper.selectBatch(batchId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubPayableBatch markBatchPaid(Long topicId, Long batchId, Long operatorId, String paymentReference) {
        if (topicId == null || batchId == null || operatorId == null || isBlank(paymentReference)) {
            throw new IllegalArgumentException("CLUB_PAYABLE_ARGUMENT_REQUIRED");
        }
        ClubPayableBatch batch = requireBatchOfEdition(topicId, batchId);
        if (!ClubPayableBatch.STATUS_CONFIRMED.equals(batch.getBatchStatus())) {
            throw new IllegalStateException("CLUB_PAYABLE_BATCH_NOT_CONFIRMED");
        }
        // ★确认人 ≠ 付款登记人。SQL 的 CAS 与 DB CHECK 各自还有一道。
        // 写回只放稳定机器码，确保不被 GlobalExceptionHandler 的 120 字符闸改成「系统繁忙」。
        // 「为什么按不动 + 怎么修」已由 editionLedgerView.separation 结构化读面承载。
        if (operatorId.equals(batch.getConfirmedBy())) {
            throw new IllegalStateException("CLUB_PAYABLE_BATCH_SEPARATION_REQUIRED");
        }
        if (mapper.markBatchPaidCas(batchId, operatorId, new Date(), paymentReference) != 1) {
            throw new IllegalStateException("CLUB_PAYABLE_BATCH_PAID_CONFLICT");
        }
        return mapper.selectBatch(batchId);
    }

    /**
     * ★批次的对象级归属回读：批次自报的 {@code topic_id} 必须等于路径上那一期。
     *
     * <p>与 {@link #reversePayable} 的 {@code CLUB_PAYABLE_TOPIC_MISMATCH} 同形，理由也一样：
     * 四条 CAS 的 {@code where} 只有 {@code id}，授权那一层判的是路径上的 topic ——
     * 少了这一句，一个裸 batchId 就能把<b>另一期</b>的批次确认掉 / 登记付款 / 开票。
     * {@code club_payable_batch.topic_id} 本轮已经是非空列，所以这不是「表达不了」，是「有列要用」。
     */
    private ClubPayableBatch requireBatchOfEdition(Long topicId, Long batchId) {
        lockTopic(topicId);
        ClubPayableBatch batch = mapper.selectBatch(batchId);
        if (batch == null) throw new IllegalStateException("CLUB_PAYABLE_BATCH_NOT_FOUND");
        if (!topicId.equals(batch.getTopicId())) {
            throw new IllegalStateException("CLUB_PAYABLE_BATCH_TOPIC_MISMATCH");
        }
        return batch;
    }

    private void appendAccrual(TopicEditionFinalization finalization, String component,
                               BigDecimal amount, Long operatorId) {
        if (amount == null || amount.signum() < 0) {
            throw new IllegalStateException("CLUB_PAYABLE_AMOUNT_INVALID:" + component);
        }
        ClubPayable payable = new ClubPayable();
        payable.setTopicId(finalization.getTopicId());
        payable.setClubId(finalization.getClubId());
        payable.setComponent(component);
        payable.setEntryType(ClubPayable.ENTRY_ACCRUAL);
        payable.setAmount(amount);
        payable.setIdempotencyKey(finalKey(finalization.getTopicId()));
        payable.setCreatedBy(operatorId);
        if (mapper.insertPayable(payable) != 1) throw new IllegalStateException("CLUB_PAYABLE_WRITE_FAILED");
    }

    private static String finalKey(Long topicId) {
        return "FINAL:" + topicId;
    }

    private void lockTopic(Long topicId) {
        com.chengyinhub.business.domain.CmsTopic topic = topicMapper.selectCmsTopicByIdForUpdate(topicId);
        if (topic == null || !topicId.equals(topic.getId())) {
            throw new IllegalStateException("EDITION_FINAL_TOPIC_NOT_FOUND");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
