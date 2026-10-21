package com.chengyinhub.business.mapper.merchantauthoring;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.chengyinhub.business.domain.merchantauthoring.MerchantAuthoringRows.Approval;
import com.chengyinhub.business.domain.merchantauthoring.MerchantAuthoringRows.Draft;
import com.chengyinhub.business.domain.merchantauthoring.MerchantAuthoringRows.Environment;
import com.chengyinhub.business.domain.merchantauthoring.MerchantAuthoringRows.Job;
import com.chengyinhub.business.domain.merchantauthoring.MerchantAuthoringRows.Release;
import com.chengyinhub.business.domain.merchantauthoring.MerchantAuthoringRows.Revision;

public interface MerchantAuthoringMapper {
    int insertJob(Job row);

    Job findJob(@Param("ownerId") Long ownerId, @Param("requestId") String requestId);

    int updateClaimedJob(Job row);

    int cancelJob(@Param("ownerId") Long ownerId, @Param("id") Long id, @Param("updatedAtMs") long updatedAtMs);

    int insertDraft(Draft row);

    Draft findDraftById(@Param("id") String id);

    Draft lockDraft(@Param("id") String id, @Param("merchantId") Long merchantId);

    List<Draft> listDrafts(@Param("merchantId") Long merchantId);

    int casDraftVersion(@Param("id") String id, @Param("ownerMerchantId") Long merchantId,
            @Param("expected") int expected, @Param("next") int next);

    int insertRevision(Revision row);

    Revision findRevision(@Param("draftId") String draftId, @Param("version") int version);

    int insertApproval(Approval row);

    Approval findApproval(@Param("draftId") String draftId, @Param("version") int version, @Param("kind") String kind);

    int invalidateApprovals(@Param("draftId") String draftId);

    int insertRelease(Release row);

    Release findRelease(@Param("ownerId") Long ownerId, @Param("requestId") String requestId);

    Release findReleaseById(@Param("id") Long id);

    Release latestReleaseForDraft(@Param("draftId") String draftId);

    int withdrawRelease(@Param("ownerId") Long ownerId, @Param("id") Long id,
            @Param("withdrawnAtMs") long withdrawnAtMs, @Param("withdrawRequestId") String withdrawRequestId);

    Release findPublic(@Param("merchantId") Long merchantId, @Param("themeId") Long themeId,
            @Param("releaseId") Long releaseId, @Param("runId") String runId, @Param("bindingKey") String bindingKey);

    Release latestPublished(@Param("merchantId") Long merchantId, @Param("themeId") Long themeId);

    int insertEnvironment(Environment row);

    Environment findEnvironment(@Param("releaseId") Long releaseId);
}
