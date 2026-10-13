package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ClubEditionCompensationTerms;
import com.chengyinhub.business.domain.ClubEditionHours;
import com.chengyinhub.business.domain.ClubEditionHoursCorrection;
import com.chengyinhub.business.domain.ClubExtraHoursApproval;
import com.chengyinhub.business.domain.ClubQualityDecision;
import com.chengyinhub.business.domain.TopicEditionFundsAttribution;
import com.chengyinhub.business.mapper.ClubEditionCompensationMapper;
import com.chengyinhub.business.service.IClubEditionCompensationService;
import com.chengyinhub.business.service.support.CanonicalDigest;
import com.chengyinhub.business.service.support.ExtraApproverReachability;
import com.chengyinhub.business.service.support.FinalCompensationFacts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/**
 * A1 Q + A2 EXTRA_HOURS。三条纪律贯穿全类：
 *
 * <ol>
 *   <li>开售冻结的条款是唯一权威（rate=40、每维 200、证据截止，
 *       以及 2026-08-25 加入的 HEADCOUNT 单价与封顶），FINAL 绝不回读最新配置；</li>
 *   <li>执行俱乐部只认冻结条款里的那一个，请求体里的 clubId 不自证；</li>
 *   <li>缺事实一律 PENDING 或抛错，绝不把「没查到」当成 0 通过 / 满额发放。</li>
 * </ol>
 */
@Service
public class ClubEditionCompensationServiceImpl implements IClubEditionCompensationService {

    /** A2 单价：40 元 / 人小时，开售冻结。 */
    private static final BigDecimal EXTRA_HOUR_RATE = new BigDecimal("40");
    /** A1 每维金额：0 或 200，没有中间值。 */
    private static final BigDecimal QUALITY_DIMENSION_AMOUNT = new BigDecimal("200");
    /**
     * F 的绝对下限：¥2,000／期。
     *
     * <p>2026-08-25 起是<b>常量</b>下界。原先是 {@code max(2000, 40×ΣH_plan)}，
     * 工时那一项随自报工时退役一起删了 —— 停掉写入之后再拿它反推资金闸，
     * 等于拿一个没人维护的字段当闸。
     */
    private static final BigDecimal MINIMUM_FIXED_COMPENSATION = new BigDecimal("2000");
    private static final Set<String> HOUR_KINDS =
            new HashSet<String>(Arrays.asList(ClubEditionCompensationTerms.HOUR_KINDS));
    private static final Set<String> QUALITY_DIMENSIONS =
            new HashSet<String>(Arrays.asList(ClubEditionCompensationTerms.QUALITY_DIMENSIONS));
    /** 更正链上溯的最大跳数；越界即视为数据异常，该链不产生额度。 */
    private static final int MAX_CORRECTION_CHAIN = 64;
    /** 契约只认这两种归责：经证明由商家或平台造成的过错才排除计酬。 */
    private static final Set<String> FAULT_PARTIES =
            new HashSet<String>(Arrays.asList("MERCHANT", "PLATFORM"));

    private final ClubEditionCompensationMapper mapper;
    private final ExtraApproverReachability approverReachability;

