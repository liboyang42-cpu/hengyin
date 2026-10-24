package com.chengyinhub.web.service.verify;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.vo.CityNodeVO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.impl.QrcodeServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Existing city-node verifier command. Current merchant authorization, claim lock, reward CAS,
 * coupon stock/history and audit stay in the same REQUIRED transaction, without external I/O. */
@Service
public class CityNodeRedemptionApplication {
    private static final Logger logger = LoggerFactory.getLogger(CityNodeRedemptionApplication.class);
    private final IMerchantAccessService merchantAccessService;
    private final RoamMapper roamMapper;
    private final ISmsCouponHistoryService couponHistoryService;
    private final BusinessAuditEventMapper businessAuditEventMapper;
    private final QrcodeServiceImpl qrcodeService;
    private final String dynSecret;
    private static final long DYN_TTL_MS = RegistrationTicketApplication.DYN_TTL_MS;

    public CityNodeRedemptionApplication(IMerchantAccessService merchantAccessService, RoamMapper roamMapper,
            ISmsCouponHistoryService couponHistoryService, BusinessAuditEventMapper businessAuditEventMapper,
            QrcodeServiceImpl qrcodeService,
            @Value("${verify.dyncode-secret:${token.secret}}") String dynSecret) {
        this.merchantAccessService = merchantAccessService;
        this.roamMapper = roamMapper;
        this.couponHistoryService = couponHistoryService;
        this.businessAuditEventMapper = businessAuditEventMapper;
        this.qrcodeService = qrcodeService;
        this.dynSecret = dynSecret;
    }

    /** Issuance keeps its original admission/sign/inline-PNG order without a transaction. */
    public AjaxResult issueCityNodeCode(long memberId, Long poiId) {
        if (memberId <= 0) return AjaxResult.error("请先登录");
        if (poiId == null || poiId <= 0) return AjaxResult.error("参数缺失");
        CityNodeVO poi = roamMapper.selectMerchantNodeDetail(poiId);
        if (poi == null) return AjaxResult.error("据点不存在");
        if (roamMapper.selectPlayerMerchantNodeDetail(poiId, 0L) == null
                && roamMapper.selectPendingCityNodeCouponId(poiId, memberId) == null) {
            return AjaxResult.error("据点未上线，且没有待核销记录");
        }

        long now = System.currentTimeMillis();
        long nonce = ThreadLocalRandom.current().nextLong();
        // regId=poiId,type="citynode_"+玩家memberId(两者均在 HMAC 签名内,商家不可篡改发券对象)
        String code = VerifyDynCodeUtil.issue(poiId, "citynode_" + memberId, now, DYN_TTL_MS, nonce, dynSecret);
        String qrcodeUrl = null;
        try {
            qrcodeUrl = qrcodeService.generateInlineQrcode(code, 300, 300);
        } catch (Exception ignore) {
            logger.error("签发据点核销码二维码生成失败", ignore);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("code", code);
        data.put("qrcodeUrl", qrcodeUrl);
        data.put("ttlMs", DYN_TTL_MS);
        data.put("expiresAt", now + DYN_TTL_MS);
        data.put("poiId", poiId);
        return AjaxResult.success(data);
    }

    @Transactional(rollbackFor = Exception.class)
    public AjaxResult redeemCityNodeCode(long principalMemberId, String code) {
        if (principalMemberId <= 0) return AjaxResult.error("请先登录");
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.VERIFY);
        VerifyDynCodeUtil.Result r = VerifyDynCodeUtil.verify(code, System.currentTimeMillis(), dynSecret);
        if (!r.valid) return AjaxResult.error(r.reason == null ? "核销码无效" : r.reason);
        if (r.type == null || !r.type.startsWith("citynode_")) return AjaxResult.error("非据点核销码");
        Long poiId = r.regId;
        long playerId;
        try {
            playerId = Long.parseLong(r.type.substring("citynode_".length()));
        } catch (Exception e) {
            return AjaxResult.error("核销码格式错误");
        }
        roamMapper.lockClaimOwner(playerId);
        CityNodeVO poi = roamMapper.selectMerchantNodeDetail(poiId);
        if (poi == null) return AjaxResult.error("据点不存在");
        // roam_poi.merchant_id 是 mms_merchant.id，不能拿登录 memberId 或 Owner memberId 比。
        if (poi.getMerchantId() == null || !poi.getMerchantId().equals(access.getMerchantId())) {
            return AjaxResult.error("无权核销该据点");
        }
        Long couponId = roamMapper.selectPendingCityNodeCouponId(poiId, playerId);
        if (couponId == null) return AjaxResult.error("玩家未到店打卡,或该券已核销");
        // 幂等:玩家须已到店打卡(status=0 待核销);0→1 条件更新只成功一次,防重复发券
        int flipped = roamMapper.redeemCityNodeReward(poiId, playerId);
        if (flipped == 0) return AjaxResult.error("玩家未到店打卡,或该券已核销");
        // C-30:券已失效(平台3)/已停发(商家4) → 跳过发券,据点核销照常完成,回执明说「未发放」
        boolean couponSkipped = couponId > 0 && couponHistoryService.isCouponNotIssuable(couponId);
        if (couponSkipped) {
            logger.warn("COUPON_DISABLED_SKIP caller=cityNodeRedeem poiId={} memberId={} couponId={}", poiId, playerId, couponId);
        } else if (couponId > 0 && !couponHistoryService.grantCouponById(playerId, couponId)) {
            // C1 库存耗尽:抛业务异常触发本方法 @Transactional 回滚(status 翻转+占坑一并撤,允许补货后重扫),
            // 并由全局异常处理器转成友好 AjaxResult;不用 setRollbackOnly+return(那会抛 UnexpectedRollbackException→500)
            throw new ServiceException("券库存不足,核销失败");
        }
        BusinessAuditEvent audit = new BusinessAuditEvent();
        audit.setDomainType("CITY_NODE");
        audit.setDomainId(poiId);
        audit.setOwnerMemberId(access.getOwnerMemberId());
        audit.setActorMemberId(principalMemberId);
        audit.setActorRoleCode(access.getRoleCode());
        audit.setAction("CITY_NODE_REDEEM");
        audit.setTargetType("ROAM_CHECKIN");
        audit.setTargetId(poiId);
        audit.setRelationId(playerId);
        audit.setBeforeJson("{\"status\":0}");
        audit.setAfterJson("{\"status\":1}");
        audit.setRequestId("citynode-redeem-" + poiId + "-" + playerId);
        audit.setCreateTime(new java.util.Date());
        if (businessAuditEventMapper.insertBusinessAuditEvent(audit) != 1) {
            throw new IllegalStateException("据点核销审计写入失败");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("memberId", playerId);
        data.put("poiId", poiId);
        data.put("couponId", couponId);
        data.put("couponGranted", !couponSkipped);
        return AjaxResult.success(couponSkipped ? "核销成功,该券已失效或停发,未发放" : "核销成功,券已发放玩家", data);
    }
}
