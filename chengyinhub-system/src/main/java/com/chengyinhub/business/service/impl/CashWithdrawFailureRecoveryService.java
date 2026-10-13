package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MemberCashWithdraw;
import com.chengyinhub.business.mapper.MemberCashWithdrawMapper;
import com.chengyinhub.business.service.IUmsMemberService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.Date;

/** 微信明确失败后的原账回补；外部查询在调用方，CAS/余额/终态在此同事务。 */
@Service
public class CashWithdrawFailureRecoveryService {
    @Autowired private MemberCashWithdrawMapper mapper;
    @Autowired private IUmsMemberService umsMemberService;

    /** 提现原账与CW/CD原业务号先同事务提交，再由调用方扣余额/调用外部渠道。 */
    @Transactional(rollbackFor = Exception.class)
    public void prepare(MemberCashWithdraw original) {
        if (mapper.insertMemberCashWithdraw(original) != 1 || original.getId() == null) {
            throw new IllegalStateException("提现原账写入失败");
        }
        MemberCashWithdraw identity = new MemberCashWithdraw();
        identity.setId(original.getId()); identity.setOutBatchNo("CW" + original.getId());
        identity.setOutDetailNo("CD" + original.getId());
        if (mapper.updateMemberCashWithdraw(identity) != 1) {
            throw new IllegalStateException("提现原业务号写入失败，未扣款");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean recover(Long id, Long memberId, BigDecimal amount, Date now,
                           String reason, String channelErrorCode, String detailId) {
        MemberCashWithdraw claim = new MemberCashWithdraw();
        claim.setId(id); claim.setStatus(MemberCashWithdraw.STATUS_FAILED_MANUAL);
        claim.setLastQueryTime(now); claim.setFailReason(reason);
        claim.setChannelErrorCode(channelErrorCode); claim.setWechatDetailId(detailId);
        // CAS拿到该行锁后回读当前原账；输者不动余额。冻结条件仍由现有mapper权威执行。
        if (mapper.updateStatusIfProcessing(claim) != 1) return false;
        MemberCashWithdraw current = mapper.selectById(id);
        if (current == null || memberId == null || !memberId.equals(current.getMemberId())
                || amount == null || current.getAmount() == null || amount.compareTo(current.getAmount()) != 0) {
            throw new IllegalStateException("提现原账回读不一致");
        }
        umsMemberService.addBalance(current.getMemberId(), current.getAmount());
        MemberCashWithdraw refunded = new MemberCashWithdraw();
        refunded.setId(id); refunded.setStatus(MemberCashWithdraw.STATUS_FAILED_REFUNDED);
        refunded.setFinishTime(now); refunded.setAuditBy(0L); refunded.setAuditTime(now);
        if (mapper.updateMemberCashWithdraw(refunded) != 1) {
            throw new IllegalStateException("提现回补终态写入失败");
        }
        return true;
    }
}
