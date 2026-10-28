package com.chengyinhub.business.service;

import com.chengyinhub.business.access.merchant.*;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.CoopInviteRequestResult;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.CoopInviteServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CoopInviteOriginalRequestTest {
    private CoopInviteServiceImpl service;
    private CoopInviteRequestReceiptMapper receipts;
    private CoopInviteMapper invites;
    private CmsTopicMapper topics;
    private ClubMapper clubs;
    private IMerchantAccessService access;
    private ICoopClubApplyService applies;
    private INotifyService notify;
    private final Map<String, CoopInviteRequestReceipt> saved = new HashMap<>();
    private CoopInvite original;
    private CmsTopic topic;

    @BeforeEach
    void setUp() {
        saved.clear();
        receipts = mock(CoopInviteRequestReceiptMapper.class);
        invites = mock(CoopInviteMapper.class);
        topics = mock(CmsTopicMapper.class);
        clubs = mock(ClubMapper.class);
        access = mock(IMerchantAccessService.class);
        applies = mock(ICoopClubApplyService.class);
        notify = mock(INotifyService.class);
        MmsMerchantMapper merchants = mock(MmsMerchantMapper.class);
        when(merchants.selectMmsMerchantByMemberId(anyLong())).thenAnswer(call -> merchant(call.getArgument(0)));
        when(merchants.selectMmsMerchantByMemberIdForUpdate(anyLong())).thenAnswer(call -> merchant(call.getArgument(0)));
        topic = new CmsTopic();
        topic.setId(10L); topic.setMemberId(1L); topic.setDelFlag(0); topic.setLifecycle(1);
        topic.setRecruitDeadline(new Date(System.currentTimeMillis() + 86400000));
        when(topics.selectCmsTopicById(anyLong())).thenReturn(topic);
        when(topics.selectCmsTopicByIdForUpdate(anyLong())).thenReturn(topic);
        when(receipts.selectByRequest(anyLong(), anyString())).thenAnswer(call -> saved.get(call.getArgument(0) + ":" + call.getArgument(1)));
        when(receipts.selectByRequestForUpdate(anyLong(), anyString())).thenAnswer(call -> saved.get(call.getArgument(0) + ":" + call.getArgument(1)));
        when(receipts.insertReceipt(any())).thenAnswer(call -> {
            CoopInviteRequestReceipt row = call.getArgument(0);
            row.setId(7L); saved.put(row.getPrincipalMemberId() + ":" + row.getRequestId(), row); return 1;
        });
        when(receipts.bindInvite(anyLong(), anyLong())).thenReturn(1);
        when(receipts.selectTopicInvitesForUpdate(anyLong(), any())).thenReturn(Collections.emptyList());
        when(invites.insertCoopInvite(any())).thenAnswer(call -> {
            original = call.getArgument(0); original.setId(9007199254740993L); return 1;
        });
        when(invites.selectCoopInviteById(anyLong())).thenAnswer(call -> original);
        when(invites.selectCoopInviteByIdForUpdate(anyLong())).thenAnswer(call -> original);
        service = new CoopInviteServiceImpl();
        ReflectionTestUtils.setField(service, "requestReceiptMapper", receipts);
        ReflectionTestUtils.setField(service, "coopInviteMapper", invites);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(service, "clubMapper", clubs);
        ReflectionTestUtils.setField(service, "mmsMerchantMapper", merchants);
        ReflectionTestUtils.setField(service, "merchantAccessService", access);
        org.springframework.transaction.PlatformTransactionManager manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
        ReflectionTestUtils.setField(service, "transactionManager", manager);
        ReflectionTestUtils.setField(service, "coopClubApplyService", applies);
        ReflectionTestUtils.setField(service, "notifyService", notify);
        ReflectionTestUtils.setField(service, "clubCoopWindowPolicy", new com.chengyinhub.business.service.support.ClubCoopWindowPolicy(
                mock(com.chengyinhub.business.service.support.CommercialTopicPolicy.class)));
    }

    private MmsMerchant merchant(Long id) {
        MmsMerchant row = new MmsMerchant(); row.setMemberId(id); row.setStatus(1L); row.setAccountStatus(1L); row.setDelFlag(0); return row;
    }
    private CoopInvite request() {
        CoopInvite row = new CoopInvite(); row.setRequestId("vr_original"); row.setInviteType(0);
        row.setTopicId(10L); row.setToType("merchant"); row.setToId(2L); row.setMessage("hello");
        row.setShareMode(2); row.setFixedFee(new BigDecimal("12.00")); return row;
    }
    private CoopInviteRequestResult send(CoopInvite request) {
        return service.createOrReplayInvite(1L, request, () -> {});
    }

    @Test
    void serialReplayBypassesCreationGatesAfterHandlingConversionAndExpiry() {
        AtomicInteger firstChecks = new AtomicInteger();
        CoopInviteRequestResult first = service.createOrReplayInvite(1L, request(), firstChecks::incrementAndGet);
        assertFalse(first.getReplayed());
        assertEquals("9007199254740993", first.getInviteId());
        assertEquals("", first.getScope());
        topic.setRecruitDeadline(new Date(0)); topic.setLifecycle(5);
        for (int state : List.of(1, 2, 3, 4, 5)) {
            original.setStatus(state); original.setExpireTime(new Date(0));
            CoopInviteRequestResult replay = service.createOrReplayInvite(1L, request(), firstChecks::incrementAndGet);
            assertTrue(replay.getReplayed()); assertEquals(first.getInviteId(), replay.getInviteId());
        }
        assertEquals(1, firstChecks.get());
        verify(invites, times(1)).insertCoopInvite(any());
        verify(receipts, times(1)).bindInvite(anyLong(), anyLong());
        verifyNoInteractions(notify);
    }

    @Test
    void originalStationProjectionKeepsExactIdAndRejectsNonStationReceiptTypes() {
        send(request());
        CoopInviteRequestReceipt receipt = saved.values().iterator().next();
        assertEquals("9007199254740993", CoopInviteRequestResult.created(receipt, true,
                9007199254740993L).getSupplyStationId());
        assertNull(CoopInviteRequestResult.created(receipt, true).getSupplyStationId());
        assertThrows(IllegalArgumentException.class, () -> CoopInviteRequestResult.created(receipt, true, 0L));
        receipt.setInviteType(1);
        assertThrows(IllegalArgumentException.class, () -> CoopInviteRequestResult.created(receipt, true, 71L));
    }

    @Test
    void everyEffectiveContractFieldConflictsAndScaleAliasesReplay() {
        send(request());
        List<Consumer<CoopInvite>> changes = List.of(
                r -> r.setToId(3L), r -> r.setTopicId(11L), r -> r.setMessage("changed"),
                r -> r.setFixedFee(new BigDecimal("13")), r -> r.setShareMode(0),
                r -> r.setShareRate(BigDecimal.ONE), r -> r.setGameId(8L), r -> r.setOriginApplyId(9L),
                r -> r.setSupplyStationId(71L));
        for (Consumer<CoopInvite> change : changes) {
            CoopInvite changed = request(); change.accept(changed);
            ServiceException failure = assertThrows(ServiceException.class, () -> send(changed));
            assertEquals(409, failure.getCode()); assertEquals("COOP_REQUEST_CONFLICT", failure.getMessage());
        }
        CoopInvite scaleAlias = request(); scaleAlias.setFixedFee(new BigDecimal("12.000"));
        assertTrue(send(scaleAlias).getReplayed());
        verify(invites, times(1)).insertCoopInvite(any());
    }

    @Test
    void spoofedAndServerOverriddenFieldsDoNotChangeFingerprint() {
        send(request());
        CoopInvite spoof = request(); spoof.setId(99L); spoof.setFromId(88L); spoof.setFromType("official");
        spoof.setNodeId(9L); spoof.setTargetLevel(99); spoof.setStatus(1); spoof.setHandleTime(new Date());
        spoof.setExpireTime(new Date(0)); spoof.setCreateTime(new Date(0)); spoof.setConfigDeadline(new Date(0));
        spoof.setDelFlag(1); spoof.setCreateBy("spoof"); spoof.setHandleReason("spoof");
        assertTrue(send(spoof).getReplayed());
        assertEquals(9007199254740993L, spoof.getId());
    }

    @Test
    void crossAccountCannotReadOrEnumerateOriginalAndKeyIsNotAuthorization() {
        send(request());
        assertEquals("NOT_FOUND", service.findInviteRequest(9L, "vr_original").getState());
        topic.setMemberId(9L);
        CoopInviteRequestResult unavailable = service.findInviteRequest(1L, "vr_original");
        assertEquals("UNAVAILABLE", unavailable.getState()); assertNull(unavailable.getInviteId()); assertNull(unavailable.getTopicId());
        assertEquals(403, assertThrows(ServiceException.class, () -> send(request())).getCode());
        verify(invites, times(1)).insertCoopInvite(any());
    }

    @Test
    void revokedEmployeeAndMerchantSubjectMigrationFailClosed() {
        when(access.require(1L, MerchantPermission.PROJECT_MANAGE)).thenReturn(new MerchantAccessContext(1L, 8L, 1L, "owner"));
        CoopInvite merchant = request(); merchant.setScope(" merchant ");
        assertEquals("MERCHANT", send(merchant).getScope());
        when(access.require(1L, MerchantPermission.PROJECT_MANAGE)).thenThrow(new ServiceException("revoked", 403));
        assertEquals("UNAVAILABLE", service.findInviteRequest(1L, merchant.getRequestId()).getState());
        assertEquals(403, assertThrows(ServiceException.class, () -> send(merchant)).getCode());
        doReturn(new MerchantAccessContext(1L, 8L, 4L, "owner")).when(access).require(1L, MerchantPermission.PROJECT_MANAGE);
        topic.setMemberId(4L);
        assertEquals("UNAVAILABLE", service.findInviteRequest(1L, merchant.getRequestId()).getState());
    }

    @Test
    void deletedInviteOrTopicIsUnavailableAndReadPathsHaveNoWritesOrNotifications() {
        send(request()); clearInvocations(receipts, invites, topics, clubs);
        assertEquals("CREATED", service.findInviteRequest(1L, "vr_original").getState());
        when(receipts.selectPage(1L, null, 2)).thenReturn(List.of(saved.get("1:vr_original"), saved.get("1:vr_original")));
        original.setDelFlag(1);
        Map<String, Object> page = service.listInviteRequests(1L, null, 1);
        assertEquals("7", page.get("nextCursor")); assertEquals(true, page.get("hasMore"));
        CoopInviteRequestResult item = (CoopInviteRequestResult) ((List<?>) page.get("rows")).get(0);
        assertEquals("UNAVAILABLE", item.getState()); assertNull(item.getScope());
        original.setDelFlag(0); topic.setDelFlag(1);
        assertEquals("UNAVAILABLE", service.findInviteRequest(1L, "vr_original").getState());
        verify(receipts, never()).insertReceipt(any()); verify(receipts, never()).bindInvite(anyLong(), anyLong());
        verify(receipts, never()).selectByRequestForUpdate(anyLong(), anyString());
        verify(invites, never()).insertCoopInvite(any()); verify(invites, never()).expirePendingInvites(any());
        verify(invites, never()).selectCoopInviteByIdForUpdate(anyLong()); verifyNoInteractions(notify);
    }

    @Test
    void validationRejectsBadKeysAndPaginationBeforeAnyClaim() {
        for (String key : Arrays.asList(null, "", "has space", "é", "a".repeat(65))) {
            CoopInvite request = request(); request.setRequestId(key);
            assertEquals(400, assertThrows(ServiceException.class, () -> send(request)).getCode());
        }
        assertThrows(ServiceException.class, () -> service.listInviteRequests(1L, 0L, 1));
        assertThrows(ServiceException.class, () -> service.listInviteRequests(1L, null, 51));
        verify(receipts, never()).insertReceipt(any());
    }

    @Test
    void failedInviteOrBindNeverReturnsAnUnboundReceipt() {
        doReturn(0).when(invites).insertCoopInvite(any());
        assertThrows(IllegalStateException.class, () -> send(request()));
        verify(receipts, never()).bindInvite(anyLong(), anyLong());
        saved.clear(); when(invites.insertCoopInvite(any())).thenAnswer(call -> { ((CoopInvite) call.getArgument(0)).setId(22L); return 1; });
        when(receipts.bindInvite(anyLong(), anyLong())).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> send(request()));
    }
    @Test
    void clubPublisherAndRecipientOwnershipChangesAreUnavailableAndType1ScopeIsIgnored() {
        Club publisher = new Club(); publisher.setId(50L); publisher.setMemberId(1L); publisher.setDelFlag(0);
        Club recipient = new Club(); recipient.setId(60L); recipient.setMemberId(2L); recipient.setDelFlag(0);
        topic.setClubId(50L);
        when(clubs.selectClubById(50L)).thenReturn(publisher); when(clubs.selectClubByIdForUpdate(50L)).thenReturn(publisher);
        when(clubs.selectClubById(60L)).thenReturn(recipient); when(clubs.selectClubByIdForUpdate(60L)).thenReturn(recipient);
        CoopInvite first = request(); first.setInviteType(1); first.setToType("club"); first.setToId(60L); first.setScope("MERCHANT");
        assertEquals("", send(first).getScope());
        CoopInvite alias = request(); alias.setInviteType(1); alias.setToType("club"); alias.setToId(60L); alias.setScope("unused");
        assertTrue(send(alias).getReplayed());
        recipient.setMemberId(3L);
        assertEquals("UNAVAILABLE", service.findInviteRequest(1L, first.getRequestId()).getState());
        recipient.setMemberId(2L); publisher.setMemberId(3L);
        assertEquals("UNAVAILABLE", service.findInviteRequest(1L, first.getRequestId()).getState());
    }

    @Test
    void historicalAndOfficialTypesCannotClaimOrdinaryReceipt() {
        for (int type : List.of(2, 3)) {
            CoopInvite request = request(); request.setInviteType(type);
            assertEquals(400, assertThrows(ServiceException.class, () -> send(request)).getCode());
        }
        verify(receipts, never()).insertReceipt(any());
    }

    @Test
    void actualListJsonUsesRowsAndTerminalCursorNull() throws Exception {
        send(request());
        when(receipts.selectPage(1L, null, 21)).thenReturn(List.of(saved.get("1:vr_original")));
        Map<String, Object> page = service.listInviteRequests(1L, null, 20);
        com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(page));
        assertTrue(json.has("rows")); assertFalse(json.has("items"));
        assertEquals(1, json.path("rows").size());
        assertEquals("9007199254740993", json.path("rows").get(0).path("inviteId").asText());
        assertTrue(json.path("rows").get(0).path("inviteId").isTextual());
        assertFalse(json.path("hasMore").asBoolean()); assertTrue(json.path("nextCursor").isNull());
    }

    @Test
    void subjectMovedWhileWaitingForTopicLockCannotBindOldClaim() {
        CmsTopic moved = new CmsTopic(); moved.setId(10L); moved.setMemberId(9L); moved.setDelFlag(0);
        moved.setLifecycle(1); moved.setRecruitDeadline(new Date(System.currentTimeMillis() + 86400000));
        when(topics.selectCmsTopicByIdForUpdate(10L)).thenReturn(moved);
        assertEquals(403, assertThrows(ServiceException.class, () -> send(request())).getCode());
        verify(invites, never()).insertCoopInvite(any()); verify(receipts, never()).bindInvite(anyLong(), anyLong());
    }

}
