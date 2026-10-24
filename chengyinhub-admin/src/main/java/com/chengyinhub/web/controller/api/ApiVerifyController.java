package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.service.support.ExploreTicketUsePolicy;
import java.util.Date;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.vo.CityNodeVO;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.IGroupCodeService;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.chengyinhub.common.utils.OwnershipUtils;
/**
 * 核销动态码(BE-11 · Layer B 游戏外核销)。
 *
 * <p>玩家侧签发自己报名单的动态核销码(nonce+TTL),供商家/俱乐部扫码后端校验(D-8 收数据方扫)。
 * 本控制器先只负责<b>签发</b>,与在跑的静态 verificationCode 核销(ApiRegistrationController.scanQrCode)并存;
 * 扫码端接入(先试动态码 verify、失败回落静态码)后续切,避免一次性动到资金核销主路径。
 */
@Tag(name = "核销动态码")
@RestController
@RequestMapping("/api/verify")
public class ApiVerifyController extends BaseController {

    /** 动态码有效期(毫秒):入场码需留足出示时间,取 5 分钟;过期由前端重新签发 */
    private static final long DYN_TTL_MS = 300_000L;

    @Autowired
    private ICmsRegistrationService registrationService;

    @Autowired
    private com.chengyinhub.business.service.RegistrationSeatService registrationSeatService;

    @Autowired
    private ICmsActivityService activityService;

    /** [P0-14] ③ 出码判据:还有没有欠的章节(verification_status 对 ③ 是「被核销过」不是「核销完」)。 */
    @Autowired
    private com.chengyinhub.business.service.IPerkLedgerService perkLedgerService;

    @Autowired
    private com.chengyinhub.business.service.impl.QrcodeServiceImpl qrcodeService;

    /** 核销码密钥:默认复用 JWT secret(已是服务端注入的真密钥),可用 verify.dyncode-secret 单独覆盖 */
    @Value("${verify.dyncode-secret:${token.secret}}")
    private String dynSecret;

    @Autowired
    private RoamMapper roamMapper;

    @Autowired
    private IGroupCodeService groupCodeService;

    @Autowired
    private ISmsCouponHistoryService couponHistoryService;

    @Autowired
    private IMerchantAccessService merchantAccessService;

    @Autowired
    private BusinessAuditEventMapper businessAuditEventMapper;

