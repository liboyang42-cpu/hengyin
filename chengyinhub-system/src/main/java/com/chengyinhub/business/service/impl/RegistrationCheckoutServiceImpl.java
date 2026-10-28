package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.dto.RegistrationOrderResult;
import com.chengyinhub.business.domain.dto.RegistrationQuoteResult;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IRegistrationCheckoutService;
import com.chengyinhub.business.service.IRegistrationOrderService;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.business.service.PaymentPreorderException;
import com.chengyinhub.business.service.RegistrationCheckoutException;
import com.chengyinhub.business.service.RegistrationCheckoutResult;
import com.chengyinhub.business.service.RegistrationRequestConflictException;
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
    private IWechatPayService wechatPayService;

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
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public RegistrationCheckoutResult createOrReplay(Long memberId, RegistrationRequest request) {
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
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public RegistrationCheckoutResult retryPayment(Long memberId, Long registrationId) {
        return retryPayment(memberId, registrationId, false);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public RegistrationCheckoutResult retryPayment(Long memberId, Long registrationId, boolean appChannel) {
        if (!wechatPayService.isReady()) {
            throw new RegistrationCheckoutException(503, "支付服务暂不可用，请稍后再试");
        }
        CmsRegistration registration = registrationService.beginPaymentAttempt(registrationId, memberId);
        Map<String, Object> data = mapOrder(registration, singleton(registration));
        data.put("payParams", createWechatOrder(registration, appChannel));
        return new RegistrationCheckoutResult("支付参数获取成功", data);
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
        try {
            return appChannel
                    ? wechatPayService.createAppOrder(registration)
                    : wechatPayService.createOrder(registration);
        } catch (PaymentPreorderException failure) {
            // 本次发送前拒绝也不能证明这个原号之前未被受理；保留 PAYING，交原号查单/关单恢复。
            throw failure;
        } catch (RuntimeException unknown) {
            throw PaymentPreorderException.resultUncertain(
                    unknown.getMessage() == null ? "微信预下单结果未知" : unknown.getMessage(), unknown);
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
