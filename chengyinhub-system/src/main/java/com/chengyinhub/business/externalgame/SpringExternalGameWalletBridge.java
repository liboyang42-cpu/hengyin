package com.chengyinhub.business.externalgame;

import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.business.service.IUmsMemberPointsDetailService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Default wallet bridge backed by UmsMember points and TEST balance ledgers.
 */
@Component
public class SpringExternalGameWalletBridge implements ExternalGameWalletBridge {
    private static final long EVENT_TYPE_EXTGAME_REWARD = 99L;
    private static final long BALANCE_EVENT_TYPE_EXTGAME = 6L;

    private final UmsMemberMapper memberMapper;
    private final IUmsMemberService memberService;
    private final IUmsMemberPointsDetailService pointsDetailService;
    private final IUmsMemberBalanceDetailService balanceDetailService;
    /** G3: optional so existing wiring and tests are unchanged; null means ungoverned (as before). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.chengyinhub.business.pointsgov.PointsGovernanceService governance;

    public SpringExternalGameWalletBridge(
            UmsMemberMapper memberMapper,
            IUmsMemberService memberService,
            IUmsMemberPointsDetailService pointsDetailService,
            IUmsMemberBalanceDetailService balanceDetailService) {
        this.memberMapper = memberMapper;
        this.memberService = memberService;
        this.pointsDetailService = pointsDetailService;
        this.balanceDetailService = balanceDetailService;
    }

    @Override
    public void creditPoints(long memberId, int points, String businessKey, String reason) {
        if (points <= 0) return;
        Long currentRaw = memberMapper.selectPointsForUpdate(memberId);
        long current = currentRaw == null ? 0L : currentRaw;
        if (!memberService.addPoints(memberId, points)) {
            throw new IllegalStateException("外部游戏积分入账失败: memberId=" + memberId);
        }
        UmsMemberPointsDetail detail = new UmsMemberPointsDetail();
        detail.setMemberId(memberId);
        detail.setCurrentPoints(current);
        detail.setAfterPoints(current + points);
        detail.setChangePoints((long) points);
        detail.setChangeType(1L);
        detail.setEventType(EVENT_TYPE_EXTGAME_REWARD);
        detail.setRewardBusinessKey(businessKey);
        detail.setChangeReason(reason);
        detail.setDelFlag(0);
        detail.setCreateTime(DateUtils.getNowDate());
        if (pointsDetailService.insertUmsMemberPointsDetail(detail) != 1) {
            throw new IllegalStateException("外部游戏积分明细写入失败: memberId=" + memberId);
        }
    }

    @Override
    public PointsCredit creditPointsGoverned(long memberId, int points, String businessKey, String reason) {
        if (points <= 0) return new PointsCredit(0, "NOTHING");
        // Lock the member row first so the governance daily sum and the credit see the same state.
        memberMapper.selectPointsForUpdate(memberId);
        if (governance != null) {
            com.chengyinhub.business.pointsgov.PointsGovernanceService.Admission admission =
                    governance.admit(memberId, EVENT_TYPE_EXTGAME_REWARD, points);
            if (!admission.admitted()) return new PointsCredit(0, admission.outcome());
            creditPoints(memberId, points, businessKey, reason);
            return new PointsCredit(points, admission.outcome());
        }
        creditPoints(memberId, points, businessKey, reason);
        return new PointsCredit(points, "UNGOVERNED");
    }

    @Override
    public void creditBalanceFen(long memberId, long fen, String businessKey, String reason) {
        if (fen <= 0L) return;
        BigDecimal currentRaw = memberMapper.selectBalanceForUpdate(memberId);
        BigDecimal current = currentRaw == null ? BigDecimal.ZERO : currentRaw;
        BigDecimal delta = BigDecimal.valueOf(fen).divide(BigDecimal.valueOf(100), 2, RoundingMode.UNNECESSARY);
        if (!memberService.addBalance(memberId, delta)) {
            throw new IllegalStateException("外部游戏分成余额入账失败: memberId=" + memberId);
        }
        UmsMemberBalanceDetail detail = new UmsMemberBalanceDetail();
        detail.setMemberId(memberId);
        detail.setCurrentBalance(current);
        detail.setAfterBalance(current.add(delta));
        detail.setChangeBalance(delta);
        detail.setChangeType(1L);
        detail.setEventType(BALANCE_EVENT_TYPE_EXTGAME);
        detail.setChangeReason(reason + (businessKey == null ? "" : " [" + businessKey + "]"));
        detail.setDelFlag(0L);
        detail.setCreateTime(DateUtils.getNowDate());
        if (balanceDetailService.insertUmsMemberBalanceDetail(detail) != 1) {
            throw new IllegalStateException("外部游戏余额明细写入失败: memberId=" + memberId);
        }
    }
}
