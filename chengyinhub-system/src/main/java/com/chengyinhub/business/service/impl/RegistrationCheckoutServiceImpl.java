package com.chengyinhub.business.service.impl;

import com.chengyinhub.common.config.RestoreDispatchGuard;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.dto.RegistrationOrderResult;
import com.chengyinhub.business.domain.dto.RegistrationQuoteResult;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IRegistrationCheckoutService;
import com.chengyinhub.business.service.IRegistrationOrderService;
import com.chengyinhub.business.service.WechatPaymentOperations;
import com.chengyinhub.business.service.PaymentPreorderException;
import com.chengyinhub.business.service.RegistrationCheckoutException;
import com.chengyinhub.business.service.RegistrationCheckoutResult;
import com.chengyinhub.business.service.RegistrationRequestConflictException;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.common.constant.EntitlementInvalidReason;
import com.chengyinhub.common.utils.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报名结算应用编排。事务状态迁移由 ICmsRegistrationService 完成，外部微信调用发生在其提交之后。
 */
@Service
public class RegistrationCheckoutServiceImpl implements IRegistrationCheckoutService {

    @Autowired
    private ICmsRegistrationService registrationService;
    @Autowired
    private IRegistrationOrderService registrationOrderService;
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("wechatPaymentSdkGateway")
    private WechatPaymentOperations wechatPayService;
    /** 只读:多票父单找支付中的主单。 */
    @Autowired
    private CmsRegistrationMapper cmsRegistrationMapper;

    @Override
    public RegistrationQuoteResult quote(Long memberId, RegistrationRequest request) {
        if (request == null) {
            throw new RegistrationCheckoutException(400, "报名参数不能为空");
        }
        if (request.getWaitlistOfferId() != null || request.getWaitlistOfferToken() != null) {
            return registrationService.quoteRegistrationForOffer(memberId, request);
        }
        return registrationService.quoteRegistration(memberId, request.getOwnerType(), request.getOwnerId(),
                request.getTicketId(), request.getTickets(), request.getIsUsePoint());
    }

