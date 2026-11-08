package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.MarketingProduct;
import com.chengyinhub.business.domain.MarketingProductItem;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.dto.MarketingGroupCheckoutCreateRequest;
import com.chengyinhub.business.domain.dto.MarketingGroupCheckoutQuoteRequest;
import com.chengyinhub.business.domain.dto.MarketingGroupCheckoutQuoteResult;
import com.chengyinhub.business.domain.dto.MarketingGroupCheckoutResult;
import com.chengyinhub.business.domain.dto.RegistrationQuoteResult;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.domain.dto.RegistrationTicketItem;
import com.chengyinhub.business.exception.MarketingProductException;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.business.service.IMarketingGroupCheckoutService;
import com.chengyinhub.business.service.IRegistrationCheckoutService;
import com.chengyinhub.business.service.RegistrationCheckoutResult;
import com.chengyinhub.business.service.support.MarketingProductRules;
import com.chengyinhub.common.config.ComplianceProperties;
import com.chengyinhub.common.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 团体/学校商品到既有报名结算的适配实现。
 *
 * <p><b>不变量</b>：
 * <ul>
 *   <li>商品必须是 product_kind=5/6 且 fulfillment_route=2，绑定的是真实活动票种；</li>
 *   <li>票种所属活动必须绑定主题，且该主题在商品内容绑定内（无关票种/跨 owner 直接拒绝）；</li>
 *   <li>报价与建单只调用 {@link IRegistrationCheckoutService} 的原方法：金额、积分、库存、
 *       场次、名额竞争、幂等、支付事实都由原链路决定；</li>
 *   <li>本类不写任何订单/席位/权益表，不接收客户端金额，也不把商品目录价当成交价。</li>
 * </ul>
 */
@Service
public class MarketingGroupCheckoutServiceImpl implements IMarketingGroupCheckoutService {

    private static final Logger log = LoggerFactory.getLogger(MarketingGroupCheckoutServiceImpl.class);

    private static final String SIGNUP_DATA_SHARING_DOC = "activity_host_data_sharing";
    private static final String SIGNUP_DATA_SHARING_SCENE = "activity_signup";
    private static final String QUOTE_ENDPOINT = "/api/marketing/group-checkout/quote";
    private static final String CREATE_ENDPOINT = "/api/marketing/group-checkout/create";
    private static final int OWNER_TYPE_ACTIVITY = 2;

    private final MarketingProductRules rules;
    private final ICmsActivityService activityService;
    private final IRegistrationCheckoutService checkoutService;
    private final IConsentRecordService consentRecordService;
    private final ComplianceProperties complianceProperties;

    public MarketingGroupCheckoutServiceImpl(MarketingProductRules rules,
                                             ICmsActivityService activityService,
                                             IRegistrationCheckoutService checkoutService,
                                             IConsentRecordService consentRecordService,
                                             ComplianceProperties complianceProperties) {
        this.rules = rules;
        this.activityService = activityService;
        this.checkoutService = checkoutService;
        this.consentRecordService = consentRecordService;
        this.complianceProperties = complianceProperties;
    }

