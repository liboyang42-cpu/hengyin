package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.RoamPresenceRevokeIntent;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface RoamPresenceRevokeIntentMapper {
    Long selectPreviousConsentIdForUpdate(@Param("memberId") Long memberId);
    int insertIntent(RoamPresenceRevokeIntent intent);
    RoamPresenceRevokeIntent selectByRequestId(@Param("requestId") String requestId);
    List<String> selectDue(@Param("limit") int limit);
    int claim(@Param("requestId") String requestId, @Param("ownerToken") String ownerToken);
    int complete(@Param("requestId") String requestId, @Param("ownerToken") String ownerToken);
    int fail(@Param("requestId") String requestId, @Param("ownerToken") String ownerToken);
    int escalateExpired(@Param("requestId") String requestId);
}
