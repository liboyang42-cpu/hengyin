package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubCorporatePayeeProfile;
import com.chengyinhub.business.domain.TopicSalePayeeSnapshot;

import java.util.Date;
import java.util.Map;

public interface IClubCorporatePayeeService {

    /** 写资料：关键字段一变即新建 version 并使既有审核失效；新版本恒为 PENDING。 */
    ClubCorporatePayeeProfile createVersion(Long clubId, ClubCorporatePayeeProfile input, Long operatorId);

    /** 财务一次审核；同一 version 并发只有一个 CAS 成功。 */
    ClubCorporatePayeeProfile verify(Long clubId, Long profileId, Long financeUserId,
                                     boolean pass, String observedLicenseHash);

    /** 默认视图：一律掩码，绝不下发明文。 */
    Map<String, Object> maskedView(Long clubId);

    /** 受控解密（business:clubpayee:payout 专用）。 */
    Map<String, Object> payoutView(Long clubId);

    /** 开售闸：current + VERIFIED + 关键字段齐 + 执照 hash 一致 + 密钥可用，任一不满足即阻断。 */
    ClubCorporatePayeeProfile requireVerifiedForSale(Long clubId);

    /** 开售同事务写不可变快照。 */
    TopicSalePayeeSnapshot snapshotForSale(Long topicId, Long clubId, Date frozenAt);

    /** FINAL/应付闸：只读开售快照，禁止用最新 profile 覆盖历史。 */
    TopicSalePayeeSnapshot requireSnapshotForFinal(Long topicId, Long clubId);
}
