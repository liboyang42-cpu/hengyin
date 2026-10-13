package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.service.support.CoopOrderLineupSource;
import com.chengyinhub.business.service.support.FailClosedCoopOrderLineupSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

class FailClosedCoopOrderLineupSourceTest {

    private static final Long TOPIC_ID = 61L;
    private CoopOrderMapper mapper;
    private CoopInviteMapper inviteMapper;
    private FailClosedCoopOrderLineupSource source;

    @BeforeEach
    void setUp() {
        mapper = mock(CoopOrderMapper.class);
        inviteMapper = mock(CoopInviteMapper.class);
        when(inviteMapper.selectCoopInviteById(anyLong())).thenAnswer(invocation ->
                acceptedType1(invocation.getArgument(0)));
        source = new FailClosedCoopOrderLineupSource(mapper, inviteMapper);
    }

    @Test
    void completeType1SnapshotReturnsDeterministicOrganizer() {
        CoopOrder order = completeOrganizer(91L);
        when(mapper.selectEffectiveOrdersByTopicId(TOPIC_ID)).thenReturn(Collections.singletonList(order));

        CoopOrderLineupSource.ReadResult result = source.readEffectiveLineup(TOPIC_ID);

        assertTrue(result.getRepairReasons().isEmpty());
        assertEquals(1, result.getEntries().size());
        CoopOrderLineupSource.LineupEntry entry = result.getEntries().get(0);
        assertEquals("CLUB", entry.getSubjectType());
        assertEquals(Long.valueOf(701L), entry.getSubjectId());
        assertEquals("ORGANIZER", entry.getRole());
        assertEquals("TOPIC", entry.getScopeType());
        assertEquals(TOPIC_ID, entry.getScopeId());
        assertEquals("COOP_ORDER", entry.getContractRefType());
        assertEquals(Long.valueOf(91L), entry.getContractRefId());
        assertEquals(Long.valueOf(501L), entry.getInitiatorMemberId());
        assertFalse(entry.isCashObligation());
    }

    @Test
    void cashObligationComesFromFrozenTermsSnapshotNotMutableLegacyColumns() {
        CoopOrder order = completeOrganizer(92L);
        order.setShareMode(0);
        order.setPerksSnapshot(null);
        order.setTermsSnapshot("{\"compensationMode\":\"SALES_PERCENT\",\"benefitMode\":\"NONE\"}");
        when(mapper.selectEffectiveOrdersByTopicId(TOPIC_ID)).thenReturn(Collections.singletonList(order));

        CoopOrderLineupSource.ReadResult result = source.readEffectiveLineup(TOPIC_ID);

        assertTrue(result.getRepairReasons().isEmpty());
        assertTrue(result.getEntries().get(0).isCashObligation());
    }

    @Test
    void eachMissingW2FieldFailsClosedAndNamesOrderId() {
        assertRepair(missing("role"), "roleSnapshot");
        assertRepair(missing("scopeType"), "scopeTypeSnapshot");
        assertRepair(missing("scopeId"), "scopeIdSnapshot");
        assertRepair(missing("purchaseKinds"), "eligiblePurchaseKindsSnapshot");
        assertRepair(missing("evidenceRule"), "fulfillmentEvidenceRuleSnapshot");
        assertRepair(missing("terms"), "termsSnapshot");

    }

    @Test
    void wrongOwnerScopeOrType1ContractShapeFailsClosed() {
        CoopOrder wrongOwner = completeOrganizer(94L);
        wrongOwner.setToType("merchant");
        assertRepair(wrongOwner, "toType=club");

        CoopOrder wrongScope = completeOrganizer(95L);
        wrongScope.setScopeIdSnapshot(999L);
        assertRepair(wrongScope, "scopeIdSnapshot=topicId");

        CoopOrder wrongKinds = completeOrganizer(96L);
        wrongKinds.setEligiblePurchaseKindsSnapshot("EXPLORE_PASS");
        assertRepair(wrongKinds, "GUIDED_TICKET");

        CoopOrder wrongEvidence = completeOrganizer(97L);
        wrongEvidence.setFulfillmentEvidenceRuleSnapshot("REGISTRATION_SCAN");
        assertRepair(wrongEvidence, "COOP_GROUP_VERIFY");
    }

    @Test
    void mapperContractBreachForVoidedOrDeletedRowIsNotSilentlyIgnored() {
        CoopOrder voided = completeOrganizer(98L);
        voided.setStatus(1);
        assertRepair(voided, "status=0");

        CoopOrder deleted = completeOrganizer(99L);
        deleted.setDelFlag(2);
        assertRepair(deleted, "delFlag=0");
    }

