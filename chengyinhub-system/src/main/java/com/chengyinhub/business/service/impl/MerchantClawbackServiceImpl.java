package com.chengyinhub.business.service.impl;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.MerchantClawbackApplication;
import com.chengyinhub.business.domain.MerchantPayableBatch;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.mapper.MerchantClawbackApplicationMapper;
import com.chengyinhub.business.mapper.MerchantPayableBatchMapper;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IMerchantClawbackService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.support.MakerCheckerGuard;

/**
 * M块:商家违规倒扣(2026-07-25 拍板)。见 {@link IMerchantClawbackService} 的边界说明。
 *
 * <p>★2026-07-25 已核实:BALANCE 路径不能走 {@code IUmsMemberService.addBalance}——
 * 那层校验 {@code balance<=0} 即抛异常(只许加钱),负数必抛。改为直接注入
 * {@link UmsMemberMapper} 调用,不改动公共 API 本身(那是被广泛复用的接口,风险面
 * 不该为了这一个扣款场景放大)。与 {@link MerchantReferralBountyService} 的
 * {@code clawbackBalance} 同一处理原则(该服务同日一并修复了同款 bug)。
 */
@Service
public class MerchantClawbackServiceImpl implements IMerchantClawbackService {

    private static final Logger log = LoggerFactory.getLogger(MerchantClawbackServiceImpl.class);

    /** 余额流水 event_type:商家违规倒扣(区别于 91=拓展人赏金追回) */
    public static final long EVENT_TYPE_MERCHANT_CLAWBACK = 92L;

    @Autowired
    private MerchantClawbackApplicationMapper applicationMapper;

    @Autowired
    private MerchantPayableBatchMapper batchMapper;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private UmsMemberMapper umsMemberMapper;

    @Autowired
    private UmsMemberBalanceDetailMapper balanceDetailMapper;

