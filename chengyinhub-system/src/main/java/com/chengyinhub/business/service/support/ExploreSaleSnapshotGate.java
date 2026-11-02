package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.TopicSaleSnapshot;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.TopicSaleSnapshotMapper;
import com.chengyinhub.common.constant.ProductType;

/**
 * FREE_EXPLORE 售票前的开售快照门禁；必须在任何订单/库存/权益/资金写前调用。
 *
 * <p>2026-11-02 业主裁决(C-22)：取消「每人限 1 张」，探店日四店版同样取消。本门禁不再按账号数票，
 * 只保留「没有冻结开售快照不卖」与快照完整性校验；库存仍由票种库存行把关。
 */
public final class ExploreSaleSnapshotGate {
    private static final java.time.Instant CANONICAL15_EFFECTIVE_AT =
            java.time.Instant.parse("2026-08-14T16:00:00Z");
    private ExploreSaleSnapshotGate() {}

    public static void assertSnapshotFrozen(CmsTopic sellerTopic,
                                            TopicSaleSnapshotMapper snapshotMapper,
                                            CmsRegistrationMapper registrationMapper) {
        if (sellerTopic == null
                || !Integer.valueOf(ProductType.FREE_EXPLORE).equals(sellerTopic.getProductType())) return;
        TopicSaleSnapshot snapshot = snapshotMapper.selectSnapshotByTopicId(sellerTopic.getId());
        if (snapshot == null) {
            // 审计真源明确要求历史 SELLING 豁免；只认功能启用前已实际开售的不可变时间事实。
            if (sellerTopic.getSellingStartedAt() == null
                    || !sellerTopic.getSellingStartedAt().toInstant().isBefore(CANONICAL15_EFFECTIVE_AT)) {
                throw new IllegalStateException("SALE_SNAPSHOT_MISSING");
            }
        } else {
            // 方法开头已断言 sellerTopic 是 FREE_EXPLORE,这里按同一事实取校验档位(read 内即完整性校验)。
            TopicSaleCanonicalCodec.read(snapshot.getSchemaVersion(), snapshot.getCanonicalJson(),
                    ProductType.FREE_EXPLORE);
        }
        if (registrationMapper.lockExploreTopicForUpdate(sellerTopic.getId()) == null) {
            throw new IllegalStateException("主题不存在，拒绝售票");
        }
    }
}