    @Override
    @Transactional(readOnly = true)
    public MarketingGroupCheckoutQuoteResult quote(long callerMemberId,
                                                   MarketingGroupCheckoutQuoteRequest request) {
        requireMember(callerMemberId);
        if (request == null) {
            throw MarketingProductException.badRequest("PRODUCT_REQUIRED", "请选择商品");
        }
        Binding binding = resolve(request.getProductId(), request.getQuantity());
        RegistrationRequest registrationRequest = baseRequest(binding,
                request.getIsUsePoint(), request.getOfferCode(), request.getParticipateDate());
        RegistrationQuoteResult quote = checkoutService.quote(callerMemberId, registrationRequest);
        if (quote == null) {
            throw MarketingProductException.conflict("REGISTRATION_QUOTE_UNAVAILABLE",
                    "报名报价暂不可用，请稍后再试");
        }
        return toQuoteResult(binding, request, quote);
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public MarketingGroupCheckoutResult create(long callerMemberId,
                                               MarketingGroupCheckoutCreateRequest request) {
        requireMember(callerMemberId);
        if (request == null || request.getProductId() == null) {
            throw MarketingProductException.badRequest("PRODUCT_REQUIRED", "请选择商品");
        }
        requireSignupConsent(callerMemberId);
        String requestId = trimToNull(request.getRequestId());
        if (requestId == null) {
            throw MarketingProductException.badRequest("REQUEST_ID_REQUIRED", "报名幂等键不能为空");
        }
        if (requestId.length() > 64) {
            throw MarketingProductException.badRequest("REQUEST_ID_INVALID", "幂等键长度不能超过64个字符");
        }
        if (trimToNull(request.getQuoteSign()) == null) {
            throw MarketingProductException.badRequest("QUOTE_SIGN_REQUIRED", "缺少报价签名，请重新报价");
        }
        if (trimToNull(request.getRealName()) == null) {
            throw MarketingProductException.badRequest("REAL_NAME_REQUIRED", "请输入姓名");
        }
        if (trimToNull(request.getPhone()) == null) {
            throw MarketingProductException.badRequest("PHONE_REQUIRED", "请输入联系电话");
        }
        String offerCode = trimToNull(request.getOfferCode());
        if (offerCode != null && offerCode.length() > 64) {
            throw MarketingProductException.badRequest("OFFER_CODE_INVALID", "优惠码长度不能超过64个字符");
        }
        String marketingSign = trimToNull(request.getMarketingSign());
        if (marketingSign != null && marketingSign.length() > 128) {
            throw MarketingProductException.badRequest("MARKETING_SIGN_INVALID", "促销指纹长度不能超过128个字符");
        }
        String sourceChannelCode = trimToNull(request.getSourceChannelCode());
        if (sourceChannelCode != null && sourceChannelCode.length() > 64) {
            throw MarketingProductException.badRequest("SOURCE_CHANNEL_INVALID", "票源标记长度不能超过64个字符");
        }
        Binding binding = resolve(request.getProductId(), request.getQuantity());
        RegistrationRequest registrationRequest = baseRequest(binding,
                request.getIsUsePoint(), offerCode, request.getParticipateDate());
        registrationRequest.setRealName(trimToNull(request.getRealName()));
        registrationRequest.setPhone(trimToNull(request.getPhone()));
        registrationRequest.setEmail(trimToNull(request.getEmail()));
        registrationRequest.setAddress(trimToNull(request.getAddress()));
        registrationRequest.setAddressId(request.getAddressId());
        registrationRequest.setSourceClubId(request.getSourceClubId());
        registrationRequest.setSourceChannelCode(sourceChannelCode);
        registrationRequest.setRequestId(requestId);
        registrationRequest.setQuoteSign(trimToNull(request.getQuoteSign()));
        registrationRequest.setMarketingSign(marketingSign);
        registrationRequest.setPayChannel(trimToNull(request.getPayChannel()));
        RegistrationCheckoutResult original = checkoutService.createOrReplay(callerMemberId, registrationRequest);
        return toResult(binding, original);
    }

    /**
     * 解析并校验「商品 → 活动票种 → 活动/主题」这一段关系。
     * 票种本身的可售/场次/库存仍交给原报价与建单闸，本方法不复制那些规则。
     */
    private Binding resolve(Long productId, Integer requestedQuantity) {
        if (productId == null || productId <= 0) {
            throw MarketingProductException.badRequest("PRODUCT_REQUIRED", "请选择商品");
        }
        MarketingProduct product = rules.requireProduct(productId);
        rules.requireSellable(product, new Date());
        Integer kind = product.getProductKind();
        if (kind == null || (kind != MarketingProduct.KIND_GROUP_TEAM
                && kind != MarketingProduct.KIND_SCHOOL_GROUP)) {
            throw MarketingProductException.conflict("GROUP_PRODUCT_KIND_INVALID",
                    "该商品不是团体/学校商品");
        }
        if (!product.usesTicketRegistration()) {
            throw MarketingProductException.conflict("GROUP_PRODUCT_ROUTE_INVALID",
                    "团体/学校商品必须走既有报名结算");
        }
        if (!Integer.valueOf(2).equals(product.getPurchaseKind())) {
            // 团体/学校固定映射 SELF_PLAY_PASS(2)；误配探索票会走 reserveEntitlements，必须拒绝。
            throw MarketingProductException.conflict("GROUP_PURCHASE_KIND_INVALID",
                    "团体/学校商品必须映射自玩通行证");
        }
        int quantity = requestedQuantity == null ? 1 : requestedQuantity;
        rules.requireQuantity(quantity);

        OmsTicket ticket = rules.boundTicket(product);
        if (ticket == null || ticket.getId() == null || ticket.getOwnerId() == null) {
            throw MarketingProductException.conflict("TICKET_BINDING_NOT_FOUND", "绑定的票种不存在");
        }
        if (!Long.valueOf(OWNER_TYPE_ACTIVITY).equals(ticket.getOwnerType())) {
            // 主题级报名会落到自玩通行证，不是团体席位链；这里 fail-closed。
            throw MarketingProductException.conflict("GROUP_TICKET_OWNER_UNSUPPORTED",
                    "团体/学校商品必须绑定活动票种");
        }
        CmsActivity activity = activityService.selectCmsActivityById(ticket.getOwnerId());
        if (activity == null || Integer.valueOf(2).equals(activity.getDelFlag())) {
            throw MarketingProductException.conflict("GROUP_ACTIVITY_NOT_FOUND", "票种所属活动不存在");
        }
        Long topicId = activity.getTopicId();
        if (topicId == null || topicId <= 0) {
            throw MarketingProductException.conflict("GROUP_ACTIVITY_TOPIC_MISSING",
                    "票种所属活动没有绑定主题");
        }
        if (!productCoversTopic(rules.items(product.getId()), topicId)) {
            throw MarketingProductException.forbidden("GROUP_TICKET_PRODUCT_MISMATCH",
                    "票种所属活动不在商品内容范围内");
        }
        return new Binding(product, ticket, activity, topicId, quantity);
    }

    /** 只构造原报名请求；任何金额/折扣字段都不由客户端提供。 */
    private RegistrationRequest baseRequest(Binding binding, Integer isUsePoint,
                                            String offerCode, String participateDate) {
        RegistrationRequest request = new RegistrationRequest();
        request.setOwnerType(OWNER_TYPE_ACTIVITY);
        request.setOwnerId(binding.activity.getId());
        request.setIsUsePoint(normalizePointFlag(isUsePoint));
        request.setOfferCode(trimToNull(offerCode));
        RegistrationTicketItem line = new RegistrationTicketItem();
        line.setTicketId(binding.ticket.getId());
        line.setCount(binding.quantity);
        line.setParticipateDate(trimToNull(participateDate));
        request.setTickets(Collections.singletonList(line));
        return request;
    }

    private MarketingGroupCheckoutQuoteResult toQuoteResult(Binding binding,
                                                            MarketingGroupCheckoutQuoteRequest request,
                                                            RegistrationQuoteResult quote) {
        MarketingProduct product = binding.product;
        MarketingGroupCheckoutQuoteResult result = new MarketingGroupCheckoutQuoteResult();
        result.setProductId(product.getId());
        result.setProductCode(product.getProductCode());
        result.setProductName(product.getProductName());
        result.setProductKind(product.getProductKind());
        result.setProductRevision(product.getRevision());
        result.setTermsVersion(product.getTermsVersion());
        result.setPurchaseRoute(MarketingProduct.ROUTE_TICKET_REGISTRATION);
        result.setPurchaseRouteReason("USE_REGISTRATION_CHECKOUT");
        result.setAmountAuthority("REGISTRATION_CHECKOUT");
        result.setCatalogCashPrice(product.getCashPrice());
        result.setCatalogPointsPrice(product.getPointsPrice());
        result.setSeatCount(product.getSeatCount());
        result.setQuantity(binding.quantity);
        result.setParticipateDate(trimToNull(request.getParticipateDate()));
        result.setIsUsePoint(normalizePointFlag(request.getIsUsePoint()));
        result.setOfferCode(trimToNull(request.getOfferCode()));
        result.setOwnerType(OWNER_TYPE_ACTIVITY);
        result.setActivityId(binding.activity.getId());
        result.setTopicId(binding.topicId);
        result.setTicketId(binding.ticket.getId());
        result.setSessionStartAt(binding.ticket.getStartTime());
        result.setSessionEndAt(binding.ticket.getEndTime());
        result.setSignupDeadline(binding.ticket.getSignupDeadline());
        result.setRemainingInventory(binding.ticket.getRemainingInventory());
        result.setQuoteEndpoint(QUOTE_ENDPOINT);
        result.setCreateEndpoint(CREATE_ENDPOINT);
        result.setQuote(quote);
        return result;
    }

    private MarketingGroupCheckoutResult toResult(Binding binding, RegistrationCheckoutResult original) {
        Map<String, Object> data = original.getData();
        Map<String, Object> registration = data == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(data));
        MarketingGroupCheckoutResult result = new MarketingGroupCheckoutResult();
        result.setProductId(binding.product.getId());
        result.setPurchaseRoute(MarketingProduct.ROUTE_TICKET_REGISTRATION);
        result.setOwnerType(OWNER_TYPE_ACTIVITY);
        result.setActivityId(binding.activity.getId());
        result.setTopicId(binding.topicId);
        result.setTicketId(binding.ticket.getId());
        result.setQuantity(binding.quantity);
        result.setMessage(original.getMessage());
        result.setRegistrationId(asLong(registration.get("registrationId")));
        result.setRegistrationIds(asLongList(registration.get("registrationIds")));
        result.setRegistration(registration);
        return result;
    }

