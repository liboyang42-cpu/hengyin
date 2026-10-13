package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.support.CoopOrderLineupSource;
import com.chengyinhub.business.service.support.MerchantPublishedTopicTransferGate;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantPublishedTopicTransferGateTest {

    private static final Long TOPIC_ID = 61L;
    private static final Long PUBLISHER_ID = 501L;
    private MmsMerchantMapper merchantMapper;
    private ClubMapper clubMapper;
    private CoopOrderLineupSource lineupSource;
    private MerchantPublishedTopicTransferGate gate;

    @BeforeEach
    void setUp() {
        merchantMapper = mock(MmsMerchantMapper.class);
        clubMapper = mock(ClubMapper.class);
        lineupSource = mock(CoopOrderLineupSource.class);
        gate = new MerchantPublishedTopicTransferGate(merchantMapper, clubMapper, lineupSource);
    }

    @Test
    void merchantPublisherIsResolvedByTopicMemberOwnershipNotTopicType() {
        CmsTopic topic = freeExploreTopic();
        when(merchantMapper.selectMmsMerchantByMemberId(PUBLISHER_ID)).thenReturn(merchant(PUBLISHER_ID));
        when(lineupSource.readEffectiveLineup(TOPIC_ID)).thenReturn(lineup(organizer(701L)));

        assertDoesNotThrow(() -> gate.assertTransferred(topic));

        verify(merchantMapper).selectMmsMerchantByMemberId(PUBLISHER_ID);
        verify(merchantMapper, never()).selectMmsMerchantByMemberId(TOPIC_ID);
    }

    @Test
    void merchantFreeExploreRequiresExactlyOneCompleteOrganizer() {
        CmsTopic topic = freeExploreTopic();
        when(merchantMapper.selectMmsMerchantByMemberId(PUBLISHER_ID)).thenReturn(merchant(PUBLISHER_ID));

        IllegalStateException none = assertThrows(IllegalStateException.class,
                () -> gate.assertTransferred(topic, lineup()));
        assertTrue(none.getMessage().contains("当前=0"));

        IllegalStateException multiple = assertThrows(IllegalStateException.class,
                () -> gate.assertTransferred(topic, lineup(organizer(701L), organizer(702L))));
        assertTrue(multiple.getMessage().contains("当前=2"));

        CoopOrderLineupSource.LineupEntry wrongScope = new CoopOrderLineupSource.LineupEntry(
                "CLUB", 701L, "ORGANIZER", "TOPIC", 999L, "COOP_ORDER", 91L, false,
                PUBLISHER_ID);
        IllegalStateException malformed = assertThrows(IllegalStateException.class,
                () -> gate.assertTransferred(topic, lineup(wrongScope)));
        assertTrue(malformed.getMessage().contains("scope"));

        CoopOrderLineupSource.LineupEntry wrongInitiator = new CoopOrderLineupSource.LineupEntry(
                "CLUB", 701L, "ORGANIZER", "TOPIC", TOPIC_ID, "COOP_ORDER", 91L, false,
                999L);
        IllegalStateException wrongOwner = assertThrows(IllegalStateException.class,
                () -> gate.assertTransferred(topic, lineup(wrongInitiator)));
        assertTrue(wrongOwner.getMessage().contains("owner"));
    }

    @Test
    void frozenSnapshotRepairReasonFailsClosedAndNamesOrder() {
        CmsTopic topic = freeExploreTopic();
        when(merchantMapper.selectMmsMerchantByMemberId(PUBLISHER_ID)).thenReturn(merchant(PUBLISHER_ID));
        CoopOrderLineupSource.ReadResult damaged = new CoopOrderLineupSource.ReadResult(
                Collections.emptyList(), Collections.singletonList("coop_order:93 roleSnapshot 缺失"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> gate.assertTransferred(topic, damaged));

        assertTrue(error.getMessage().contains("coop_order:93"));
    }

    @Test
    void nonMerchantPublisherDoesNotReceiveTransferGate() {
        CmsTopic topic = freeExploreTopic();
        when(merchantMapper.selectMmsMerchantByMemberId(PUBLISHER_ID)).thenReturn(null);

        assertDoesNotThrow(() -> gate.assertTransferred(topic));

        verify(lineupSource, never()).readEffectiveLineup(TOPIC_ID);
    }

    @Test
    void clubOwnedTopicDoesNotReceiveMerchantTransferGateWhenOwnerAlsoHasMerchantProfile() {
        CmsTopic topic = freeExploreTopic();
        topic.setClubId(701L);
        Club club = new Club();
        club.setId(701L);
        club.setMemberId(PUBLISHER_ID);
        when(clubMapper.selectClubById(701L)).thenReturn(club);
        when(merchantMapper.selectMmsMerchantByMemberId(PUBLISHER_ID)).thenReturn(merchant(PUBLISHER_ID));

        assertDoesNotThrow(() -> gate.assertTransferred(topic));
        verify(lineupSource, never()).readEffectiveLineup(TOPIC_ID);
    }

    @Test
    void inactiveMerchantRecordCannotEraseHistoricalPublisherTransferGate() {
        CmsTopic topic = freeExploreTopic();
        MmsMerchant rejected = merchant(PUBLISHER_ID);
        rejected.setStatus(2L);
        when(merchantMapper.selectMmsMerchantByMemberId(PUBLISHER_ID)).thenReturn(rejected);

        assertThrows(IllegalStateException.class, () -> gate.assertTransferred(topic));
        verify(lineupSource).readEffectiveLineup(TOPIC_ID);
    }

    @Test
    void mismatchedMerchantOwnershipFailsClosedInsteadOfBypassing() {
        CmsTopic topic = freeExploreTopic();
        when(merchantMapper.selectMmsMerchantByMemberId(PUBLISHER_ID)).thenReturn(merchant(999L));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> gate.assertTransferred(topic));

        assertTrue(error.getMessage().contains("memberId=" + PUBLISHER_ID));
    }

    @Test
    void transferBecomesInvalidWhenAcceptedOrderIsVoidedAfterSaleReview() {
        CmsTopic topic = freeExploreTopic();
        when(merchantMapper.selectMmsMerchantByMemberId(PUBLISHER_ID)).thenReturn(merchant(PUBLISHER_ID));
        when(lineupSource.readEffectiveLineup(TOPIC_ID))
                .thenReturn(lineup(organizer(701L)))
                .thenReturn(lineup());

        assertDoesNotThrow(() -> gate.assertTransferred(topic));
        IllegalStateException stale = assertThrows(IllegalStateException.class,
                () -> gate.assertTransferred(topic));

        assertTrue(stale.getMessage().contains("当前=0"));
    }

    private static CmsTopic freeExploreTopic() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setMemberId(PUBLISHER_ID);
        topic.setProductType(ProductType.FREE_EXPLORE);
        return topic;
    }

    private static MmsMerchant merchant(Long memberId) {
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(801L);
        merchant.setMemberId(memberId);
        merchant.setStatus(1L);
        merchant.setAccountStatus(1L);
        merchant.setDelFlag(0);
        return merchant;
    }

    private static CoopOrderLineupSource.ReadResult lineup(CoopOrderLineupSource.LineupEntry... entries) {
        return new CoopOrderLineupSource.ReadResult(Arrays.asList(entries), Collections.emptyList());
    }

    private static CoopOrderLineupSource.LineupEntry organizer(Long clubId) {
        return new CoopOrderLineupSource.LineupEntry("CLUB", clubId, "ORGANIZER", "TOPIC", TOPIC_ID,
                "COOP_ORDER", clubId + 100, false, PUBLISHER_ID);
    }
}
