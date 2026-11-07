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
    private ExploreSaleSnapshotGate() {}

    public static void assertSnapshotFrozen(CmsTopic sellerTopic,
                                            TopicSaleSnapshotMapper snapshotMapper,
                                            CmsRegistrationMapper registrationMapper) {
        if (sellerTopic == null
                || !Integer.valueOf(ProductType.FREE_EXPLORE).equals(sellerTopic.getProductType())) return;
        TopicSaleSnapshot snapshot = snapshotMapper.selectSnapshotByTopicId(sellerTopic.getId());
        if (snapshot == null) {
            throw new IllegalStateException("SALE_SNAPSHOT_MISSING");
        } else {
            // 方法开头已断言 sellerTopic 是 FREE_EXPLORE,这里按同一事实取校验档位(read 内即完整性校验)。
            if (TopicSaleCanonicalCodec.read(snapshot.getSchemaVersion(), snapshot.getCanonicalJson(),
                    ProductType.FREE_EXPLORE).requiresFourStoreContract()) {
                throw new IllegalStateException("四店固定补偿产品已停止新售票，已售合同继续履行");
            }
        }
        if (registrationMapper.lockExploreTopicForUpdate(sellerTopic.getId()) == null) {
            throw new IllegalStateException("主题不存在，拒绝售票");
        }
    }

    private static Long positiveClub(Long id) { return id != null && id > 0 ? id : null; }

    /** 只拦新的普通探索场次/售票，不改存量合同的结算权利。 */
    public static void assertSessionClub(CmsTopic topic, Long sessionClubId) {
        if (topic != null && Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType())
                && !java.util.Objects.equals(positiveClub(sessionClubId), positiveClub(topic.getClubId()))) {
            throw new IllegalStateException("自由探索只开放内容所属俱乐部的场次，不能跨俱乐部售票");
        }
    }
}
