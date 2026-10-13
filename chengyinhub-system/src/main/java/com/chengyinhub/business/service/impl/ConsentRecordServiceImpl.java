package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.mapper.ConsentRecordMapper;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.common.utils.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class ConsentRecordServiceImpl implements IConsentRecordService {
    @Autowired
    private ConsentRecordMapper consentRecordMapper;

    @Override
    public int record(Long memberId, String docType, String docVersion, String scene,
                      String eventType, String requestId, String ipAddress) {
        return record(memberId, docType, docVersion, scene, null, null,
                eventType, requestId, ipAddress);
    }

    @Override
    public int record(Long memberId, String docType, String docVersion, String scene,
                      String scopeType, Long scopeId, String eventType, String requestId, String ipAddress) {
        if (memberId == null || memberId <= 0 || StringUtils.isEmpty(docType) || StringUtils.isEmpty(docVersion)
                || StringUtils.isEmpty(scene) || StringUtils.isEmpty(requestId)
                || !("AGREE".equals(eventType) || "REVOKE".equals(eventType))
                || !validScope(scopeType, scopeId)) {
            throw new IllegalArgumentException("同意记录参数不合法");
        }
        Date now = new Date();
        ConsentRecord record = new ConsentRecord();
        record.setMemberId(memberId);
        record.setDocType(docType);
        record.setDocVersion(docVersion);
        record.setScene(scene);
        record.setScopeType(scopeType);
        record.setScopeId(scopeId);
        record.setEventType(eventType);
        record.setRequestId(requestId);
        record.setIpAddress(ipAddress);
        record.setOccurredAt(now);
        record.setCreateTime(now);
        try {
            return consentRecordMapper.insertConsentRecord(record);
        } catch (DuplicateKeyException ignored) {
            return 0;
        }
    }

    @Override
    public ConsentRecord latest(Long memberId, String docType, String scene) {
        return consentRecordMapper.selectLatest(memberId, docType, scene);
    }

    @Override
    public ConsentRecord latest(Long memberId, String docType, String scene, String scopeType, Long scopeId) {
        if (!validScope(scopeType, scopeId)) return null;
        return consentRecordMapper.selectLatestScoped(memberId, docType, scene, scopeType, scopeId);
    }

    @Override
    public boolean hasCurrentAgreement(Long memberId, String docType, String scene, String currentVersion) {
        if (memberId == null || memberId <= 0 || StringUtils.isEmpty(docType)
                || StringUtils.isEmpty(scene) || StringUtils.isEmpty(currentVersion)) {
            return false;
        }
        ConsentRecord record = latest(memberId, docType, scene);
        return record != null && currentVersion.equals(record.getDocVersion())
                && "AGREE".equals(record.getEventType());
    }

    @Override
    public boolean hasCurrentAgreement(Long memberId, String docType, String scene,
                                       String scopeType, Long scopeId, String currentVersion) {
        if (memberId == null || memberId <= 0 || StringUtils.isEmpty(docType)
                || StringUtils.isEmpty(scene) || StringUtils.isEmpty(currentVersion)
                || !validScope(scopeType, scopeId)) {
            return false;
        }
        ConsentRecord record = latest(memberId, docType, scene, scopeType, scopeId);
        return record != null && currentVersion.equals(record.getDocVersion())
                && "AGREE".equals(record.getEventType());
    }

    private boolean validScope(String scopeType, Long scopeId) {
        if (scopeType == null && scopeId == null) return true;
        return "MERCHANT".equals(scopeType) && scopeId != null && scopeId > 0L;
    }
}
