package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.TopicSaleSnapshot;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.TopicSaleSnapshotMapper;
import com.chengyinhub.common.constant.ProductType;

import java.util.List;

/** FREE_EXPLORE 同源 topic 一账号一票的生产门禁；必须在任何订单/库存/权益/资金写前调用。 */
public final class ExploreAccountTicketGate {
    private static final java.time.Instant CANONICAL15_EFFECTIVE_AT =
            java.time.Instant.parse("2026-08-14T16:00:00Z");
    private ExploreAccountTicketGate() {}

    public static void assertAvailable(CmsTopic sellerTopic, Long memberId,
                                       TopicSaleSnapshotMapper snapshotMapper,
                                       CmsRegistrationMapper registrationMapper) {
        assertAvailable(sellerTopic, memberId, null, snapshotMapper, registrationMapper);
    }

    /**
     * C-22(业主批准默认：按场次限购，不按主题)：普通自由探索在某一场(activity)上一账号一票，
     * 同一主题的下一场照常可买；四店探店日合同(FOUR_STORE_REDEEM，一期一票)与无场次的自玩通行证仍按主题。
     * 完成奖励本身按 (topic, member) 唯一，多买一场不会重复领。
     */
    public static void assertAvailable(CmsTopic sellerTopic, Long memberId, Long activityId,
                                       TopicSaleSnapshotMapper snapshotMapper,
                                       CmsRegistrationMapper registrationMapper) {
        if (sellerTopic == null
                || !Integer.valueOf(ProductType.FREE_EXPLORE).equals(sellerTopic.getProductType())) return;
        TopicSaleSnapshot snapshot = snapshotMapper.selectSnapshotByTopicId(sellerTopic.getId());
        int max;
        boolean perEdition = true;
        if (snapshot == null) {
            // 审计真源明确要求历史 SELLING 豁免；只认功能启用前已实际开售的不可变时间事实。
            if (sellerTopic.getSellingStartedAt() == null
                    || !sellerTopic.getSellingStartedAt().toInstant().isBefore(CANONICAL15_EFFECTIVE_AT)) {
                throw new IllegalStateException("SALE_SNAPSHOT_MISSING");
            }
            max = 1;
        } else {
            // 方法开头已断言 sellerTopic 是 FREE_EXPLORE,这里按同一事实取校验档位。
            com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1 canonical = TopicSaleCanonicalCodec.read(
                    snapshot.getSchemaVersion(), snapshot.getCanonicalJson(), ProductType.FREE_EXPLORE);
            max = canonical.maxTicketPerAccount(ProductType.FREE_EXPLORE);
            perEdition = canonical.requiresFourStoreContract();
        }
        if (registrationMapper.lockExploreTopicForUpdate(sellerTopic.getId()) == null) {
            throw new IllegalStateException("主题不存在，拒绝售票");
        }
        List<Long> liveIds = !perEdition && activityId != null
                ? registrationMapper.selectLiveExploreTicketIdsInActivityForUpdate(activityId, memberId)
                : registrationMapper.selectLiveExploreTicketIdsForUpdate(sellerTopic.getId(), memberId);
        if (liveIds != null && liveIds.size() >= max) {
            throw new IllegalStateException(!perEdition && activityId != null
                    ? "EXPLORE_SESSION_TICKET_LIMIT_REACHED" : "SHOP_DAY_ACCOUNT_TICKET_LIMIT_REACHED");
        }
    }
}
