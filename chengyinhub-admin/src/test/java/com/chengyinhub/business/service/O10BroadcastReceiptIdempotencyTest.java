package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubEventCampaign;
import com.chengyinhub.business.domain.ClubEventDelivery;
import com.chengyinhub.business.domain.dto.ClubEventCampaignRequest;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.service.impl.ClubEventNotificationServiceImpl;
import com.chengyinhub.business.service.impl.ClubEventNotificationTransactionService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Doc2 O-10:群发回执幂等。同一 requestId 的重放(串行或并发)只能落一个 campaign、
 * 每个收件人一条 delivery 回执,已 SENT 的回执在重试/补发时不会再触达 IM。
 * 数据库侧唯一键 uk_club_event_campaign_request / uk_club_event_delivery_recipient
 * 见 migration_club_role_event_ops_20260823.sql;这里锁住服务层不依赖唯一键兜底的行为。
 */
class O10BroadcastReceiptIdempotencyTest {
    private static final Long ACTOR = 41L;
    private static final Long CLUB = 21L;
    private static final Long ACTIVITY = 31L;
    private IClubAccessService access;
    private ClubEventOpsMapper mapper;
    private IImService im;
    private ClubEventNotificationTransactionService tx;
    private ClubEventNotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        access = mock(IClubAccessService.class);
        mapper = mock(ClubEventOpsMapper.class);
        ClubMapper clubs = mock(ClubMapper.class);
        im = mock(IImService.class);
        tx = new ClubEventNotificationTransactionService(mapper, im, access, clubs);
        service = new ClubEventNotificationServiceImpl(access, mapper, tx);
        ReflectionTestUtils.setField(service, "wxContentSecurityService",
                mock(IWxContentSecurityService.class));
        ReflectionTestUtils.setField(service, "umsMemberService", mock(IUmsMemberService.class));
        when(access.require(ACTOR, CLUB, ACTIVITY, ClubPermission.EVENT_OPERATE)).thenReturn(
                new ClubAccessContext(ACTOR, CLUB, 99L, ACTIVITY, Collections.singleton(ClubRoleCode.EVENT_LEAD)));
        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(99L);
        club.setStatus(1L);
        club.setDelFlag(0);
        when(clubs.selectClubByIdForUpdate(CLUB)).thenReturn(club);
        when(mapper.isEligibleNotificationRecipient(any(), any(), any(), any(), any())).thenReturn(1);
        when(mapper.claimNotificationOutbox(any(), any())).thenReturn(1);
    }

    @Test
    void concurrentReplayLosingTheUniqueKeyReturnsWinnerWithoutAnyReceiptOrSend() {
        ClubEventCampaign winner = campaign(61L, "dup-req", 2, 2, 0, ClubEventCampaign.COMPLETED);
        when(mapper.selectCampaignByRequest(CLUB, ACTOR, "dup-req")).thenReturn(null, winner);
        when(mapper.selectRegisteredRecipients(CLUB, ACTIVITY)).thenReturn(Arrays.asList(51L, 52L));
        when(mapper.insertCampaign(any())).thenThrow(new DuplicateKeyException("uk_club_event_campaign_request"));

        assertSame(winner, service.send(ACTOR, request("dup-req", "请提前十分钟到场")));

        verify(mapper, never()).insertDelivery(any());
        verify(mapper, never()).insertOutbox(any(), any());
        verify(mapper, never()).selectDispatchableByCampaign(any(), anyInt());
        verify(im, never()).sendSystem(any(), any(), any(), any());
    }

    @Test
    void serialReplaysOfACompletedCampaignNeverCreateReceiptsOrResend() {
        ClubEventCampaign existing = campaign(61L, "replay", 2, 2, 0, ClubEventCampaign.COMPLETED);
        when(mapper.selectCampaignByRequest(CLUB, ACTOR, "replay")).thenReturn(existing);

        for (int i = 0; i < 3; i++) {
            assertSame(existing, service.send(ACTOR, request("replay", "请提前十分钟到场")));
        }

        verify(mapper, never()).insertCampaign(any());
        verify(mapper, never()).insertDelivery(any());
        verify(mapper, never()).selectRegisteredRecipients(any(), any());
        verify(im, never()).sendSystem(any(), any(), any(), any());
    }

    @Test
    void replayWithSameRequestIdButDifferentContentIsConflictNotSecondCampaign() {
        when(mapper.selectCampaignByRequest(CLUB, ACTOR, "replay")).thenReturn(
                campaign(61L, "replay", 1, 1, 0, ClubEventCampaign.COMPLETED));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.send(ACTOR, request("replay", "改过的内容")));

        assertEquals(Integer.valueOf(HttpStatus.CONFLICT), error.getCode());
        verify(mapper, never()).insertCampaign(any());
        verify(im, never()).sendSystem(any(), any(), any(), any());
    }

    @Test
    void duplicatedAudienceRowsProduceExactlyOneReceiptPerRecipient() {
        when(mapper.selectRegisteredRecipients(CLUB, ACTIVITY)).thenReturn(Arrays.asList(51L, 52L, 51L, 52L, 51L));
        when(mapper.insertCampaign(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, ClubEventCampaign.class).setId(61L);
            return 1;
        });
        when(mapper.insertDelivery(any())).thenAnswer(invocation -> {
            ClubEventDelivery row = invocation.getArgument(0);
            row.setId(100L + row.getRecipientMemberId());
            return 1;
        });
        when(mapper.insertOutbox(any(), any())).thenReturn(1);
        when(mapper.selectDispatchableByCampaign(61L, 500)).thenReturn(Collections.emptyList());
        when(mapper.refreshCampaignCounts(61L)).thenReturn(1);
        when(mapper.selectCampaignById(61L)).thenReturn(
                campaign(61L, "fanout", 2, 0, 0, ClubEventCampaign.PROCESSING));

        ClubEventCampaign result = service.send(ACTOR, request("fanout", "请提前十分钟到场"));

        assertEquals(Integer.valueOf(2), result.getTotalCount());
        ArgumentCaptor<ClubEventCampaign> created = ArgumentCaptor.forClass(ClubEventCampaign.class);
        verify(mapper).insertCampaign(created.capture());
        assertEquals(Integer.valueOf(2), created.getValue().getTotalCount());
        ArgumentCaptor<ClubEventDelivery> receipts = ArgumentCaptor.forClass(ClubEventDelivery.class);
        verify(mapper, times(2)).insertDelivery(receipts.capture());
        List<ClubEventDelivery> rows = receipts.getAllValues();
        assertEquals(new HashSet<>(Arrays.asList(51L, 52L)),
                new HashSet<>(Arrays.asList(rows.get(0).getRecipientMemberId(), rows.get(1).getRecipientMemberId())));
        verify(mapper, times(2)).insertOutbox(any(), any());
    }

    @Test
    void redeliveringAnAlreadySentReceiptIsANoOp() {
        ClubEventDelivery sent = delivery(121L, 51L, ClubEventDelivery.SENT);
        when(mapper.selectDeliveryByIdForUpdate(121L)).thenReturn(sent);

        assertFalse(tx.deliver(campaign(61L, "sent", 1, 1, 0, ClubEventCampaign.COMPLETED), 121L));

        verify(im, never()).sendSystem(any(), any(), any(), any());
        verify(mapper, never()).markDeliverySentCas(any(), any(), any(), any());
        verify(mapper, never()).completeOutbox(any());
    }

    @Test
    void lateFailureReceiptCannotOverwriteASentReceipt() {
        when(mapper.selectDeliveryByIdForUpdate(121L)).thenReturn(delivery(121L, 51L, ClubEventDelivery.SENT));

        assertFalse(tx.recordFailure(61L, 121L, "timeout", new Date(System.currentTimeMillis() + 60_000L)));

        verify(mapper, never()).markDeliveryFailedCas(anyLong(), any(), any(), any());
        verify(mapper, never()).rescheduleOutbox(any(), any());
    }

    @Test
    void manualRetryOnlyRedispatchesUnsentReceipts() {
        // 回执表里 51 已 SENT、52 FAILED:重试只取可派发行(SQL 以 status in PENDING/FAILED 过滤),
        // 51 不会被第二次触达;每个收件人仍只有一条回执。
        ClubEventCampaign partial = campaign(61L, "retry", 2, 1, 1, ClubEventCampaign.PARTIAL_FAILED);
        ClubEventDelivery failed = delivery(122L, 52L, ClubEventDelivery.FAILED);
        failed.setAttemptCount(1);
        when(mapper.selectCampaignById(61L)).thenReturn(partial, partial,
                campaign(61L, "retry", 2, 2, 0, ClubEventCampaign.COMPLETED));
        when(mapper.selectDispatchableByCampaign(61L, 500)).thenReturn(Collections.singletonList(failed));
        when(mapper.selectDeliveryByIdForUpdate(122L)).thenReturn(failed);
        when(im.sendSystem(any(), any(), any(), any())).thenReturn(502L);
        when(mapper.markDeliverySentCas(any(), any(), any(), any())).thenReturn(1);
        when(mapper.completeOutbox(122L)).thenReturn(1);
        when(mapper.refreshCampaignCounts(61L)).thenReturn(1);

        ClubEventCampaign result = service.retryFailed(ACTOR, 61L);

        assertEquals(ClubEventCampaign.COMPLETED, result.getStatus());
        verify(im, times(1)).sendSystem(any(), any(), any(), any());
        verify(im).sendSystem(org.mockito.ArgumentMatchers.eq(52L), any(), any(), any());
        verify(im, never()).sendSystem(org.mockito.ArgumentMatchers.eq(51L), any(), any(), any());
        verify(mapper, never()).insertDelivery(any());
    }

    private ClubEventCampaignRequest request(String requestId, String content) {
        ClubEventCampaignRequest request = new ClubEventCampaignRequest();
        request.setClubId(CLUB);
        request.setActivityId(ACTIVITY);
        request.setAudienceType("REGISTERED");
        request.setChannel("IN_APP");
        request.setTitle("集合提醒");
        request.setContent(content);
        request.setRequestId(requestId);
        return request;
    }

    private ClubEventDelivery delivery(Long id, Long memberId, String status) {
        ClubEventDelivery row = new ClubEventDelivery();
        row.setId(id);
        row.setCampaignId(61L);
        row.setRecipientMemberId(memberId);
        row.setChannel("IN_APP");
        row.setStatus(status);
        row.setAttemptCount(ClubEventDelivery.SENT.equals(status) ? 1 : 0);
        row.setVersion(1);
        return row;
    }

    private ClubEventCampaign campaign(Long id, String requestId, int total, int success, int failed, String status) {
        ClubEventCampaign row = new ClubEventCampaign();
        row.setId(id);
        row.setClubId(CLUB);
        row.setActivityId(ACTIVITY);
        row.setAudienceType("REGISTERED");
        row.setChannel("IN_APP");
        row.setTitle("集合提醒");
        row.setContent("请提前十分钟到场");
        row.setActorMemberId(ACTOR);
        row.setRequestId(requestId);
        row.setStatus(status);
        row.setTotalCount(total);
        row.setSuccessCount(success);
        row.setFailedCount(failed);
        return row;
    }
}
