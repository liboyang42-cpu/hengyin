package com.chengyinhub.business.mapper.friend;

import com.chengyinhub.business.domain.friend.FriendPersistenceRow;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * Durable storage primitives only. A transaction service must supply authenticated actorId,
 * serialize quota/block checks, validate/moderate request messages, and persist command receipts.
 * This mapper does not authorize an HTTP operation and is not called by a controller yet.
 * Locks and CAS writes must share the same transaction. Zero affected rows is not success.
 */
public interface FriendPersistenceMapper
{
    FriendPersistenceRow selectPairForUpdate(@Param("actorId") long actorId,
            @Param("peerId") long peerId);

    int insertPending(@Param("actorId") long actorId, @Param("peerId") long peerId,
            @Param("message") String message, @Param("sourceType") int sourceType,
            @Param("sourceId") Long sourceId);

    int acceptIncoming(@Param("actorId") long actorId, @Param("peerId") long peerId,
            @Param("expectedVersion") long expectedVersion);

    int rejectIncoming(@Param("actorId") long actorId, @Param("peerId") long peerId,
            @Param("expectedVersion") long expectedVersion, @Param("reason") int reason);

    int withdrawOwn(@Param("actorId") long actorId, @Param("peerId") long peerId,
            @Param("expectedVersion") long expectedVersion);

    /** Must be in the same transaction as clearPairMetadata and command receipt creation. */
    int removeFriend(@Param("actorId") long actorId, @Param("peerId") long peerId,
            @Param("expectedVersion") long expectedVersion);

    int clearPairMetadata(@Param("actorId") long actorId, @Param("peerId") long peerId);

    /** Private metadata is selected only for actorId, never the reverse edge. */
    Map<String, Object> selectOwnMetadata(@Param("actorId") long actorId,
            @Param("peerId") long peerId);
}
