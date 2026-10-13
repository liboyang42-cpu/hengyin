package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.RegistrationQuoteResult;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMerchantBusinessStatusService;
import com.chengyinhub.business.service.IRegistrationOrderCreateGate;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.business.service.RegistrationCheckoutException;
import com.chengyinhub.common.utils.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/** 新建报名的实时门禁；幂等重放由持久化订单证明，不依赖实时规则或支付可用性。 */
@Service
public class RegistrationOrderCreateGateImpl implements IRegistrationOrderCreateGate {

    @Autowired
    private ICmsRegistrationService registrationService;
    @Autowired
    private ICmsTopicService topicService;
    @Autowired
    private IWechatPayService wechatPayService;
    @Autowired
    private IMerchantBusinessStatusService merchantBusinessStatusService;

    @Override
    public void validate(Long memberId, RegistrationRequest request) {
        // 拍板 2026-09-16 #10:商家已打烊/临时闭店时,与其相关的新报名/购票不可下单(已售不受影响,
        // 幂等重放也不走本门禁)。文案与前端入口提示同源,见 IMerchantBusinessStatusService。
        if (merchantBusinessStatusService.isTargetMerchantClosed(
                request.getOwnerType(), request.getOwnerId())) {
            throw new RegistrationCheckoutException(423, IMerchantBusinessStatusService.MERCHANT_CLOSED_MESSAGE);
        }
        if (Integer.valueOf(1).equals(request.getOwnerType())) {
            CmsTopic topic = topicService.selectCmsTopicById(request.getOwnerId());
            if (topic != null && positive(topic.getSelfPlayPrice()) && !wechatPayService.isReady()) {
                throw new RegistrationCheckoutException(503, "支付服务暂不可用，请稍后再试");
            }
            return;
        }
        if (StringUtils.isBlank(request.getQuoteSign())) {
            throw new RegistrationCheckoutException(400, "缺少报价签名，请重新进入报名页");
        }
        RegistrationQuoteResult current;
        if (request.getWaitlistOfferId() != null || request.getWaitlistOfferToken() != null) {
            current = registrationService.quoteRegistrationForOffer(memberId, request);
        } else {
            current = registrationService.quoteRegistration(memberId,
                    request.getOwnerType(), request.getOwnerId(), request.getTicketId(),
                    request.getTickets(), request.getIsUsePoint());
        }
        if (!request.getQuoteSign().equals(current.getQuoteSign())) {
            throw new RegistrationCheckoutException(409, "价格已更新，请重新确认");
        }
        if (positive(current.getPayAmount()) && !wechatPayService.isReady()) {
            throw new RegistrationCheckoutException(503, "支付服务暂不可用，请稍后再试");
        }
    }

    private boolean positive(BigDecimal amount) {
        return amount != null && amount.compareTo(BigDecimal.ZERO) > 0;
    }
}
