package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.GiftEntitlement;
import com.chengyinhub.business.domain.GiftPlayFact;
import com.chengyinhub.business.domain.MarketingProduct;
import com.chengyinhub.business.domain.MarketingProductItem;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.service.support.CurrentPlayRegistration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 礼品对可玩资格的影响：待领取无人可玩、领取后仅领取人、买家/跨主题拒绝、旧非礼品行为不变。
 * 不建第二份准入，所有判定都落在原 cms_registration 那一行上。
 */
class GiftPlayEligibilityTest {

    private static final long BUYER = 7L;
    private static final long RECIPIENT = 9L;
    private static final long TOPIC = 100L;
    private static final long REGISTRATION = 500L;

    private final CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
    private final CmsActivityMapper activities = mock(CmsActivityMapper.class);

    @Test
    void pendingGiftBlocksBuyerAndRecipient() {
        buyerOwnsRow();
        IMarketingGiftPlayEligibility gift = facts(fact(GiftPlayFact.STATUS_PENDING, null));

        assertDenied(BUYER, gift);
        assertDenied(RECIPIENT, gift);
    }

    @Test
    void claimedGiftAdmitsOnlyRecipient() {
        buyerOwnsRow();
        IMarketingGiftPlayEligibility gift = facts(fact(GiftPlayFact.STATUS_CLAIMED, RECIPIENT));

        CmsRegistration admitted = CurrentPlayRegistration.require(null, registrations, RECIPIENT,
                1L, TOPIC, null, new Date(), false, gift);
        assertEquals(REGISTRATION, admitted.getId().longValue());
        assertEquals(BUYER, admitted.getMemberId().longValue(), "财务归属仍是买家");
        assertDenied(BUYER, gift);
    }

    @Test
    void selfClaimKeepsSingleAdmissionForBuyer() {
        buyerOwnsRow();
        IMarketingGiftPlayEligibility gift = facts(fact(GiftPlayFact.STATUS_CLAIMED, BUYER));

        CmsRegistration admitted = CurrentPlayRegistration.require(null, registrations, BUYER,
                1L, TOPIC, null, new Date(), false, gift);
        assertEquals(REGISTRATION, admitted.getId().longValue());
        assertDenied(RECIPIENT, gift);
    }

    @Test
    void legacyNonGiftBehaviourIsUnchanged() {
        buyerOwnsRow();
        CmsRegistration admitted = CurrentPlayRegistration.require(null, registrations, BUYER,
                1L, TOPIC, null, new Date(), false, facts());
        assertEquals(REGISTRATION, admitted.getId().longValue());
        assertDenied(RECIPIENT, facts());
    }

    @Test
    void giftFactPointingAtAnotherTopicDoesNotGrantAccess() {
        buyerOwnsRow();
        CmsRegistration foreign = row(RECIPIENT);
        foreign.setOwnerId(999L); // 别的主题的行
        when(registrations.selectCmsRegistrationById(900L)).thenReturn(foreign);
        GiftPlayFact crossTopic = fact(GiftPlayFact.STATUS_CLAIMED, RECIPIENT);
        crossTopic.setRegistrationId(900L);

        // 只按 id 补行不够：必须仍是本 owner 域的成功行；否则拒绝（不接受客户端/越域 id）。
        assertDenied(RECIPIENT, facts(crossTopic));
    }

    @Test
    void revokedRegistrationRowIsNeverAdmitted() {
        CmsRegistration row = row(BUYER);
        row.setRegistrationStatus(3);
        when(registrations.selectCmsRegistrationList(any())).thenAnswer(inv -> {
            CmsRegistration probe = inv.getArgument(0);
            return BUYER == probe.getMemberId() ? Collections.singletonList(row) : new ArrayList<>();
        });
        IMarketingGiftPlayEligibility gift = facts(fact(GiftPlayFact.STATUS_CLAIMED, RECIPIENT));

        assertDenied(RECIPIENT, gift);
        assertDenied(BUYER, gift);
    }

    @Test
    void eligibilityQueryCarriesActorAndDoesNotFanOut() {
        com.chengyinhub.business.mapper.GiftEntitlementMapper gifts =
                mock(com.chengyinhub.business.mapper.GiftEntitlementMapper.class);
        when(gifts.selectPlayFactsByOwner(RECIPIENT, 1L, TOPIC))
                .thenReturn(List.of(fact(GiftPlayFact.STATUS_CLAIMED, RECIPIENT)));
        com.chengyinhub.business.service.impl.MarketingGiftPlayEligibility eligibility =
                new com.chengyinhub.business.service.impl.MarketingGiftPlayEligibility(gifts);

        List<GiftPlayFact> facts = eligibility.playFacts(RECIPIENT, 1L, TOPIC);

        assertEquals(1, facts.size());
        // 只按 actor 取（buyer 行 or claimed给本人），不拿全主题所有人的准入事实。
        org.mockito.Mockito.verify(gifts).selectPlayFactsByOwner(RECIPIENT, 1L, TOPIC);
        org.mockito.Mockito.verifyNoMoreInteractions(gifts);
    }

    @Test
    void activityScopeNeverConsultsGiftFacts() {
        com.chengyinhub.business.mapper.GiftEntitlementMapper gifts =
                mock(com.chengyinhub.business.mapper.GiftEntitlementMapper.class);
        com.chengyinhub.business.service.impl.MarketingGiftPlayEligibility eligibility =
                new com.chengyinhub.business.service.impl.MarketingGiftPlayEligibility(gifts);

        assertTrue(eligibility.playFacts(BUYER, 2L, 555L).isEmpty());
        org.mockito.Mockito.verifyNoInteractions(gifts);
    }

    // ------------------------------------------------------------------ helpers

    private void buyerOwnsRow() {
        CmsRegistration row = row(BUYER);
        when(registrations.selectCmsRegistrationList(any())).thenAnswer(inv -> {
            CmsRegistration probe = inv.getArgument(0);
            return BUYER == probe.getMemberId() ? Collections.singletonList(row) : new ArrayList<>();
        });
        when(registrations.selectCmsRegistrationByIdForUpdate(REGISTRATION)).thenReturn(row);
        when(registrations.selectCmsRegistrationById(REGISTRATION)).thenReturn(row);
    }

    private void assertDenied(long memberId, IMarketingGiftPlayEligibility gift) {
        // 只断言"不放行"；具体文案沿用既有入口（有原对象=失效 / 无对象=先购买），不在此锁死。
        assertThrows(IllegalStateException.class,
                () -> CurrentPlayRegistration.require(null, registrations, memberId, 1L, TOPIC,
                        null, new Date(), false, gift));
    }

    private IMarketingGiftPlayEligibility facts(GiftPlayFact... rows) {
        List<GiftPlayFact> list = new ArrayList<>();
        Collections.addAll(list, rows);
        return (memberId, ownerType, ownerId) -> list;
    }

    private GiftPlayFact fact(int status, Long claimedMemberId) {
        GiftPlayFact fact = new GiftPlayFact();
        fact.setRegistrationId(REGISTRATION);
        fact.setStatus(status);
        fact.setClaimedMemberId(claimedMemberId);
        return fact;
    }

    private CmsRegistration row(long memberId) {
        CmsRegistration row = new CmsRegistration();
        row.setId(REGISTRATION);
        row.setMemberId(memberId);
        row.setOwnerType(1L);
        row.setOwnerId(TOPIC);
        row.setRegistrationStatus(2);
        row.setPaymentStatus(2);
        row.setPurchaseKind(2);
        row.setExpiresAt(new Date(System.currentTimeMillis() + 86_400_000L));
        return row;
    }
}
