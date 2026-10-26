package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CoopInviteRequestReceipt;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface CoopInviteRequestReceiptMapper {
    CoopInviteRequestReceipt selectByRequest(@Param("principalMemberId") Long principalMemberId, @Param("requestId") String requestId);
    /** Current read after a unique-key wait; a previous RR snapshot must not hide the winner. */
    CoopInviteRequestReceipt selectByRequestForUpdate(@Param("principalMemberId") Long principalMemberId, @Param("requestId") String requestId);
    List<com.chengyinhub.business.domain.CoopInvite> selectTopicInvitesForUpdate(@Param("topicId") Long topicId, @Param("now") java.util.Date now);
    int insertReceipt(CoopInviteRequestReceipt receipt);
    int bindInvite(@Param("id") Long id, @Param("inviteId") Long inviteId);
    List<CoopInviteRequestReceipt> selectPage(@Param("principalMemberId") Long principalMemberId,
            @Param("before") Long before, @Param("limit") int limit);
}
