package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.ITopicCommercialReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit doubles model persistence; transaction/SQL/concurrency proof belongs to CoopSupplyStationMySqlIT. */
class CoopSupplyStationContractTest {
    private final CoopInviteMapper invites = mock(CoopInviteMapper.class);
    private final CoopOrderMapper orders = mock(CoopOrderMapper.class);
    private final CmsTopicMapper topics = mock(CmsTopicMapper.class);
    private final MmsMerchantMapper merchants = mock(MmsMerchantMapper.class);
    private CoopInviteServiceImpl service;
    private CoopInvite original;
    private CmsTopic topic;

    @BeforeEach void fixture() {
        service = new CoopInviteServiceImpl();
        ReflectionTestUtils.setField(service,"coopInviteMapper",invites);
        ReflectionTestUtils.setField(service,"coopOrderMapper",orders);
        ReflectionTestUtils.setField(service,"cmsTopicMapper",topics);
        ReflectionTestUtils.setField(service,"mmsMerchantMapper",merchants);
        ReflectionTestUtils.setField(service,"coopCreditLogMapper",mock(CoopCreditLogMapper.class));
        ReflectionTestUtils.setField(service,"coopPerkMapper",mock(CoopPerkMapper.class));
        ReflectionTestUtils.setField(service,"commercialReviewService",mock(ITopicCommercialReviewService.class));
        topic = new CmsTopic(); topic.setId(7L); topic.setMemberId(11L); topic.setDelFlag(0);
        topic.setProductType(1); topic.setLifecycle(1); topic.setRecruitDeadline(new Date(System.currentTimeMillis()+3600000));
        when(topics.selectCmsTopicById(7L)).thenReturn(topic);
        when(topics.selectCmsTopicByIdForUpdate(7L)).thenReturn(topic);
        MmsMerchant merchant = new MmsMerchant(); merchant.setMemberId(22L);
        merchant.setStatus(1L); merchant.setAccountStatus(1L); merchant.setDelFlag(0);
        when(merchants.selectMmsMerchantByMemberId(22L)).thenReturn(merchant);
        when(merchants.selectMmsMerchantByMemberIdForUpdate(22L)).thenReturn(merchant);
        original = invite(41L,101L);
        when(invites.selectCoopInviteById(41L)).thenReturn(original);
        when(invites.selectCoopInviteByIdForUpdate(41L)).thenReturn(original);
        when(invites.selectCoopInviteList(any())).thenReturn(Collections.emptyList());
        when(invites.selectSupplyStations(7L,22L)).thenReturn(List.of(station(101L,7L,22L),station(102L,7L,22L)));
        when(invites.selectSupplyStationForUpdate(anyLong(),eq(7L),eq(22L)))
                .thenAnswer(c -> station(c.getArgument(0),7L,22L));
        when(invites.selectEffectiveSupplyOrders(7L,22L)).thenReturn(Collections.emptyList());
        when(invites.selectEffectiveSupplyOrdersForUpdate(7L,22L)).thenReturn(Collections.emptyList());
        when(invites.selectOriginalCoopOrdersForUpdate(any(),any())).thenReturn(Collections.emptyList());
        when(invites.selectSupplyStationNames(anyList())).thenReturn(List.of(station(101L,7L,22L)));
        doAnswer(c -> { CoopInvite update=c.getArgument(0); if(update.getStatus()!=null) original.setStatus(update.getStatus()); return 1; })
                .when(invites).updateCoopInvite(any());
        when(invites.insertCoopInvite(any())).thenReturn(1);
        doAnswer(c -> { CoopOrder order=c.getArgument(0); order.setId(81L); return 1; }).when(orders).insertCoopOrder(any());
    }

    private static CoopInvite invite(Long id,Long stationId) {
        CoopInvite row=new CoopInvite(); row.setId(id); row.setInviteType(0); row.setStatus(0); row.setDelFlag(0);
        row.setTopicId(7L); row.setFromType("talent"); row.setFromId(11L); row.setToType("merchant"); row.setToId(22L);
        row.setSupplyStationId(stationId); row.setShareMode(2); row.setFixedFee(new java.math.BigDecimal("12.00")); return row;
    }
    private static CmsRegistrationMerchant station(Long id,Long topic,Long member) {
        CmsRegistrationMerchant row=new CmsRegistrationMerchant(); row.setId(id); row.setTopicId(topic); row.setMemberId(member);
        row.setAuditStatus(1L); row.setDelFlag(0); row.setAddressName("原站"+id); return row;
    }
    private static CoopOrder order(CoopInvite source) {
        CoopOrder row=new CoopOrder(); row.setInviteId(source.getId()); row.setTopicId(source.getTopicId());
        row.setFromId(source.getFromId()); row.setToId(source.getToId()); row.setToType(source.getToType());
        row.setGameId(source.getGameId()); return row;
    }
    private CoopOrder frozenOrder() {
        original.setStatus(1); CoopOrder row=order(original); service.createCoopOrder(row); clearInvocations(orders); return row;
    }

