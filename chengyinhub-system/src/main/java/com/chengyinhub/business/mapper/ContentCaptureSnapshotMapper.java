package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ContentCaptureSnapshot;
import org.apache.ibatis.annotations.Param;

/** Insert/read only. No update, delete, approval, publication or release identity allocation. */
public interface ContentCaptureSnapshotMapper {
    ContentCaptureSnapshot selectOwnedRequest(@Param("topicId") long topicId,
            @Param("memberId") long memberId, @Param("requestId") String requestId);
    int insertFrozen(ContentCaptureSnapshot snapshot);
}
