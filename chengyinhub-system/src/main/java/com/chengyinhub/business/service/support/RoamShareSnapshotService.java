package com.chengyinhub.business.service.support;

import com.chengyinhub.business.mapper.RoamShareSnapshotMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 串行化单会员的匿名路线快照配额写入。 */
@Service
public class RoamShareSnapshotService {
    public static final int MAX_SNAPSHOTS_PER_MEMBER = 50;

    private final RoamShareSnapshotMapper snapshotMapper;
    private final UmsMemberMapper memberMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.mapper.RoamMapper roamMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.service.impl.RoamLocationConsentService locationConsent;

    public RoamShareSnapshotService(RoamShareSnapshotMapper snapshotMapper, UmsMemberMapper memberMapper) {
        this.snapshotMapper = snapshotMapper;
        this.memberMapper = memberMapper;
    }

    @Transactional
    public String publish(Long memberId, Long clientTs, Long sessionId, String snapshotJson) {
        if (memberMapper.lockMemberById(memberId) == null) return null;
        locationConsent.requireCurrentAgreement(memberId);
        com.chengyinhub.business.domain.RoamSession session = sessionId == null ? null : roamMapper.selectOwnedSessionForUpdate(sessionId, memberId);
        if (session == null || !memberId.equals(session.getMemberId()) || !Integer.valueOf(1).equals(session.getStatus())
                || session.getFinishedAt() == null) throw new IllegalStateException("仅可分享本人的已结束漫游");
        if (com.chengyinhub.business.support.RoamLocationRetention.expired(session.getFinishedAt(), new java.util.Date()))
            throw new IllegalStateException("足迹已超过30天保留期限");
        if (snapshotMapper.selectTokenByMemberTs(memberId, clientTs) == null) {
            int overflow = snapshotMapper.countByMember(memberId) - MAX_SNAPSHOTS_PER_MEMBER + 1;
            if (overflow > 0) snapshotMapper.deleteOldestByMember(memberId, overflow);
        }
        snapshotMapper.upsertOwnedSnapshot(UUID.randomUUID().toString().replace("-", ""),
                memberId, clientTs, sessionId, snapshotJson);
        return snapshotMapper.selectTokenByMemberTs(memberId, clientTs);
    }
}
