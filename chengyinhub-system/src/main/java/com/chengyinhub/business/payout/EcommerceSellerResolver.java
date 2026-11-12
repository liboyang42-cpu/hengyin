package com.chengyinhub.business.payout;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.EcomSubMerchant;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.EcomPayoutMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.IProductRoleService;
import org.springframework.stereotype.Service;

/**
 * 为一笔门票报名单找出卖家事实，交给 {@link SellerOfRecordPolicy} 决定是否以二级商户下单。
 * 开关关闭时不读任何表，直接返回平台卖家（与现有行为一致）。
 */
@Service
public class EcommerceSellerResolver {
    private final PayoutSwitches switches;
    private final EcomPayoutMapper payoutMapper;
    private final CmsTopicMapper topicMapper;
    private final CmsActivityMapper activityMapper;
    private final MmsMerchantMapper merchantMapper;
    private final IProductRoleService productRoleService;

    public EcommerceSellerResolver(PayoutSwitches switches, EcomPayoutMapper payoutMapper, CmsTopicMapper topicMapper,
                                   CmsActivityMapper activityMapper, MmsMerchantMapper merchantMapper,
                                   IProductRoleService productRoleService) {
        this.switches = switches;
        this.payoutMapper = payoutMapper;
        this.topicMapper = topicMapper;
        this.activityMapper = activityMapper;
        this.merchantMapper = merchantMapper;
        this.productRoleService = productRoleService;
    }

    public SellerOfRecordPolicy.Decision decide(CmsRegistration registration) {
        if (!switches.sellerRoutingEnabled()) return SellerOfRecordPolicy.decide(null, switches);
        return SellerOfRecordPolicy.decide(facts(registration), switches);
    }

    SellerOfRecordPolicy.Facts facts(CmsRegistration registration) {
        if (registration == null || registration.getOwnerId() == null) return null;
        long ownerType = registration.getOwnerType() == null ? 0 : registration.getOwnerType();
        SellerOfRecordPolicy.ProductKind kind;
        boolean platformOwned;
        Long clubId;
        Long memberId;
        if (ownerType == 1L) {
            CmsTopic topic = topicMapper.selectCmsTopicById(registration.getOwnerId());
            if (topic == null) return null;
            kind = SellerOfRecordPolicy.ProductKind.TOPIC_TICKET;
            platformOwned = productRoleService.isPlatformOwned(topic.getId());
            clubId = topic.getClubId();
            memberId = topic.getMemberId();
        } else if (ownerType == 2L) {
            CmsActivity activity = activityMapper.selectCmsActivityById(registration.getOwnerId());
            if (activity == null) return null;
            kind = SellerOfRecordPolicy.ProductKind.ACTIVITY_TICKET;
            platformOwned = activity.getTopicId() != null && productRoleService.isPlatformOwned(activity.getTopicId());
            clubId = activity.getClubId();
            memberId = activity.getMemberId();
        } else {
            return null;
        }
        SellerOfRecordPolicy.SellerType sellerType;
        Long sellerId;
        if (clubId != null && clubId > 0) {
            sellerType = SellerOfRecordPolicy.SellerType.CLUB;
            sellerId = clubId;
        } else {
            MmsMerchant merchant = memberId == null ? null : merchantMapper.selectMmsMerchantByMemberId(memberId);
            if (merchant != null && Long.valueOf(1L).equals(merchant.getStatus())
                    && Long.valueOf(1L).equals(merchant.getAccountStatus())
                    && Integer.valueOf(0).equals(merchant.getDelFlag())) {
                sellerType = SellerOfRecordPolicy.SellerType.MERCHANT;
                sellerId = merchant.getId();
            } else {
                sellerType = SellerOfRecordPolicy.SellerType.MEMBER;
                sellerId = memberId;
            }
        }
        String state = null;
        String subMchid = null;
        if (sellerType == SellerOfRecordPolicy.SellerType.CLUB || sellerType == SellerOfRecordPolicy.SellerType.MERCHANT) {
            EcomSubMerchant row = payoutMapper.selectSubMerchant(sellerType.name(), sellerId);
            if (row != null) {
                state = row.getApplymentState();
                subMchid = row.getSubMchid();
            }
        }
        return new SellerOfRecordPolicy.Facts(kind, platformOwned, sellerType, sellerId, state, subMchid);
    }
}
