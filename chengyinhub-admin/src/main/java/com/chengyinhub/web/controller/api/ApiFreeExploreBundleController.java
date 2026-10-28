package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.exception.FreeExploreBundleException;
import com.chengyinhub.business.service.IFreeExploreBundleService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.function.Supplier;

/**
 * F3 自由探索套票 Phase 1(DOC2 D-11)。
 *
 * <p>小程序与 App 共用同一组接口、同一份回读结构(不按客户端分叉);所有写接口带客户端幂等键,
 * 结果 UNKNOWN 时用 receipt 接口回读。写接口受 reservation 开关门控,默认关闭。
 * 本控制器不发起任何支付/退款:退款只给只读报价,资金动作仍走既有路径。
 */
@RestController
@RequestMapping("/api/free-explore/bundle")
public class ApiFreeExploreBundleController extends BaseController {
    private final IFreeExploreBundleService service;
    private final IMerchantAccessService merchantAccessService;

    public ApiFreeExploreBundleController(IFreeExploreBundleService service,
                                          IMerchantAccessService merchantAccessService) {
        this.service = service;
        this.merchantAccessService = merchantAccessService;
    }

    /** 探索日主题页的套票目录:纯读,不建单、不加锁(切换分类不会产生任何下单锁)。 */
    @GetMapping("/catalog")
    public AjaxResult catalog(@RequestParam("topicId") Long topicId) {
        return run(() -> service.catalog(topicId));
    }

    @GetMapping("/order")
    public AjaxResult order(@RequestParam("registrationId") Long registrationId) {
        return run(() -> service.orderView(currentMemberId(), registrationId));
    }

    /** 下单(既有 /api/registration 建单接口)结果 UNKNOWN 时,按同一 requestId 回读回执。 */
    @GetMapping("/order/receipt")
    public AjaxResult orderReceipt(@RequestParam("requestId") String requestId) {
        return run(() -> service.orderReceiptByRequestId(currentMemberId(), requestId));
    }

    @GetMapping("/order/refund-quote")
    public AjaxResult refundQuote(@RequestParam("registrationId") Long registrationId) {
        return run(() -> service.refundQuote(currentMemberId(), registrationId));
    }

    @PostMapping("/reservation")
    public AjaxResult reserve(@RequestBody ReservationRequest body) {
        return run(() -> {
            Long memberId = currentMemberId();
            Date startAt = body.getStartAt() == null ? null : new Date(body.getStartAt());
            try {
                return service.reserve(memberId, body.getRegistrationId(), body.getUnitId(), body.getOfferId(),
                        startAt, body.getIdempotencyKey());
            } catch (DuplicateKeyException concurrentSameKey) {
                // 同键并发:败者事务已回滚,在新事务里走重放分支(指纹不同则 409)。
                return service.reserve(memberId, body.getRegistrationId(), body.getUnitId(), body.getOfferId(),
                        startAt, body.getIdempotencyKey());
            }
        });
    }

    @GetMapping("/reservation/receipt")
    public AjaxResult reservationReceipt(@RequestParam("idempotencyKey") String idempotencyKey) {
        return run(() -> service.reservationByIdempotencyKey(currentMemberId(), idempotencyKey));
    }

    @PostMapping("/reservation/{reservationId}/cancel")
    public AjaxResult cancel(@PathVariable Long reservationId, @RequestBody(required = false) CancelRequest body) {
        return run(() -> service.cancelReservation(currentMemberId(), reservationId,
                body != null && Boolean.TRUE.equals(body.getUseWaiver())));
    }

    @PostMapping("/exit-decision")
    public AjaxResult exitDecision(@RequestBody ExitDecisionRequest body) {
        return run(() -> {
            Long memberId = currentMemberId();
            try {
                return service.decideMerchantExit(memberId, body.getUnitId(), body.getDecision(),
                        body.getTargetOfferId(), body.getIdempotencyKey());
            } catch (DuplicateKeyException concurrentSameKey) {
                return service.decideMerchantExit(memberId, body.getUnitId(), body.getDecision(),
                        body.getTargetOfferId(), body.getIdempotencyKey());
            }
        });
    }

    @GetMapping("/merchant/reservations")
    public AjaxResult merchantReservations(@RequestParam("from") Long from, @RequestParam("to") Long to) {
        return run(() -> service.merchantReservations(merchantOwner(),
                from == null ? null : new Date(from), to == null ? null : new Date(to)));
    }

    /** 商家记爽约:只消耗该单位,不是核销、不计提、不产生分成。核销仍走既有 /scan_qr_code_chapter(商家扫玩家券)。 */
    @PostMapping("/merchant/reservation/{reservationId}/no-show")
    public AjaxResult noShow(@PathVariable Long reservationId) {
        return run(() -> service.markNoShow(merchantOwner(), reservationId));
    }

    private AjaxResult run(Supplier<Object> action) {
        try {
            return success(action.get());
        } catch (FreeExploreBundleException e) {
            return AjaxResult.error(409, e.getMessage()).put("businessCode", e.getBusinessCode());
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? 409 : e.getCode(), e.getMessage());
        }
    }

    private Long merchantOwner() {
        MerchantAccessContext access = merchantAccessService.require(currentMemberId(), MerchantPermission.VERIFY);
        return access.getOwnerMemberId();
    }

    private Long currentMemberId() {
        try {
            LoginAppUser login = getLoginAppUser();
            if (login == null || login.getAppUser() == null || login.getAppUser().getUserId() == null) {
                throw new FreeExploreBundleException("LOGIN_REQUIRED", "请先登录");
            }
            return login.getAppUser().getUserId();
        } catch (ServiceException e) {
            throw new FreeExploreBundleException("LOGIN_REQUIRED", "请先登录");
        }
    }

    public static class ReservationRequest {
        private Long registrationId;
        private Long unitId;
        private Long offerId;
        /** 预约开始时间,epoch 毫秒 */
        private Long startAt;
        private String idempotencyKey;

        public Long getRegistrationId() { return registrationId; }
        public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
        public Long getUnitId() { return unitId; }
        public void setUnitId(Long unitId) { this.unitId = unitId; }
        public Long getOfferId() { return offerId; }
        public void setOfferId(Long offerId) { this.offerId = offerId; }
        public Long getStartAt() { return startAt; }
        public void setStartAt(Long startAt) { this.startAt = startAt; }
        public String getIdempotencyKey() { return idempotencyKey; }
        public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    }

    public static class CancelRequest {
        /** 开始前不足 12 小时取消时,主动使用本月唯一一次豁免 */
        private Boolean useWaiver;

        public Boolean getUseWaiver() { return useWaiver; }
        public void setUseWaiver(Boolean useWaiver) { this.useWaiver = useWaiver; }
    }

    public static class ExitDecisionRequest {
        private Long unitId;
        /** SWAP_CONSENTED / REFUND_UNFULFILLED */
        private String decision;
        private Long targetOfferId;
        private String idempotencyKey;

        public Long getUnitId() { return unitId; }
        public void setUnitId(Long unitId) { this.unitId = unitId; }
        public String getDecision() { return decision; }
        public void setDecision(String decision) { this.decision = decision; }
        public Long getTargetOfferId() { return targetOfferId; }
        public void setTargetOfferId(Long targetOfferId) { this.targetOfferId = targetOfferId; }
        public String getIdempotencyKey() { return idempotencyKey; }
        public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    }
}
