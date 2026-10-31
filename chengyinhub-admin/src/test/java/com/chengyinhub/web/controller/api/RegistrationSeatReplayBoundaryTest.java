package com.chengyinhub.web.controller.api;

import com.chengyinhub.web.service.verify.RegistrationTicketApplication;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.access.merchant.*;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real controller and C seat service. Mapper/merchant doubles do not prove SQL/JWT/transaction behavior. */
class RegistrationSeatReplayBoundaryTest {
    private static final String SECRET = "seat-boundary-fixture-signing-value";
    private ApiRegistrationController scanner;
    private ApiVerifyController reader;
    private RegistrationSeatService service;
    private ICmsRegistrationService orders;
    private RegistrationSeatMapper seats;
    private CmsRegistrationMapper registrations;
    private CmsTopicMapper topics;
    private CmsActivityMapper activities;
    private ICmsActivityService activityService;
    private IMerchantAccessService merchants;
    private IClubMemberService clubs;
    private IPointsAwardService points;
    private IGrowthService growth;
    private BusinessAuditEventMapper audits;
    private CmsRegistration order;
    private CmsActivity activity;
    private RegistrationSeat committed;
    private final List<RegistrationSeat> rows = new ArrayList<>();
    private final Date originalTime = new Date(1700000000000L);

