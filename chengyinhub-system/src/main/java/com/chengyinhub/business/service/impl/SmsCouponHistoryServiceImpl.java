package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.uuid.IdUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.SmsCouponHistoryMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.ISettlementEntryService;
import com.chengyinhub.business.service.IRoleService;
import org.springframework.transaction.annotation.Transactional;

/**
 * 优惠券领取使用记录Service业务层处理
 * 
 * @author webxh
 * @date 2025-09-01
 */
@Service
public class SmsCouponHistoryServiceImpl implements ISmsCouponHistoryService 
{
    @Autowired
    private SmsCouponHistoryMapper smsCouponHistoryMapper;

    @Autowired
    private com.chengyinhub.business.mapper.SmsCouponMapper smsCouponMapper;

    @Autowired
    private ISettlementEntryService settlementEntryService;

    @Autowired
    private BusinessAuditEventMapper businessAuditEventMapper;

    @Autowired
    private com.chengyinhub.business.mapper.CouponSourceGrantMapper couponSourceGrantMapper;
    @Autowired
    private com.chengyinhub.business.mapper.RewardDropLogMapper rewardDropLogMapper;

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(SmsCouponHistoryServiceImpl.class);

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RedemptionResult redeemCoupon(Long historyId, Long ownerMemberId, Long merchantId,
                                         Long actorMemberId, String actorRoleCode) {
        if (historyId == null || ownerMemberId == null || ownerMemberId <= 0) {
            return RedemptionResult.NOT_FOUND;
        }
        if (actorMemberId == null || actorMemberId <= 0
                || actorRoleCode == null || actorRoleCode.trim().isEmpty()) {
            throw new IllegalArgumentException("优惠券核销操作者不合法");
        }
        boolean clubActor = IRoleService.ROLE_CLUB.equals(actorRoleCode);
        boolean merchantActor = MerchantRoleCode.MERCHANT_OWNER.equals(actorRoleCode)
                || MerchantRoleCode.isOperatorRole(actorRoleCode);
        if (merchantId == null ? !clubActor : merchantId <= 0 || !merchantActor) {
            throw new IllegalArgumentException("优惠券核销作用域与操作者角色不匹配");
        }
        SmsCouponHistory history = smsCouponHistoryMapper.selectSmsCouponHistoryById(historyId);
        if (history == null || (history.getDelFlag() != null && history.getDelFlag() != 0)) {
            return RedemptionResult.NOT_FOUND;
        }
        com.chengyinhub.business.domain.SmsCoupon coupon =
                smsCouponMapper.selectSmsCouponById(history.getCouponId());
        if (coupon == null) {
            return RedemptionResult.NOT_FOUND;
        }
        if (!ownerMemberId.equals(coupon.getMemberId())) {
            return RedemptionResult.FORBIDDEN;
        }
        if (merchantId != null) {
            if (!Integer.valueOf(1).equals(coupon.getScopeType())
                    || !merchantId.equals(coupon.getMerchantId())) {
                return RedemptionResult.FORBIDDEN;
            }
        } else if (Integer.valueOf(1).equals(coupon.getScopeType())) {
            return RedemptionResult.FORBIDDEN;
        }
        if (Integer.valueOf(1).equals(history.getUseStatus())) {
            return RedemptionResult.ALREADY_USED;
        }
        if (Integer.valueOf(2).equals(history.getUseStatus())) {
            return RedemptionResult.EXPIRED;
        }

        java.util.Date now = new java.util.Date();
        // COUPON-RPT-17:过期兜底要排在手动失效判定之前 —— 券过了 endTime 又说被「停用」是假话,
        // 商家据此判断「这张券还能不能用」会得到错的理由。判定顺序只影响回给谁的哪种终态,
        // 不放松 C-30:没过期的失效券照旧 DISABLED,过期的券照旧不能核销。
        if (history.getEndTime() != null && history.getEndTime().before(now)) {
            if (smsCouponHistoryMapper.expireUnusedCoupon(historyId, now) == 0) {
                SmsCouponHistory current = smsCouponHistoryMapper.selectSmsCouponHistoryByIdForUpdate(historyId);
                if (current == null || (current.getDelFlag() != null && current.getDelFlag() != 0)) return RedemptionResult.NOT_FOUND;
                if (current != null && Integer.valueOf(1).equals(current.getUseStatus())) {
                    return RedemptionResult.ALREADY_USED;
                }
                if (current == null || !Integer.valueOf(2).equals(current.getUseStatus())) {
                    throw new IllegalStateException("优惠券过期状态更新失败");
                }
            }
            return RedemptionResult.EXPIRED;
        }
        // C-30:平台手动失效即停用,已领未用的券也不得再核销(已核销事实不动,库存/领取记录不回收)。
        if (isDisabled(coupon)) {
            return RedemptionResult.DISABLED;
        }
        // 使用期承诺:start 前拒核销,start 时(含)起可核销。与 claimUnusedCoupon 的
        // (start_time is null or start_time <= useTime) 条件保持一致,服务层先拦为快路径,
        // Mapper CAS 为并发/竞态下的权威闸门。
        if (history.getStartTime() != null && history.getStartTime().after(now)) {
            return RedemptionResult.NOT_STARTED;
        }
        if (smsCouponHistoryMapper.claimUnusedCoupon(historyId, now) == 0) {
            SmsCouponHistory current = smsCouponHistoryMapper.selectSmsCouponHistoryByIdForUpdate(historyId);
            if (current == null || (current.getDelFlag() != null && current.getDelFlag() != 0)) return RedemptionResult.NOT_FOUND;
            if (current != null && Integer.valueOf(1).equals(current.getUseStatus())) {
                return RedemptionResult.ALREADY_USED;
            }
            if (current != null && Integer.valueOf(2).equals(current.getUseStatus())) {
                return RedemptionResult.EXPIRED;
            }
            if (current != null && current.getStartTime() != null && current.getStartTime().after(now)) {
                return RedemptionResult.NOT_STARTED;
            }
            throw new IllegalStateException("优惠券状态已变化，请重试");
        }

        settlementEntryService.recordCouponVerification(history, coupon);
        // 延续 history→coupon 锁序；当前模板已失效时零行抛错，连同历史CAS和事务内结算全部回滚。
        if (smsCouponMapper.incrementUseCount(coupon.getId()) != 1) {
            throw new IllegalStateException("优惠券核销计数更新失败");
        }
        BusinessAuditEvent audit = new BusinessAuditEvent();
        audit.setDomainType(merchantId == null ? "CLUB" : "MERCHANT");
        // legacy club coupon 没有 club_id 字段，只能用历史 owner identity 作为审计 surrogate；
        // 它不是 club 表行主键，后续只有新增 coupon.club_id/迁移回填后才能消除此语义债。
        audit.setDomainId(merchantId == null ? ownerMemberId : merchantId);
        audit.setOwnerMemberId(ownerMemberId);
        audit.setActorMemberId(actorMemberId);
        audit.setActorRoleCode(actorRoleCode);
        audit.setAction("COUPON_REDEEM");
        audit.setTargetType("SMS_COUPON_HISTORY");
        audit.setTargetId(historyId);
        audit.setRelationId(coupon.getId());
        audit.setBeforeJson("{\"useStatus\":0}");
        audit.setAfterJson("{\"useStatus\":1}");
        // CAS 已保证重复请求不再写审计；撤销后可再次核销，每次成功置位需要独立审计键。
        audit.setRequestId("coupon-redeem:" + historyId + ":" + IdUtils.fastSimpleUUID());
        audit.setCreateTime(now);
        if (businessAuditEventMapper.insertBusinessAuditEvent(audit) != 1) {
            throw new IllegalStateException("优惠券核销审计写入失败");
        }
        return RedemptionResult.REDEEMED;
    }

