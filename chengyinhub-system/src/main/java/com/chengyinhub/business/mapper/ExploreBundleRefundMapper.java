package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ExploreBundleRefundHold;
import org.apache.ibatis.annotations.Param;

public interface ExploreBundleRefundMapper {
    ExploreBundleRefundHold selectByRegistration(@Param("registrationId") Long registrationId);
    ExploreBundleRefundHold selectByRegistrationForUpdate(@Param("registrationId") Long registrationId);
    int insert(ExploreBundleRefundHold hold);
    int update(ExploreBundleRefundHold hold);
}