    private void requireSignupConsent(long memberId) {
        String version = complianceProperties == null
                ? null : complianceProperties.versionOf(SIGNUP_DATA_SHARING_DOC);
        if (StringUtils.isEmpty(version)) {
            log.error("报名信息单独同意版本未配置，拒绝团体/学校建单 memberId={}", memberId);
            throw MarketingProductException.conflict("SIGNUP_DATA_SHARING_VERSION_MISSING",
                    "报名信息单独同意版本未配置");
        }
        boolean agreed;
        try {
            agreed = consentRecordService.hasCurrentAgreement(
                    memberId, SIGNUP_DATA_SHARING_DOC, SIGNUP_DATA_SHARING_SCENE, version);
        } catch (RuntimeException failure) {
            log.error("报名信息单独同意校验失败，拒绝团体/学校建单 memberId={}", memberId, failure);
            agreed = false;
        }
        if (!agreed) {
            throw MarketingProductException.forbidden("SIGNUP_DATA_SHARING_CONSENT_REQUIRED",
                    "请先完成报名信息提供的单独同意");
        }
    }

    private boolean productCoversTopic(List<MarketingProductItem> items, Long topicId) {
        if (items == null) return false;
        for (MarketingProductItem item : items) {
            if (item != null && topicId.equals(item.getTopicId())) return true;
        }
        return false;
    }

    private void requireMember(long memberId) {
        if (memberId <= 0) throw MarketingProductException.unauthorized();
    }

    private int normalizePointFlag(Integer isUsePoint) {
        return isUsePoint != null && isUsePoint == 1 ? 1 : 0;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Long asLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : null;
    }

    private List<Long> asLongList(Object value) {
        if (!(value instanceof Collection)) return Collections.<Long>emptyList();
        List<Long> ids = new ArrayList<>();
        for (Object element : (Collection<?>) value) {
            if (element instanceof Number) ids.add(((Number) element).longValue());
        }
        return ids;
    }

    private static final class Binding {
        private final MarketingProduct product;
        private final OmsTicket ticket;
        private final CmsActivity activity;
        private final Long topicId;
        private final int quantity;

        private Binding(MarketingProduct product, OmsTicket ticket, CmsActivity activity,
                        Long topicId, int quantity) {
            this.product = product;
            this.ticket = ticket;
            this.activity = activity;
            this.topicId = topicId;
            this.quantity = quantity;
        }
    }
}
