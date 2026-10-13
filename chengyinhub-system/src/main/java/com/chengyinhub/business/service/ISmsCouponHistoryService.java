package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.SmsCouponHistory;

/**
 * 优惠券领取使用记录Service接口
 * 
 * @author webxh
 * @date 2025-09-01
 */
public interface ISmsCouponHistoryService 
{
    enum RedemptionResult {
        REDEEMED,
        ALREADY_USED,
        EXPIRED,
        NOT_STARTED,
        /** 平台已手动失效(sms_coupon.status=3)。商家停发(4)不在此列:已领的仍可核销。 */
        DISABLED,
        NOT_FOUND,
        FORBIDDEN
    }

    /** 商家核销：CAS、结算、使用计数和实际操作者审计在同一事务内完成。 */
    RedemptionResult redeemCoupon(Long historyId, Long ownerMemberId, Long merchantId,
                                  Long actorMemberId, String actorRoleCode);

    /**
     * 撤销券核销(平台后台唯一入口;商家端不给)。**CAS 幂等**:仅 use_status=1 的行可撤,
     * 重复撤销第二次拿 0 行,直接返回 false,不二次回退计数/作废分成。
     *
     * <p>同事务:use_status 1→0 → sms_coupon.use_count-1 → 作废仍待结算的券分成明细 →
     * 落不可变审计(谁撤的/为什么/撤的哪条,写失败整体回滚)。
     *
     * @param operatorUserId 发起撤销的平台后台账号 id(SysUser.userId);必填,用于审计
     * @return true=本次撤销;false=该券当前不是已核销态(重复撤销/从未核销)
     */
    boolean reverseRedemption(Long historyId, String reason, Long operatorUserId);

    /** 某领取记录的不可变审计流水(核销/撤销,含操作者与原因),时间倒序 —— 撤销入口的读回面。 */
    java.util.List<com.chengyinhub.business.domain.BusinessAuditEvent> listAuditTrail(Long historyId);

    /**
     * 查询优惠券领取使用记录
     * 
     * @param id 优惠券领取使用记录主键
     * @return 优惠券领取使用记录
     */
    public SmsCouponHistory selectSmsCouponHistoryById(Long id);

    /**
     * 查询优惠券领取使用记录列表
     * 
     * @param smsCouponHistory 优惠券领取使用记录
     * @return 优惠券领取使用记录集合
     */
    public List<SmsCouponHistory> selectSmsCouponHistoryList(SmsCouponHistory smsCouponHistory);

    /**
     * 新增优惠券领取使用记录
     * 
     * @param smsCouponHistory 优惠券领取使用记录
     * @return 结果
     */
    public int insertSmsCouponHistory(SmsCouponHistory smsCouponHistory);

    /**
     * BE-06 下沉:统一发券。给会员发一张券(赠送 getType=0 / 未用 useStatus=0 / COU 码 + 二维码)。
     * 收口原本散在 ApiPlayProgressController(通关发券)与 ApiRegistrationController(扫码核销发券×2)三处的重复实现。
     * @param coupon 已加载的券(null 静默跳过)
     */
    public boolean grantCoupon(Long memberId, com.chengyinhub.business.domain.SmsCoupon coupon);

    /** CRM 等需真实逐人回执的窄接口；成功返回 sms_coupon_history.id，库存耗尽返回 null。 */
    public Long grantCouponWithReceipt(Long memberId, com.chengyinhub.business.domain.SmsCoupon coupon);

    /** 同 grantCoupon,按券 ID 加载后发放(券不存在或库存耗尽返回 false)。 */
    public boolean grantCouponById(Long memberId, Long couponId);

    /** 原reward_drop来源幂等交付；仅真实来源可调用，重放返回原historyId，尚未交付返回null。 */
    Long grantCouponBySource(Long memberId, Long couponId, String sourceType, Long sourceId);

    /**
     * C-30:券是否已不可发放 —— 平台手动失效(status=3)或商家停发(status=4)。
     * 发券失败即回滚主流程的调用方先问这一句:不可发放 = 跳过发券、主流程照常
     * (打 COUPON_DISABLED_SKIP 日志);库存耗尽/券不存在仍走 grantCouponById=false 的失败口径。
     *
     * <p>⚠️ 这是**发券**闸门,不是核销闸门:商家停发的券已领的仍可核销(核销只拦 status=3)。
     */
    boolean isCouponNotIssuable(Long couponId);

    /**
     * 修改优惠券领取使用记录
     * 
     * @param smsCouponHistory 优惠券领取使用记录
     * @return 结果
     */
    public int updateSmsCouponHistory(SmsCouponHistory smsCouponHistory);

    /**
     * 批量删除优惠券领取使用记录
     * 
     * @param ids 需要删除的优惠券领取使用记录主键集合
     * @return 结果
     */
    public int deleteSmsCouponHistoryByIds(Long[] ids);

    /**
     * 删除优惠券领取使用记录信息
     * 
     * @param id 优惠券领取使用记录主键
     * @return 结果
     */
    public int deleteSmsCouponHistoryById(Long id);

    /**
     * 批量更新指定用户的过期优惠券状态
     * @param memberId 用户ID（发布优惠券的用户）
     */
    public int updateExpiredCouponsByMemberId(Long memberId);
}
