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

    /** 发布结果:令牌 + 本次写入后的版本号。 */
    public static final class Published {
        private final String token;
        private final Integer version;

        Published(String token, Integer version) { this.token = token; this.version = version; }

        public String getToken() { return token; }
        public Integer getVersion() { return version; }
    }

    /** 带了 expectedVersion 却与库里当前版本不符(含那条已被撤销)。 */
    public static final class VersionConflictException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        VersionConflictException() { super("这条足迹分享已在别处更新或撤销，请刷新后再试"); }
    }

    @Transactional
    public String publish(Long memberId, Long clientTs, Long sessionId, String snapshotJson) {
        Published published = publish(memberId, clientTs, sessionId, snapshotJson, null);
        return published == null ? null : published.getToken();
    }

    /**
     * @param expectedVersion null = 不做 CAS(旧客户端);非 null 时必须等于当前版本,否则抛 {@link VersionConflictException}。
     *                        CAS 在会员行锁内判定,同一会员的发布已串行,不会两次都通过。
     */
    @Transactional
    public Published publish(Long memberId, Long clientTs, Long sessionId, String snapshotJson, Integer expectedVersion) {
        if (memberMapper.lockMemberById(memberId) == null) return null;
        locationConsent.requireCurrentAgreement(memberId);
        com.chengyinhub.business.domain.RoamSession session = sessionId == null ? null : roamMapper.selectOwnedSessionForUpdate(sessionId, memberId);
        if (session == null || !memberId.equals(session.getMemberId()) || !Integer.valueOf(1).equals(session.getStatus())
                || session.getFinishedAt() == null) throw new IllegalStateException("仅可分享本人的已结束漫游");
        if (com.chengyinhub.business.support.RoamLocationRetention.expired(session.getFinishedAt(), new java.util.Date()))
            throw new IllegalStateException("足迹已超过30天保留期限");
        boolean exists = snapshotMapper.selectTokenByMemberTs(memberId, clientTs) != null;
        if (expectedVersion != null) {
            Integer current = exists ? snapshotMapper.selectVersionByMemberTs(memberId, clientTs) : null;
            if (current == null || !current.equals(expectedVersion)) throw new VersionConflictException();
        }
        if (!exists) {
            int overflow = snapshotMapper.countByMember(memberId) - MAX_SNAPSHOTS_PER_MEMBER + 1;
            if (overflow > 0) snapshotMapper.deleteOldestByMember(memberId, overflow);
        }
        snapshotMapper.upsertOwnedSnapshot(UUID.randomUUID().toString().replace("-", ""),
                memberId, clientTs, sessionId, snapshotJson);
        String token = snapshotMapper.selectTokenByMemberTs(memberId, clientTs);
        return token == null ? null : new Published(token, snapshotMapper.selectVersionByMemberTs(memberId, clientTs));
    }
}