    @Test void createRequiresExplicitRealStationBeforeInsert() {
        CoopInvite request=invite(null,null);
        assertThrows(IllegalStateException.class,()->service.insertCoopInvite(request));
        verify(invites,never()).insertCoopInvite(any()); verify(orders,never()).insertCoopOrder(any());
    }
    @Test void createRejectsCrossTopicMerchantAndUnapprovedStation() {
        for(CmsRegistrationMerchant wrong:List.of(station(101L,8L,22L),station(101L,7L,23L),station(102L,7L,22L))) {
            when(invites.selectSupplyStationForUpdate(101L,7L,22L)).thenReturn(wrong);
            assertThrows(IllegalStateException.class,()->service.insertCoopInvite(invite(null,101L)));
        }
        CmsRegistrationMerchant pending=station(101L,7L,22L); pending.setAuditStatus(0L);
        when(invites.selectSupplyStationForUpdate(101L,7L,22L)).thenReturn(pending);
        assertThrows(IllegalStateException.class,()->service.insertCoopInvite(invite(null,101L)));
        verify(invites,never()).insertCoopInvite(any());
    }
    @Test void createAllowsExistingLegalStationWithoutInventingStatusOrValidationMethodGate() {
        service.insertCoopInvite(invite(null,101L));
        verify(invites).insertCoopInvite(argThat(r->Long.valueOf(101).equals(r.getSupplyStationId())&&r.getStatus()==0));
        InOrder locks=inOrder(topics,invites); locks.verify(topics).selectCmsTopicByIdForUpdate(7L);
        locks.verify(invites).selectSupplyStationForUpdate(101L,7L,22L);
    }
    @Test void missingOrChangedConfirmationCannotAcceptOrCreateOrder() {
        assertThrows(IllegalStateException.class,()->service.acceptInviteWithLock(41L,null));
        assertThrows(IllegalStateException.class,()->service.acceptInviteWithLock(41L,null,102L));
        verify(invites,never()).updateCoopInvite(any()); verify(orders,never()).insertCoopOrder(any());
        assertEquals(0,original.getStatus()); assertEquals(101L,original.getSupplyStationId());
    }
    @Test void acceptanceFreezesServerScopeAndOriginalFinancialAxes() {
        service.acceptInviteWithLock(41L,null,101L);
        var captor=org.mockito.ArgumentCaptor.forClass(CoopOrder.class); verify(orders).insertCoopOrder(captor.capture());
        CoopOrder row=captor.getValue(); var scope=JSON.parseObject(row.getTermsSnapshot()).getJSONObject("supplyBillingScope");
        assertEquals("REGISTRATION_STATION_V1",scope.getString("version")); assertEquals(101L,scope.getLong("stationId"));
        assertEquals(7L,scope.getLong("topicId")); assertEquals(22L,scope.getLong("merchantMemberId"));
        assertEquals("SUPPLY",row.getRoleSnapshot()); assertEquals("TOPIC",row.getScopeTypeSnapshot());
        assertEquals("GUIDED_TICKET",row.getEligiblePurchaseKindsSnapshot()); assertEquals("REGISTRATION_SCAN",row.getFulfillmentEvidenceRuleSnapshot());
        assertEquals(2,row.getShareMode()); assertEquals(new java.math.BigDecimal("12.00"),row.getFixedFee());
        assertEquals(101L,original.getSupplyStationId()); assertNull(original.getNodeId());
    }
    @Test void occupiedStationRejectsNewInviteAndOrderButExactOriginalReplayRemainsStable() {
        CoopOrder existing=frozenOrder();
        when(invites.selectEffectiveSupplyOrdersForUpdate(7L,22L)).thenReturn(List.of(existing));
        assertThrows(IllegalStateException.class,()->service.insertCoopInvite(invite(null,101L)));
        CoopInvite other=invite(42L,101L); other.setStatus(1);
        when(invites.selectCoopInviteById(42L)).thenReturn(other); when(invites.selectCoopInviteByIdForUpdate(42L)).thenReturn(other);
        assertThrows(IllegalStateException.class,()->service.createCoopOrder(order(other)));
        when(invites.selectOriginalCoopOrdersForUpdate(41L,null)).thenReturn(List.of(existing));
        assertEquals(existing.getId(),service.createCoopOrder(order(original)));
        verify(orders,never()).insertCoopOrder(any());
    }
    @Test void distinctAvailableStationRemainsSelectableAndNoOrderHistoryIsLoadedAcrossScope() {
        CoopOrder existing=frozenOrder(); when(invites.selectEffectiveSupplyOrders(7L,22L)).thenReturn(List.of(existing));
        assertEquals(List.of(102L),service.listSupplyStations(7L,22L,11L).stream().map(CmsRegistrationMerchant::getId).toList());
        verify(invites).selectEffectiveSupplyOrders(7L,22L); verifyNoInteractions(orders);
    }
    @Test void publicOrderFunnelCannotUseNoInviteForgedIdentityOrPendingInvite() {
        assertThrows(IllegalStateException.class,()->service.createCoopOrder(new CoopOrder()));
        assertThrows(IllegalStateException.class,()->service.createCoopOrder(order(original)));
        original.setStatus(1); CoopOrder forged=order(original); forged.setToId(23L);
        assertThrows(IllegalStateException.class,()->service.createCoopOrder(forged)); verify(orders,never()).insertCoopOrder(any());
    }
    @Test void immutableStationCannotBeEditedBeforeOrAfterAcceptance() {
        CoopInvite patch=new CoopInvite(); patch.setId(41L); patch.setSupplyStationId(102L);
        assertThrows(IllegalStateException.class,()->service.updateCoopInvite(patch));
        original.setStatus(1); assertThrows(IllegalStateException.class,()->service.updateCoopInvite(patch));
        verify(invites,never()).updateCoopInvite(any());
    }
    @Test void historyNeverGetsStationOrV1ContractFromAcceptPayload() {
        original.setSupplyStationId(null);
        assertThrows(IllegalStateException.class,()->service.acceptInviteWithLock(41L,null,101L));
        service.acceptInviteWithLock(41L,null);
        var captor=org.mockito.ArgumentCaptor.forClass(CoopOrder.class); verify(orders).insertCoopOrder(captor.capture());
        assertFalse(JSON.parseObject(captor.getValue().getTermsSnapshot()).containsKey("supplyBillingScope"));
        assertNull(original.getSupplyStationId());
    }
    @Test void readNamesUseOnlyExactOriginalTupleAndNeverFirstCurrentStationFallback() {
        original.setSupplyStationName("client-forged");
        CoopInvite wrong=invite(42L,101L); wrong.setTopicId(8L);
        CoopInvite legacy=invite(43L,null);
        CoopInvite club=invite(44L,null); club.setInviteType(1); club.setToType("club");
        CoopInvite node=invite(45L,null); node.setInviteType(2); node.setNodeId(9L);
        when(invites.selectCoopInviteListReadOnly(any(),any())).thenReturn(List.of(original,wrong,legacy,club,node));
        List<CoopInvite> rows=service.selectCoopInviteListReadOnly(new CoopInvite());
        assertEquals("原站101",rows.get(0).getSupplyStationName()); assertNull(rows.get(1).getSupplyStationName());
        assertNull(rows.get(2).getSupplyStationName()); assertNull(rows.get(2).getSupplyStationId());
        assertEquals(Boolean.TRUE,rows.get(0).getRequiresSupplyStation()); assertNull(rows.get(2).getRequiresSupplyStation());
        assertEquals(Boolean.FALSE,rows.get(3).getRequiresSupplyStation());
        assertEquals(Boolean.FALSE,rows.get(4).getRequiresSupplyStation());
        assertNull(rows.get(3).getSupplyStationName()); assertNull(rows.get(4).getSupplyStationName());
        verify(invites,times(1)).selectSupplyStationNames(anyList()); verify(invites,never()).updateCoopInvite(any());
        verify(invites,never()).selectSupplyStations(any(),any());
    }
    @Test void responseNameAndApplicabilityAreNotWritableJsonInputs() throws Exception {
        ObjectMapper json=new ObjectMapper();
        CoopInvite decoded=json.readValue("{\"supplyStationId\":101,\"supplyStationName\":\"forged\",\"requiresSupplyStation\":false}",CoopInvite.class);
        assertEquals(101L,decoded.getSupplyStationId()); assertNull(decoded.getSupplyStationName()); assertNull(decoded.getRequiresSupplyStation());
        decoded.setSupplyStationName("original"); decoded.setRequiresSupplyStation(true);
        var tree=json.readTree(json.writeValueAsString(decoded)); assertEquals("101",tree.path("supplyStationId").asText());
        assertEquals("original",tree.path("supplyStationName").asText()); assertTrue(tree.path("requiresSupplyStation").asBoolean());
    }
    @Test void creatorReadRejectsWrongOwnerNonclassicAndStorageFailureRatherThanFalseApplicability() {
        assertThrows(IllegalStateException.class,()->service.listSupplyStations(7L,22L,99L));
        topic.setProductType(2); assertThrows(IllegalStateException.class,()->service.listSupplyStations(7L,22L,11L));
        topic.setProductType(1); when(invites.selectSupplyStations(7L,22L)).thenThrow(new IllegalStateException("storage"));
        assertThrows(IllegalStateException.class,()->service.listSupplyStations(7L,22L,11L));
        verify(invites,never()).insertCoopInvite(any()); verify(orders,never()).insertCoopOrder(any());
    }
}
