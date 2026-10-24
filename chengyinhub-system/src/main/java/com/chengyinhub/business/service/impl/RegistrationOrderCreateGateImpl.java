package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MemberCapability;
import com.chengyinhub.business.domain.dto.RegistrationQuoteResult;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.service.ICapabilityService;
import com.chengyinhub.business.service.ICmsActivityService;
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

    /** 拍板 2026-10-22:商家可以加入俱乐部,但不能报名俱乐部活动。前端「商家不能报名」同源。 */
    public static final String MERCHANT_CLUB_SIGNUP_MESSAGE = "商家不能报名俱乐部活动";

    @Autowired
    private ICmsRegistrationService registrationService;
    @Autowired
    private ICmsTopicService topicService;
    @Autowired
    private IWechatPayService wechatPayService;
    @Autowired
    private IMerchantBusinessStatusService merchantBusinessStatusService;
    @Autowired
    private ICmsActivityService activityService;
    @Autowired
    private ICapabilityService capabilityService;

    @Override
    public void validate(Long memberId, RegistrationRequest request) {
        // 拍板 2026-09-16 #10:商家已打烊/临时闭店时,与其相关的新报名/购票不可下单(已售不受影响,
        // 幂等重放也不走本门禁)。文案与前端入口提示同源,见 IMerchantBusinessStatusService。
        if (merchantBusinessStatusService.isTargetMerchantClosed(
                request.getOwnerType(), request.getOwnerId())) {
            throw new RegistrationCheckoutException(423, IMerchantBusinessStatusService.MERCHANT_CLOSED_MESSAGE);
        }
        if (isClubOwned(request) && capabilityService.hasCapability(memberId, MemberCapability.CAP_MERCHANT)) {
            throw new RegistrationCheckoutException(403, MERCHANT_CLUB_SIGNUP_MESSAGE);
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

    /** 报名标的(主题/活动)是否归属某个俱乐部:主题看 cms_topic.club_id,活动看 cms_activity.club_id。 */
    private boolean isClubOwned(RegistrationRequest request) {
        if (request.getOwnerId() == null) return false;
        Long clubId = null;
        if (Integer.valueOf(1).equals(request.getOwnerType())) {
            CmsTopic topic = topicService.selectCmsTopicById(request.getOwnerId());
            clubId = topic == null ? null : topic.getClubId();
        } else if (Integer.valueOf(2).equals(request.getOwnerType())) {
            CmsActivity activity = activityService.selectCmsActivityById(request.getOwnerId());
            clubId = activity == null ? null : activity.getClubId();
        }
        return clubId != null && clubId > 0;
    }

    private boolean positive(BigDecimal amount) {
        return amount != null && amount.compareTo(BigDecimal.ZERO) > 0;
    }
}
