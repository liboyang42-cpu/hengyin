package com.chengyinhub.web.service.verify;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.RegistrationSeatService;
import com.chengyinhub.business.service.impl.QrcodeServiceImpl;
import com.chengyinhub.business.service.support.ExploreTicketUsePolicy;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.OwnershipUtils;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Registration ticket issuance and seat receipt projection, with the original admission order.
 * No outer transaction: prepareOwnedSeat and receipt reads retain their existing short transactions;
 * HMAC/inline PNG generation remains outside them. HTTP actor acquisition stays in the controller. */
@Service
public class RegistrationTicketApplication {
    private static final Logger logger = LoggerFactory.getLogger(RegistrationTicketApplication.class);
    public static final long DYN_TTL_MS = 300_000L;

    private final ICmsRegistrationService registrationService;
    private final RegistrationSeatService registrationSeatService;
    private final ICmsActivityService activityService;
    private final IPerkLedgerService perkLedgerService;
    private final QrcodeServiceImpl qrcodeService;
    private final String dynSecret;
    /** V-02:新码签发即作废同报名单同类型的旧码。 */
    private final RegistrationDynCodeLedger dynCodeLedger;

    public RegistrationTicketApplication(ICmsRegistrationService registrationService,
            RegistrationSeatService registrationSeatService, ICmsActivityService activityService,
            IPerkLedgerService perkLedgerService, QrcodeServiceImpl qrcodeService,
            @Value("${verify.dyncode-secret:${token.secret}}") String dynSecret, RegistrationDynCodeLedger dynCodeLedger) {
        this.registrationService = registrationService;
        this.registrationSeatService = registrationSeatService;
        this.activityService = activityService;
        this.perkLedgerService = perkLedgerService;
        this.qrcodeService = qrcodeService;
        this.dynSecret = dynSecret;
        this.dynCodeLedger = dynCodeLedger;
    }

    public AjaxResult issueDynCode(Long actorMemberId, Long registrationId, Integer seatNo) {
        Long memberId = actorMemberId;
        if (memberId == null || memberId <= 0) {
            return AjaxResult.error("请先登录");
        }
        if (registrationId == null) {
            return AjaxResult.error("参数缺失");
        }
        CmsRegistration reg = registrationService.selectCmsRegistrationById(registrationId);
        if (reg == null) {
            return AjaxResult.error("报名单不存在");
        }
        // 归属校验:只能给自己的报名单出码
        if (OwnershipUtils.notOwner(reg.getMemberId(), memberId)) {
            return AjaxResult.error("无权操作该报名单");
        }
        // 只给"已支付/报名成功"(registrationStatus==2)的报名单出核销码;待支付/已取消/已退款一律拒
        Integer regStatus = reg.getRegistrationStatus();
        if (regStatus == null || regStatus.intValue() != 2
                || !Integer.valueOf(2).equals(reg.getPaymentStatus())) {
            return AjaxResult.error("订单未完成支付或已结束,无法生成核销码");
        }
        // 消费完了的不再出码。
        //
        // ⚠️ [P0-14] **不能裸判 verification_status==1**:③ 首次核销就置位(那是退款闸
        // RefundPolicy.verifiedVerdict 拦得住「核销一个就不能退」的前提),它对 ③ 只表示
        // 「被核销过至少一次」。裸判会让玩家核销完第 1 章就**再也拿不到码**去核销第 2~5 章
        // ⇒ ③ 的多章核销链路当场断掉,且零告警(玩家只看到「该票已核销」,商家只看到没人来)。
        // ⇒ 判据收进 isTicketFullyConsumed(与 AccountDeregistration 的活跃单判定同一真源)。
        //    非 ③ 的票没有 entitlement ⇒ 该方法退化成 verification_status==1 ⇒ 逐字走原逻辑,零影响。
        boolean multiTopicSeat = (Long.valueOf(1L).equals(reg.getOwnerType()) || Long.valueOf(2L).equals(reg.getOwnerType()))
                && reg.getOrderNum() != null && reg.getOrderNum() > 1;
        if (multiTopicSeat && seatNo == null) {
            Map<String, Object> choice = new HashMap<>();
            choice.put("requiresSeatSelection", true); choice.put("registrationId", registrationId);
            choice.put("seatCount", reg.getOrderNum());
            return AjaxResult.success("请选择本次出示的席位，一次仅核销一席", choice);
        }
        com.chengyinhub.business.domain.RegistrationSeat selectedSeat = multiTopicSeat
                ? registrationSeatService.prepareOwnedSeat(registrationId, memberId, seatNo) : null;
        if (!multiTopicSeat && perkLedgerService.isTicketFullyConsumed(reg.getVerificationStatus(), reg.getId())) {
            return AjaxResult.error("该票已核销");
        }

        Long ownerType = reg.getOwnerType();
        if (Long.valueOf(2L).equals(ownerType)) {
            CmsActivity activity = activityService.selectCmsActivityById(reg.getOwnerId());
            if (activity == null || Integer.valueOf(2).equals(activity.getDelFlag())) {
                return AjaxResult.error("活动不存在");
            }
            if (activity.getCancelTime() != null) {
                return AjaxResult.error("活动已取消，无法生成核销码");
            }
            String rejection = ExploreTicketUsePolicy.rejection(reg.getPurchaseKind(), activity.getEndDate(), new Date());
            if (rejection != null) return AjaxResult.error(rejection);
        } else if (Integer.valueOf(3).equals(reg.getPurchaseKind())) {
            return AjaxResult.error("探索票所属活动不可用");
        }
        String type = selectedSeat == null
                ? ((ownerType != null && ownerType.longValue() == 2L) ? "activity" : "topic")
                : com.chengyinhub.business.service.RegistrationSeatService.codeType(ownerType, selectedSeat.getSeatNo());
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
        // 只有真把新码交到玩家手里(有图)才作废旧码;出图失败时玩家手上的旧码照常可用。
        if (qrcodeUrl != null && dynCodeLedger != null) {
            dynCodeLedger.recordIssued(code, DYN_TTL_MS);
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

    public AjaxResult seatReceipt(Long actorMemberId, Long registrationId, Integer seatNo, Long activityId) {
        var receipt = registrationSeatService.seatReceipt(registrationId, seatNo, activityId, actorMemberId);
        return receipt == null ? new AjaxResult(520, "原席位核销结果待确认，请勿改选其他席位重发")
                : AjaxResult.success("该席位已核销，请勿重复提供权益", receipt);
    }
}