    /**
     * 撤销券核销(平台后台唯一入口;商家端不给 —— 自助撤销是新的刷单面,未拍板)。
     *
     * <p>顺序即安全边界,别调换:
     * <ol>
     *   <li><b>条件置位(CAS)是唯一幂等闸</b>:use_status 1→0,拿 0 行立即 return false,
     *       后面一概不执行 —— 二次回退计数/二次作废分成都是资损。</li>
     *   <li>券的核销计数 -1(0 行响亮抛错,计数与流水失配不许静默)。</li>
     *   <li>删除仍待结算的券分成明细(从未进批次、无资金动过;留着旧行会让重核销撞
     *       uk_source_receiver,把「券恢复可用」本身废掉 —— 详见 Mapper javadoc)。</li>
     *   <li>不可变审计(谁撤的/为什么/哪条),写失败整体回滚 —— 审计缺失比撤销失败严重。</li>
     * </ol>
     *
     * <p>刻意不做:券的 use_time 清除(它记录被撤销那次核销发生在何时,再核销会覆盖)、
     * 通关奖励/成长值等核销时副作用(仓库既定口径:勋章是不可变事件)。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean reverseRedemption(Long historyId, String reason, Long operatorUserId) {
        if (historyId == null || historyId <= 0) {
            throw new IllegalArgumentException("核销记录不能为空");
        }
        if (operatorUserId == null || operatorUserId <= 0) {
            throw new IllegalArgumentException("撤销核销操作者不合法");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("撤销原因不能为空");
        }
        SmsCouponHistory history = smsCouponHistoryMapper.selectSmsCouponHistoryById(historyId);
        if (history == null) {
            throw new IllegalStateException("核销记录不存在");
        }
        com.chengyinhub.business.domain.SmsCoupon coupon =
                smsCouponMapper.selectSmsCouponById(history.getCouponId());
        if (coupon == null) {
            throw new IllegalStateException("优惠券不存在");
        }
        java.util.Date now = new java.util.Date();
        // ★ 唯一幂等闸。拿 0 行 = 该行当前不是已核销态(重复撤销/从未核销)⇒ 立即返回,
        //   不得继续回退计数、作废分成 —— 那些都是二次执行即资损的动作。
        if (smsCouponHistoryMapper.reverseUsedCoupon(historyId, now) == 0) {
            return false;
        }
        if (smsCouponMapper.decrementUseCount(coupon.getId()) != 1) {
            throw new IllegalStateException("优惠券核销计数回退失败");
        }
        int settlementRemoved = settlementEntryService.deletePendingCouponVerification(historyId);
        writeReverseAudit(history, coupon, reason.trim(), operatorUserId, now);
        logger.info("券核销已撤销 historyId={}, operatorUserId={}, reason={}, 删除待结算分成明细={} 条",
                historyId, operatorUserId, reason.trim(), settlementRemoved);
        return true;
    }

    /**
     * 撤销核销的追加式审计,与撤销同事务。审计写失败必须抛,让整个撤销回滚 ——
     * 撤销事实(券态/计数/分成)都能从库里读回来,唯独「谁撤的」只在审计里。
     */
    private void writeReverseAudit(SmsCouponHistory history, com.chengyinhub.business.domain.SmsCoupon coupon,
                                   String reason, Long operatorUserId, java.util.Date now) {
        Long merchantId = Integer.valueOf(1).equals(coupon.getScopeType()) ? coupon.getMerchantId() : null;
        BusinessAuditEvent audit = new BusinessAuditEvent();
        // 域与核销审计同源(与 redeemCoupon 的取值口径逐字一致),否则同一个域的动作读不到一起。
        audit.setDomainType(merchantId == null ? "CLUB" : "MERCHANT");
        audit.setDomainId(merchantId == null ? coupon.getMemberId() : merchantId);
        audit.setOwnerMemberId(coupon.getMemberId());
        audit.setActorType(BusinessAuditEvent.ACTOR_TYPE_SYS_USER);
        audit.setActorMemberId(operatorUserId);
        audit.setActorRoleCode("SYS_USER");
        audit.setAction("COUPON_REDEEM_REVERSE");
        audit.setTargetType("SMS_COUPON_HISTORY");
        audit.setTargetId(history.getId());
        audit.setRelationId(coupon.getId());
        audit.setBeforeJson("{\"useStatus\":1}");
        audit.setAfterJson("{\"useStatus\":0}");
        audit.setReason(reason);
        // CAS 已挡住重复撤销；use_time 的数据库秒精度不能区分同秒内的多次核销周期。
        audit.setRequestId("coupon-redeem-reverse:" + history.getId() + ":" + IdUtils.fastSimpleUUID());
        audit.setCreateTime(now);
        if (businessAuditEventMapper.insertBusinessAuditEvent(audit) != 1) {
            throw new IllegalStateException("优惠券撤销核销审计写入失败,撤销已回滚");
        }
    }

