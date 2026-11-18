package com.chengyinhub.business.service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * F10(FE-G13/FE-G14):自由探索「作者填完票价直接发布开售」。
 *
 * <p>谁发布谁写:没有「运营写开售草稿」这一步。立项审核照常保留;开售审核由作者发布自动记为通过,
 * 审批时间线与业务审计可见,运营可事后下架。
 */
public interface ITopicAuthorSaleService {

    /** 编辑器票价区与「更多 → 编辑」用的状态。 */
    final class Readiness {
        private Long topicId;
        private Integer lifecycle;
        private int totalSlots;
        private int filledSlots;
        private int openSlots;
        private boolean canFillPrice;
        private boolean canPublish;

        public Long getTopicId() { return topicId; }
        public void setTopicId(Long topicId) { this.topicId = topicId; }
        public Integer getLifecycle() { return lifecycle; }
        public void setLifecycle(Integer lifecycle) { this.lifecycle = lifecycle; }
        public int getTotalSlots() { return totalSlots; }
        public void setTotalSlots(int totalSlots) { this.totalSlots = totalSlots; }
        public int getFilledSlots() { return filledSlots; }
        public void setFilledSlots(int filledSlots) { this.filledSlots = filledSlots; }
        public int getOpenSlots() { return openSlots; }
        public void setOpenSlots(int openSlots) { this.openSlots = openSlots; }
        public boolean isCanFillPrice() { return canFillPrice; }
        public void setCanFillPrice(boolean canFillPrice) { this.canFillPrice = canFillPrice; }
        public boolean isCanPublish() { return canPublish; }
        public void setCanPublish(boolean canPublish) { this.canPublish = canPublish; }
    }

    /** 只读:作者本人的自由探索主题当前位置承接与可否填价/发布。 */
    Readiness readiness(Long topicId, Long authorMemberId);

    /**
     * 一个事务内:锁价(复用 confirmPrice 的地板价/空价补齐)→ 同步全部场次票价 → 自动生成 canonical 草稿
     * → 记开售审核为作者发布 → finalizeForSale 开售。
     *
     * @param ticketPrices 每张主题票的终价(必须恰好覆盖全部票)
     * @return 开售时刻(毫秒)
     */
    long publishForSale(Long topicId, Long authorMemberId, Map<Long, BigDecimal> ticketPrices);
}