    /** 2026-09-17 总控裁定:余额不足不再扣成负数,扣到 0 + 差额记欠款(与结算冲销同一规则)。 */
    @Autowired
    private com.chengyinhub.business.service.support.SettlementDebtLedger settlementDebtLedger;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantClawbackApplication initiate(Long merchantMemberId, String reason, BigDecimal amount, String operator) {
        MakerCheckerGuard.requireActor(operator, "operator");
        if (merchantMemberId == null) {
            throw new IllegalArgumentException("merchantMemberId 不能为空");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("违规/投诉依据说明不能为空");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("倒扣金额必须大于 0");
        }

        // ★ 结算路径由系统判定,不接受调用方指定 —— 运营不该去猜「下一批」是哪个批次。
        MerchantPayableBatch target = batchMapper.selectNextUnpaidBatch(merchantMemberId);

        MerchantClawbackApplication a = new MerchantClawbackApplication();
        a.setMerchantMemberId(merchantMemberId);
        a.setReason(reason.trim());
        a.setAmount(amount);
        a.setStatus(MerchantClawbackApplication.STATUS_PENDING_REVIEW);
        a.setInitiatedBy(operator);
        a.setCreateTime(new Date());
        if (target != null) {
            a.setSettlePath(MerchantClawbackApplication.SETTLE_PATH_OFFSET_BATCH);
            a.setTargetBatchId(target.getId());
        } else {
            a.setSettlePath(MerchantClawbackApplication.SETTLE_PATH_BALANCE);
        }

        applicationMapper.insertApplication(a);
        log.info("[MerchantClawback] 发起倒扣申请 id={} merchantMemberId={} amount={} settlePath={} targetBatchId={} operator={}",
                a.getId(), merchantMemberId, amount, a.getSettlePath(), a.getTargetBatchId(), operator);
        return a;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean confirmExecute(Long id, String operator) {
        MakerCheckerGuard.requireActor(operator, "operator");
        MerchantClawbackApplication a = applicationMapper.selectById(id);
        if (a == null) {
            throw new IllegalStateException("倒扣申请不存在");
        }
        if (!MerchantClawbackApplication.STATUS_PENDING_REVIEW.equals(a.getStatus())) {
            return false;   // 已执行或已驳回,别让运营对着失败猜
        }
        // ★双人复核:复核人须≠发起人,与 MerchantPayableServiceImpl.markBatchPaid 同型闸。
        MakerCheckerGuard.requireDistinct(a.getInitiatedBy(), operator);

        if (MerchantClawbackApplication.SETTLE_PATH_OFFSET_BATCH.equals(a.getSettlePath())) {
            executeOffsetBatch(a);
        } else {
            executeBalance(a, operator);
        }

        Date now = new Date();
        // ★ CAS 放最后:目标端(批次/余额)已经真的改动过,这里只是把申请状态从 PENDING_REVIEW
        // 收口到 EXECUTED。若这一步返回 0(并发下别的线程已经把它推进/驳回),整条方法抛出,
        // 事务回滚会连同上面已做的批次扣减/余额扣款一并撤销 —— 不会出现「钱扣了两次」或
        // 「钱扣了、申请却没收口」的半吊子状态。
        int rows = applicationMapper.markExecutedIfPending(id, operator, now, now);
        if (rows == 0) {
            throw new IllegalStateException("并发冲突:该申请在执行过程中已被处理,已回滚本次扣款");
        }
        log.warn("[MerchantClawback] 已执行倒扣 id={} merchantMemberId={} amount={} settlePath={} confirmedBy={}",
                id, a.getMerchantMemberId(), a.getAmount(), a.getSettlePath(), operator);
        return true;
    }

    private void executeOffsetBatch(MerchantClawbackApplication a) {
        if (a.getTargetBatchId() == null) {
            throw new IllegalStateException("OFFSET_BATCH 路径缺少目标批次");
        }
        // ⚠️ 不做同步防负校验:扣减后金额可能变负,留痕说明扣了什么,与既有 CAS 风格一致。
        int rows = batchMapper.deductForClawback(a.getTargetBatchId(), a.getAmount());
        if (rows == 0) {
            // 发起复核之间该批次已被打款/流转 —— 不能悄悄改走余额路径(运营没确认过金额是否
            // 仍适用于余额扣款),必须让运营看到失败、重新判断处理方式。
            throw new IllegalStateException("目标批次已不可冲抵(可能已被打款或状态已变化),请驳回后人工重新处理");
        }
    }

    private void executeBalance(MerchantClawbackApplication a, String operator) {
        // ★ 收款(付款)对象必须在扣款动作之前查出来 —— 与 MerchantReferralBountyService 的
        // tryActivate/tryClawback 同一条纪律:顺序错了就是「记录说追回了、钱没扣」的假账。
        UmsMember merchant = umsMemberService.selectUmsMemberById(a.getMerchantMemberId());
        if (merchant == null) {
            throw new IllegalStateException("商家不存在,不可执行余额扣款 merchantMemberId=" + a.getMerchantMemberId());
        }
        // 加锁读当前已提交余额(不能用普通 select:RR 快照 + 一级缓存会读到旧值,
        // 导致把本够扣的钱误记成欠款、或余额已被提走时误判够扣),与并发结算入账/提现串行。
        BigDecimal before = umsMemberMapper.selectBalanceForUpdate(merchant.getId());
        if (before == null) {
            throw new IllegalStateException("商家不存在,不可执行余额扣款 merchantMemberId=" + a.getMerchantMemberId());
        }
        // 2026-09-17 总控裁定(9-16 拍板第 2 条「平台人工判定」):不再扣成负数。
        // 余额够就全扣;不够只扣到 0,差额记欠款 30 天,从后续结算抵扣,逾期禁止发布。
        BigDecimal actual = a.getAmount().min(before.max(BigDecimal.ZERO));
        settlementDebtLedger.record(merchant.getId(), com.chengyinhub.business.service.support.SettlementDebtLedger.SOURCE_CLAWBACK,
                a.getId(), a.getAmount().subtract(actual));
        if (actual.signum() <= 0) {
            log.warn("[MerchantClawback] 余额为 {},倒扣 {} 全额记欠款 id={} merchantMemberId={}",
                    before, a.getAmount(), a.getId(), merchant.getId());
            return;
        }
        if (umsMemberMapper.deductBalance(merchant.getId(), actual) != 1) {
            throw new IllegalStateException("余额扣款失败 merchantMemberId=" + a.getMerchantMemberId());
        }

        UmsMemberBalanceDetail d = new UmsMemberBalanceDetail();
        d.setMemberId(merchant.getId());
        d.setCurrentBalance(before);
        d.setAfterBalance(before.subtract(actual));
        d.setChangeBalance(actual.negate());
        d.setChangeType(2L);
        d.setEventType(EVENT_TYPE_MERCHANT_CLAWBACK);
        d.setEventId(a.getId());
        d.setChangeReason("商家违规倒扣(申请#" + a.getId() + "):" + a.getReason());
        d.setCreateTime(new Date());
        balanceDetailMapper.insertUmsMemberBalanceDetail(d);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean reject(Long id, String operator, String rejectReason) {
        MakerCheckerGuard.requireActor(operator, "operator");
        if (rejectReason == null || rejectReason.trim().isEmpty()) {
            throw new IllegalArgumentException("驳回原因不能为空");
        }
        return applicationMapper.markRejectedIfPending(id, operator, new Date(), rejectReason.trim()) > 0;
    }

    @Override
    public MerchantClawbackApplication selectById(Long id) {
        return applicationMapper.selectById(id);
    }

    @Override
    public List<MerchantClawbackApplication> listByMerchant(Long merchantMemberId) {
        return applicationMapper.selectByMerchantMemberId(merchantMemberId);
    }

    @Override
    public List<MerchantClawbackApplication> list(Long merchantMemberId, String status) {
        MerchantClawbackApplication query = new MerchantClawbackApplication();
        query.setMerchantMemberId(merchantMemberId);
        query.setStatus(status);
        return applicationMapper.selectList(query);
    }
}
