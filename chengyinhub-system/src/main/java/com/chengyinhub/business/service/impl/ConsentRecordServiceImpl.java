package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.mapper.ConsentRecordMapper;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class ConsentRecordServiceImpl implements IConsentRecordService {
    public static final String REQUEST_ID_CONFLICT_MESSAGE = "请求标识已用于其他同意事件";

    @Autowired
    private ConsentRecordMapper consentRecordMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int record(Long memberId, String docType, String docVersion, String scene,
                      String eventType, String requestId, String ipAddress) {
        return record(memberId, docType, docVersion, scene, null, null,
                eventType, requestId, ipAddress);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int record(Long memberId, String docType, String docVersion, String scene,
                      String scopeType, Long scopeId, String eventType, String requestId, String ipAddress) {
        if (memberId == null || memberId <= 0 || StringUtils.isEmpty(docType) || StringUtils.isEmpty(docVersion)
                || StringUtils.isEmpty(scene) || StringUtils.isEmpty(requestId)
                || !("AGREE".equals(eventType) || "REVOKE".equals(eventType))
                || !validScope(scopeType, scopeId)) {
            throw new IllegalArgumentException("同意记录参数不合法");
        }
        if ("privacy_policy".equals(docType) && ("roam_location".equals(scene) || "home_city_location".equals(scene)))
            throw new IllegalArgumentException("定位授权必须校验当前状态");
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
        } catch (DuplicateKeyException duplicate) {
            // 事件只追加；共享当前读既避开旧快照，也无需将重复插入的共享锁升级。
            ConsentRecord original = consentRecordMapper.selectByRequestIdForShare(requestId);
            if (original == null || !Objects.equals(original.getMemberId(), record.getMemberId())
                    || !Objects.equals(original.getDocType(), record.getDocType())
                    || !Objects.equals(original.getDocVersion(), record.getDocVersion())
                    || !Objects.equals(original.getScene(), record.getScene())
                    || !Objects.equals(original.getScopeType(), record.getScopeType())
                    || !Objects.equals(original.getScopeId(), record.getScopeId())
                    || !Objects.equals(original.getEventType(), record.getEventType())) {
                throw new ServiceException(REQUEST_ID_CONFLICT_MESSAGE, HttpStatus.CONFLICT);
            }
            return 0;
        }
    }

    @Override
    public ConsentRecord byRequestId(Long memberId, String requestId) {
        if (memberId == null || memberId <= 0 || StringUtils.isEmpty(requestId)) return null;
        return consentRecordMapper.selectByRequestId(memberId, requestId);
    }

    @Override
    public ConsentRecord latest(Long memberId, String docType, String scene) {
        if ("privacy_policy".equals(docType) && "roam_location".equals(scene))
            return consentRecordMapper.selectLatestRoamLocation(memberId);
        if ("privacy_policy".equals(docType) && "home_city_location".equals(scene))
            return consentRecordMapper.selectLatestLocation(memberId, scene);
        if ("privacy_policy".equals(docType) && "roam_presence".equals(scene))
            return consentRecordMapper.selectLatestRoamPresence(memberId);
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
