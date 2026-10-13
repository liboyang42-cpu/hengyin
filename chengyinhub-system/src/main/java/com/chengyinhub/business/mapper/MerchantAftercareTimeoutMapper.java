package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.RefundApplication;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * 售后超时转平台的**系统侧**写入(拍板 2026-09-16 #12)。
 *
 * <p>刻意与 {@link MerchantAftercareMapper} 分开:后者是商家响应/意见的读写口,
 * 有一条冻结契约钉着「售后 mapper 不得更新 refund_application」(商家意见永不改资金状态)。
 * 超时转平台是系统任务写的旁路时间戳,不改审核 status、不改打款 payout_status,
 * 但仍是 refund_application 上的写,所以单独立 mapper,别把两个变更面混在一起。
 */
public interface MerchantAftercareTimeoutMapper {

    /**
     * 商家售后超时扫描:仍是平台待审(status=0)、商家侧无同意/驳回意见(与「待回应」桶同口径)、
     * 建单已早于 {@code cutoff}、且尚未转过平台的退款单(至多 200 条,单轮封顶防长事务)。
     */
    List<RefundApplication> selectStaleMerchantResponse(@Param("cutoff") Date cutoff);

    /**
     * 幂等标记「商家超时,已转平台客服」:仅首次(platform_takeover_at 为空)且仍为平台待审时生效。
     * 返回 0 表示并发/重复扫描的 loser 或状态已变 —— 调用方不得再发通知。
     */
    int markPlatformTakeover(@Param("refundId") Long refundId,
                             @Param("takeoverAt") Date takeoverAt);
}