    @Operation(summary = "签发我的动态核销码(玩家出码,商家/俱乐部扫)")
    @PostMapping("/dyncode/issue")
    public AjaxResult issueDynCode(Long registrationId, Integer seatNo) {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            memberId = 0L;
        }
        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }
        if (registrationId == null) {
            return error("参数缺失");
        }
        CmsRegistration reg = registrationService.selectCmsRegistrationById(registrationId);
        if (reg == null) {
            return error("报名单不存在");
        }
        // 归属校验:只能给自己的报名单出码
        if (OwnershipUtils.notOwner(reg.getMemberId(), memberId)) {
            return error("无权操作该报名单");
        }
        // 只给"已支付/报名成功"(registrationStatus==2)的报名单出核销码;待支付/已取消/已退款一律拒
        Integer regStatus = reg.getRegistrationStatus();
        if (regStatus == null || regStatus.intValue() != 2
                || !Integer.valueOf(2).equals(reg.getPaymentStatus())) {
            return error("订单未完成支付或已结束,无法生成核销码");
        }
        // 消费完了的不再出码。
        //
        // ⚠️ [P0-14] **不能裸判 verification_status==1**:③ 首次核销就置位(那是退款闸
        // RefundPolicy.verifiedVerdict 拦得住「核销一个就不能退」的前提),它对 ③ 只表示
        // 「被核销过至少一次」。裸判会让玩家核销完第 1 章就**再也拿不到码**去核销第 2~5 章
        // ⇒ ③ 的多章核销链路当场断掉,且零告警(玩家只看到「该票已核销」,商家只看到没人来)。
        // ⇒ 判据收进 isTicketFullyConsumed(与 AccountDeregistration 的活跃单判定同一真源)。
        //    非 ③ 的票没有 entitlement ⇒ 该方法退化成 verification_status==1 ⇒ 逐字走原逻辑,零影响。
        boolean multiTopicSeat = Long.valueOf(1L).equals(reg.getOwnerType())
                && reg.getOrderNum() != null && reg.getOrderNum() > 1;
        com.chengyinhub.business.domain.RegistrationSeat selectedSeat = multiTopicSeat
                ? registrationSeatService.prepareOwnedSeat(registrationId, memberId, seatNo) : null;
        if (!multiTopicSeat && perkLedgerService.isTicketFullyConsumed(reg.getVerificationStatus(), reg.getId())) {
            return error("该票已核销");
        }

        Long ownerType = reg.getOwnerType();
        if (Long.valueOf(2L).equals(ownerType)) {
            CmsActivity activity = activityService.selectCmsActivityById(reg.getOwnerId());
            if (activity == null || Integer.valueOf(2).equals(activity.getDelFlag())) {
                return error("活动不存在");
            }
            if (activity.getCancelTime() != null) {
                return error("活动已取消，无法生成核销码");
            }
            String rejection = ExploreTicketUsePolicy.rejection(reg.getPurchaseKind(), activity.getEndDate(), new Date());
            if (rejection != null) return error(rejection);
        } else if (Integer.valueOf(3).equals(reg.getPurchaseKind())) {
            return error("探索票所属活动不可用");
        }
        String type = selectedSeat == null
                ? ((ownerType != null && ownerType.longValue() == 2L) ? "activity" : "topic")
                : com.chengyinhub.business.service.RegistrationSeatService.codeType(selectedSeat.getSeatNo());
        long now = System.currentTimeMillis();
        long nonce = ThreadLocalRandom.current().nextLong();
        String code = VerifyDynCodeUtil.issue(reg.getId(), type, now, DYN_TTL_MS, nonce, dynSecret);

        // 短期动态码直接渲染PNG，与团码同源，不依赖对象存储上传。
        String qrcodeUrl = null;
        try {
            qrcodeUrl = qrcodeService.generateInlineQrcode(code, 300, 300);
        } catch (Exception ignore) {
            logger.error("签发核销码二维码生成失败", ignore);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("code", code);
        data.put("qrcodeUrl", qrcodeUrl);
        data.put("ttlMs", DYN_TTL_MS);
        data.put("expiresAt", now + DYN_TTL_MS);
        data.put("type", type);
        if (selectedSeat != null) {
            data.put("registrationId", registrationId); data.put("seatId", selectedSeat.getId());
            data.put("seatNo", selectedSeat.getSeatNo()); data.put("seatStatus", selectedSeat.getStatus());
            data.put("verifiedAt", selectedSeat.getVerifiedAt());
        }
        return AjaxResult.success(data);
    }

    /** Java compatibility only; a multi-seat order still requires an explicit signed seat. */
    public AjaxResult issueDynCode(Long registrationId) { return issueDynCode(registrationId, null); }

    @Operation(summary = "签发我的据点核销码(玩家出码,商家扫码领券)")
    @PostMapping("/citynode/issue")
    public AjaxResult issueCityNodeCode(Long poiId) {
        long memberId = appUid();
        if (memberId <= 0) return error("请先登录");
        if (poiId == null || poiId <= 0) return error("参数缺失");
        CityNodeVO poi = roamMapper.selectMerchantNodeDetail(poiId);
        if (poi == null) return error("据点不存在");
        if (roamMapper.selectPlayerMerchantNodeDetail(poiId, 0L) == null
                && roamMapper.selectPendingCityNodeCouponId(poiId, memberId) == null) {
            return error("据点未上线，且没有待核销记录");
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

    @Operation(summary = "商家扫玩家据点核销码,校验后发券(C2 防伪造GPS薅券)")
    @PostMapping("/citynode/redeem")
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult redeemCityNodeCode(String code) {
        long principalMemberId = appUid();
        if (principalMemberId <= 0) return error("请先登录");
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.VERIFY);
        VerifyDynCodeUtil.Result r = VerifyDynCodeUtil.verify(code, System.currentTimeMillis(), dynSecret);
        if (!r.valid) return error(r.reason == null ? "核销码无效" : r.reason);
        if (r.type == null || !r.type.startsWith("citynode_")) return error("非据点核销码");
        Long poiId = r.regId;
        long playerId;
        try {
            playerId = Long.parseLong(r.type.substring("citynode_".length()));
        } catch (Exception e) {
            return error("核销码格式错误");
        }
        roamMapper.lockClaimOwner(playerId);
        CityNodeVO poi = roamMapper.selectMerchantNodeDetail(poiId);
        if (poi == null) return error("据点不存在");
        // roam_poi.merchant_id 是 mms_merchant.id，不能拿登录 memberId 或 Owner memberId 比。
        if (poi.getMerchantId() == null || !poi.getMerchantId().equals(access.getMerchantId())) {
            return error("无权核销该据点");
        }
        Long couponId = roamMapper.selectPendingCityNodeCouponId(poiId, playerId);
        if (couponId == null) return error("玩家未到店打卡,或该券已核销");
        // 幂等:玩家须已到店打卡(status=0 待核销);0→1 条件更新只成功一次,防重复发券
        int flipped = roamMapper.redeemCityNodeReward(poiId, playerId);
        if (flipped == 0) return error("玩家未到店打卡,或该券已核销");
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

    @Operation(summary = "签发本团的团核销码(D22:主理人/管理员出码,商家扫)")
    @PostMapping("/groupcode/issue")
    public AjaxResult issueGroupCode(Long activityId) {
        return groupCodeService.issue(activityId, appUid());
    }

    /**
     * D22 · T2b:商家扫团码 = 记「该商家接待了该团」。
     *
     * <p>① 是有领队产品,团整体走完全程 ⇒ 每个节点商家接待的是**整个团**,不是零散来客。
     * 所以组织槽有人时,商家不扫玩家票(那是俱乐部干的),改扫领队出示的团码;
     * 他的分账基数 = 「他接待过的那些团各自核销了多少人」,由 settleCoopTopic 结算时现查。
     *
     * <p>本端点**不动钱、不置核销位**,只落一条存在性台账 —— 钱在 settleCoopTopic 出。
     */
    @Operation(summary = "商家扫团核销码,记录接待该团(D22 · T2b)")
    @PostMapping("/groupcode/redeem")
    public AjaxResult redeemGroupCode(String code) {
        long principalMemberId = appUid();
        if (principalMemberId <= 0) return error("请先登录");
        MerchantAccessContext access = merchantAccessService.require(
                principalMemberId, MerchantPermission.VERIFY);
        return groupCodeService.redeem(code, access.getOwnerMemberId(),
                principalMemberId, access.getRoleCode());
    }

    /** 取当前登录 App 用户 id;未登录/异常返回 0。 */
    private long appUid() {
        try {
            Long id = getAppUserId();
            return id == null ? 0L : id;
        } catch (Exception e) {
            return 0L;
        }
    }
}
