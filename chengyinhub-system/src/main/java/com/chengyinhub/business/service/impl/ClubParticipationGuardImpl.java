package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ClubGovernanceBan;
import com.chengyinhub.business.mapper.ClubGovernanceMapper;
import com.chengyinhub.business.service.IClubParticipationGuard;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

@Service
public class ClubParticipationGuardImpl implements IClubParticipationGuard {
    @Autowired private ClubGovernanceMapper clubGovernanceMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void requireAllowed(Long clubId, Long memberId) {
        if (!positive(clubId) || !positive(memberId)) throw forbidden();
        Date now = new Date();
        clubGovernanceMapper.expireStaleByClubAndMember(clubId, memberId, now);
        ClubGovernanceBan row = clubGovernanceMapper.selectEffectiveByClubAndMember(clubId, memberId, now);
        if (row == null) return;
        if (!clubId.equals(row.getClubId()) || !memberId.equals(row.getTargetMemberId())
                || !ClubGovernanceBan.STATUS_ACTIVE.equals(row.getStatus())) throw forbidden();
        if (ClubGovernanceBan.SOURCE_CLUB.equals(row.getSourceType())) {
            if (row.getExpiresAt() == null) throw forbidden();
            if (!row.getExpiresAt().after(now)) return;
            throw blocked();
        }
        if (ClubGovernanceBan.SOURCE_PLATFORM.equals(row.getSourceType())) {
            if (row.getExpiresAt() != null) throw forbidden();
            throw blocked();
        }
        throw forbidden();
    }

    private boolean positive(Long value) { return value != null && value > 0; }
    private ServiceException blocked() {
        return new ServiceException("你当前无法参与该俱乐部", HttpStatus.FORBIDDEN);
    }
    private ServiceException forbidden() {
        return new ServiceException("俱乐部治理状态无效", HttpStatus.FORBIDDEN);
    }
}
