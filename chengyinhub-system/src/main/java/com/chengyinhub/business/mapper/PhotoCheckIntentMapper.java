package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.PhotoCheckIntentRow;
import org.apache.ibatis.annotations.Param;

public interface PhotoCheckIntentMapper {
    int insertPrepared(PhotoCheckIntentRow row);
    PhotoCheckIntentRow selectByKey(@Param("memberId") Long memberId,
            @Param("sessionId") Long sessionId, @Param("actionKey") String actionKey);
    PhotoCheckIntentRow selectByKeyForUpdate(@Param("memberId") Long memberId,
            @Param("sessionId") Long sessionId, @Param("actionKey") String actionKey);
    PhotoCheckIntentRow selectOwned(@Param("id") Long id, @Param("memberId") Long memberId,
            @Param("fingerprint") String fingerprint);
    PhotoCheckIntentRow selectForUpdate(@Param("id") Long id, @Param("fingerprint") String fingerprint);
    int claim(@Param("id") Long id, @Param("memberId") Long memberId, @Param("fingerprint") String fingerprint);
    int saveSnapshot(PhotoCheckIntentRow row);
    int saveResult(PhotoCheckIntentRow row);
    int markUnknown(@Param("id") Long id, @Param("fingerprint") String fingerprint);
    int countMatchingReceipt(@Param("id") Long id, @Param("fingerprint") String fingerprint,
            @Param("attemptLogId") Long attemptLogId);
    int markApplied(@Param("id") Long id, @Param("fingerprint") String fingerprint,
            @Param("attemptLogId") Long attemptLogId);
}
