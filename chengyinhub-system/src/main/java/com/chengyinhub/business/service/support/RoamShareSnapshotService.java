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
<<<<<<< public
    public String publish(Long memberId, Long clientTs, Long sessionId, String snapshotJson) {
        if (memberMapper.lockMemberById(memberId) == null) return null;
        locationConsent.requireCurrentAgreement(memberId);
        com.chengyinhub.business.domain.RoamSession session = sessionId == null ? null : roamMapper.selectOwnedSessionForUpdate(sessionId, memberId);
        if (session == null || !memberId.equals(session.getMemberId()) || !Integer.valueOf(1).equals(session.getStatus())
                || session.getFinishedAt() == null) throw new IllegalStateException("仅可分享本人的已结束漫游");
        if (com.chengyinhub.business.support.RoamLocationRetention.expired(session.getFinishedAt(), new java.util.Date()))
            throw new IllegalStateException("足迹已超过30天保留期限");
=======
    public String publish(Long memberId, Long clientTs, String snapshotJson) {
        Result r = publish(memberId, clientTs, snapshotJson, null);
        return r == null ? null : r.token;
    }

    /**
     * M0117:expectedVersion 为空 = 旧客户端语义(同足迹覆盖并 +1 版本);
     * 非空 = 版本条件更新,当前版本不一致返回 conflict,不覆盖已发布快照。
     * 行已被撤回(不存在)时带 expectedVersion 也算冲突:撤回后的旧编辑不能把链接复活。
     */
    @Transactional
    public Result publish(Long memberId, Long clientTs, String snapshotJson, Integer expectedVersion) {
        if (memberMapper.lockMemberById(memberId) == null) return null;
        if (expectedVersion != null) {
            if (snapshotMapper.updateSnapshotIfVersion(memberId, clientTs, snapshotJson, expectedVersion) != 1) {
                return Result.conflict(snapshotMapper.selectVersionByMemberTs(memberId, clientTs));
            }
            return new Result(snapshotMapper.selectTokenByMemberTs(memberId, clientTs),
                    snapshotMapper.selectVersionByMemberTs(memberId, clientTs), false);
        }
>>>>>>> private-feature/w2-r
        if (snapshotMapper.selectTokenByMemberTs(memberId, clientTs) == null) {
            int overflow = snapshotMapper.countByMember(memberId) - MAX_SNAPSHOTS_PER_MEMBER + 1;
            if (overflow > 0) snapshotMapper.deleteOldestByMember(memberId, overflow);
        }
<<<<<<< public
        snapshotMapper.upsertOwnedSnapshot(UUID.randomUUID().toString().replace("-", ""),
                memberId, clientTs, sessionId, snapshotJson);
        return snapshotMapper.selectTokenByMemberTs(memberId, clientTs);
=======
        snapshotMapper.upsertSnapshot(UUID.randomUUID().toString().replace("-", ""),
                memberId, clientTs, snapshotJson);
        return new Result(snapshotMapper.selectTokenByMemberTs(memberId, clientTs),
                snapshotMapper.selectVersionByMemberTs(memberId, clientTs), false);
    }

    /** 发布结果:token 为空且非冲突 = 不可用。 */
    public static final class Result {
        public final String token;
        public final Integer version;
        public final boolean conflict;

        Result(String token, Integer version, boolean conflict) {
            this.token = token;
            this.version = version;
            this.conflict = conflict;
        }

        static Result conflict(Integer currentVersion) {
            return new Result(null, currentVersion, true);
        }
>>>>>>> private-feature/w2-r
    }
}