    @BeforeEach void setup() {
        rows.clear(); scanner = spy(new ApiRegistrationController()); reader = spy(new ApiVerifyController());
        doReturn(81L).when(scanner).getAppUserId(); doReturn(51L).when(reader).getAppUserId();
        service = new RegistrationSeatService(); orders = mock(ICmsRegistrationService.class);
        seats = mock(RegistrationSeatMapper.class); registrations = mock(CmsRegistrationMapper.class);
        topics = mock(CmsTopicMapper.class); activities = mock(CmsActivityMapper.class);
        merchants = mock(IMerchantAccessService.class); audits = mock(BusinessAuditEventMapper.class);
        points = mock(IPointsAwardService.class); growth = mock(IGrowthService.class); clubs = mock(IClubMemberService.class);
        activityService = mock(ICmsActivityService.class);
        order = new CmsRegistration(); order.setId(31L); order.setMemberId(51L); order.setOwnerType(2L); order.setOwnerId(61L);
        order.setOrderNum(3L); order.setPurchaseKind(PurchaseKind.GUIDED_TICKET);
        order.setPaymentStatus(4); order.setRegistrationStatus(3); order.setVerificationStatus(1);
        activity = new CmsActivity(); activity.setId(61L); activity.setTopicId(41L); activity.setMemberId(81L);
        activity.setClubId(71L); activity.setDelFlag(0);
        CmsTopic topic = new CmsTopic(); topic.setId(41L); topic.setIsSettlement(0);
        committed = new RegistrationSeat(); committed.setId(101L); committed.setRegistrationId(31L); committed.setSeatNo(1);
        committed.setStatus(1); committed.setTopicId(41L); committed.setActivityId(61L); committed.setVerifierMemberId(81L); committed.setVerifiedAt(originalTime);
        rows.add(committed);
        when(orders.selectCmsRegistrationById(31L)).thenReturn(order);
        when(registrations.selectCmsRegistrationByIdForUpdate(31L)).thenReturn(order);
        when(activities.selectCmsActivityById(61L)).thenReturn(activity);
        when(activities.selectCmsActivityByIdForUpdate(61L)).thenReturn(activity);
        when(activityService.selectCmsActivityById(61L)).thenReturn(activity);
        when(topics.selectCmsTopicByIdForUpdate(41L)).thenReturn(topic);
        when(seats.lockRegistration(31L)).thenReturn(31L);
        when(seats.selectByRegistrationForUpdate(31L)).thenReturn(rows);
        when(seats.markVerified(any())).thenReturn(1); when(audits.insertBusinessAuditEvent(any())).thenReturn(1);
        for (Object[] field : new Object[][]{{"seats", seats}, {"registrations", registrations}, {"topics", topics}, {"activities", activities},
                {"registrationService", orders}, {"clubs", clubs}, {"audits", audits}, {"merchants", merchants}})
            ReflectionTestUtils.setField(service, (String) field[0], field[1]);
        for (Object[] field : new Object[][]{{"registrationSeatService", service}, {"registrationService", orders}, {"cmsActivityService", activityService},
                {"activityService", activityService}, {"merchantAccessService", merchants}, {"pointsAwardService", points}, {"growthService", growth}, {"dynSecret", SECRET}})
            ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(scanner), (String) field[0], field[1]);
        ReflectionTestUtils.setField(reader, "registrationTicketApplication", new RegistrationTicketApplication(
                orders, service, activityService, mock(IPerkLedgerService.class),
                mock(com.chengyinhub.business.service.impl.QrcodeServiceImpl.class), SECRET, mock(com.chengyinhub.web.service.verify.RegistrationDynCodeLedger.class)));
    }
    private String code(boolean expired, int seat) {
        return VerifyDynCodeUtil.issue(31L, "activity_seat_" + seat,
                System.currentTimeMillis() - (expired ? 600000 : 0), 300000, 7, SECRET);
    }
    private void assertOriginal(AjaxResult response) {
        assertEquals(200, response.get("code")); assertEquals("该席位已核销，请勿重复提供权益", response.get("msg"));
        RegistrationSeatService.Receipt receipt = (RegistrationSeatService.Receipt) response.get("data");
        assertEquals(101L, receipt.seatId()); assertEquals(31L, receipt.registrationId()); assertEquals(1, receipt.seatNo());
        assertEquals(41L, receipt.topicId()); assertEquals(61L, receipt.activityId()); assertEquals(81L, receipt.verifierMemberId());
        assertEquals(originalTime, receipt.verifiedAt()); assertTrue(receipt.replayed()); assertFalse(receipt.registrationFirstTime());
    }
    private void assertNoNewEffects() {
        verify(seats, never()).insertSeat(any()); verify(seats, never()).markVerified(any()); verifyNoInteractions(audits, points, growth);
        verify(orders, never()).verifyAndSettle(any(), any(), any(), any());
        verify(orders, never()).verifyAndSettle(any(), any(), any(), any(), any(), any());
        verify(orders, never()).verifyAndSettleForGroupActivity(any(), any(), any());
        verify(orders, never()).verifyAndSettleForOwnStation(any(), any(), any(), any());
        verify(orders, never()).verifyAndSettleForOwnStation(any(), any(), any(), any(), any(), any());
        assertEquals(1, rows.size()); assertEquals(1, committed.getStatus()); assertEquals(originalTime, committed.getVerifiedAt());
    }
    @Test void refundedCurrentPaymentCannotEraseAnAuthorizedCommittedActivitySeat() {
        assertOriginal(scanner.scanDynamicCode(code(false, 1))); assertNoNewEffects();
    }
    @Test void movedClubWindowCannotEraseTheAuthorizedOriginalSeat() {
        order.setPaymentStatus(2); order.setRegistrationStatus(2);
        activity.setStartDate(new Date(System.currentTimeMillis() + 2 * 60 * 60 * 1000L));
        assertOriginal(scanner.scanDynamicCode(code(false, 1))); assertNoNewEffects();
    }
    @Test void expiredTransportStillReadsTheOriginalFactThroughDynamicGenericAndGroupEntrances() {
        activity.setCancelTime(new Date()); activity.setIsSettlement(1);
        String expired = code(true, 1); assertFalse(VerifyDynCodeUtil.verify(expired, System.currentTimeMillis(), SECRET).valid);
        assertOriginal(scanner.scanDynamicCode(expired)); assertOriginal(scanner.scanQrCode("activity", expired, null));
        assertOriginal(scanner.scanGroupMemberTicket(expired, 61L)); assertNoNewEffects();
    }
    @Test void absentTerminalFactKeepsTheExpiredRefusalAndNeverAttemptsANewUse() {
        committed.setStatus(0); committed.setVerifiedAt(null);
        AjaxResult result = scanner.scanDynamicCode(code(true, 1));
        assertNotEquals(200, result.get("code")); assertEquals("已过期", result.get("msg")); assertFalse(result.containsKey("data"));
        verify(seats, never()).markVerified(any()); verifyNoInteractions(audits, points, growth);
        verify(orders, never()).verifyAndSettle(any(), any(), any(), any(), any(), any());
    }
    @Test void paidWindowAndRefundGatesRemainForANewUnverifiedSeat() {
        RegistrationSeat second = new RegistrationSeat(); second.setId(102L); second.setRegistrationId(31L); second.setSeatNo(2); second.setStatus(0); rows.add(second);
        assertEquals("订单未完成支付或已退款,无法核销", scanner.scanDynamicCode(code(false, 2)).get("msg"));
        order.setPaymentStatus(2); order.setRegistrationStatus(2); activity.setStartDate(new Date(System.currentTimeMillis() + 2 * 60 * 60 * 1000L));
        assertEquals("俱乐部场次开始前60分钟内才可核销", scanner.scanDynamicCode(code(false, 2)).get("msg"));
        assertEquals(0, second.getStatus()); verify(seats, never()).markVerified(any()); verifyNoInteractions(audits, points, growth);
    }
    @Test void newSeatStillCannotUseCancelledOrSettledActivity() {
        order.setPaymentStatus(2); order.setRegistrationStatus(2);
        RegistrationSeat second = new RegistrationSeat(); second.setId(102L); second.setRegistrationId(31L); second.setSeatNo(2); second.setStatus(0); rows.add(second);
        activity.setCancelTime(new Date());
        assertEquals(403, scanner.scanDynamicCode(code(false, 2)).get("code"));
        activity.setCancelTime(null); activity.setIsSettlement(1);
        assertEquals(409, scanner.scanDynamicCode(code(false, 2)).get("code"));
        assertEquals(0, second.getStatus()); verify(seats, never()).markVerified(any()); verifyNoInteractions(audits, points, growth);
        verify(orders, never()).verifyAndSettle(any(), any(), any(), any(), any(), any());
    }
    @Test void newLegalSecondSeatStillUsesTheOriginalPathAndDoesNotAccrueTheOrderAgain() {
        order.setPaymentStatus(2); order.setRegistrationStatus(2);
        RegistrationSeat second = new RegistrationSeat(); second.setId(102L); second.setRegistrationId(31L); second.setSeatNo(2); second.setStatus(0); rows.add(second);
        AjaxResult result = scanner.scanDynamicCode(code(false, 2));
        assertEquals(200, result.get("code"));
        var receipt = (RegistrationSeatService.Receipt) result.get("data");
        assertEquals(102L, receipt.seatId()); assertFalse(receipt.replayed()); assertFalse(receipt.registrationFirstTime());
        verify(seats).markVerified(second); verify(audits).insertBusinessAuditEvent(any()); verifyNoInteractions(points);
        verify(orders, never()).verifyAndSettle(any(), any(), any(), any(), any(), any());
        verify(growth).deliverStoreVerification(31L);
    }
    @Test void expiredTopicSeatKeepsOriginalGroupBindingAndChecksTheCurrentClubOwner() {
        order.setOwnerType(1L); order.setOwnerId(41L);
        when(seats.lockClubOwner(71L)).thenReturn(81L); when(clubs.isTopicOwner(41L, 71L, 81L)).thenReturn(true);
        CoopGroupMemberVerify binding = new CoopGroupMemberVerify(); binding.setRegistrationId(31L); binding.setTopicId(41L);
        binding.setActivityId(61L); binding.setVerifierMemberId(81L); binding.setVerifyTime(originalTime);
        when(seats.selectGroupForUpdate(31L)).thenReturn(binding);
        String expired = VerifyDynCodeUtil.issue(31L, "topic_seat_1", 1000, 300000, 7, SECRET);
        assertOriginal(scanner.scanGroupMemberTicket(expired, 61L)); assertOriginal(scanner.scanQrCode("topic", expired, 61L));
        when(seats.lockClubOwner(71L)).thenReturn(82L);
        assertEquals(403, scanner.scanGroupMemberTicket(expired, 61L).get("code")); assertNoNewEffects();
    }
    @Test void explicitTopicSeatCannotFallThroughGenericEntryIntoWholeOrderVerificationWithoutAGroup() {
        order.setOwnerType(1L); order.setOwnerId(41L); order.setPaymentStatus(2); order.setRegistrationStatus(2);
        String code = VerifyDynCodeUtil.issue(31L, "topic_seat_1", System.currentTimeMillis(), 300000, 7, SECRET);
        AjaxResult refusal = scanner.scanQrCode("topic", code, null);
        assertEquals(403, refusal.get("code")); assertEquals("请选择带队场次后再核销", refusal.get("msg")); assertNoNewEffects();
    }
    @Test void buyerReceiptCannotBeReboundToAnotherRequestedActivity() {
        ServiceException refusal = assertThrowsExactly(ServiceException.class, () -> reader.seatReceipt(31L, 1, 62L));
        assertEquals(Integer.valueOf(409), refusal.getCode()); assertNoNewEffects();
    }
    @Test void buyerReadPermissionCannotBecomeVerifierAcknowledgementPermission() {
        assertOriginal(reader.seatReceipt(31L, 1, null)); doReturn(51L).when(scanner).getAppUserId();
        when(merchants.require(51L, MerchantPermission.VERIFY)).thenThrow(new ServiceException("无核销权限", 403));
        assertEquals(403, scanner.scanDynamicCode(code(false, 1)).get("code")); assertNoNewEffects();
    }
    @Test void currentDelegateMayReadOriginalOwnerFactButRevokedOrForeignMerchantCannotAck() {
        doReturn(82L).when(scanner).getAppUserId();
        when(merchants.require(82L, MerchantPermission.VERIFY)).thenReturn(new MerchantAccessContext(82L, 91L, 81L, "MERCHANT_CHECKIN"));
        assertOriginal(scanner.scanDynamicCode(code(true, 1)));
        when(merchants.require(82L, MerchantPermission.VERIFY)).thenReturn(new MerchantAccessContext(82L, 92L, 999L, "MERCHANT_CHECKIN"));
        assertEquals(403, scanner.scanDynamicCode(code(true, 1)).get("code"));
        when(merchants.require(82L, MerchantPermission.VERIFY)).thenThrow(new ServiceException("无核销权限", 403));
        assertEquals(403, scanner.scanDynamicCode(code(true, 1)).get("code")); assertNoNewEffects();
    }
    @Test void transferredActivityOwnershipUsesCurrentAuthorizationButPreservesOriginalVerifierAttribution() {
        activity.setMemberId(83L);
        when(merchants.require(81L, MerchantPermission.VERIFY)).thenThrow(new ServiceException("无核销权限", 403));
        assertEquals(403, scanner.scanDynamicCode(code(true, 1)).get("code"));
        doReturn(83L).when(scanner).getAppUserId();
        assertOriginal(scanner.scanDynamicCode(code(true, 1))); assertNoNewEffects();
    }
    @Test void wrongGroupAndClaimedOwnerTypeCannotAckAnotherIdentity() {
        assertNotEquals(200, scanner.scanGroupMemberTicket(code(true, 1), 62L).get("code"));
        assertNotEquals(200, scanner.scanGroupMemberTicket(code(true, 1), null).get("code"));
        var refusal = scanner.scanQrCode("topic", code(true, 1), 61L);
        assertInstanceOf(Integer.class, refusal.get("code"));
        assertNotEquals(200, refusal.get("code")); assertNoNewEffects();
    }
    @Test void tamperedExpiredCodeAndOtherQrFamiliesNeverReadASeatFact() {
        String expired = code(true, 1);
        assertNotEquals(200, scanner.scanDynamicCode(expired.replace("activity_seat_1", "activity_seat_2")).get("code"));
        String originalNonSeat = VerifyDynCodeUtil.issue(31L, "activity", 1000, 300000, 7, SECRET);
        assertEquals("已过期", scanner.scanDynamicCode(originalNonSeat).get("msg"));
        verify(seats, never()).selectByRegistrationForUpdate(any()); assertNoNewEffects();
    }
    @Test void ordinaryPaidReplayKeepsTheExistingQueuedGrowthBehaviorWithoutAwardingAgain() {
        order.setPaymentStatus(2); order.setRegistrationStatus(2);
        assertOriginal(scanner.scanDynamicCode(code(false, 1)));
        verify(growth).deliverStoreVerification(31L); verifyNoInteractions(points, audits); verify(seats, never()).markVerified(any());
    }
    @Test void incompleteTerminalFactCannotBeAcknowledgedByVerifierOrBuyerRead() {
        committed.setVerifierMemberId(null);
        assertEquals(409, scanner.scanDynamicCode(code(true, 1)).get("code"));
        ServiceException refusal = assertThrowsExactly(ServiceException.class, () -> reader.seatReceipt(31L, 1, null));
        assertEquals(Integer.valueOf(409), refusal.getCode()); assertNoNewEffects();
    }
    @Test void expiredSignedSeatStillRequiresTheRealAuthenticatedActorBeforeAnyFactRead() {
        doReturn(0L).when(scanner).getAppUserId();
        assertEquals("请先登录", scanner.scanDynamicCode(code(true, 1)).get("msg"));
        verify(seats, never()).selectByRegistrationForUpdate(any()); assertNoNewEffects();
    }
    @Test void databaseUncertaintyCannotBeConvertedToNotExecutedOrLeakSql() {
        when(seats.selectByRegistrationForUpdate(31L)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("SELECT private_seat_secret"));
        AjaxResult result = scanner.scanDynamicCode(code(true, 1));
        assertEquals(520, result.get("code")); assertFalse(result.toString().contains("private_seat_secret"));
        assertFalse(result.containsKey("intentNotExecuted")); verifyNoInteractions(points, growth, audits);
    }
}
