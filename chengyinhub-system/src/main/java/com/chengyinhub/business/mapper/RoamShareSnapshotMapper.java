package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * 漫游足迹分享快照(第二轮拍板 17)。表 roam_share_snapshot,见 migration_20260917_roam_share_snapshot.sql。
 */
public interface RoamShareSnapshotMapper {
    /** 同一条足迹(member_id + client_ts)重复分享:更新快照、保留令牌。 */
    int upsertSnapshot(@Param("shareToken") String shareToken, @Param("memberId") Long memberId,
                       @Param("clientTs") Long clientTs, @Param("snapshotJson") String snapshotJson);

    /** Production publication binds a verified owned, finished session. */
    int upsertOwnedSnapshot(@Param("shareToken") String shareToken, @Param("memberId") Long memberId,
                            @Param("clientTs") Long clientTs, @Param("sessionId") Long sessionId, @Param("snapshotJson") String snapshotJson);

    String selectTokenByMemberTs(@Param("memberId") Long memberId, @Param("clientTs") Long clientTs);

    int countByMember(@Param("memberId") Long memberId);

    /** 超出单人上限时删最旧的若干条(本机足迹也只留最近 50 条)。 */
    int deleteOldestByMember(@Param("memberId") Long memberId, @Param("limit") int limit);

    String selectSnapshotJsonByToken(@Param("shareToken") String shareToken);

    int deleteBySession(@Param("sessionId") Long sessionId, @Param("memberId") Long memberId);
    int deleteExpiredUnbound(@Param("cutoff") java.util.Date cutoff, @Param("limit") int limit);

    int deleteByMember(@Param("memberId") Long memberId);
}
