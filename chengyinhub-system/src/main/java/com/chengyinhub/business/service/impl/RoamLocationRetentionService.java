package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.mapper.RoamShareSnapshotMapper;
import com.chengyinhub.business.support.RoamLocationRetention;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Date;

@Service
public class RoamLocationRetentionService {
    private final RoamMapper mapper;
    private final RoamShareSnapshotMapper shares;
    public RoamLocationRetentionService(RoamMapper mapper, RoamShareSnapshotMapper shares) {
        this.mapper = mapper; this.shares = shares;
    }
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;
    /** 客户端上报的会话档案同为定位数据(geohash7+时刻)，随会话位置一并按保留期删除。 */
    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.mapper.RoamSessionRecordMapper records;
    public int clearRewardLocations(Date cutoff,int limit) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Roam cleanup candidates require a transaction-free read");
        java.util.List<java.util.Map<String,Object>> candidates=mapper.selectExpiredRewardLocations(cutoff,limit);
        if(candidates.isEmpty())return 0;
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
            java.util.SortedSet<Long> members=new java.util.TreeSet<>();
            for(java.util.Map<String,Object> candidate:candidates)members.add(((Number)candidate.get("member_id")).longValue());
            for(Long member:members)mapper.lockClaimOwner(member);
            mapper.requireClaimKey();
            int cleared=0;
            for(java.util.Map<String,Object> candidate:candidates) {
                Long member=((Number)candidate.get("member_id")).longValue(),id=((Number)candidate.get("id")).longValue();
                java.util.Map<String,Object> row=mapper.selectCleanupRewardForUpdate(id,member,cutoff);
                if(row==null)continue;
                String fingerprint=row.get("poi_id")==null ? (String)row.get("claim_fingerprint")
                        : RoamLocationRetention.claimFingerprint("reward",member,row.get("poi_id"));
                if(mapper.clearRewardLocation(id,member,fingerprint)!=1)
                    throw new IllegalStateException("Roam reward cleanup receipt missing");
                cleared++;
            }
            return cleared;
        });
    }
    @Transactional
    public int clear(Long id, Long memberId, Date cutoff) {
        mapper.lockClaimOwner(memberId);
        RoamSession row = mapper.selectOwnedSessionForUpdate(id, memberId);
        if (row == null || !Integer.valueOf(1).equals(row.getStatus())
                || (row.getFinishedAt()!=null && row.getFinishedAt().after(cutoff)) || row.getLocationClearedAt() != null) return 0;
        mapper.requireClaimKey();
        String result = RoamLocationRetention.stripJson(row.getFinishResultJson());
        for (java.util.Map<String,Object> point : mapper.selectSessionTileLocations(id,memberId)) {
            String fingerprint = RoamLocationRetention.claimFingerprint("tile",memberId,point.get("geohash7"));
            if (mapper.clearSessionTileLocation(((Number)point.get("id")).longValue(),memberId,fingerprint)!=1)
                throw new IllegalStateException("Roam tile cleanup receipt missing");
        }
        for (java.util.Map<String,Object> point : mapper.selectSessionPoiLocations(id,memberId)) {
            String fingerprint = RoamLocationRetention.claimFingerprint("poi",memberId,point.get("poi_id"));
            if (mapper.clearSessionPoiLocation(((Number)point.get("id")).longValue(),memberId,fingerprint)!=1)
                throw new IllegalStateException("Roam POI cleanup receipt missing");
        }
        for (java.util.Map<String,Object> point : mapper.selectSessionShopLocations(id,memberId)) {
            mapper.recordAnonymousShopVisitor(memberId,((Number)point.get("source_type")).intValue(),
                    ((Number)point.get("source_id")).longValue());
            String fingerprint = RoamLocationRetention.claimFingerprint("shop",memberId,point.get("source_type")+":"+point.get("source_id"));
            if (mapper.clearSessionShopLocation(((Number)point.get("id")).longValue(),memberId,fingerprint)!=1)
                throw new IllegalStateException("Roam shop cleanup receipt missing");
        }
        shares.deleteBySession(id, memberId);
        if (records != null) records.deleteBySession(id, memberId);
        if (mapper.clearEndedSessionLocation(id, memberId, cutoff, result) != 1)
            throw new IllegalStateException("Roam location cleanup changed concurrently");
        return 1;
    }
}
