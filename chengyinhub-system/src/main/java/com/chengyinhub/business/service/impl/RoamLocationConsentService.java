package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.mapper.ConsentRecordMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.common.config.ComplianceProperties;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Date;
import java.util.Objects;

/** Consent and actual roam geometry writes share the real member mutex, before any session/fact lock. */
@Service
public class RoamLocationConsentService {
    private final RoamMapper roam;
    private final ConsentRecordMapper records;
    private final ComplianceProperties properties;
    public RoamLocationConsentService(RoamMapper roam, ConsentRecordMapper records, ComplianceProperties properties) {
        this.roam=roam; this.records=records; this.properties=properties;
    }
    public static class ConsentChangedException extends IllegalArgumentException {
        public ConsentChangedException() { super("定位授权状态已变化，请重新明确选择"); }
    }
    @Transactional(rollbackFor=Exception.class)
    public ConsentRecord record(Long member, String event, String requestId, String expectedRequestId, String ip) {
        return record(member, "roam_location", event, requestId, expectedRequestId, ip);
    }
    @Transactional(rollbackFor=Exception.class)
    public ConsentRecord record(Long member, String scene, String event, String requestId, String expectedRequestId, String ip) {
        if (!("roam_location".equals(scene) || "home_city_location".equals(scene)))
            throw new IllegalArgumentException("定位授权场景不合法");
        if (member == null || member <= 0 || requestId == null || requestId.isBlank() || requestId.length()>64
                || !("AGREE".equals(event) || "REVOKE".equals(event))) throw new IllegalArgumentException("定位授权参数不合法");
        if (roam.lockClaimOwner(member)==null) throw new ServiceException("账号状态已变化");
        ConsentRecord current="roam_location".equals(scene) ? records.selectLatestRoamLocationForUpdate(member)
                : records.selectLatestLocationForUpdate(member, scene);
        ConsentRecord previous=records.selectByRequestId(member,requestId);
        if (previous!=null) {
            if (!"privacy_policy".equals(previous.getDocType()) || !scene.equals(previous.getScene())
                    || !event.equals(previous.getEventType()) || previous.getScopeType()!=null || previous.getScopeId()!=null)
                throw new IllegalArgumentException("请求标识已被其他授权使用");
            // A committed old AGREE receipt is never permission to restart after a later REVOKE.
            if (current==null || !requestId.equals(current.getRequestId())
                    || !Objects.equals(properties.versionOf("privacy_policy"),current.getDocVersion())) throw new ConsentChangedException();
            return previous;
        }
        if ("AGREE".equals(event) && !Objects.equals(current==null?"":current.getRequestId(),expectedRequestId))
            throw new ConsentChangedException();
        String version=properties.versionOf("privacy_policy");
        if (version==null || version.isBlank()) throw new IllegalStateException("Privacy version unavailable");
        ConsentRecord row=new ConsentRecord(); Date now=new Date();
        row.setMemberId(member);row.setDocType("privacy_policy");row.setDocVersion(version);row.setScene(scene);
        row.setEventType(event);row.setRequestId(requestId);row.setIpAddress(ip);row.setOccurredAt(now);row.setCreateTime(now);
        if(records.insertConsentRecord(row)!=1)throw new IllegalStateException("定位授权未保存");
        // 撤回漫游定位在服务端立即生效:同一事务里关闭本人所有未结束会话的「附近的人」展示,
        // 不等 5 分钟心跳过期,也不依赖客户端是否还在运行。
        if("roam_location".equals(scene) && "REVOKE".equals(event)) roam.updatePresenceVisibility(null,member,false);
        ConsentRecord receipt=records.selectByRequestId(member,requestId);
        if(receipt==null)throw new IllegalStateException("定位授权回读失败");
        return receipt;
    }
    /** Caller retains the original complete geometry/reward transaction; no separate commit or client purpose tag. */
    public void requireCurrentAgreement(Long member) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Roam location gate requires original transaction");
        if (roam.lockClaimOwner(member)==null) throw new ServiceException("账号状态已变化");
        ConsentRecord latest=records.selectLatestRoamLocationForUpdate(member);
        if(latest==null || !"AGREE".equals(latest.getEventType())
                || !Objects.equals(properties.versionOf("privacy_policy"),latest.getDocVersion()))
            throw new ServiceException("请先明确同意漫游定位；撤回后不会接收新位置");
    }
}