    public ClubEditionCompensationServiceImpl(ClubEditionCompensationMapper mapper,
                                              ExtraApproverReachability approverReachability) {
        this.mapper = mapper;
        this.approverReachability = approverReachability;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubEditionCompensationTerms freezeTermsForSale(Long topicId, Long clubId, Date frozenAt,
                                                           Date evidenceDueAt, Date fulfillmentEndAt) {
        if (topicId == null || clubId == null || frozenAt == null || evidenceDueAt == null
                || fulfillmentEndAt == null) {
            throw new IllegalArgumentException("EDITION_TERMS_ARGUMENT_REQUIRED");
        }
        ClubEditionCompensationTerms existing = mapper.selectTerms(topicId);
        // 幂等重试读回原条款：开售冻结的证据截止与费率一旦写下就不许被后来的调用改写。
        if (existing != null) return existing;

        // ★2026-08-25 拆掉「工时决定保底下限」这层耦合：
        //   原来这里要求四类 H_plan 全部登记，并用 F_contract = max(¥2,000, ¥40×ΣH_plan) 卡开售。
        //   自报工时整条退役之后，工时已经不参与任何结算 —— 再留着这层耦合，后果是
        //   「后台把计划工时填高一点，这一期就开不了售」，而且是开售当场才炸的错，最难查。
        //   现在 H_plan 只是记录字段，不再是开售前置，也不再决定 F 的下界。
        ClubEditionCompensationTerms terms = new ClubEditionCompensationTerms();
        terms.setTopicId(topicId);
        terms.setExecutingClubId(clubId);
        // ★F 固定补偿的真源是当期成本版本。缺当期成本行绝不静默冻 0：
        //   「少付不多付」只对平台是安全侧，对合同对手方是违约，而且零告警、事后不可救 ——
        //   freezeTermsForSale 幂等读回原件，全仓没有第二个写入点，也没有 terms 更正方法。
        //   这与本类第 3 条纪律（缺事实一律 PENDING 或抛错）一致。
        BigDecimal fixed = mapper.selectCurrentFixedCompensation(topicId);
        if (fixed == null) {
            throw new IllegalStateException("EDITION_TERMS_FIXED_COMPENSATION_MISSING");
        }
        // ★合同下界现在是一个【绝对值】¥2,000／期，不再乘 ΣH_plan（见上面那段拆耦合的注释）。
        //   这条护栏留着的理由没变:「缺成本行拒售」只堵了一半 —— 更常走的那条是
        //   「有成本行、但那一项是 0」，而 0 正是后台成本弹窗对未填项的默认值
        //   （DB CHECK 也只要求 ≥0）。真发成 0 元，对合同对手方是违约且零告警。
        if (fixed.compareTo(MINIMUM_FIXED_COMPENSATION) < 0) {
            throw new IllegalStateException("EDITION_TERMS_FIXED_COMPENSATION_BELOW_CONTRACT:"
                    + MINIMUM_FIXED_COMPENSATION.stripTrailingZeros().toPlainString());
        }
        terms.setFixedCompensation(fixed);
        freezeHeadcountConfig(topicId, terms);
        terms.setExtraHourRate(EXTRA_HOUR_RATE);
        terms.setQualityDimensionAmount(QUALITY_DIMENSION_AMOUNT);
        terms.setQualityDimensions(String.join(",", ClubEditionCompensationTerms.QUALITY_DIMENSIONS));
        terms.setEvidenceDueAt(evidenceDueAt);
        terms.setFulfillmentEndAt(fulfillmentEndAt);
        // ★审批人 = 开售前在当期成本版本上<b>显式登记</b>的项目负责人，不是 ORGANIZER 槽的 declared_by
        //   （那是「谁点了那个按钮」的审计字段，契约明写「不参与主体归属判断」，幂等重试还保留最初那个人）。
        //   总纲 §2.1.2「超范围劳动的小时单价和审批人必须在开售快照中冻结」+ 锁定裁决「项目负责人事前审批」。
        Long approver = mapper.selectCurrentExtraApprover(topicId);
        if (approver == null) {
            throw new IllegalStateException("EDITION_TERMS_EXTRA_APPROVER_MISSING");
        }
        // 冻进去的人必须真的按得动预批按钮。冻错了就是本期 EXTRA 永久批不了、且没有 terms 更正路径。
        if (!approverReachability.canApproveExtraHours(approver)) {
            throw new IllegalStateException("EDITION_TERMS_EXTRA_APPROVER_UNREACHABLE:" + approver);
        }
        terms.setExtraApproverUserId(approver);
        terms.setFrozenAt(frozenAt);
        terms.setConfigHash(configHash(terms));
        if (mapper.insertTerms(terms) != 1) throw new IllegalStateException("EDITION_TERMS_WRITE_FAILED");
        // 三维在开售当场落成 PENDING 行（不预置任何金额）：一次确认的 CAS 需要有行可 CAS，
        // 而「T+7 前是 PENDING、不是默认 600」也必须有一条明确的事实来承载。
        for (String dimension : ClubEditionCompensationTerms.QUALITY_DIMENSIONS) {
            ClubQualityDecision decision = new ClubQualityDecision();
            decision.setTopicId(topicId);
            decision.setClubId(clubId);
            decision.setDimension(dimension);
            decision.setDecisionStatus(ClubQualityDecision.STATUS_PENDING);
            decision.setDueAt(evidenceDueAt);
            decision.setVersion(1L);
            decision.setCurrent(1);
            decision.setExcludedByFault(0);
            if (mapper.insertQualityDecision(decision) != 1) {
                throw new IllegalStateException("EDITION_QUALITY_SEED_FAILED");
            }
        }
        return terms;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void planHours(Long topicId, Long clubId, String hourKind, BigDecimal plannedHours, Long operatorId) {
        if (topicId == null || clubId == null || operatorId == null) {
            throw new IllegalArgumentException("EDITION_HOURS_ARGUMENT_REQUIRED");
        }
        requireHourKind(hourKind);
        if (plannedHours == null || plannedHours.signum() < 0) {
            throw new IllegalArgumentException("EDITION_HOURS_AMOUNT_INVALID");
        }
        // 开售一冻结，计划工时就是不可变事实 —— 旧 upsertHours 能覆盖它，正是那条后门被封死的原因。
        // 这一句只是给人话的快速失败；★真正消除 TOCTOU 的是 upsertPlannedHours 自带的
        // `where not exists (select 1 from club_edition_compensation_terms ...)` 守卫：
        // 「先 selectTerms 判 null，再 upsert」之间 freezeTermsForSale 可以提交，
        // 而 REPEATABLE READ 下本事务的快照压根看不见它 —— 那不是理论窗口，是结构性的。
        // 冻结后把 planned 改小会直接抬高 EXTRA 应付（actualExtra = confirmed − planned）。
        if (mapper.selectTerms(topicId) != null) {
            throw new IllegalStateException("EDITION_HOURS_PLAN_FROZEN");
        }
        if (mapper.upsertPlannedHours(topicId, clubId, hourKind, plannedHours) < 1) {
            // 守卫命中 0 行的唯一原因就是 terms 已存在（写失败会抛 SQL 异常，不会走到这里）。
            throw new IllegalStateException("EDITION_HOURS_PLAN_FROZEN");
        }
    }

    /**
     * 把当期成本版本上登记的 HEADCOUNT 配置冻进条款（2026-08-25 承接费换轨）。
     *
     * <p>缺当期成本行 = 这一期<b>不按人头计酬</b>（rate 冻 0、cap 冻 null），只发保底 F。
     * 这里刻意<b>不</b>抛错：与 F 不同，人头计酬是可选档 —— 老期次、只发保底的期次都合法。
     * 而 F 缺行必须抛，因为那会真的少付一笔已经承诺的钱。
     *
     * <p>rate 与 cap 一次读回同一行，不拆成两个标量查询：分两次查会在成本改版的窗口里
     * 冻出「rate 来自新版、cap 来自旧版」的组合，那种钱算错了也不会有任何报错。
     * ⚠️ 这句只管这两个数之间：F 仍走 selectCurrentFixedCompensation 单查，
     * F 与 rate 之间那个跨版本窗口本来就在，本次没关它。
     */
    private void freezeHeadcountConfig(Long topicId, ClubEditionCompensationTerms terms) {
        Map<String, Object> config = mapper.selectCurrentHeadcountConfig(topicId);
        BigDecimal rate = config == null ? null : toDecimal(config.get("headcountRate"));
        BigDecimal cap = config == null ? null : toDecimal(config.get("headcountCap"));
        if (rate != null && rate.signum() < 0) {
            throw new IllegalStateException("EDITION_TERMS_HEADCOUNT_RATE_INVALID");
        }
        if (cap != null && cap.signum() < 0) {
            throw new IllegalStateException("EDITION_TERMS_HEADCOUNT_CAP_INVALID");
        }
        terms.setHeadcountRate(rate == null ? BigDecimal.ZERO : rate);
        terms.setHeadcountCap(cap);
    }

    private static BigDecimal toDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal) return (BigDecimal) value;
        return new BigDecimal(String.valueOf(value));
    }

