package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubWaitlistClaim;
import com.chengyinhub.business.domain.dto.RegistrationRequest;

import java.util.Date;

/** 候补 hold 与报名事务之间的唯一桥接 seam。 */
public interface IClubWaitlistOrderBridge {
    /** 只校验当前 OFFERED hold，供无副作用报价使用；不得在此推进状态或再次扣库存。 */
    ClubWaitlistClaim validateForQuote(Long memberId, RegistrationRequest request, Date now);
    ClubWaitlistClaim claimForOrder(Long memberId, RegistrationRequest request, Date now);
    void bindRegistration(ClubWaitlistClaim claim, Long registrationId);
    void convertedByRegistration(Long registrationId);
}
