package com.chengyinhub.business.mapper;

import java.util.Date;

import org.apache.ibatis.annotations.Param;

import com.chengyinhub.business.domain.NpcChatRequest;

/** NPC Chat requestId 幂等状态 Mapper。 */
public interface NpcChatRequestMapper
{
    NpcChatRequest selectByUserAndRequestId(@Param("userId") Long userId,
                                             @Param("requestId") String requestId);

    int insertNpcChatRequest(NpcChatRequest request);

    /** 把失去 lease 的请求终态化，释放同一用户同一漫游会话的唯一进行中槽位。 */
    int expireProcessingByUserAndSession(@Param("userId") Long userId,
                                         @Param("sessionId") Long sessionId,
                                         @Param("safeText") String safeText);

    /** 仅拥有本次 lease 的调用能写入终态，防止迟到 provider 响应覆盖新状态。 */
    int finishIfLeaseOwned(NpcChatRequest request);

    /** provider 调用未完成时续租，进程崩溃后仍由 lease 到期释放并发槽位。 */
    int renewLease(@Param("userId") Long userId, @Param("requestId") String requestId,
                   @Param("leaseToken") String leaseToken, @Param("leaseUntil") Date leaseUntil);

    int deleteNpcChatRequestsBefore(@Param("cutoff") Date cutoff, @Param("limit") int limit);
}
