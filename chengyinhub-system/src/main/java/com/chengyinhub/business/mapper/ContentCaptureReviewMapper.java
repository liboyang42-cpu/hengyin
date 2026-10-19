package com.chengyinhub.business.mapper;
import com.chengyinhub.business.domain.*;
import org.apache.ibatis.annotations.Param;
import java.util.List;
/** Current locking source reads and append-only review bindings; no release write. */
public interface ContentCaptureReviewMapper {
    CmsTopic lockTopic(@Param("topicId") long topicId);
    List<ProductRole> lockContentOwners(@Param("topicId") long topicId);
    ContentCaptureSnapshot lockSnapshot(@Param("snapshotId") long snapshotId);
    ContentCaptureReviewBinding selectRequest(@Param("topicId") long topicId, @Param("memberId") long memberId, @Param("requestId") String requestId);
    ContentCaptureReviewBinding selectSnapshot(@Param("snapshotId") long snapshotId);
    ContentCaptureReviewBinding selectTask(@Param("taskId") long taskId);
    AuditRecord selectLatestDecision(@Param("taskId") long taskId);
    int insertBinding(ContentCaptureReviewBinding binding);
}