    @Test
    void organizerMustComeFromAcceptedType1InviteOwnedByTopicPublisher() {
        CoopOrder fakeType0 = completeOrganizer(102L);
        when(inviteMapper.selectCoopInviteById(fakeType0.getInviteId()))
                .thenReturn(sourceInvite(fakeType0.getInviteId(), 0, 1, 501L));
        assertRepair(fakeType0, "inviteType=1");

        CoopOrder wrongInitiator = completeOrganizer(103L);
        when(inviteMapper.selectCoopInviteById(wrongInitiator.getInviteId()))
                .thenReturn(sourceInvite(wrongInitiator.getInviteId(), 1, 1, 999L));
        assertRepair(wrongInitiator, "fromId 与来源邀约不一致");

        CoopOrder pending = completeOrganizer(104L);
        when(inviteMapper.selectCoopInviteById(pending.getInviteId()))
                .thenReturn(sourceInvite(pending.getInviteId(), 1, 0, 501L));
        assertRepair(pending, "status=1");

        CoopOrder deletedSource = completeOrganizer(105L);
        when(inviteMapper.selectCoopInviteById(deletedSource.getInviteId())).thenReturn(null);
        assertRepair(deletedSource, "来源邀约不存在或已删除");
    }

    @Test
    void organizerLegacyPastConfigDeadlineStaysInLineup() {
        // 阻断#8 / 台账 1-11:存量已接受邀约带着过期的 72h 死线,阵容读取不得据此判无效
        CoopOrder order = completeOrganizer(106L);
        CoopInvite legacy = acceptedType1(order.getInviteId());
        legacy.setConfigDeadline(new Date(System.currentTimeMillis() - 1_000L));
        when(inviteMapper.selectCoopInviteById(order.getInviteId())).thenReturn(legacy);
        when(mapper.selectEffectiveOrdersByTopicId(TOPIC_ID)).thenReturn(Collections.singletonList(order));

        CoopOrderLineupSource.ReadResult result = source.readEffectiveLineup(TOPIC_ID);

        assertTrue(result.getRepairReasons().isEmpty(), String.valueOf(result.getRepairReasons()));
        assertEquals(1, result.getEntries().size());
    }

    private CoopOrder missing(String field) {
        CoopOrder order = completeOrganizer(93L);
        if ("role".equals(field)) order.setRoleSnapshot(null);
        if ("scopeType".equals(field)) order.setScopeTypeSnapshot(null);
        if ("scopeId".equals(field)) order.setScopeIdSnapshot(null);
        if ("purchaseKinds".equals(field)) order.setEligiblePurchaseKindsSnapshot(null);
        if ("evidenceRule".equals(field)) order.setFulfillmentEvidenceRuleSnapshot(null);
        if ("terms".equals(field)) order.setTermsSnapshot(null);
        return order;
    }

    private void assertRepair(CoopOrder order, String field) {
        when(mapper.selectEffectiveOrdersByTopicId(TOPIC_ID)).thenReturn(Collections.singletonList(order));
        CoopOrderLineupSource.ReadResult result = source.readEffectiveLineup(TOPIC_ID);
        assertTrue(result.getEntries().isEmpty());
        assertEquals(1, result.getRepairReasons().size());
        assertTrue(result.getRepairReasons().get(0).contains("coop_order:" + order.getId()));
        assertTrue(result.getRepairReasons().get(0).contains(field), result.getRepairReasons().get(0));
    }

    private static CoopOrder completeOrganizer(Long orderId) {
        CoopOrder order = new CoopOrder();
        order.setId(orderId);
        order.setInviteId(orderId + 1000);
        order.setTopicId(TOPIC_ID);
        order.setFromId(501L);
        order.setToId(701L);
        order.setToType("club");
        order.setRoleSnapshot("ORGANIZER");
        order.setScopeTypeSnapshot("TOPIC");
        order.setScopeIdSnapshot(TOPIC_ID);
        order.setEligiblePurchaseKindsSnapshot("GUIDED_TICKET");
        order.setFulfillmentEvidenceRuleSnapshot("COOP_GROUP_VERIFY");
        order.setTermsSnapshot("{\"compensationMode\":\"NONE\",\"benefitMode\":\"NONE\"}");
        order.setStatus(0);
        order.setDelFlag(0);
        return order;
    }


    private static CoopInvite acceptedType1(Long inviteId) {
        return sourceInvite(inviteId, 1, 1, 501L);
    }

    private static CoopInvite sourceInvite(Long inviteId, int inviteType, int status, Long fromId) {
        CoopInvite invite = new CoopInvite();
        invite.setId(inviteId);
        invite.setInviteType(inviteType);
        invite.setFromId(fromId);
        invite.setToType("club");
        invite.setToId(701L);
        invite.setTopicId(TOPIC_ID);
        invite.setStatus(status);
        invite.setDelFlag(0);
        return invite;
    }
}