    /**
     * 已退役的写路径统一在这里挡住（2026-08-25 产品裁决）。
     *
     * <p>退役的是<b>实际</b>工时这一整条：俱乐部自报 → 后台确认 → 更正 → EXTRA 预批／更正。
     * 理由是实际工时线下无法核实，而 EXTRA 单价 ¥40／人小时 —— 自报即计酬等于给了一条
     * 「凭空写小时数换钱」的通道，服务端没有任何事实能证伪它。
     *
     * <p><b>没有退役的</b>：{@code planHours}（计划工时是 F 的金额来源，由平台登记，不是自报）、
     * 全部 quality 路径（证据是可核验的文件哈希，不是自陈数字）。
     *
     * <p>为什么是「入口拒绝」而不是删方法：{@code club_edition_hours} 与
     * {@code club_extra_hour_approval} 的历史行是审计事实，读路径（compensationView / 对账）
     * 仍要能原样读回；删掉写方法会连带删掉它们的领域语义。
     */
    private static void rejectRetiredHoursWrite() {
        throw new IllegalStateException("EDITION_ACTUAL_HOURS_RETIRED");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reportHours(Long topicId, Long clubId, String hourKind, BigDecimal actualHours, Long reporterId) {
        rejectRetiredHoursWrite();
        requireExecutingClub(topicId, clubId);
        requireHourKind(hourKind);
        if (actualHours == null || actualHours.signum() < 0 || reporterId == null) {
            throw new IllegalArgumentException("EDITION_HOURS_AMOUNT_INVALID");
        }
        // 自报只写 reported_*；它不是已确认实际工时，FINAL 不看它。
        if (mapper.reportActualHoursCas(topicId, clubId, hourKind, actualHours, reporterId) != 1) {
            throw new IllegalStateException("EDITION_HOURS_REPORT_CONFLICT");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmActualHours(Long topicId, Long clubId, String hourKind, BigDecimal actualHours,
                                   Long operatorId) {
        rejectRetiredHoursWrite();
        requireExecutingClub(topicId, clubId);
        requireHourKind(hourKind);
        if (actualHours == null || actualHours.signum() < 0 || operatorId == null) {
            throw new IllegalArgumentException("EDITION_HOURS_AMOUNT_INVALID");
        }
        // CAS 的旧状态条件写在 XML 里（必须是 REPORTED）⇒ 请求体传 "CONFIRMED" 没有任何作用。
        if (mapper.confirmActualHoursCas(topicId, clubId, hourKind, actualHours, operatorId) != 1) {
            throw new IllegalStateException("EDITION_HOURS_CONFIRM_CONFLICT");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void correctConfirmedHours(Long topicId, Long clubId, String hourKind, String reason, Long operatorId) {
        rejectRetiredHoursWrite();
        requireExecutingClub(topicId, clubId);
        requireHourKind(hourKind);
        if (operatorId == null) throw new IllegalArgumentException("EDITION_HOURS_ARGUMENT_REQUIRED");
        // 锁定读:审计要快照的是**这一条**确认事实,快照读会让并发的另一次更正把它换掉。
        ClubEditionHours current = mapper.selectHoursForUpdate(topicId, clubId, hourKind);
        if (current == null || !"CONFIRMED".equals(current.getConfirmStatus())
                || current.getConfirmedActualHours() == null || current.getConfirmedAt() == null) {
            throw new IllegalStateException("EDITION_HOURS_NOT_CONFIRMED");
        }
        // 理由是这条审计行的全部价值:没有理由的更正等于「有人改过,不知道为什么」。
        // 校验放在锁定读之后:先证明「真有一条已确认事实可更正」,再挑参数的毛病 —— 报错顺序与调用者的心智一致。
        if (isBlank(reason)) throw new IllegalArgumentException("EDITION_HOURS_CORRECTION_REASON_REQUIRED");
        ClubEditionHoursCorrection audit = new ClubEditionHoursCorrection();
        audit.setTopicId(topicId);
        audit.setClubId(clubId);
        audit.setHourKind(hourKind);
        audit.setPreviousConfirmedHours(current.getConfirmedActualHours());
        audit.setPreviousConfirmedBy(current.getConfirmedBy());
        audit.setPreviousConfirmedAt(current.getConfirmedAt());
        audit.setReopenedBy(operatorId);
        audit.setReason(reason.trim());
        // ★先留痕、后退回。顺序反了就有一个「已退回但没留痕」的窗口,而那正是旧事实唯一的去处。
        //   两步同一个事务:退回 CAS 没命中 ⇒ 整笔回滚 ⇒ 不留孤儿审计行。
        if (mapper.insertHoursCorrection(audit) != 1) {
            throw new IllegalStateException("EDITION_HOURS_CORRECTION_WRITE_FAILED");
        }
        if (mapper.reopenConfirmedHoursCas(topicId, clubId, hourKind,
                current.getConfirmedAt(), operatorId) != 1) {
            throw new IllegalStateException("EDITION_HOURS_CORRECTION_CONFLICT");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void preapproveExtraHours(Long topicId, Long clubId, String category, BigDecimal approvedHours,
                                     Date workStartedAt, String idempotencyKey, Long approverId) {
        rejectRetiredHoursWrite();
        ClubEditionCompensationTerms terms = requireExecutingClub(topicId, clubId);
        requireHourKind(category);
        if (approvedHours == null || approvedHours.signum() <= 0) {
            throw new IllegalArgumentException("EDITION_EXTRA_HOURS_INVALID");
        }
        if (workStartedAt == null || approverId == null || isBlank(idempotencyKey)) {
            throw new IllegalArgumentException("EDITION_EXTRA_ARGUMENT_REQUIRED");
        }
        requireFrozenApprover(terms, approverId);
        requireWorkStartInsideEdition(terms, workStartedAt);
        ClubExtraHoursApproval approval = new ClubExtraHoursApproval();
        approval.setTopicId(topicId);
        approval.setClubId(clubId);
        approval.setCategory(category);
        approval.setApprovedHours(approvedHours);
        approval.setApprovedBy(approverId);
        // ★approvedAt 留空：由 XML 的 CURRENT_TIMESTAMP(3) 写。Java 传时间 = 允许「事后补预审批」自证。
        approval.setWorkStartedAt(workStartedAt);
        approval.setIdempotencyKey(idempotencyKey);
        if (mapper.insertExtraApproval(approval) != 1) {
            throw new IllegalStateException("EDITION_EXTRA_WRITE_FAILED");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void correctExtraHours(Long topicId, Long clubId, Long correctionOf, BigDecimal approvedHours,
                                  String idempotencyKey, Long approverId) {
        rejectRetiredHoursWrite();
        ClubEditionCompensationTerms terms = requireExecutingClub(topicId, clubId);
        if (correctionOf == null || approverId == null || isBlank(idempotencyKey)) {
            throw new IllegalArgumentException("EDITION_EXTRA_ARGUMENT_REQUIRED");
        }
        if (approvedHours == null || approvedHours.signum() < 0) {
            throw new IllegalArgumentException("EDITION_EXTRA_HOURS_INVALID");
        }
        requireFrozenApprover(terms, approverId);
        ClubExtraHoursApproval original = mapper.selectExtraApprovalById(correctionOf);
        if (original == null || !topicId.equals(original.getTopicId()) || !clubId.equals(original.getClubId())) {
            throw new IllegalStateException("EDITION_EXTRA_APPROVAL_NOT_FOUND");
        }
        // 追认不得抬高原预算：更正只能往下调，否则「先少批、事后补高」就绕开了事前审批。
        if (approvedHours.compareTo(original.getApprovedHours()) > 0) {
            throw new IllegalArgumentException("EDITION_EXTRA_CORRECTION_CANNOT_RAISE");
        }
        // ★一条审批只能被更正一次。两条兄弟更正各自「没抬高原件」，合起来却把额度按条累加 ⇒ 超额付款。
        // 想继续下调就更正链尾那条（append-only 的链，不是并列的兄弟）。
        // 这里给人话，真正的并发防线是 DB 的 uk_club_extra_correction_of。
        if (mapper.countCorrectionsOf(correctionOf) > 0) {
            throw new IllegalStateException("EDITION_EXTRA_ALREADY_CORRECTED");
        }
        ClubExtraHoursApproval correction = new ClubExtraHoursApproval();
        correction.setTopicId(topicId);
        correction.setClubId(clubId);
        correction.setCategory(original.getCategory());
        correction.setApprovedHours(approvedHours);
        correction.setApprovedBy(approverId);
        // 继承原事件的开工时刻：更正不改变「当初是否事前审批」这个事实。
        correction.setWorkStartedAt(original.getWorkStartedAt());
        correction.setIdempotencyKey(idempotencyKey);
        correction.setCorrectionOf(correctionOf);
        if (mapper.insertExtraApproval(correction) != 1) {
            throw new IllegalStateException("EDITION_EXTRA_WRITE_FAILED");
        }
    }

    // 2026-09-10 用户裁决:探店日不再交质量证据。submitQualityEvidence 与
    // qualityEvidenceState 整条删除 —— 它们是 evidence_hash 的唯一写入点与回读点。

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmQualityOnce(Long topicId, Long clubId, String dimension, boolean pass, String reason,
                                   Long operatorId) {
        requireExecutingClub(topicId, clubId);
        requireDimension(dimension);
        if (operatorId == null) throw new IllegalArgumentException("EDITION_QUALITY_ARGUMENT_REQUIRED");
        // 一次确认不可逆（CAS 的旧状态写死 PENDING）：判不过 = 该维永久锁死 0 元。
        // 「依据什么这么判」必须落库 —— 本仓 @Log 不写请求体，sys_oper_log 里查不到它。
        if (isBlank(reason)) throw new IllegalArgumentException("EDITION_QUALITY_REASON_REQUIRED");
        // ★★确认侧<b>不</b>比证据截止。截止约束的是交付时刻（总纲 §2.1.2「按清单交付」），
        //   不是平台的审批时刻 —— 两件事套同一个 due_at，就成了「俱乐部按时交、平台晚判一分钟
        //   这一维归 0」，方向正好是锁定裁决禁止的那一侧（平台过错不得转嫁给俱乐部）。
        // ⚠️ 2026-09-10 用户裁决:探店日不再交质量证据,这道「缺证据不得判过」的闸一并删除。
        //    它此时已经是**死闸** —— 唯一的证据写入点删掉之后 evidence_hash 永远是 null,
        //    pass=true 必抛,每一维到期都落 0。留着不是保护,是把 Q 焊死在 0。
        //    代价要说清楚:判过与否现在**完全**由后台那次人工确认承重,没有第二重校验,
        //    误点一下就是 200。仅剩的约束是权限(business:clubquality:confirm)与必填的 reason。
        // 只有 0 或 200 两个取值；百分比 / 均分 / 模糊评分在这里没有入口。
        BigDecimal amount = pass ? QUALITY_DIMENSION_AMOUNT : BigDecimal.ZERO;
        if (mapper.confirmQualityCas(topicId, clubId, dimension, amount, reason.trim(), operatorId,
                new Date()) != 1) {
            throw new IllegalStateException("EDITION_QUALITY_ALREADY_CONFIRMED");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void excludeQualityByFault(Long topicId, Long clubId, String dimension, String faultParty,
                                      String reason, Long operatorId) {
        ClubEditionCompensationTerms terms = requireExecutingClub(topicId, clubId);
        requireDimension(dimension);
        if (operatorId == null) throw new IllegalArgumentException("EDITION_QUALITY_ARGUMENT_REQUIRED");
        // 只有「已证明由商家或平台造成」这两种归责能触发排除；别的理由要走正常的 pass=false。
        if (!FAULT_PARTIES.contains(faultParty)) {
            throw new IllegalArgumentException("EDITION_QUALITY_FAULT_PARTY_INVALID");
        }
        // ★理由必填。这是全仓唯一能推翻一个已确认 Q 决定的动作，方向是加钱（每维 +200，
        //   一期最多 +600），触发条件只是操作者自行断言归责方 —— 没有证据要求、没有时间窗、
        //   也没有第二双眼睛（§7.1 E3 是有意的）。「是哪一笔事故、依据什么定的责」只有落进
        //   reason 列才留得下来：fault_party 只有两个枚举值，@Log 不写请求体。
        if (isBlank(reason)) throw new IllegalArgumentException("EDITION_QUALITY_REASON_REQUIRED");
        ClubQualityDecision current = currentDecision(topicId, clubId, dimension);
        if (current == null || current.getId() == null) {
            throw new IllegalStateException("EDITION_QUALITY_DECISION_MISSING");
        }
        if (Integer.valueOf(1).equals(current.getExcludedByFault())) {
            throw new IllegalStateException("EDITION_QUALITY_ALREADY_EXCLUDED");
        }
        // 版本化更正链：先把当前版本置为非 current，再追加新版本指向它。
        // rows≠1 说明并发改过 current，整笔回滚 —— 绝不产生两个 current。
        if (mapper.supersedeCurrentQuality(topicId, clubId, dimension) != 1) {
            throw new IllegalStateException("EDITION_QUALITY_SUPERSEDE_CONFLICT");
        }
        ClubQualityDecision excluded = new ClubQualityDecision();
        excluded.setTopicId(topicId);
        excluded.setClubId(clubId);
        excluded.setDimension(dimension);
        // 排除后这一维必须落成已决：留 PENDING 会让 FINAL 一直卡在 T+7 之前不能结。
        excluded.setDecisionStatus(ClubQualityDecision.STATUS_CONFIRMED);
        // ★方向按总纲 §2.1.2 字面：「商家或平台故障导致的未完成，从俱乐部绩效责任中剔除」——
        //   剔除的宾语是<b>失分事件</b>，不是报酬。所以该维不扣分、按冻结条款照贡献满额，
        //   绝不让商家/平台的过错落成俱乐部少拿这 200（一期最多三维 = 每期 600 的系统性少付）。
        //   上一版判了多少不会丢：它作为 is_current=0 的历史版本原样留在表里，
        //   fault_party 记下是谁的过错，两条合起来就是完整的留痕。
        excluded.setAmount(terms.getQualityDimensionAmount());
        excluded.setEvidenceHash(current.getEvidenceHash());
        excluded.setExcludedByFault(1);
        excluded.setFaultParty(faultParty);
        excluded.setReason(reason.trim());
        excluded.setDueAt(current.getDueAt());
        excluded.setConfirmedBy(operatorId);
        excluded.setConfirmedAt(new Date());
        excluded.setVersion(current.getVersion() == null ? 2L : current.getVersion() + 1L);
        excluded.setCurrent(1);
        excluded.setCorrectionOf(current.getId());
        if (mapper.insertQualityDecision(excluded) != 1) {
            throw new IllegalStateException("EDITION_QUALITY_EXCLUSION_WRITE_FAILED");
        }
    }

    /** 该维当前生效的决定；没有就是 null（开售种子没落 ⇒ 上游早就 fail closed 了）。 */
    private ClubQualityDecision currentDecision(Long topicId, Long clubId, String dimension) {
        List<ClubQualityDecision> decisions = mapper.selectCurrentQualityDecisions(topicId, clubId);
        if (decisions == null) return null;
        for (ClubQualityDecision decision : decisions) {
            if (decision != null && dimension.equals(decision.getDimension())
                    && Integer.valueOf(1).equals(decision.getCurrent())) {
                return decision;
            }
        }
        return null;
    }

    
    @Override
    @Transactional(readOnly = true)
    public FinalCompensationFacts computeFinalCompensationFacts(Long topicId, Date now) {
        if (topicId == null || now == null) throw new IllegalArgumentException("EDITION_TERMS_ARGUMENT_REQUIRED");
        ClubEditionCompensationTerms terms = requireFrozenTerms(topicId);
        Long clubId = terms.getExecutingClubId();

        // ★2026-08-25 产品裁决：俱乐部【自报工时】与 EXTRA 整条退役。
        //   理由：实际工时线下无法核实，而 EXTRA 是 ¥40／人小时 —— 自报即计酬 =
        //   一条「凭空写小时数换钱」的资损通道，前后端都没有能证伪它的服务端事实。
        //
        //   为什么摘掉这道闸不影响 F：F 的金额读的是【开售冻结】的
        //   terms.getFixedCompensation()（2026-08-25 起下界是绝对值 ¥2,000，不再乘 ΣH_plan），
        //   与自报无关。confirmedHoursByKind 原本【只】喂 extraByCategory 一处 ——
        //   它卡住 F/Q 是副作用不是设计意图：一个按计划干完、零超额的俱乐部也必须先填
        //   四个框才能拿到保底，那是一道无收益的手续。
        //
        //   ⚠️ 停的是【新增】不是【存量】：写路径退役前已经把四类工时确认完、并且批过 EXTRA
        //   的期次，必须照原样结清。把 extraTotal 一刀切成 0 会让这些期次少付 ——
        //   本类开头第 3 条纪律与 freezeTermsForSale 的注释都写死了：
        //   「少付不多付」对平台是安全侧，对合同对手方是违约，而且零告警、事后不可救。
        //   所以这里保留原计算，只把它从「FINAL 的阻塞条件」降级成「有就算、没有就 0」。
        BigDecimal quality = qualityTotal(topicId, clubId, terms, now);
        if (quality == null) return FinalCompensationFacts.pending(clubId, "EDITION_QUALITY_PENDING");

        Map<String, ClubEditionHours> confirmed = confirmedHoursByKind(topicId, clubId);
        Map<String, BigDecimal> extraByCategory;
        if (confirmed != null) {
            extraByCategory = extraByCategory(topicId, clubId, terms, confirmed);
        } else {
            // ★confirmedHoursByKind 是【四类全齐才返回】,缺一即 null。所以「确认了 3 类、
            //   但已经批过 EXTRA」这种半路停下的存量期次,直接落 0 就是**静默少付**:
            //   改之前它是 PENDING(不付、看得见),改之后会变成 settled 且金额缺一块、零告警。
            //   那正是本类第 3 条纪律禁止的「把『没查到』当成 0 通过」。
            //   有已批 EXTRA 却凑不齐工时事实 ⇒ 仍然 PENDING,交给人去把那几类补确认完
            //   (那条路径虽已退役,但存量数据要靠 DBA/后台补,不能靠这里默默抹平)。
            List<ClubExtraHoursApproval> pendingApprovals = mapper.selectExtraApprovals(topicId, clubId);
            if (pendingApprovals != null && !pendingApprovals.isEmpty()) {
                return FinalCompensationFacts.pending(clubId, "EDITION_HOURS_NOT_CONFIRMED");
            }
            // 没有任何已批 EXTRA ⇒ 本来就无可计,落 0 不会少付谁的钱。
            extraByCategory = Collections.emptyMap();
        }
        BigDecimal extraTotal = BigDecimal.ZERO;
        for (BigDecimal value : extraByCategory.values()) extraTotal = extraTotal.add(value);
        // ★这道闸【不是】跟着 TICKET_SOURCE 一起退役的残留,它现在独立成立:
        //   没有 FINAL 六量快照 = 这一期的钱还没结清,任何分量都不该发。
        //   退役前它是「算票源奖励顺带发现缺 FINAL」的副作用;副作用会跟着被算的东西一起消失,
        //   所以这里把它显式立起来。少了它,缺 FINAL 的期次会走到 headcountAmount,
        //   归因读出空集合 ⇒ 净票数 0 ⇒ 静默结 0 —— 又是一次「把没查到当成 0」。
        if (!hasCurrentFinalSnapshot(topicId)) {
            return FinalCompensationFacts.pending(clubId, "EDITION_FUNDS_FINAL_MISSING");
        }
        BigDecimal headcount = headcountAmount(topicId, terms);
        if (headcount == null) {
            return FinalCompensationFacts.pending(clubId, "EDITION_HEADCOUNT_FACTS_MISSING");
        }
        return FinalCompensationFacts.settled(clubId, terms.getFixedCompensation(), quality,
                extraTotal, headcount, extraByCategory);
    }

    /**
     * HEADCOUNT：按<b>已赚票数</b>计酬的承接费（2026-08-25 换轨，取代按工时的 EXTRA）。
     *
     * <pre>headcount = min(冻结单价 × Σ已赚票数, 冻结封顶)</pre>
     *
     * <p>★口径是 {@code topic_edition_funds_attribution.net_ticket_count}，<b>不是</b>
     * {@code ticket_count}。后者是 {@code count(*)} over {@code payment_status = 2} 的毛计数
     * （它要与六量里的 {@code n_cash} 对账，动不得），全额退款的报名照样算 1 张 ——
     * 拿它计酬就是「整场退光了还照付承接费」。{@code net_ticket_count} 与
     * {@code earned_amount} 共用同一个退款 join、在同一事务里算出，才是真的已扣退款。
     *
     * <p>它同时满足工时永远满足不了的两条：FINAL <b>同事务固化</b>（报名表事后再怎么变都
     * 改不动它）、是平台侧产出而不是自陈投入。这正是它替代工时的全部理由。
     *
     * <p>★读的是<b>全部</b>归因行之和，不按 source_club_id 过滤 —— 承接方的酬劳跟这一期
     * 卖了多少张有关，跟每张票是谁引流来的无关（那是 TICKET_SOURCE 的事）。
     *
     * <p>返回 {@code null} = 事实不完整 ⇒ 整体 PENDING（本类第 3 条纪律：缺事实不许当 0 过）。
     * 唯一会走到这里的真实情形：这份 FINAL 快照是 {@code net_ticket_count} 落库<b>之前</b>
     * 冻的，那一列为 NULL。此时既不许当 0（少付），也不许退回读毛计数（多付）。
     * 未配置人头计酬（rate=0 且 cap 为空）时直接返回 0，<b>不</b>去读归因 ——
     * 那种期次本来就不看票数，缺 FINAL 快照也不该把它卡住。
     */
    private BigDecimal headcountAmount(Long topicId, ClubEditionCompensationTerms terms) {
        if (!isHeadcountConfigured(terms)) return BigDecimal.ZERO;
        BigDecimal rate = terms.getHeadcountRate() == null ? BigDecimal.ZERO : terms.getHeadcountRate();
        // 归因为空集合是【合法且正确】的答案：这一期一张没卖 ⇒ 净票数 0 ⇒ 承接费 0。
        // 「压根没有 FINAL 快照」不会走到这里 —— 上游 hasCurrentFinalSnapshot 已先判 PENDING。
        List<TopicEditionFundsAttribution> rows = mapper.selectCurrentFinalAttributions(topicId);
        BigDecimal tickets = BigDecimal.ZERO;
        for (TopicEditionFundsAttribution row : rows) {
            // 老快照没有这个事实（列是后加的，历史行为 NULL）⇒ 整体 PENDING。
            if (row == null || row.getNetTicketCount() == null || row.getNetTicketCount() < 0L) {
                return null;
            }
            tickets = tickets.add(BigDecimal.valueOf(row.getNetTicketCount()));
        }
        BigDecimal amount = rate.multiply(tickets).setScale(2, RoundingMode.HALF_UP);
        BigDecimal cap = terms.getHeadcountCap();
        // 封顶是平台侧的敞口上界:cap 为 null 表示这一期不封顶,是显式配置不是缺失。
        return (cap != null && amount.compareTo(cap) > 0) ? cap : amount;
    }

    /**
     * 本期是否已有生效的 FINAL 六量快照。
     *
     * <p>判据取 {@code g_earned} 是否读得到 —— 它是 FINAL 快照的必填量,读不到只有一种解释:
     * 这一期还没结出 FINAL。这一条与 2026-08-25 TICKET_SOURCE 退役<b>前</b>等价
     * （那时是 {@code FinalTicketSourceRewardSource.read} 里的 {@code gEarned == null} 分支）。
     *
     * <p>⚠️ 但<b>不是「整个 read() 逐条等价」</b>,别把这句读大了：旧 {@code read()} 除这条外还跑
     * {@code TicketSourceRewardCalculator} 的三类 fail-closed（归因守恒、逐行票数/金额合法性、
     * DISTRIBUTION 槽资格），作用于 <b>headcount 现在消费的同一批归因行</b>；本方法只判快照在不在。
     * 之所以可以这么退：那三类里与钱相关的守恒断言在<b>写入侧</b>仍然在跑
     * （{@code TopicEditionFundsServiceImpl.validateProducedFinalAttributions} /
     * {@code validatePersistedFinalAttributions}，且本轮又加了 net ≤ gross 一条），
     * 只读消费侧不再重复一遍。代价是：若快照被人手改成半写状态，只读侧不会再第二次报红。
     */
    private boolean hasCurrentFinalSnapshot(Long topicId) {
        return mapper.selectCurrentFinalGEarned(topicId) != null;
    }

    @Override
    @Transactional(readOnly = true)
    public ClubEditionCompensationTerms requireFrozenTerms(Long topicId) {
        if (topicId == null) throw new IllegalArgumentException("EDITION_TERMS_ARGUMENT_REQUIRED");
        ClubEditionCompensationTerms terms = mapper.selectTerms(topicId);
        if (terms == null) throw missingTerms(topicId);
        if (!configHash(terms).equals(terms.getConfigHash())) {
            throw new IllegalStateException("EDITION_TERMS_TAMPERED");
        }
        return terms;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> editionsForExecutingClub(Long clubId) {
        if (clubId == null) throw new IllegalArgumentException("EDITION_HOURS_ARGUMENT_REQUIRED");
        List<Map<String, Object>> editions = mapper.selectEditionsForExecutingClub(clubId);
        if (editions == null) return new ArrayList<Map<String, Object>>();
        // ★逐行回读：SQL 已按 executing_club_id 收窄，这里再核一遍它真的照做了。
        //   哪天谁把那个谓词改丢，别的俱乐部的期次会出现在这个俱乐部的选期控件里，
        //   而选错期的质量证据会直接计入另一期的 Q。
        for (Map<String, Object> row : editions) {
            Object owner = row.get("executingClubId");
            if (owner == null || !clubId.toString().equals(String.valueOf(owner))) {
                throw new IllegalStateException("EDITION_CLUB_SCOPE_LEAK");
            }
        }
        return editions;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> compensationView(Long topicId) {
        if (topicId == null) throw new IllegalArgumentException("EDITION_TERMS_ARGUMENT_REQUIRED");
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        ClubEditionCompensationTerms terms = mapper.selectTerms(topicId);
        view.put("terms", terms);
        // 还没开售冻结（或历史豁免期）时给空视图，不抛 —— 这是读接口，不是写动作的前置闸。
        // 俱乐部只认冻结条款里的那一个，请求体里的 clubId 在这条路上没有位置。
        Long clubId = terms == null ? null : terms.getExecutingClubId();
        view.put("qualityDecisions", clubId == null
                ? new ArrayList<ClubQualityDecision>() : mapper.selectCurrentQualityDecisions(topicId, clubId));
        view.put("extraApprovals", clubId == null
                ? new ArrayList<ClubExtraHoursApproval>() : mapper.selectExtraApprovals(topicId, clubId));
        view.put("hours", clubId == null
                ? new ArrayList<ClubEditionHours>() : mapper.selectHoursForClub(topicId, clubId));
        return view;
    }

    /** 四类全部 CONFIRMED 才返回；缺一类就返回 null ⇒ 整体 PENDING，不允许部分应付。 */
    private Map<String, ClubEditionHours> confirmedHoursByKind(Long topicId, Long clubId) {
        List<ClubEditionHours> rows = mapper.selectHoursForClub(topicId, clubId);
        Map<String, ClubEditionHours> byKind = new LinkedHashMap<String, ClubEditionHours>();
        if (rows != null) {
            for (ClubEditionHours row : rows) {
                if (row != null && "CONFIRMED".equals(row.getConfirmStatus())
                        && row.getConfirmedActualHours() != null && row.getPlannedHours() != null) {
                    byKind.put(row.getHourKind(), row);
                }
            }
        }
        for (String kind : ClubEditionCompensationTerms.HOUR_KINDS) {
            if (!byKind.containsKey(kind)) return null;
        }
        return byKind;
    }

    /** 返回 null 表示尚未到证据截止且仍有未决维度 ⇒ PENDING（不是默认 600）。 */
    private BigDecimal qualityTotal(Long topicId, Long clubId, ClubEditionCompensationTerms terms, Date now) {
        List<ClubQualityDecision> decisions = mapper.selectCurrentQualityDecisions(topicId, clubId);
        Map<String, ClubQualityDecision> byDimension = new LinkedHashMap<String, ClubQualityDecision>();
        if (decisions != null) {
            for (ClubQualityDecision decision : decisions) {
                if (decision != null && Integer.valueOf(1).equals(decision.getCurrent())) {
                    byDimension.put(decision.getDimension(), decision);
                }
            }
        }
        // 截止时刻用开售冻结的那个，不用每行自带的，也不用 JVM 默认时区推算。
        boolean dueReached = !now.before(terms.getEvidenceDueAt());
        BigDecimal total = BigDecimal.ZERO;
        for (String dimension : ClubEditionCompensationTerms.QUALITY_DIMENSIONS) {
            ClubQualityDecision decision = byDimension.get(dimension);
            boolean settled = decision != null
                    && ClubQualityDecision.STATUS_CONFIRMED.equals(decision.getDecisionStatus());
            if (!settled) {
                if (!dueReached) return null;
                continue;  // 到期仍缺必需证据的维度显式落 0
            }
            BigDecimal amount = decision.getAmount();
            if (amount == null || (amount.compareTo(BigDecimal.ZERO) != 0
                    && amount.compareTo(terms.getQualityDimensionAmount()) != 0)) {
                throw new IllegalStateException("EDITION_QUALITY_AMOUNT_INVALID");
            }
            // ★excluded_by_fault 的行在写侧就已经把金额落成满额（见 excludeQualityByFault 的注释）：
            //   「从俱乐部绩效责任中剔除」= 不因商家/平台的过错扣俱乐部的分，读侧不再另做方向判断。
            //   这一列留着是审计事实（这 200 是被排除路径给的，不是靠证据判过来的），不是计算开关。
            total = total.add(amount);
        }
        return total;
    }

    /**
     * 每条更正链的有效额度。
     *
     * <p>两条纪律，缺一条就出资损：
     * <ol>
     *   <li><b>事前审批性看根审批行，不看链尾</b>。更正行的 approved_at 由数据库在更正当时写，
     *       按定义晚于继承来的 work_started_at；套在它自己身上恒为假 ⇒ 更正行被丢、原行又已被
     *       supersede ⇒ 走契约唯一合法的调整路径反而让该类应付整类归零。</li>
     *   <li><b>一条根审批只认一条链尾</b>。两条兄弟更正各自都「没抬高原件」，
     *       按条累加就把额度翻倍 ⇒ 超额付款。写侧已拒 + DB 唯一键兜底，这里是第三道。</li>
     * </ol>
     */
    private Map<String, BigDecimal> extraByCategory(Long topicId, Long clubId,
                                                    ClubEditionCompensationTerms terms,
                                                    Map<String, ClubEditionHours> confirmed) {
        List<ClubExtraHoursApproval> approvals = mapper.selectExtraApprovals(topicId, clubId);
        Map<Long, ClubExtraHoursApproval> byId = new LinkedHashMap<Long, ClubExtraHoursApproval>();
        Set<Long> superseded = new HashSet<Long>();
        if (approvals != null) {
            for (ClubExtraHoursApproval approval : approvals) {
                if (approval == null || approval.getId() == null) continue;
                byId.put(approval.getId(), approval);
                if (approval.getCorrectionOf() != null) superseded.add(approval.getCorrectionOf());
            }
        }
        // 根审批 id -> 该链当前生效的链尾（同一根出现多条兄弟时只认 id 最大的那条，绝不累加）。
        Map<Long, ClubExtraHoursApproval> tipByRoot = new LinkedHashMap<Long, ClubExtraHoursApproval>();
        for (ClubExtraHoursApproval approval : byId.values()) {
            if (superseded.contains(approval.getId())) continue;
            ClubExtraHoursApproval root = rootApprovalOf(approval, byId);
            // 链断（指向的原件查不到）⇒ 事前审批性无从证明 ⇒ 该链不产生任何额度。
            if (root == null) continue;
            ClubExtraHoursApproval kept = tipByRoot.get(root.getId());
            if (kept == null || kept.getId() < approval.getId()) tipByRoot.put(root.getId(), approval);
        }

        Map<String, BigDecimal> approvedByCategory = new LinkedHashMap<String, BigDecimal>();
        for (Map.Entry<Long, ClubExtraHoursApproval> chain : tipByRoot.entrySet()) {
            ClubExtraHoursApproval root = byId.get(chain.getKey());
            ClubExtraHoursApproval tip = chain.getValue();
            // 事前审批：根行的 approvedAt 必须严格早于 workStartedAt，否则整条链不产生任何可付额度。
            if (root.getApprovedAt() == null || root.getWorkStartedAt() == null
                    || !root.getApprovedAt().before(root.getWorkStartedAt())) {
                continue;
            }
            // 类别只认根行的：更正行复制它，但真源在最初那一笔。
            if (!HOUR_KINDS.contains(root.getCategory()) || tip.getApprovedHours() == null) continue;
            BigDecimal running = approvedByCategory.get(root.getCategory());
            approvedByCategory.put(root.getCategory(),
                    running == null ? tip.getApprovedHours() : running.add(tip.getApprovedHours()));
        }

        Map<String, BigDecimal> payable = new LinkedHashMap<String, BigDecimal>();
        for (String category : ClubEditionCompensationTerms.HOUR_KINDS) {
            ClubEditionHours hours = confirmed.get(category);
            BigDecimal actualExtra = hours.getConfirmedActualHours().subtract(hours.getPlannedHours());
            if (actualExtra.signum() < 0) actualExtra = BigDecimal.ZERO;
            BigDecimal approved = approvedByCategory.get(category);
            if (approved == null) approved = BigDecimal.ZERO;
            // 四类各算各的：本类的实际额外只能吃本类的批准额度，禁止跨类借。
            BigDecimal payableHours = actualExtra.min(approved);
            payable.put(category, terms.getExtraHourRate().multiply(payableHours)
                    .setScale(2, RoundingMode.HALF_UP));
        }
        return payable;
    }

    /** 顺 correctionOf 上溯到根审批行；链长有界，数据被写坏时返回 null ⇒ 该链 fail closed 不计额度。 */
    private static ClubExtraHoursApproval rootApprovalOf(ClubExtraHoursApproval tip,
                                                         Map<Long, ClubExtraHoursApproval> byId) {
        ClubExtraHoursApproval node = tip;
        for (int hop = 0; hop <= MAX_CORRECTION_CHAIN && node != null; hop++) {
            if (node.getCorrectionOf() == null) return node;
            node = byId.get(node.getCorrectionOf());
        }
        return null;
    }

    private ClubEditionCompensationTerms requireExecutingClub(Long topicId, Long clubId) {
        if (topicId == null || clubId == null) throw new IllegalArgumentException("EDITION_HOURS_ARGUMENT_REQUIRED");
        ClubEditionCompensationTerms terms = mapper.selectTerms(topicId);
        if (terms == null) throw missingTerms(topicId);
        // 执行俱乐部只认开售冻结里的那一个；请求体传别人的 clubId 不自证。
        if (!clubId.equals(terms.getExecutingClubId())) {
            throw new IllegalStateException("EDITION_CLUB_NOT_EXECUTING");
        }
        return terms;
    }

    /**
     * 审批人必须是开售快照里冻住的那一个。
     *
     * <p>不冻的话，「项目负责人事前审批」里「项目负责人是谁」这一半就等于「当天谁持
     * business:clubextra:approve」—— 而那个分派开售之后还能改，等于没冻。
     */
    private static void requireFrozenApprover(ClubEditionCompensationTerms terms, Long approverId) {
        if (terms.getExtraApproverUserId() == null
                || !terms.getExtraApproverUserId().equals(approverId)) {
            throw new IllegalStateException("EDITION_EXTRA_APPROVER_NOT_FROZEN");
        }
    }

    /**
     * 开工时刻不得由请求方随便填一个未来时刻自证「事前」。
     *
     * <p>两条边界都取<b>权威事实</b>，不发明常量：
     * <ul>
     *   <li>下界 = 服务端当前时刻。批的必须是<b>还没干</b>的活；真正承重的是 XML 里
     *       {@code #{workStartedAt} &gt; CURRENT_TIMESTAMP(3)} 那一句 —— 它和写 approved_at 的是
     *       <b>同一个库时钟同一条语句</b>，所以「approved_at 早于 work_started_at」由数据库自己保证，
     *       读侧那条事前性判据再也不能被填一个未来时刻洗白。</li>
     *   <li>上界 = 开售冻结的证据截止（履约窗结束 + 7 天）。复盘类工时本来就在活动结束之后，
     *       所以上界取 T+7 而不是履约窗结束本身。</li>
     * </ul>
     */
    private static void requireWorkStartInsideEdition(ClubEditionCompensationTerms terms, Date workStartedAt) {
        if (!workStartedAt.after(new Date())) {
            throw new IllegalStateException("EDITION_EXTRA_WORK_ALREADY_STARTED");
        }
        if (terms.getEvidenceDueAt() != null && workStartedAt.after(terms.getEvidenceDueAt())) {
            throw new IllegalStateException("EDITION_EXTRA_WORK_OUTSIDE_EDITION");
        }
    }

    
    /**
     * 缺条款时区分两种成因，两种都 fail-closed，但错误码不同。
     *
     * <p>{@code EDITION_LEGACY_EXEMPT} = 这一期在开售闸上线<b>之前</b>就已经开售，
     * 已由 {@code migration_20260816_tandianri_batch3_funds_legacy_edition_exemption.sql}
     * 显式登记为历史豁免 —— 不是配置没落，补配置也救不回来（开售当时的收款人版本与成本版本
     * 今天无法证明，绝不补伪快照）。
     * {@code EDITION_TERMS_MISSING} = 该有条款却没有，那才是要去查开售链路的那一类。
     */
    private IllegalStateException missingTerms(Long topicId) {
        return new IllegalStateException(
                mapper.countLegacyExemption(topicId) > 0 ? "EDITION_LEGACY_EXEMPT" : "EDITION_TERMS_MISSING");
    }

    private void requireHourKind(String hourKind) {
        if (!HOUR_KINDS.contains(hourKind)) throw new IllegalArgumentException("EDITION_HOURS_KIND_INVALID");
    }

    private void requireDimension(String dimension) {
        if (!QUALITY_DIMENSIONS.contains(dimension)) {
            throw new IllegalArgumentException("EDITION_QUALITY_DIMENSION_INVALID");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 条款指纹：开售冻结的每个数字都进 hash，FINAL 一比就知道有没有被「最新配置」改写过。 */
    public static String configHash(ClubEditionCompensationTerms t) {
        String canonical = CanonicalDigest.join(
                String.valueOf(t.getTopicId()), String.valueOf(t.getExecutingClubId()),
                plain(t.getFixedCompensation()), plain(t.getExtraHourRate()),
                plain(t.getQualityDimensionAmount()), String.valueOf(t.getQualityDimensions()),
                t.getEvidenceDueAt() == null ? "null" : String.valueOf(t.getEvidenceDueAt().getTime()),
                // 履约窗结束与审批人也进指纹：总纲要求它们「在开售快照中冻结」，
                // 只落列不进 hash 就是「冻了但改了没人知道」。
                t.getFulfillmentEndAt() == null ? "null" : String.valueOf(t.getFulfillmentEndAt().getTime()),
                String.valueOf(t.getExtraApproverUserId()));
        // ★HEADCOUNT 的两个冻结项也必须进指纹 —— 理由同上面那句「只落列不进 hash 就是
        //   『冻了但改了没人知道』」。它们直接决定一笔应付金额，比履约窗更该被钉住。
        //
        //   ⚠️ 但只在【真的配了人头计酬】时才追加,不能无条件拼进去:
        //   存量条款是在这两列存在之前冻的,DB 加列后它们是 rate=0.00 / cap=NULL。
        //   无条件追加会让每一条存量条款的重算指纹都变 ⇒ requireFrozenTerms 全部抛
        //   EDITION_TERMS_TAMPERED ⇒ 所有历史期次的 FINAL 当场瘫掉,而且看起来像被人改过库。
        //   「零配置 ≡ 不参与指纹」在安全侧是成立的:
        //     rate 0→非0 / 非0→改值 / 非0→0，三种改动重算后都会与存档指纹不符 ⇒ 照样判红。
        if (isHeadcountConfigured(t)) {
            canonical = CanonicalDigest.join(canonical, plain(t.getHeadcountRate()), plain(t.getHeadcountCap()));
        }
        return CanonicalDigest.sha256Hex(canonical);
    }

    /** 是否真的配了人头计酬。rate 为空或 0 且 cap 为空 = 这一期只发保底，不进指纹（见 configHash）。 */
    private static boolean isHeadcountConfigured(ClubEditionCompensationTerms t) {
        boolean hasRate = t.getHeadcountRate() != null && t.getHeadcountRate().signum() != 0;
        return hasRate || t.getHeadcountCap() != null;
    }

    private static String plain(BigDecimal value) {
        return value == null ? "null" : value.stripTrailingZeros().toPlainString();
    }
}
