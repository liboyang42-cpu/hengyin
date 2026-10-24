package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.RegistrationSeat;
import com.chengyinhub.business.mapper.RegistrationSeatMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

/**
 * Canonical current seat facts for settlement; no registration command/verification dependency.
 * Caller already owns the original topic/registration locks. This reader joins that transaction
 * and retains both original locking queries; financial eligibility stays with the frozen contract.
 */
@Service
public class RegistrationSeatSettlementReader {
    private final RegistrationSeatMapper seats;

    public RegistrationSeatSettlementReader(RegistrationSeatMapper seats) {
        this.seats = seats;
    }

    /** G reads actual facts after locking its original parents/registrations. No money eligibility inference. */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public List<RegistrationSeat> verifiedSeatsForGroup(Long topicId, Long activityId, Date cutoff) {
        if (topicId == null || topicId <= 0 || activityId == null || activityId <= 0 || cutoff == null) {
            throw new IllegalArgumentException("席位事实查询范围缺失");
        }
        if (!seats.selectUnprovenMultiGroupOrdersForUpdate(topicId, activityId, cutoff).isEmpty()) {
            throw new ServiceException("多人整单核销缺少逐席位证明，不能推算履约费，请人工核验", 409);
        }
        return seats.selectVerifiedForGroupForUpdate(topicId, activityId, cutoff);
    }

}
