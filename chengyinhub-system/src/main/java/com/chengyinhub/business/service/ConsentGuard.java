package com.chengyinhub.business.service;

import com.chengyinhub.common.config.ComplianceProperties;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Service;

/** A missing/stale/revoked separate agreement never authorizes sending personal media to a provider. */
@Service
public class ConsentGuard {
    private final IConsentRecordService records;
    private final ComplianceProperties versions;
    public ConsentGuard(IConsentRecordService records, ComplianceProperties versions) {
        this.records = records;
        this.versions = versions;
    }
    public void requireCurrentAgreement(Long memberId, String docType, String scene) {
        if (!records.hasCurrentAgreement(memberId, docType, scene, versions.versionOf(docType))) {
            if ("photo_model_processing".equals(docType)) {
                throw new ServiceException("请先单独同意照片发送第三方模型处理", 400)
                        .setData(java.util.Map.of("reason", "PHOTO_MODEL_CONSENT_REQUIRED"));
            }
            throw new ServiceException("请先单独同意声音克隆处理");
        }
    }
}
