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
