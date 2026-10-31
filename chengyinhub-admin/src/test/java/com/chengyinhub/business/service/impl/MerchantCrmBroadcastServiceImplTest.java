package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.MerchantCrmBroadcast;
import com.chengyinhub.business.domain.MerchantCrmBroadcastCandidate;
import com.chengyinhub.business.domain.MerchantCrmBroadcastRecipient;
import com.chengyinhub.business.domain.MerchantCrmMarketingConsent;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.MerchantCrmBroadcastRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmCustomerQuery;
import com.chengyinhub.business.domain.vo.MerchantCrmBroadcastPreviewVO;
import com.chengyinhub.business.domain.vo.MerchantCrmBroadcastVO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.MerchantCrmAppMapper;
import com.chengyinhub.business.mapper.MerchantCrmBroadcastMapper;
import com.chengyinhub.business.mapper.MerchantCrmMarketingConsentMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.IPlayerRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 定向广播的服务端硬约束必须被测试锁死:
 * ① 只发给「已明确同意该商家站内触达」的客户 —— 未同意者绝不进投递通道;
 * ② 同一客户每天最多 1 条由 delivery guard 兜底(DAILY_CAP 即跳过);
 * ③ 同一商家每天最多 3 条;
 * ④ 内容机审不通过/审核不可用一律不发。
 *
 * <p>🔴 负控:删掉 {@code deliverOne} 里的 consent == null 分支(或把 finalize 状态改成恒 SUCCESS)
 * 后,{@link #deliversOnlyConsentedCustomersAndRecordsSkips} 必须红。</p>
 */
class MerchantCrmBroadcastServiceImplTest {

    private static final Long PRINCIPAL = 7L;
    private static final Long MERCHANT_ROW = 55L;
    private static final Long OWNER_MEMBER = 66L;

    private final IMerchantAccessService accessService = mock(IMerchantAccessService.class);
    private final MerchantCrmAppMapper appMapper = mock(MerchantCrmAppMapper.class);
    private final MerchantCrmBroadcastMapper broadcastMapper = mock(MerchantCrmBroadcastMapper.class);
    private final MerchantCrmMarketingConsentMapper consentMapper = mock(MerchantCrmMarketingConsentMapper.class);
    private final MerchantCrmDeliveryGuardService deliveryGuardService = mock(MerchantCrmDeliveryGuardService.class);
    private final MerchantCrmNotificationAdapter notificationAdapter = mock(MerchantCrmNotificationAdapter.class);
    private final MmsMerchantMapper merchantMapper = mock(MmsMerchantMapper.class);
    private final BusinessAuditEventMapper auditMapper = mock(BusinessAuditEventMapper.class);
    private final IWxContentSecurityService wxSec = mock(IWxContentSecurityService.class);
    private final IUmsMemberService umsMemberService = mock(IUmsMemberService.class);
    private final IPlayerRoleService playerRoleService = mock(IPlayerRoleService.class);

    private final MerchantCrmBroadcastServiceImpl service = new MerchantCrmBroadcastServiceImpl();

    @BeforeEach
    void wire() {
        ReflectionTestUtils.setField(service, "accessService", accessService);
        ReflectionTestUtils.setField(service, "appMapper", appMapper);
        ReflectionTestUtils.setField(service, "broadcastMapper", broadcastMapper);
        ReflectionTestUtils.setField(service, "consentMapper", consentMapper);
        ReflectionTestUtils.setField(service, "deliveryGuardService", deliveryGuardService);
        ReflectionTestUtils.setField(service, "notificationAdapter", notificationAdapter);
        ReflectionTestUtils.setField(service, "merchantMapper", merchantMapper);
        ReflectionTestUtils.setField(service, "auditMapper", auditMapper);
        ReflectionTestUtils.setField(service, "wxSec", wxSec);
        ReflectionTestUtils.setField(service, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(service, "playerRoleService", playerRoleService);

        when(accessService.require(PRINCIPAL, MerchantPermission.MARKETING_WRITE))
                .thenReturn(new MerchantAccessContext(PRINCIPAL, MERCHANT_ROW, OWNER_MEMBER, "MERCHANT_OWNER"));
        when(merchantMapper.selectMmsMerchantById(MERCHANT_ROW)).thenReturn(activeMerchant());
        when(auditMapper.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(1);
        when(wxSec.checkText(anyString(), any())).thenReturn(null);
        UmsMember actor = new UmsMember();
        actor.setId(PRINCIPAL);
        actor.setWxOpenId("openid-actor-7");
        when(umsMemberService.selectUmsMemberById(PRINCIPAL)).thenReturn(actor);
        when(playerRoleService.roleName(anyString())).thenAnswer(inv -> "SCOUT".equals(inv.getArgument(0)) ? "侦察员" : "");
    }

    @Test
    void previewCountsConsentAndDailyGuardPerServerTruth() {
        when(appMapper.selectOwnedBroadcastCandidates(eq(OWNER_MEMBER), any(MerchantCrmCustomerQuery.class), eq(200)))
                .thenReturn(Arrays.asList(candidate(101L, "林青", "第一队", "SCOUT"),
                        candidate(102L, "周野", null, null), candidate(103L, "陈小雨", "第一队", null)));
        when(appMapper.selectOwnedCustomerCount(eq(OWNER_MEMBER), any(MerchantCrmCustomerQuery.class))).thenReturn(37L);
        when(consentMapper.selectLatestOptIn(101L, OWNER_MEMBER, MERCHANT_ROW, "IN_APP")).thenReturn(consent(11L));
        when(consentMapper.selectLatestOptIn(103L, OWNER_MEMBER, MERCHANT_ROW, "IN_APP")).thenReturn(consent(13L));
        when(broadcastMapper.countGuardToday(MERCHANT_ROW, 101L, "IN_APP")).thenReturn(1);
        when(broadcastMapper.countTodayByMerchant(MERCHANT_ROW)).thenReturn(1);

        MerchantCrmBroadcastRequest request = request();
        MerchantCrmBroadcastPreviewVO preview = service.preview(PRINCIPAL, request);

        assertEquals(37L, preview.getFilterTotalCount());
        assertEquals(3, preview.getAudienceCount());
        assertEquals(2, preview.getConsentedCount());
        assertEquals(1, preview.getNoConsentCount());
        assertEquals(1, preview.getFrequencyLimitedCount());
        assertEquals(1, preview.getDeliverableCount());
        assertEquals(200, preview.getRecipientLimit());
        assertEquals(3, preview.getMerchantDailyLimit());
        assertEquals(1, preview.getMerchantDailyUsed());
        assertEquals(2, preview.getMerchantDailyRemaining());
    }

    /** 走查 v2 R07:主题工作台「定向广播」按主题收窄,主题 id 进名册查询(SQL 只取该主题已支付报名)。 */
    @Test
    void topicIdNarrowsAudienceQueryToThatTopic() {
        ArgumentCaptor<MerchantCrmCustomerQuery> captor =
                ArgumentCaptor.forClass(MerchantCrmCustomerQuery.class);
        when(appMapper.selectOwnedBroadcastCandidates(eq(OWNER_MEMBER), captor.capture(), eq(200)))
                .thenReturn(Collections.singletonList(candidate(101L, "林青", null, null)));
        MerchantCrmBroadcastRequest request = request();
        request.setTopicId(990045L);

        MerchantCrmBroadcastPreviewVO preview = service.preview(PRINCIPAL, request);

        assertEquals(1, preview.getAudienceCount());
        assertEquals(Long.valueOf(990045L), captor.getValue().getTopicId());
    }

    @Test
    void invalidTopicIdIsRejected() {
        MerchantCrmBroadcastRequest request = request();
        request.setTopicId(0L);
        assertThrows(ServiceException.class,
                () -> service.preview(PRINCIPAL, request));
    }

    @Test
    void teamScopeNarrowsServerSideByOwnedTeamName() {
        when(appMapper.selectOwnedBroadcastCandidates(eq(OWNER_MEMBER), any(MerchantCrmCustomerQuery.class), eq(200)))
                .thenReturn(Arrays.asList(candidate(101L, "林青", "第一队", "SCOUT"),
                        candidate(102L, "周野", "第二队", "SCOUT")));
        MerchantCrmBroadcastRequest request = request();
        request.setScope("TEAM");
        request.setGroups(Collections.singletonList("第一队"));

        MerchantCrmBroadcastPreviewVO preview = service.preview(PRINCIPAL, request);

        assertEquals(1, preview.getAudienceCount());
    }

    // M-09:可触达 0 人(都未同意)时不建广播、不占当日次数 —— 原先会冻结一份全跳过名单并吃掉一个日配额。
    @Test
    void rejectsZeroReachableBeforeClaimingDailySlot() {
        when(broadcastMapper.selectByRequestId(MERCHANT_ROW, "broadcast-test-001")).thenReturn(null);
        when(appMapper.selectOwnedBroadcastCandidates(eq(OWNER_MEMBER), any(MerchantCrmCustomerQuery.class), eq(200)))
                .thenReturn(Collections.singletonList(candidate(101L, "林青", null, null)));

        ServiceException error = assertThrows(ServiceException.class, () -> service.send(PRINCIPAL, request()));

        assertEquals(400, error.getCode());
        verify(broadcastMapper, never()).insertBroadcast(any(MerchantCrmBroadcast.class));
        verify(broadcastMapper, never()).insertRecipient(any(MerchantCrmBroadcastRecipient.class));
    }

    @Test
    void membersScopeKeepsOnlyPickedCustomersInsideCurrentFilter() {
        when(appMapper.selectOwnedBroadcastCandidates(eq(OWNER_MEMBER), any(MerchantCrmCustomerQuery.class), eq(200)))
                .thenReturn(Arrays.asList(candidate(101L, "林青", null, null), candidate(102L, "周野", null, null)));
        MerchantCrmBroadcastRequest request = request();
        request.setScope("MEMBERS");
        request.setMemberIds(Arrays.asList(102L, 999L)); // 999 不在当前筛选里,不得混进名单

        MerchantCrmBroadcastPreviewVO preview = service.preview(PRINCIPAL, request);

        assertEquals(1, preview.getAudienceCount());
    }

    @Test
    void membersScopeRequiresAtLeastOnePickedCustomer() {
        MerchantCrmBroadcastRequest request = request();
        request.setScope("MEMBERS");
        request.setMemberIds(Collections.emptyList());

        ServiceException error = assertThrows(ServiceException.class, () -> service.preview(PRINCIPAL, request));

        assertEquals(400, error.getCode());
        assertEquals("请先选择要发送的客户", error.getMessage());
    }

    @Test
    void rejectsWhenMerchantDailyLimitReachedWithoutInsertingAnything() {
        when(broadcastMapper.selectByRequestId(MERCHANT_ROW, "broadcast-test-001")).thenReturn(null);
        when(broadcastMapper.countTodayByMerchant(MERCHANT_ROW)).thenReturn(3);

        ServiceException error = assertThrows(ServiceException.class, () -> service.send(PRINCIPAL, request()));

        assertEquals(429, error.getCode());
        verify(broadcastMapper, never()).insertBroadcast(any(MerchantCrmBroadcast.class));
        verify(notificationAdapter, never()).sendBroadcast(anyLong(), any(MerchantCrmBroadcast.class));
    }

    @Test
    void rejectsBlockedContentBeforeAnyInsert() {
        when(broadcastMapper.selectByRequestId(MERCHANT_ROW, "broadcast-test-001")).thenReturn(null);
        when(wxSec.checkText(anyString(), any())).thenReturn("内容包含违规信息，请修改后重试");

        ServiceException error = assertThrows(ServiceException.class, () -> service.send(PRINCIPAL, request()));

        assertEquals(400, error.getCode());
        verify(broadcastMapper, never()).insertBroadcast(any(MerchantCrmBroadcast.class));
    }

    @Test
    void busyContentCheckIsRetryableNotViolation() {
        when(broadcastMapper.selectByRequestId(MERCHANT_ROW, "broadcast-test-001")).thenReturn(null);
        when(wxSec.checkText(anyString(), any())).thenReturn(IWxContentSecurityService.MSG_BUSY);

        ServiceException error = assertThrows(ServiceException.class, () -> service.send(PRINCIPAL, request()));

        assertEquals(503, error.getCode());
        verify(broadcastMapper, never()).insertBroadcast(any(MerchantCrmBroadcast.class));
    }

    @Test
    void deliversOnlyConsentedCustomersAndRecordsSkips() {
        when(broadcastMapper.selectByRequestId(MERCHANT_ROW, "broadcast-test-001")).thenReturn(null);
        when(broadcastMapper.countTodayByMerchant(MERCHANT_ROW)).thenReturn(0);
        when(appMapper.selectOwnedBroadcastCandidates(eq(OWNER_MEMBER), any(MerchantCrmCustomerQuery.class), eq(200)))
                .thenReturn(Arrays.asList(candidate(101L, "林青", null, null), candidate(102L, "周野", null, null)));
        when(consentMapper.selectLatestOptIn(101L, OWNER_MEMBER, MERCHANT_ROW, "IN_APP")).thenReturn(consent(11L));
        when(broadcastMapper.insertBroadcast(any(MerchantCrmBroadcast.class))).thenAnswer(invocation -> {
            ((MerchantCrmBroadcast) invocation.getArgument(0)).setId(9L);
            return 1;
        });
        when(broadcastMapper.insertRecipient(any(MerchantCrmBroadcastRecipient.class))).thenReturn(1);
        when(broadcastMapper.selectRecipients(MERCHANT_ROW, 9L, 200)).thenReturn(Arrays.asList(
                recipient(501L, 101L), recipient(502L, 102L)));
        when(deliveryGuardService.claim(MERCHANT_ROW, 9L, 501L, 101L, "IN_APP"))
                .thenReturn(MerchantCrmDeliveryGuardService.Claim.acquired(1L, "lease", 0L));
        when(deliveryGuardService.beginDispatch(any(MerchantCrmDeliveryGuardService.Claim.class)))
                .thenReturn(MerchantCrmDeliveryGuardService.Claim.acquired(1L, "lease", 1L));
        when(notificationAdapter.sendBroadcast(eq(101L), any(MerchantCrmBroadcast.class))).thenReturn(77L);
        when(broadcastMapper.markRecipientDelivered(MERCHANT_ROW, 9L, 501L, 77L)).thenReturn(1);
        when(broadcastMapper.markRecipientSkipped(MERCHANT_ROW, 9L, 502L,
                MerchantCrmBroadcastRecipient.SKIPPED_NO_CONSENT, "NO_CONSENT", "客户未同意或已退订")).thenReturn(1);
        when(broadcastMapper.finalizeBroadcast(any(MerchantCrmBroadcast.class))).thenReturn(1);

        MerchantCrmBroadcastVO result = service.send(PRINCIPAL, request());

        assertEquals("PARTIAL_FAILED", result.getStatus());
        assertEquals(2, result.getAudienceCount());
        assertEquals(1, result.getConsentedCount());
        assertEquals(1, result.getNoConsentCount());
        assertEquals(1, result.getDeliveredCount());
        assertEquals(0, result.getFailedCount());
        // 只有已同意的那位进投递通道;未同意者连 guard 都不该占额度
        verify(notificationAdapter, times(1)).sendBroadcast(eq(101L), any(MerchantCrmBroadcast.class));
        verify(notificationAdapter, never()).sendBroadcast(eq(102L), any(MerchantCrmBroadcast.class));
        verify(deliveryGuardService, never()).claim(MERCHANT_ROW, 9L, 502L, 102L, "IN_APP");
        verify(broadcastMapper).markRecipientSkipped(MERCHANT_ROW, 9L, 502L,
                MerchantCrmBroadcastRecipient.SKIPPED_NO_CONSENT, "NO_CONSENT", "客户未同意或已退订");

        ArgumentCaptor<MerchantCrmBroadcast> inserted = ArgumentCaptor.forClass(MerchantCrmBroadcast.class);
        verify(broadcastMapper).insertBroadcast(inserted.capture());
        assertEquals(1, inserted.getValue().getDailySlot());
        assertEquals(2, inserted.getValue().getAudienceCount());
        assertEquals("今晚 8 点临时闭店，别跑空", inserted.getValue().getContent());
    }

    @Test
    void dailyCappedCustomerIsSkippedWithReason() {
        when(broadcastMapper.selectByRequestId(MERCHANT_ROW, "broadcast-test-001")).thenReturn(null);
        when(appMapper.selectOwnedBroadcastCandidates(eq(OWNER_MEMBER), any(MerchantCrmCustomerQuery.class), eq(200)))
                .thenReturn(Collections.singletonList(candidate(101L, "林青", null, null)));
        when(consentMapper.selectLatestOptIn(101L, OWNER_MEMBER, MERCHANT_ROW, "IN_APP")).thenReturn(consent(11L));
        when(broadcastMapper.insertBroadcast(any(MerchantCrmBroadcast.class))).thenAnswer(invocation -> {
            ((MerchantCrmBroadcast) invocation.getArgument(0)).setId(9L);
            return 1;
        });
        when(broadcastMapper.insertRecipient(any(MerchantCrmBroadcastRecipient.class))).thenReturn(1);
        when(broadcastMapper.selectRecipients(MERCHANT_ROW, 9L, 200))
                .thenReturn(Collections.singletonList(recipient(501L, 101L)));
        when(deliveryGuardService.claim(MERCHANT_ROW, 9L, 501L, 101L, "IN_APP"))
                .thenReturn(MerchantCrmDeliveryGuardService.Claim.dailyCap());
        when(broadcastMapper.markRecipientSkipped(MERCHANT_ROW, 9L, 501L,
                MerchantCrmBroadcastRecipient.SKIPPED_FREQUENCY, "DAILY_CAP",
                "客户今日已收到或已占用该商家触达额度")).thenReturn(1);
        when(broadcastMapper.finalizeBroadcast(any(MerchantCrmBroadcast.class))).thenReturn(1);

        MerchantCrmBroadcastVO result = service.send(PRINCIPAL, request());

        assertEquals("FAILED", result.getStatus());
        assertEquals(1, result.getFrequencySkippedCount());
        assertEquals(0, result.getDeliveredCount());
        verify(notificationAdapter, never()).sendBroadcast(anyLong(), any(MerchantCrmBroadcast.class));
    }

    @Test
    void replayByRequestIdReturnsExistingBroadcastWithoutNewSend() {
        MerchantCrmBroadcast existing = new MerchantCrmBroadcast();
        existing.setId(9L);
        existing.setMerchantId(MERCHANT_ROW);
        existing.setActorMemberId(PRINCIPAL);
        existing.setContent("今晚 8 点临时闭店，别跑空");
        existing.setStatus("SUCCESS");
        existing.setDeliveredCount(3);
        when(broadcastMapper.selectByRequestId(MERCHANT_ROW, "broadcast-test-001")).thenReturn(existing);

        MerchantCrmBroadcastVO result = service.send(PRINCIPAL, request());

        assertEquals(9L, result.getId());
        assertEquals("SUCCESS", result.getStatus());
        assertEquals(3, result.getDeliveredCount());
        verify(broadcastMapper, never()).insertBroadcast(any(MerchantCrmBroadcast.class));
        verify(notificationAdapter, never()).sendBroadcast(anyLong(), any(MerchantCrmBroadcast.class));
    }

    private MerchantCrmBroadcastRequest request() {
        MerchantCrmBroadcastRequest request = new MerchantCrmBroadcastRequest();
        request.setContent("今晚 8 点临时闭店，别跑空");
        request.setRequestId("broadcast-test-001");
        return request;
    }

    private MerchantCrmBroadcastCandidate candidate(Long memberId, String name, String teamName, String roleCode) {
        MerchantCrmBroadcastCandidate candidate = new MerchantCrmBroadcastCandidate();
        candidate.setMemberId(memberId);
        candidate.setName(name);
        candidate.setTeamName(teamName);
        candidate.setRoleCode(roleCode);
        return candidate;
    }

    private MerchantCrmBroadcastRecipient recipient(Long id, Long memberId) {
        MerchantCrmBroadcastRecipient recipient = new MerchantCrmBroadcastRecipient();
        recipient.setId(id);
        recipient.setMemberId(memberId);
        return recipient;
    }

    private MerchantCrmMarketingConsent consent(Long id) {
        MerchantCrmMarketingConsent consent = new MerchantCrmMarketingConsent();
        consent.setId(id);
        return consent;
    }

    private MmsMerchant activeMerchant() {
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(MERCHANT_ROW);
        merchant.setMemberId(OWNER_MEMBER);
        merchant.setName("夜跑咖啡");
        merchant.setStatus(1L);
        merchant.setAccountStatus(1L);
        merchant.setDelFlag(0);
        return merchant;
    }
}
