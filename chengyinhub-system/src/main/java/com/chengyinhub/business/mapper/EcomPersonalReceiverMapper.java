package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.EcomPersonalReceiver;
import org.apache.ibatis.annotations.Param;

public interface EcomPersonalReceiverMapper {
    EcomPersonalReceiver selectByMemberAndAppid(@Param("memberId") Long memberId, @Param("appid") String appid);
    EcomPersonalReceiver selectByMemberAndAppidForUpdate(@Param("memberId") Long memberId, @Param("appid") String appid);
    /** Insert only: no update/upsert can replace a verified identity. */
    int insertVerified(EcomPersonalReceiver receiver);
}
