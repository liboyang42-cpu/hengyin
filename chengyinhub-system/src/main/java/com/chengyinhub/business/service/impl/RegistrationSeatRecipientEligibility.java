package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.RegistrationSeatInvitation;
import com.chengyinhub.business.domain.SeatRecipientFact;
import com.chengyinhub.business.mapper.RegistrationSeatInvitationMapper;
import com.chengyinhub.business.service.IRegistrationSeatRecipientEligibility;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 只读投影：原已付行 + 已领取邀请；退款/撤销由 SQL 的已付形状直接收口。 */
@Service
public class RegistrationSeatRecipientEligibility implements IRegistrationSeatRecipientEligibility {

    private final RegistrationSeatInvitationMapper invitations;

    public RegistrationSeatRecipientEligibility(RegistrationSeatInvitationMapper invitations) {
        this.invitations = invitations;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SeatRecipientFact> playFacts(Long memberId, long ownerType, Long ownerId) {
        if (memberId == null || memberId <= 0L || ownerId == null
                || !(ownerType == 1L || ownerType == 2L)) {
            return Collections.emptyList();
        }
        List<SeatRecipientFact> facts = invitations.selectClaimedFactsByRecipient(memberId, ownerType, ownerId);
        return facts == null ? Collections.<SeatRecipientFact>emptyList() : facts;
    }

    @Override
    @Transactional
    public boolean claimedUnderRegistrationLock(SeatRecipientFact fact, Long memberId, CmsRegistration registration) {
        if (fact == null || fact.getInvitationId() == null || memberId == null || registration == null
                || !Objects.equals(fact.getRegistrationId(), registration.getId())
                || !Integer.valueOf(2).equals(registration.getRegistrationStatus())
                || !Integer.valueOf(2).equals(registration.getPaymentStatus())) return false;
        RegistrationSeatInvitation row = invitations.selectByIdForUpdate(fact.getInvitationId());
        return row != null && row.claimed()
                && Objects.equals(row.getRegistrationId(), registration.getId())
                && Objects.equals(row.getInviteeMemberId(), memberId)
                && Objects.equals(row.getOwnerMemberId(), registration.getMemberId())
                && Objects.equals(row.getOwnerType(), registration.getOwnerType())
                && Objects.equals(row.getOwnerId(), registration.getOwnerId())
                && row.getSeatNo() != null && row.getSeatNo() >= 2
                && registration.getOrderNum() != null && row.getSeatNo() <= registration.getOrderNum()
                && Objects.equals(row.getSeatNo(), fact.getSeatNo());
    }
}
