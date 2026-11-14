package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.SmsCouponHistory;

public interface ISettlementEntryService {
    /**
     * 报名支付成功时入账。活动类执行商家=活动归属，立即入账；
     * 主题类执行商家在核销时才由商家池确定，此处跳过，延迟到核销入账，避免错误归属。
     */
    int recordOnPayment(CmsRegistration registration);

    /** 核销/已知执行商家时入账：按规则拆 平台/商家/创作者 多条待结算明细。 */
    int recordPaidRegistration(CmsRegistration registration, Long merchantId);

    /**
     * 第十三轮 m6:俱乐部自有自由探索场次结算时,给一张已付未退的 ③ 探索票补记分账行(主理人 / 平台,status=0,
     * 随本场一起置已结算)。支付时不入账的理由(执行商家逐章核销才定)在这里不成立:场次结算已按整场票款
     * 3% 平台、其余归场次收款人入账,收款人已定。只给已过 {@code requireSessionSettlementContract} 的场次用,
     * 让结算后退款按同一组分账行把平台与主理人的份额冲回(业主 2026-11-05)。幂等:同一报名同一收款方只一行。
     */
    int recordClubFreeExploreSessionSettlement(CmsRegistration registration, Long payeeMemberId);

    /**
     * 同上,但平台费由调用方按整场余数法分摊后传入({@code platformFee},到分),保证逐单分账行之和
     * 与场次现金平台费一致(D-29)。不再逐张 HALF_UP。
     */
    int recordClubFreeExploreSessionSettlement(CmsRegistration registration, Long payeeMemberId,
                                               java.math.BigDecimal platformFee);

    int recordCouponVerification(SmsCouponHistory history, SmsCoupon coupon);

    /**
     * 撤销券核销:删除该领取记录仍待结算的分成明细,并读回检查是否已有已结算明细
     * (已结算的不动,打红字告警;券在系统1没有打款路径,正常恒无)。
     *
     * <p>删除而不是作废:settlement_detail 的无条件唯一键 (source_type,source_id,receiver_type,
     * receiver_id) 会让「撤销→重核销」的第二次入账撞上被作废的旧行 —— 详见 Mapper javadoc。
     *
     * @return 本次删除的明细行数
     */
    int deletePendingCouponVerification(Long historyId);
}
