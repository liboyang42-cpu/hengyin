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

    public RoamShareSnapshotService(RoamShareSnapshotMapper snapshotMapper, UmsMemberMapper memberMapper) {
        this.snapshotMapper = snapshotMapper;
        this.memberMapper = memberMapper;
    }

    @Transactional
    public String publish(Long memberId, Long clientTs, String snapshotJson) {
        if (memberMapper.lockMemberById(memberId) == null) return null;
        if (snapshotMapper.selectTokenByMemberTs(memberId, clientTs) == null) {
            int overflow = snapshotMapper.countByMember(memberId) - MAX_SNAPSHOTS_PER_MEMBER + 1;
            if (overflow > 0) snapshotMapper.deleteOldestByMember(memberId, overflow);
        }
        snapshotMapper.upsertSnapshot(UUID.randomUUID().toString().replace("-", ""),
                memberId, clientTs, snapshotJson);
        return snapshotMapper.selectTokenByMemberTs(memberId, clientTs);
    }
}
