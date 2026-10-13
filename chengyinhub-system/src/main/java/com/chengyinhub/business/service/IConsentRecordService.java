package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ConsentRecord;

public interface IConsentRecordService {
    int record(Long memberId, String docType, String docVersion, String scene,
               String eventType, String requestId, String ipAddress);

    int record(Long memberId, String docType, String docVersion, String scene,
               String scopeType, Long scopeId, String eventType, String requestId, String ipAddress);

    ConsentRecord byRequestId(Long memberId, String requestId);

    ConsentRecord latest(Long memberId, String docType, String scene);

    ConsentRecord latest(Long memberId, String docType, String scene, String scopeType, Long scopeId);

    /** 最新事件必须是当前版本 AGREE；缺记录、旧版本或已撤回一律 false。 */
    boolean hasCurrentAgreement(Long memberId, String docType, String scene, String currentVersion);

    boolean hasCurrentAgreement(Long memberId, String docType, String scene,
                                String scopeType, Long scopeId, String currentVersion);
}
