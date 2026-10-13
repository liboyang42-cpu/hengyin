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
        if (sellerTopic == null
                || !Integer.valueOf(ProductType.FREE_EXPLORE).equals(sellerTopic.getProductType())) return;
        TopicSaleSnapshot snapshot = snapshotMapper.selectSnapshotByTopicId(sellerTopic.getId());
        int max;
        if (snapshot == null) {
            // 审计真源明确要求历史 SELLING 豁免；只认功能启用前已实际开售的不可变时间事实。
            if (sellerTopic.getSellingStartedAt() == null
                    || !sellerTopic.getSellingStartedAt().toInstant().isBefore(CANONICAL15_EFFECTIVE_AT)) {
                throw new IllegalStateException("SALE_SNAPSHOT_MISSING");
            }
            max = 1;
        } else {
            // 方法开头已断言 sellerTopic 是 FREE_EXPLORE,这里按同一事实取校验档位。
            max = TopicSaleCanonicalCodec.read(snapshot.getSchemaVersion(), snapshot.getCanonicalJson(),
                    ProductType.FREE_EXPLORE).maxTicketPerAccount(ProductType.FREE_EXPLORE);
        }
        if (registrationMapper.lockExploreTopicForUpdate(sellerTopic.getId()) == null) {
            throw new IllegalStateException("主题不存在，拒绝售票");
        }
        List<Long> liveIds = registrationMapper.selectLiveExploreTicketIdsForUpdate(
                sellerTopic.getId(), memberId);
        if (liveIds != null && liveIds.size() >= max) {
            throw new IllegalStateException("SHOP_DAY_ACCOUNT_TICKET_LIMIT_REACHED");
        }
    }
}
