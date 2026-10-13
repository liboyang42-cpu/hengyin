package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ClubCorporatePayeeProfile;
import com.chengyinhub.business.domain.TopicSalePayeeSnapshot;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

public interface ClubCorporatePayeeProfileMapper {
    int insertVersion(ClubCorporatePayeeProfile profile);

    /** 只读当前版本，<b>不加锁</b>；掩码视图跑在 readOnly 事务里，锁定读会被 MySQL 拒。 */
    ClubCorporatePayeeProfile selectCurrent(@Param("clubId") Long clubId);

    /** 写路径专用：建新版本前锁住当前行，防并发建出两个 current。 */
    ClubCorporatePayeeProfile selectCurrentForUpdate(@Param("clubId") Long clubId);

    ClubCorporatePayeeProfile selectVerifiedCurrent(@Param("clubId") Long clubId);

    ClubCorporatePayeeProfile selectById(@Param("id") Long id);

    /** 让既有 current 版本失效（关键字段一变就必须重新审核）。 */
    int invalidateCurrent(@Param("clubId") Long clubId);

    /** 财务一次审核：只有 current + PENDING 才能被 CAS 成终态，两人并发只有一个成功。 */
    int verifyVersionCas(@Param("id") Long id,
                         @Param("clubId") Long clubId,
                         @Param("verifiedBy") Long verifiedBy,
                         @Param("verifiedAt") Date verifiedAt,
                         @Param("verifyStatus") String verifyStatus,
                         @Param("verifyResult") String verifyResult,
                         @Param("licenseHash") String licenseHash);

    int insertSaleSnapshot(TopicSalePayeeSnapshot snapshot);

    TopicSalePayeeSnapshot selectSaleSnapshot(@Param("topicId") Long topicId,
                                              @Param("clubId") Long clubId);
}
