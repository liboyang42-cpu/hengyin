package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.GiftPlayFact;
import com.chengyinhub.business.mapper.GiftEntitlementMapper;
import com.chengyinhub.business.service.IMarketingGiftPlayEligibility;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 礼品 → 可玩资格的只读投影。
 *
 * <p>只在自玩域（owner_type=1，owner=主题）生效：礼品 SKU 只能挂在自玩通行证上，
 * 活动/场次仍按原报名资格判定，不受礼品影响（返回空）。
 */
@Service
public class MarketingGiftPlayEligibility implements IMarketingGiftPlayEligibility {

    /** 与 {@code cms_registration.owner_type} 对齐：1=主题 2=活动。 */
    private static final long OWNER_TOPIC = 1L;

    private final GiftEntitlementMapper giftMapper;

    public MarketingGiftPlayEligibility(GiftEntitlementMapper giftMapper) {
        this.giftMapper = giftMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<GiftPlayFact> playFacts(Long memberId, long ownerType, Long ownerId) {
        if (memberId == null || memberId <= 0
                || ownerType != OWNER_TOPIC || ownerId == null || ownerId <= 0) return new ArrayList<>();
        List<GiftPlayFact> facts = giftMapper.selectPlayFactsByOwner(memberId, OWNER_TOPIC, ownerId);
        return facts == null ? new ArrayList<>() : facts;
    }
}
