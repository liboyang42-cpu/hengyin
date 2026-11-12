package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubPayoutBeneficiary;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubPayoutBeneficiaryMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.payout.PayoutSwitches;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Cash belongs to a person. Personal payout freezes that person at the first obligation. */
@Component
public class ClubPrincipalPayee {
    @Autowired private ClubMapper clubMapper;
    @Autowired private UmsMemberMapper members;
    @Autowired private ClubPayoutBeneficiaryMapper beneficiaries;
    @Autowired private PayoutSwitches switches;

    public boolean activityAlreadyReady(Long activityId) {
        ClubPayoutBeneficiary frozen = beneficiaries.selectFrozenForUpdate("ACTIVITY", activityId);
        if (frozen == null) throw new ServiceException("CLUB_BENEFICIARY_REQUIRED");
        return frozen.getPayoutReadyAt() != null;
    }

    public void markActivityReady(Long activityId) {
        if (beneficiaries.markReady("ACTIVITY", activityId) != 1) throw new ServiceException("CLUB_BENEFICIARY_READY_FAILED");
    }

    public boolean personalActivity(CmsActivity activity) {
        return activity != null && activity.getClubId() != null && activity.getClubId() > 0
                && (personalEnabled() || beneficiaries != null && beneficiaries.selectFrozen("ACTIVITY", activity.getId()) != null);
    }

    private boolean personalTopic(CmsTopic topic) {
        return topic != null && topic.getClubId() != null && topic.getClubId() > 0
                && (personalEnabled() || beneficiaries != null && beneficiaries.selectFrozen("TOPIC", topic.getId()) != null);
    }

    public boolean personalEnabled() { return switches != null && switches.personalClubEnabled(); }

    public Long forActivity(CmsActivity activity) {
        if (activity == null) return null;
        if (activity.getClubId() == null || activity.getClubId() <= 0) return activity.getMemberId();
        if (personalActivity(activity)) return freeze("ACTIVITY", activity.getId(), activity.getClubId(), null).getMemberId();
        return principalOf(activity.getClubId());
    }

    public Long forTopic(CmsTopic topic) {
        if (topic == null) return null;
        if (topic.getClubId() == null || topic.getClubId() <= 0) return topic.getMemberId();
        if (personalTopic(topic)) return freeze("TOPIC", topic.getId(), topic.getClubId(), topic.getMemberId()).getMemberId();
        return principalOf(topic.getClubId());
    }

    public ClubPayoutBeneficiary frozenActivity(CmsActivity activity) {
        return personalActivity(activity)
                ? freeze("ACTIVITY", activity.getId(), activity.getClubId(), null) : null;
    }

    public ClubPayoutBeneficiary frozenTopic(CmsTopic topic) {
        return personalTopic(topic)
                ? freeze("TOPIC", topic.getId(), topic.getClubId(), topic.getMemberId()) : null;
    }

    private ClubPayoutBeneficiary freeze(String scope, Long scopeId, Long clubId, Long relatedMemberId) {
        if (scopeId == null || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new ServiceException("CLUB_BENEFICIARY_TRANSACTION_REQUIRED");
        ClubPayoutBeneficiary prior = beneficiaries.selectFrozen(scope, scopeId);
        if (prior != null) return requireScope(prior, scope, scopeId, clubId);
        Club observed = requireClub(clubMapper.selectClubById(clubId), clubId);
        // transferOwner locks member accounts (ascending) before club. Never acquire club then member.
        java.util.SortedSet<Long> accounts = new java.util.TreeSet<>(); accounts.add(observed.getMemberId());
        if (relatedMemberId != null && relatedMemberId > 0) accounts.add(relatedMemberId);
        for (Long account : accounts) if (!account.equals(members.lockMemberById(account)))
            throw new ServiceException("CLUB_PRINCIPAL_PAYEE_MISSING");
        Club current = requireClub(clubMapper.selectClubByIdForUpdate(clubId), clubId);
        if (!observed.getMemberId().equals(current.getMemberId()))
            throw new ServiceException("CLUB_BENEFICIARY_CHANGED_RETRY");
        prior = beneficiaries.selectFrozenForUpdate(scope, scopeId);
        if (prior != null) return requireScope(prior, scope, scopeId, clubId);
        if (current.getName() == null || current.getName().isBlank())
            throw new ServiceException("CLUB_BENEFICIARY_NAME_REQUIRED");
        ClubPayoutBeneficiary frozen = new ClubPayoutBeneficiary();
        frozen.setScopeType(scope); frozen.setScopeId(scopeId); frozen.setClubId(clubId);
        frozen.setClubName(current.getName()); frozen.setMemberId(current.getMemberId());
        if (beneficiaries.insertFrozen(frozen) != 1) throw new ServiceException("CLUB_BENEFICIARY_FREEZE_FAILED");
        return frozen;
    }

    private ClubPayoutBeneficiary requireScope(ClubPayoutBeneficiary value, String scope, Long id, Long clubId) {
        if (!scope.equals(value.getScopeType()) || !id.equals(value.getScopeId()) || !clubId.equals(value.getClubId())
                || value.getMemberId() == null || value.getMemberId() <= 0 || value.getClubName() == null)
            throw new ServiceException("CLUB_BENEFICIARY_IDENTITY_CONFLICT");
        return value;
    }

    private Long principalOf(Long clubId) { return requireClub(clubMapper.selectClubById(clubId), clubId).getMemberId(); }

    private Club requireClub(Club club, Long clubId) {
        if (club == null || club.getMemberId() == null || club.getMemberId() <= 0)
            throw new ServiceException("CLUB_PRINCIPAL_PAYEE_MISSING: clubId=" + clubId);
        return club;
    }
}
