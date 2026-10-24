package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CoopComplaint;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.mapper.CoopComplaintMapper;
import com.chengyinhub.business.service.ICoopComplaintService;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.business.service.IPlayerWithdrawalCooldownService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.utils.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class CoopComplaintServiceImpl implements ICoopComplaintService {

    private static final Logger logger = LoggerFactory.getLogger(CoopComplaintServiceImpl.class);
    private static final Long EVENT_COMPLAINT = 9L; // 余额明细事件:客诉垫付/扣划

    @Autowired
    private CoopComplaintMapper coopComplaintMapper;

    @Autowired
    private com.chengyinhub.business.service.support.ComplaintCloseReporterNotice complaintCloseReporterNotice;
    @Autowired
    private com.chengyinhub.business.service.support.CommittedOfficialNoticeService committedOfficialNoticeService;
    @Autowired
    private IUmsMemberService umsMemberService;
    @Autowired
    private IUmsMemberBalanceDetailService umsMemberBalanceDetailService;
    /** A3：正向补偿必须在同一事务里登记冷静期；缺 bean 时 Spring 起不来，绝不 fail-open。 */
    @Autowired
    private IPlayerWithdrawalCooldownService playerWithdrawalCooldownService;
    /** 过错方余额扣不够的差额记欠款,从其后续结算抵扣(9-16 拍板第 2 条)。 */
    @Autowired
    private com.chengyinhub.business.service.support.SettlementDebtLedger settlementDebtLedger;

    @Override
    public Long create(CoopComplaint c) {
        c.setStatus(CoopComplaint.STATUS_ACCEPTED);
        if (c.getDelFlag() == null) c.setDelFlag(0);
        c.setCreateTime(DateUtils.getNowDate());
        c.setUpdateTime(DateUtils.getNowDate());
        coopComplaintMapper.insertCoopComplaint(c);
        return c.getId();
    }

    @Override
    public java.util.List<CoopComplaint> listByTopic(Long topicId) {
        CoopComplaint q = new CoopComplaint();
        q.setTopicId(topicId);
        return coopComplaintMapper.selectCoopComplaintList(q);
    }

    @Override
    public java.util.List<CoopComplaint> selectList(CoopComplaint q) {
        return coopComplaintMapper.selectCoopComplaintList(q);
    }

    @Override
    public java.util.List<com.chengyinhub.business.domain.vo.CoopComplaintProgressVO> listMine(Long memberId) {
        if (memberId == null || memberId <= 0) throw new IllegalArgumentException("请先登录");
        return coopComplaintMapper.selectMine(memberId);
    }

    @Override
    public CoopComplaint getById(Long id) {
        return coopComplaintMapper.selectCoopComplaintById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int advance(Long id, BigDecimal amount, Long handlerId) {
        CoopComplaint c = requireOpen(id);
        // 幂等/防重复垫付:只有未垫付(ACCEPTED)态可垫付;已 ADVANCED/RECOVERING 再调=重复给玩家打真金=资损
        if (c.getStatus() == null || c.getStatus() != CoopComplaint.STATUS_ACCEPTED) {
            throw new IllegalStateException("工单已垫付或处理中,勿重复垫付");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("垫付额须大于0");
        if (c.getReporterMemberId() == null) throw new IllegalStateException("工单无投诉人");
        // ★先抢占再动钱。上面那个 Java 判断只是给用户一句人话，它是 check-then-act:
        //   两个并发 advance 都能读到 ACCEPTED，谁都不会在这里被拦下。
        //   真防线是这条带旧状态的 CAS —— 输的一方 rows=0，在任何余额变动之前就出局。
        if (coopComplaintMapper.claimAcceptedForAdvance(id, amount, handlerId, DateUtils.getNowDate()) != 1) {
            throw new IllegalStateException("工单已垫付或处理中,勿重复垫付");
        }
        UmsMemberBalanceDetail detail = moveBalance(c.getReporterMemberId(), amount, true,
                "客诉先行垫付-工单" + id, id);
        // 同事务登记 48h 冷静期，用刚插入那条明细的 generated id;
        // 禁止按 complaintId 二次模糊查「刚才那条」，也禁止把它挪到事务外。
        playerWithdrawalCooldownService.registerFromPositiveComplaintDetail(
                detail.getId(), c.getReporterMemberId(), id, amount);
        committedOfficialNoticeService.enqueueOfficial("complaint-advance:" + id, c.getReporterMemberId(), "投诉处理有进展",
                "工单#" + id + "已先行垫付 ¥" + amount.toPlainString() + "，请在投诉记录查看处理进度。");
        return 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int close(Long id, String respondentType, Long respondentMemberId, Integer faultRatio,
                     BigDecimal deductAmount, String note, Long handlerId) {
        CoopComplaint c = requireOpen(id);
        // 与垫付共用工单行的条件更新；旧状态已变化时先拒绝，不能再按旧快照扣款。
        if (coopComplaintMapper.claimOpenForClose(id, c.getStatus(), DateUtils.getNowDate()) != 1) {
            throw new IllegalStateException("工单状态已变化，请刷新后重试");
        }
        Long respondent = respondentMemberId != null ? respondentMemberId : c.getRespondentMemberId(); // 客服认定,缺省用已存值
        BigDecimal actualDeduct = BigDecimal.ZERO;
        if (deductAmount != null && deductAmount.compareTo(BigDecimal.ZERO) > 0 && respondent != null) {
            UmsMember r = umsMemberService.selectUmsMemberById(respondent);
            BigDecimal bal = r != null && r.getBalance() != null ? r.getBalance() : BigDecimal.ZERO;
            actualDeduct = bal.compareTo(deductAmount) >= 0 ? deductAmount : bal; // 余额不足只扣可扣部分
            if (actualDeduct.compareTo(BigDecimal.ZERO) > 0) {
                moveBalance(respondent, actualDeduct, false, "客诉过错扣划-工单" + id, id);
            }
            if (actualDeduct.compareTo(deductAmount) < 0) {
                logger.warn("客诉扣划不足:工单{} 过错方{} 应扣{} 实扣{},差额记欠款从后续结算抵扣",
                        id, respondent, deductAmount, actualDeduct);
                settlementDebtLedger.record(respondent,
                        com.chengyinhub.business.service.support.SettlementDebtLedger.SOURCE_COMPLAINT,
                        id, deductAmount.subtract(actualDeduct));
            }
        }
        CoopComplaint upd = new CoopComplaint();
        upd.setId(id);
        upd.setRespondentType(respondentType != null ? respondentType : c.getRespondentType());
        upd.setRespondentMemberId(respondent);
        upd.setStatus(CoopComplaint.STATUS_CLOSED); // 关闭=解冻,该主题结算下轮放款
        upd.setFaultRatio(faultRatio);
        upd.setDeductAmount(actualDeduct);
        upd.setHandleNote(note);
        upd.setHandlerId(handlerId);
        upd.setUpdateTime(DateUtils.getNowDate());
        coopComplaintMapper.updateCoopComplaint(upd);
        complaintCloseReporterNotice.submitReporter(id, c.getReporterMemberId(), handlerId, "投诉工单已处理",
                "工单#" + id + "已关闭，请在投诉记录查看处理说明。");
        if (respondent != null && respondent > 0 && !respondent.equals(c.getReporterMemberId())) {
            committedOfficialNoticeService.enqueueOfficial("complaint-close-respondent:" + id, respondent, "投诉工单处理结果",
                    "工单#" + id + "已处理，实际扣划 ¥" + actualDeduct.toPlainString() + "。请核对账务及处理说明。");
        }
        return 1;
    }

    private CoopComplaint requireOpen(Long id) {
        CoopComplaint c = coopComplaintMapper.selectCoopComplaintById(id);
        if (c == null || c.getStatus() == null || c.getStatus() >= CoopComplaint.STATUS_CLOSED) {
            throw new IllegalStateException("工单不存在或已关闭");
        }
        return c;
    }

    /**
     * 余额加/减 + 写审计明细。add=true 加(垫付给玩家),false 减(扣划过错方)。
     *
     * <p>返回已插入的明细：insertUmsMemberBalanceDetail 用 useGeneratedKeys 回填了 id，
     * 调用方直接拿它登记冷静期，不必再按 complaintId 反查「刚才那条」。
     */
    private UmsMemberBalanceDetail moveBalance(Long memberId, BigDecimal amount, boolean add, String reason,
                                               Long complaintId) {
        UmsMember before = umsMemberService.selectUmsMemberById(memberId);
        BigDecimal cur = before != null && before.getBalance() != null ? before.getBalance() : BigDecimal.ZERO;
        if (add) umsMemberService.addBalance(memberId, amount);
        else umsMemberService.deductBalance(memberId, amount);
        UmsMember after = umsMemberService.selectUmsMemberById(memberId);
        UmsMemberBalanceDetail d = new UmsMemberBalanceDetail();
        d.setMemberId(memberId);
        d.setCurrentBalance(cur);
        d.setAfterBalance(after != null ? after.getBalance() : null);
        d.setChangeBalance(add ? amount : amount.negate());
        d.setChangeReason(reason);
        d.setEventType(EVENT_COMPLAINT);
        d.setEventId(complaintId);
        d.setChangeType(add ? 1L : 2L);
        d.setDelFlag(0L);
        d.setCreateTime(DateUtils.getNowDate());
        umsMemberBalanceDetailService.insertUmsMemberBalanceDetail(d);
        return d;
    }
}