    @Override
    public java.util.List<com.chengyinhub.business.domain.BusinessAuditEvent> listAuditTrail(Long historyId) {
        if (historyId == null) {
            return java.util.Collections.emptyList();
        }
        java.util.List<com.chengyinhub.business.domain.BusinessAuditEvent> events =
                businessAuditEventMapper.selectByTarget("SMS_COUPON_HISTORY", historyId);
        return events == null
                ? java.util.Collections.<com.chengyinhub.business.domain.BusinessAuditEvent>emptyList() : events;
    }

    // BE-06 下沉:统一发券,收口 play 通关 / 扫码核销×2 三处重复实现。
    // C1 防超发:限量券(publish_count>0)原子占坑,库存耗尽拒发;不限量券正常发并计数。返回是否发放成功。
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean grantCoupon(Long memberId, com.chengyinhub.business.domain.SmsCoupon coupon) {
        return grantCouponWithReceipt(memberId, coupon) != null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long grantCouponWithReceipt(Long memberId, com.chengyinhub.business.domain.SmsCoupon coupon) {
        if (coupon == null || coupon.getId() == null) return null;
        // C-30:手动失效的券拒发;商家停发(4)同样不再新增发放。
        // 两者与库存耗尽同口径返回 null,调用方已按「发放失败」处理。
        if (isNotIssuable(coupon)) {
            logger.warn("券已失效或停发,拒发 couponId={} status={} member={}",
                    coupon.getId(), coupon.getStatus(), memberId);
            return null;
        }
        // 调用方模板可能早于停发提交；在当前行占库时原子复核发放状态及库存。
        int reserved = smsCouponMapper.tryReserveIssuableCoupon(coupon.getId());
        if (reserved == 0) {
            logger.warn("券当前不可发或库存耗尽,拒发 couponId={} member={}", coupon.getId(), memberId);
            return null;
        }
        if (reserved != 1) {
            throw new IllegalStateException("优惠券领取计数更新失败");
        }
        SmsCouponHistory h = new SmsCouponHistory();
        h.setMemberId(memberId);
        h.setCouponId(coupon.getId());
        String couponCode = "COU" + System.currentTimeMillis()
                + java.util.UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        h.setCouponCode(couponCode);
        // 核销只接受 /api/coupon/qr-token 签发的动态码；发券不再生成不可用的静态码。
        h.setGetType(0);
        h.setGetTime(new java.util.Date());
        h.setUseStatus(0);
        h.setCreateTime(new java.util.Date());
        h.setStartTime(coupon.getStartTime());
        h.setEndTime(coupon.getEndTime());
        if (smsCouponHistoryMapper.insertSmsCouponHistory(h) != 1) {
            throw new IllegalStateException("优惠券领取记录写入失败");
        }
        if (h.getId() == null) throw new IllegalStateException("优惠券领取记录缺少原ID");
        return h.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long grantCouponBySource(Long memberId, Long couponId, String sourceType, Long sourceId) {
        if (memberId == null || memberId <= 0 || couponId == null || couponId <= 0
                || !"reward_drop".equals(sourceType) || sourceId == null || sourceId <= 0) {
            throw new IllegalArgumentException("奖励券原来源不合法");
        }
        com.chengyinhub.business.domain.RewardDropLog source = rewardDropLogMapper.selectById(sourceId);
        if (source == null || !memberId.equals(source.getMemberId()) || !Integer.valueOf(3).equals(source.getDropType())) {
            throw new IllegalArgumentException("奖励券来源归属或类型不匹配");
        }
        Long originalCoupon = com.alibaba.fastjson2.JSON.parseObject(source.getParamJson()).getLong("couponTemplateId");
        if (!couponId.equals(originalCoupon)) throw new IllegalArgumentException("奖励券原模板不匹配");
        // 原历史log没有交付关联，不能猜它没发过；只有与新log同事务固化的意图可自动交付。
        com.chengyinhub.business.domain.CouponSourceGrant receipt = couponSourceGrantMapper.selectForUpdate(sourceType, sourceId);
        if (receipt == null) {
            logger.warn("奖励券原来源缺交付意图,需核查历史回执 sourceId={}", sourceId);
            return null;
        }
        if (!memberId.equals(receipt.getMemberId()) || !couponId.equals(receipt.getCouponId())) {
            throw new IllegalStateException("奖励券同来源内容冲突");
        }
        if (receipt.getHistoryId() != null) return receipt.getHistoryId();
        Long historyId = grantCouponWithReceipt(memberId, couponSourceGrantMapper.selectCouponForUpdate(couponId));
        if (historyId == null) return null;
        if (couponSourceGrantMapper.markDelivered(sourceType, sourceId, historyId) != 1) {
            throw new IllegalStateException("奖励券原来源回执写入失败");
        }
        return historyId;
    }

    @Override
    public boolean isCouponNotIssuable(Long couponId) {
        com.chengyinhub.business.domain.SmsCoupon coupon =
                couponId == null ? null : smsCouponMapper.selectSmsCouponById(couponId);
        return coupon != null && isNotIssuable(coupon);
    }

    /**
     * 不可再发放(发券闸门):3 平台手动失效 / 4 商家停发。
     * ⚠️ 与核销闸门 {@link #isDisabled} 不同:商家停发只阻新增发放,已领的照常可核销。
     */
    private static boolean isNotIssuable(com.chengyinhub.business.domain.SmsCoupon coupon) {
        return Integer.valueOf(3).equals(coupon.getStatus())
                || Integer.valueOf(4).equals(coupon.getStatus());
    }

    /** 核销闸门:sms_coupon.status 0 未开始 / 1 进行中 / 2 已结束 / 3 手动失效 / 4 已停发(可核销)。 */
    private static boolean isDisabled(com.chengyinhub.business.domain.SmsCoupon coupon) {
        return Integer.valueOf(3).equals(coupon.getStatus());
    }

    /**
     * C1 发行上限判定(纯函数,单测友好,亦供只读"是否可领"/对账复用)。
     * publish_count 为 null 或 <=0 视为不限量;>0 时 receive_count 必须 < publish_count 才可再发。
     * 运行时发放原子性由 SmsCouponMapper.tryReserveIssuableCoupon 的当前状态/库存条件 UPDATE 保证。
     */
    public static boolean hasStock(Long receiveCount, Long publishCount) {
        if (publishCount == null || publishCount <= 0) return true;
        long got = receiveCount == null ? 0L : receiveCount;
        return got < publishCount;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean grantCouponById(Long memberId, Long couponId) {
        return grantCoupon(memberId, smsCouponMapper.selectSmsCouponById(couponId));
    }

    /**
     * 查询优惠券领取使用记录
     *
     * @param id 优惠券领取使用记录主键
     * @return 优惠券领取使用记录
     */
    @Override
    public SmsCouponHistory selectSmsCouponHistoryById(Long id)
    {
        return smsCouponHistoryMapper.selectSmsCouponHistoryById(id);
    }

    /**
     * 查询优惠券领取使用记录列表
     * 
     * @param smsCouponHistory 优惠券领取使用记录
     * @return 优惠券领取使用记录
     */
    @Override
    public List<SmsCouponHistory> selectSmsCouponHistoryList(SmsCouponHistory smsCouponHistory)
    {
        return smsCouponHistoryMapper.selectSmsCouponHistoryList(smsCouponHistory);
    }

    /**
     * 新增优惠券领取使用记录
     * 
     * @param smsCouponHistory 优惠券领取使用记录
     * @return 结果
     */
    @Override
    public int insertSmsCouponHistory(SmsCouponHistory smsCouponHistory)
    {
        smsCouponHistory.setCreateTime(DateUtils.getNowDate());
        int rows = smsCouponHistoryMapper.insertSmsCouponHistory(smsCouponHistory);
        // 领取计数维护:每新增一条领取记录,父券 receive_count +1(原子)
        if (rows > 0 && smsCouponHistory.getCouponId() != null) {
            smsCouponMapper.incrementReceiveCount(smsCouponHistory.getCouponId());
        }
        return rows;
    }

    /**
     * 修改优惠券领取使用记录
     * 
     * @param smsCouponHistory 优惠券领取使用记录
     * @return 结果
     */
    @Override
    public int updateSmsCouponHistory(SmsCouponHistory smsCouponHistory)
    {
        smsCouponHistory.setUpdateTime(DateUtils.getNowDate());
        return smsCouponHistoryMapper.updateSmsCouponHistory(smsCouponHistory);
    }

    /**
     * 批量删除优惠券领取使用记录
     * 
     * @param ids 需要删除的优惠券领取使用记录主键
     * @return 结果
     */
    @Override
    public int deleteSmsCouponHistoryByIds(Long[] ids)
    {
        return smsCouponHistoryMapper.deleteSmsCouponHistoryByIds(ids);
    }

    /**
     * 删除优惠券领取使用记录信息
     * 
     * @param id 优惠券领取使用记录主键
     * @return 结果
     */
    @Override
    public int deleteSmsCouponHistoryById(Long id)
    {
        return smsCouponHistoryMapper.deleteSmsCouponHistoryById(id);
    }

    /**
     * 批量更新指定用户的过期优惠券状态
     * @param memberId 用户ID（发布优惠券的用户）
     */
    @Override
    public int updateExpiredCouponsByMemberId(Long memberId){
        return smsCouponHistoryMapper.updateExpiredCouponsByMemberId(memberId);
    }
}
