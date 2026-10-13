package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubDirectMerchantReceivable;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.Redemption;
import com.chengyinhub.business.domain.SettlementClaim;
import com.chengyinhub.business.mapper.ClubDirectMerchantReceivableMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.service.ISettlementClaimService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.common.constant.TermsMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.List;

/** 只记录俱乐部与商家之间的应收和已发生的直付；没有钱包、提现或转账依赖。 */
@Service
public class ClubDirectMerchantReceivableService {
    private final ClubDirectMerchantReceivableMapper mapper;
    private final ISettlementClaimService claimService;
    private final ITopicOperationsScopeAuthorizer scope;
    private final ClubMapper clubMapper;

    public ClubDirectMerchantReceivableService(ClubDirectMerchantReceivableMapper mapper,
            ISettlementClaimService claimService, ITopicOperationsScopeAuthorizer scope, ClubMapper clubMapper) {
        this.mapper = mapper;
        this.claimService = claimService;
        this.scope = scope;
        this.clubMapper = clubMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public void accrue(Redemption r, MerchantChapterOffer offer) {
        if (r == null || r.getId() == null || r.getId() <= 0 || offer == null || !TermsMode.REVSHARE.equals(offer.getTermsMode())
                || offer.getPayerClubId() == null || offer.getPayerClubId() <= 0
                || r.getChapterId() == null || !"ACTIVE".equals(r.getStatus())
                || !same(r.getOfferId(), offer.getId()) || !same(r.getTopicId(), offer.getTopicId())
                || !same(r.getChapterId(), offer.getChapterId()) || !same(r.getMerchantId(), offer.getMerchantId())
                || offer.getPerHeadFee() == null || offer.getPerHeadFee().signum() < 0) {
            throw new IllegalStateException("俱乐部直付核销与供给条款不一致");
        }
        // 主核销事务已锁同一权益；唯一 redemption/claim 再守一次，不用忽略插入吞掉异常。
        ClubDirectMerchantReceivable existing = mapper.selectByRedemptionForUpdate(r.getId());
        if (existing != null) {
            if (!same(existing.getPayerClubId(), offer.getPayerClubId())
                    || !same(existing.getOfferId(), offer.getId())
                    || !same(existing.getTopicId(), r.getTopicId())
                    || !same(existing.getChapterId(), r.getChapterId())
                    || !same(existing.getMerchantMemberId(), r.getMerchantId())) {
                throw new IllegalStateException("俱乐部直付付款方或供给已变化");
            }
            return;
        }
        SettlementClaim claim = claimService.claim(SettlementClaim.request(
                "TOPIC_EDITION", r.getTopicId(), "REDEMPTION", r.getId(), "MERCHANT", r.getMerchantId(),
                "CHAPTER_SUPPLY", "CLUB_DIRECT", "MERCHANT_CHAPTER_OFFER", offer.getId()));
        ClubDirectMerchantReceivable row = new ClubDirectMerchantReceivable();
        row.setClaimId(claim.getId());
        row.setRedemptionId(r.getId());
        row.setOfferId(offer.getId());
        row.setTopicId(r.getTopicId());
        row.setChapterId(r.getChapterId());
        row.setPayerClubId(offer.getPayerClubId());
        row.setMerchantMemberId(r.getMerchantId());
        row.setPerHeadFeeSnapshot(offer.getPerHeadFee());
        if (mapper.insertReceivable(row) != 1) throw new IllegalStateException("俱乐部直付应收记账失败");
    }

    @Transactional(rollbackFor = Exception.class)
    public void reverse(Long redemptionId) {
        ClubDirectMerchantReceivable row = mapper.selectByRedemptionForUpdate(redemptionId);
        if (row == null || !"OPEN".equals(row.getStatus()) && !"SETTLED".equals(row.getStatus())) return;
        if (mapper.reverse(row) != 1) throw new IllegalStateException("俱乐部直付应收撤销状态已变化");
    }

    public List<ClubDirectMerchantReceivable> listForAdmin(Long topicId) {
        if (topicId == null || !scope.canViewTopic(topicId)) throw new IllegalStateException("无主题账目查看范围");
        return mapper.selectScoped(topicId, null, null);
    }

    public List<ClubDirectMerchantReceivable> listForMerchant(Long topicId, Long ownerMemberId) {
        if (ownerMemberId == null || ownerMemberId <= 0) throw new IllegalStateException("缺少商家");
        return mapper.selectScoped(topicId, null, ownerMemberId);
    }

    public List<ClubDirectMerchantReceivable> listForClub(Long topicId, Long clubId, Long memberId) {
        Club club = clubId == null ? null : clubMapper.selectClubById(clubId);
        if (club == null || !same(club.getMemberId(), memberId)) {
            throw new IllegalStateException("无该俱乐部账目查看范围");
        }
        return mapper.selectScoped(topicId, clubId, null);
    }

    /** 后台已有财务权限和主题写范围，只记录整笔已付款/已退回事实，不模拟付款成功。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean record(Long id, Long topicId, Long payerClubId, Long merchantMemberId,
            BigDecimal amount, String actor, String voucher, boolean refund) {
        if (topicId == null || !scope.canEditTopic(topicId)) throw new IllegalStateException("无主题账目修改范围");
        if (actor == null || actor.trim().isEmpty() || voucher == null || voucher.trim().isEmpty()
                || voucher.trim().length() > 128) throw new IllegalStateException("请填写有效付款凭证，最长128字");
        ClubDirectMerchantReceivable row = mapper.selectOwnedForUpdate(id, topicId, payerClubId, merchantMemberId);
        if (row == null) throw new IllegalStateException("账目不存在或付款俱乐部、收款商家不一致");
        if (amount == null || amount.signum() <= 0 || amount.compareTo(row.getPerHeadFeeSnapshot()) != 0) {
            throw new IllegalStateException("记录金额必须等于该笔核销的应收金额");
        }
        return refund ? mapper.recordRefunded(row, actor, voucher.trim()) == 1
                : mapper.recordSettled(row, actor, voucher.trim()) == 1;
    }

    private static boolean same(Long a, Long b) { return a != null && a.equals(b); }
}