    @Override
    public boolean isPaymentReady() {
        return wechatPayService.isReady();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public RegistrationCheckoutResult createOrReplay(Long memberId, RegistrationRequest request) {
        requireNoActiveTransaction();
        if (request == null) {
            throw new RegistrationCheckoutException(400, "报名参数不能为空");
        }
        RegistrationOrderResult order;
        try {
            order = registrationOrderService.createOrReplay(memberId, request);
        } catch (RegistrationRequestConflictException conflict) {
            throw new RegistrationCheckoutException(409, conflict.getMessage());
        }
        return finishOrder(memberId, order, isAppChannel(request));
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public RegistrationCheckoutResult retryPayment(Long memberId, Long registrationId) {
        requireNoActiveTransaction();
        return retryPayment(memberId, registrationId, false);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public RegistrationCheckoutResult retryPayment(Long memberId, Long registrationId, boolean appChannel) {
        RestoreDispatchGuard.requireDispatchAllowed();
        requireNoActiveTransaction();
        List<CmsRegistration> zeroOrder = registrationService.completeZeroAmountPaymentForRetry(
                registrationId, memberId);
        if (zeroOrder != null) {
            CmsRegistration original = zeroOrder.stream()
                    .filter(row -> row != null && registrationId.equals(row.getId()))
                    .findFirst().orElseThrow(() -> new RegistrationCheckoutException(500, "原零元报名回执不完整"));
            Map<String, Object> data = mapOrder(original, zeroOrder);
            // The actor-bound transaction returned current completed rows; project their facts,
            // not a synthetic success inferred from missing payment parameters.
            data.put("paymentStatus", original.getPaymentStatus());
            data.put("registrationStatus", original.getRegistrationStatus());
            return new RegistrationCheckoutResult("报名完成确认成功", data);
        }
        if (!wechatPayService.isReady()) {
            throw new RegistrationCheckoutException(503, "支付服务暂不可用，请稍后再试");
        }
        CmsRegistration registration = registrationService.beginPaymentAttempt(registrationId, memberId);
        Map<String, Object> data = mapOrder(registration, singleton(registration));
        data.put("payParams", createWechatOrder(registration, appChannel));
        return new RegistrationCheckoutResult("支付参数获取成功", data);
    }

    /**
     * ROUND10 j6 F2:关掉微信支付面板后,单已被 beginPaymentAttempt 置为支付中(payment_status=1),
     * 而 cancelUnpaidIfActive 只收 0/3,于是这单既取消不了、又占着票,只能等过期对账。
     * 支付中的单不能凭本地状态直接关(用户可能正在另一处付款),这里复用过期对账
     *(OrderCloseTask.reconcileExpiredPaying)与主办方取消活动的「先查单、后关单」同一条路:
     * <ul>
     *   <li>NOTPAY / PAYERROR → 先关微信侧原尝试(closePaymentAttempt 关后回查为 CLOSED 才算数),再本地关单;</li>
     *   <li>CLOSED → 直接本地关单;</li>
     *   <li>USERPAYING → 用户正在付款,拒绝取消;SUCCESS → 按回调同一条链路补记已支付,拒绝取消;</li>
     *   <li>查单失败 / 原单不存在 / 单号不符 / 未知态 / 关单未确认 → 拒绝,保持原状,交给过期对账。</li>
     * </ul>
     * 本地关单走 settleClosedPayingOne:锁原订单并核对微信当前尝试仍是刚观察并关闭的那一个;
     * 若其间又发起了新尝试(继续支付),它返回 false,本次取消拒绝,新尝试照常可付。
     * 关单提交后 freezePaymentIntent 见业务单已不可支付,不会再给这单发新尝试,所以不存在「取消后又付成」的路径。
     */
    @Override
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public boolean cancelUnpaid(Long memberId, Long registrationId, String userName) {
        RestoreDispatchGuard.requireDispatchAllowed();
        requireNoActiveTransaction();
        CmsRegistration observed = registrationService.selectCmsRegistrationById(registrationId);
        if (observed == null) {
            throw new RegistrationCheckoutException(404, "报名记录不存在");
        }
        if (memberId == null || !memberId.equals(observed.getMemberId())) {
            throw new RegistrationCheckoutException(403, "无权取消他人报名");
        }
        CmsRegistration paying = isPaying(observed) ? observed : payingPrimaryOf(observed);
        if (paying == null) {
            return registrationService.cancelRegistration(registrationId, memberId, userName);
        }
        if (!memberId.equals(paying.getMemberId()) || StringUtils.isBlank(paying.getRegistrationNo())) {
            throw new RegistrationCheckoutException(409, "该报名状态已变化,无法取消");
        }
        if (!wechatPayService.isReady()) {
            throw new RegistrationCheckoutException(503, "支付结果确认中，请稍后再试");
        }
        Map<String, String> facts;
        try {
            facts = wechatPayService.queryOrderStatus(paying.getRegistrationNo());
        } catch (RestoreDispatchGuard.Blocked paused) {
            throw paused;
        } catch (RuntimeException unconfirmed) {
            // 含 WechatOrderNotExistException:NOT_FOUND 不能证明原请求未被受理。
            throw paymentUnconfirmed();
        }
        String wxNo = facts == null ? null : facts.get("wx_out_trade_no");
        String state = facts == null ? null : facts.get("trade_state");
        if (StringUtils.isBlank(wxNo) || StringUtils.isBlank(state)
                || !paying.getRegistrationNo().equals(facts.get("out_trade_no"))) {
            throw paymentUnconfirmed();
        }
        if ("SUCCESS".equalsIgnoreCase(state)) {
            registrationService.handleWechatPayCallback(facts);
            throw new RegistrationCheckoutException(409, "订单已支付，如需取消请在订单详情申请");
        }
        if ("USERPAYING".equalsIgnoreCase(state)) {
            throw new RegistrationCheckoutException(409, "订单正在支付中，请完成支付或稍后再取消");
        }
        if ("NOTPAY".equalsIgnoreCase(state) || "PAYERROR".equalsIgnoreCase(state)) {
            boolean closed;
            try {
                closed = wechatPayService.closePaymentAttempt(paying.getRegistrationNo(), wxNo);
            } catch (RestoreDispatchGuard.Blocked paused) {
                throw paused;
            } catch (RuntimeException uncertain) {
                closed = false;
            }
            if (!closed) {
                throw paymentUnconfirmed();
            }
        } else if (!"CLOSED".equalsIgnoreCase(state)) {
            throw paymentUnconfirmed();
        }
        if (registrationService.settleClosedPayingOne(paying, wxNo,
                EntitlementInvalidReason.ORGANIZER_CANCEL, userName, 3)) {
            return true;
        }
        CmsRegistration current = registrationService.selectCmsRegistrationById(registrationId);
        if (current != null && Integer.valueOf(3).equals(current.getRegistrationStatus())) {
            return true;
        }
        throw new RegistrationCheckoutException(409, "该报名状态已变化,无法取消");
    }

    private static boolean isPaying(CmsRegistration r) {
        return Integer.valueOf(1).equals(r.getPaymentStatus()) && Integer.valueOf(1).equals(r.getRegistrationStatus());
    }

    /**
     * 多票父单的从单停在 0 态,钱付没付只有支付中的主单查单说了算(同 expiredUnpaidPredicate 的排除口径)。
     * 从单被单独取消时转到主单走查单关单,settlePayingOne 会把从单一并关掉、一并回补。
     */
    private CmsRegistration payingPrimaryOf(CmsRegistration r) {
        if (!Integer.valueOf(0).equals(r.getPaymentStatus()) || !Integer.valueOf(1).equals(r.getRegistrationStatus())
                || StringUtils.isBlank(r.getParentOrderNo())) {
            return null;
        }
        List<CmsRegistration> siblings = cmsRegistrationMapper.selectByParentOrderNo(r.getParentOrderNo());
        if (siblings == null) {
            return null;
        }
        for (CmsRegistration sibling : siblings) {
            if (sibling != null && !r.getId().equals(sibling.getId()) && isPaying(sibling)) {
                return sibling;
            }
        }
        return null;
    }

    private static RegistrationCheckoutException paymentUnconfirmed() {
        return new RegistrationCheckoutException(409, "支付结果确认中，请稍后再试");
    }

    /** 请求是否指定走原生 App 支付。大小写不敏感,空值一律按 JSAPI。 */
    private boolean isAppChannel(RegistrationRequest request) {
        return request != null && "APP".equalsIgnoreCase(request.getPayChannel());
    }

    private RegistrationCheckoutResult finishOrder(Long memberId, RegistrationOrderResult order,
                                                    boolean appChannel) {
        CmsRegistration primary = order == null ? null : order.getPrimary();
        if (primary == null) {
            throw new RegistrationCheckoutException(500, "报名记录不存在");
        }
        List<CmsRegistration> registrations = order.getRegistrations();
        Map<String, Object> data = mapOrder(primary, registrations);
        if (positive(primary.getPayableAmount())) {
            if (!order.isReplayed()) {
                Integer paymentStatus = primary.getPaymentStatus();
                if (paymentStatus == null || paymentStatus == 0 || paymentStatus == 1) {
                    if (!wechatPayService.isReady()) {
                        throw new RegistrationCheckoutException(503, "支付服务暂不可用，请稍后再试");
                    }
                    CmsRegistration paying = registrationService.beginPaymentAttempt(primary.getId(), memberId);
                    data.put("payParams", createWechatOrder(paying, appChannel));
                }
            }
        } else if (!Integer.valueOf(2).equals(primary.getPaymentStatus())) {
            if (!"SUCCESS".equals(registrationService.completeZeroAmountPayment(
                    primary.getRegistrationNo()))) {
                throw new RegistrationCheckoutException(500, "零元报名支付状态确认失败");
            }
        }
        String message = Integer.valueOf(1).equals(primary.getOwnerType())
                ? "自玩通行证创建成功" : "报名创建成功";
        return new RegistrationCheckoutResult(message, data);
    }

    /**
     * 预下单。appChannel=true 走 trade_type=APP,否则走小程序 JSAPI。
     * 两条通道**只有下单这一步不同**,失败处理与状态机完全一致。
     */
    private Map<String, String> createWechatOrder(CmsRegistration registration, boolean appChannel) {
        RestoreDispatchGuard.requireDispatchAllowed();
        requireNoActiveTransaction();
        try {
            return appChannel
                    ? wechatPayService.createAppOrder(registration)
                    : wechatPayService.createOrder(registration);
        } catch (PaymentPreorderException failure) {
            // 本次发送前拒绝也不能证明这个原号之前未被受理；保留 PAYING，交原号查单/关单恢复。
            throw failure;
        } catch (RestoreDispatchGuard.Blocked paused) {
            throw paused;
        } catch (RuntimeException unknown) {
            throw PaymentPreorderException.resultUncertain(
                    unknown.getMessage() == null ? "微信预下单结果未知" : unknown.getMessage(), unknown);
        }
    }

    /** Never suspend a transaction whose original business locks would remain held across the SDK. */
    private static void requireNoActiveTransaction() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new RegistrationCheckoutException(409, "报名支付必须在原业务事务提交后发起");
        }
    }

    private Map<String, Object> mapOrder(CmsRegistration primary, List<CmsRegistration> registrations) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("registrationId", primary.getId());
        data.put("registrationNo", primary.getRegistrationNo());
        data.put("payableAmount", primary.getPayableAmount());
        data.put("expireTime", primary.getPayExpireTime());
        data.put("expiresAt", primary.getExpiresAt());
        data.put("paymentType", primary.getPaymentType());
        List<Long> ids = new ArrayList<>();
        if (registrations != null) {
            for (CmsRegistration registration : registrations) {
                ids.add(registration.getId());
            }
        }
        data.put("registrationIds", ids);
        return data;
    }

    private List<CmsRegistration> singleton(CmsRegistration registration) {
        List<CmsRegistration> registrations = new ArrayList<>();
        registrations.add(registration);
        return registrations;
    }

    private boolean positive(BigDecimal amount) {
        return amount != null && amount.compareTo(BigDecimal.ZERO) > 0;
    }


    /**
     * CU-M-124：报名没挂主题时下发的是通用卡，前端 onCardTap 只认 action，实点停在系统通知详情。
     * 那张卡过去写「可在此查看进度」= 承诺一件它做不到的事，所以卡面只报已经发生的事实。
     * 挂到主题的照旧发 signup 卡（可点开主题详情），不在这条里。
     */
    static String signupNoticeExtra(Long topicId) {
        return topicId == null
                ? "{\"title\":\"报名成功\",\"sub\":\"你的报名已提交\"}"
                : "{\"cardType\":\"signup\",\"topicId\":" + topicId + "}";
    }
}
