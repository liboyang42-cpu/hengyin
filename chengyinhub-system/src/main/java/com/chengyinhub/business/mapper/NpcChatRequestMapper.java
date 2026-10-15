package com.chengyinhub.business.mapper;

import java.util.Date;

import org.apache.ibatis.annotations.Param;

import com.chengyinhub.business.domain.NpcChatRequest;

/** NPC Chat requestId 幂等状态 Mapper。 */
public interface NpcChatRequestMapper
{
    /** Existing member row is the persistent serialization point; no new schema or provider lock. */
    Long lockChatUser(@Param("userId") Long userId);

    /** Erase answer copies and revoke old workers, retaining request identity against paid replay. */
    int redactChatRequestsByUserId(@Param("userId") Long userId);

    NpcChatRequest selectByUserAndRequestId(@Param("userId") Long userId,
                                             @Param("requestId") String requestId);

    int insertNpcChatRequest(NpcChatRequest request);

    /** 失去 lease 后结果未知；保留进行中槽位，不能因超时盲重调提供方。 */
    int expireProcessingByUserAndSession(@Param("userId") Long userId,
                                         @Param("sessionId") Long sessionId,
                                         @Param("safeText") String safeText);

    /** 仅拥有本次 lease 的调用能写入终态，防止迟到 provider 响应覆盖新状态。 */
    int finishIfLeaseOwned(NpcChatRequest request);

    /** provider 调用未完成且租约仍有效时续租；过期不能由旧 worker 复活。 */
    int renewLease(@Param("userId") Long userId, @Param("requestId") String requestId,
                   @Param("leaseToken") String leaseToken, @Param("leaseUntil") Date leaseUntil);

    int deleteNpcChatRequestsBefore(@Param("cutoff") Date cutoff, @Param("limit") int limit);
}
