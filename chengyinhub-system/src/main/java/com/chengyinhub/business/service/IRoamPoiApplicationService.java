package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.domain.dto.RoamPoiApplicationRequest;

import java.util.List;

/** roam 商户据点投放/认领；roam_poi 是申请当前态与生效资产的唯一真源。 */
public interface IRoamPoiApplicationService {
    int TYPE_PLACEMENT = 1;
    int TYPE_CLAIM = 2;
    int AUDIT_PENDING = 0;
    int AUDIT_APPROVED = 1;
    int AUDIT_REJECTED = 2;
    int AUDIT_REVIEWING = 3;

    Long submitPlacement(Long memberId, RoamPoiApplicationRequest request);
    Long submitClaim(Long memberId, Long poiId);
    /** 申请人撤回自己的待审认领；false=申请已被审核处置或已撤回，客户端应刷新。 */
    boolean cancelClaim(Long memberId, Long poiId);
    List<RoamPoi> listMine(Long memberId);
    List<RoamPoi> listForReview(Integer auditStatus);
    List<RoamPoi> listClaimable(String keyword);
    Long approve(Long applicationId);
    boolean reject(Long applicationId, String reason);
    /** 上/下架自有据点；上架与审核共用商家行锁串行化配额。 */
    boolean setOwnedNodeStatus(Long memberId, Long poiId, Integer status);
}
