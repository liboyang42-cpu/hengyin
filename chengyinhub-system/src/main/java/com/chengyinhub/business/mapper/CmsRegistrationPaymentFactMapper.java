package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.dto.RegistrationPaymentFactReceipt;
import org.apache.ibatis.annotations.Param;

/** The registration payment owner only appends after its existing successful business mutation. */
public interface CmsRegistrationPaymentFactMapper {
    int insertConsumed(@Param("registrationId") Long registrationId,
                       @Param("factId") long factId, @Param("factHash") String factHash);
    /** Immutable committed receipt; consumer must use a fresh read transaction when its RR snapshot may be older. */
    RegistrationPaymentFactReceipt selectByRegistration(@Param("registrationId") Long registrationId);
    RegistrationPaymentFactReceipt selectByRegistrationForUpdate(@Param("registrationId") Long registrationId);
}
