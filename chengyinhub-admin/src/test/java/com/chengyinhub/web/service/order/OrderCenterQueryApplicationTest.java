package com.chengyinhub.web.service.order;

import com.chengyinhub.business.access.club.*;
import com.chengyinhub.business.access.merchant.*;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.web.service.registration.RegistrationQueryApplication;
import com.chengyinhub.web.controller.api.ApiOrderCenterController;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderCenterQueryApplicationTest {
    private final IWechatPayService payment = mock(IWechatPayService.class);
    private final CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
    private final RegistrationQueryApplication registrationQuery = mock(RegistrationQueryApplication.class);
    private final ClubMembershipMapper invoices = mock(ClubMembershipMapper.class);
    private final IClubAccessService clubs = mock(IClubAccessService.class);
    private final IMerchantAccessService merchants = mock(IMerchantAccessService.class);
    private final OmsOrderMapper orders = mock(OmsOrderMapper.class);
    private final CoopInviteMapper invites = mock(CoopInviteMapper.class);
    private final MmsMerchantMapper merchantRows = mock(MmsMerchantMapper.class);
    private final ClubMapper clubRows = mock(ClubMapper.class);
    private final CmsTopicMapper topics = mock(CmsTopicMapper.class);
    private final OrderCenterQueryApplication app = new OrderCenterQueryApplication();

    OrderCenterQueryApplicationTest() {
        ClubMembershipServiceImpl membership = new ClubMembershipServiceImpl();
        field(membership, "clubMembershipMapper", invoices); field(membership, "clubAccessService", clubs);
        MerchantCommerceService commerce = new MerchantCommerceService(false); field(commerce, "orderMapper", orders);
        CoopInviteServiceImpl coop = new CoopInviteServiceImpl();
        field(coop, "coopInviteMapper", invites); field(coop, "merchantAccessService", merchants);
        field(coop, "mmsMerchantMapper", merchantRows); field(coop, "clubMapper", clubRows);
        field(coop, "cmsTopicMapper", topics);
        field(app, "wechatPayService", payment); field(app, "registrationMapper", registrations);
        field(app, "registrationQueryApplication", registrationQuery); field(app, "membershipMapper", invoices);
        field(app, "membershipService", membership); field(app, "merchantAccessService", merchants);
        field(app, "commerceService", commerce); field(app, "coopInviteService", coop);
    }

    @Test void anonymousAndMalformedRequestsCannotReachMappingOrReaders() {
        for (Long principal : new Long[]{null, 0L, -1L}) assertCode(401, app.detail(principal, "CLM123"));
        for (String number : new String[]{null, "", "12345", "a".repeat(33), "CLM 12", "中文编号123", "CLM/12", "CLM\n12"}) {
            assertCode(400, app.detail(7L, number));
        }
        verifyNoInteractions(payment, registrations, registrationQuery, invoices, clubs, merchants, orders, invites);
    }

    @Test void exactMappedAttemptUsesCanonicalRegistrationAndOnlyProjectsAllowedFields() {
        String attempt = "WXP" + "a".repeat(29);
        when(payment.resolveBusinessOrderNo(attempt)).thenReturn("REG123");
        CmsRegistration row = registration("REG123"); row.setId(9007199254740993L);
        when(registrations.selectByRegistrationNo("REG123")).thenReturn(row);
        when(registrationQuery.getRegistrationDetail(7L, "9007199254740993")).thenReturn(AjaxResult.success(row));
        assertSnapshot(controllerDetail(attempt), "REGISTRATION", "9007199254740993", "REG123", "12.30", "已支付");
        verify(payment).resolveBusinessOrderNo(attempt); verifyNoMoreInteractions(payment);
        verify(registrationQuery).getRegistrationDetail(7L, "9007199254740993");
    }

    @Test void legacyAllowedCharactersRemainExactAndMissingRowsAreNotGuessed() {
        for (String number : new String[]{"ABC|*_", "REG-12"}) {
            when(payment.resolveBusinessOrderNo(number)).thenReturn(number);
            assertCode(404, app.detail(7L, number)); verify(registrations).selectByRegistrationNo(number);
        }
        String unmapped = "WXP" + "b".repeat(29);
        when(payment.resolveBusinessOrderNo(unmapped)).thenReturn(unmapped);
        assertCode(404, app.detail(7L, unmapped));
        verifyNoInteractions(invites, orders, invoices, clubs, merchants, registrationQuery);
    }

    @Test void foreignRegistrationReturnsForbiddenBeforeCanonicalEnrichment() {
        when(payment.resolveBusinessOrderNo("REG123")).thenReturn("REG123");
        CmsRegistration foreign = registration("REG123"); foreign.setMemberId(8L);
        when(registrations.selectByRegistrationNo("REG123")).thenReturn(foreign);
        AjaxResult result = app.detail(7L, "REG123");
        assertCode(403, result); assertFalse(result.containsKey("data"));
        verifyNoInteractions(registrationQuery);
    }

    @Test void pendingRegistrationPreservesItsLocalPaymentState() {
        when(payment.resolveBusinessOrderNo("REG123")).thenReturn("REG123");
        CmsRegistration row = registration("REG123"); row.setPaymentStatus(1);
        when(registrations.selectByRegistrationNo("REG123")).thenReturn(row);
        when(registrationQuery.getRegistrationDetail(7L, "11")).thenReturn(AjaxResult.success(row));
        assertSnapshot(app.detail(7L, "REG123"), "REGISTRATION", "11", "REG123", "12.30", "支付中");
    }

    @Test void foreignRegistrationStopsAtCanonicalOwnerCheckWithoutProjection() {
        when(payment.resolveBusinessOrderNo("REG123")).thenReturn("REG123");
        when(registrations.selectByRegistrationNo("REG123")).thenReturn(registration("REG123"));
        RegistrationQueryApplication canonical = new RegistrationQueryApplication();
        ICmsRegistrationService reader = mock(ICmsRegistrationService.class);
        CmsRegistration foreign = registration("REG123"); foreign.setMemberId(8L);
        when(reader.selectCmsRegistrationById(11L)).thenReturn(foreign);
        field(canonical, "registrationService", reader); field(app, "registrationQueryApplication", canonical);
        AjaxResult result = app.detail(7L, "REG123");
        assertCode(500, result); assertFalse(result.containsKey("data"));
        verify(reader).selectCmsRegistrationById(11L); verifyNoMoreInteractions(reader);
    }

    @Test void ownedRegistrationTraversesRealCanonicalDetailWithoutAnyWrite() {
        when(payment.resolveBusinessOrderNo("REG123")).thenReturn("REG123");
        CmsRegistration row = registration("REG123"); row.setOwnerType(2L); row.setOwnerId(81L);
        when(registrations.selectByRegistrationNo("REG123")).thenReturn(row);
        RegistrationQueryApplication canonical = new RegistrationQueryApplication();
        ICmsRegistrationService reader = mock(ICmsRegistrationService.class);
        ICmsActivityService activities = mock(ICmsActivityService.class);
        IRefundService refunds = mock(IRefundService.class);
        when(reader.selectCmsRegistrationById(11L)).thenReturn(row);
        field(canonical, "registrationService", reader); field(canonical, "activityService", activities);
        field(canonical, "refundService", refunds); field(app, "registrationQueryApplication", canonical);
        assertSnapshot(controllerDetail("REG123"), "REGISTRATION", "11", "REG123", "12.30", "已支付");
        verify(reader).selectCmsRegistrationById(11L); verifyNoMoreInteractions(reader);
        verify(activities).selectCmsActivityById(81L); verifyNoMoreInteractions(activities);
        verify(refunds).selectRefundList(any()); verifyNoMoreInteractions(refunds);
        verify(payment).resolveBusinessOrderNo("REG123"); verifyNoMoreInteractions(payment);
    }

    @Test void membershipUsesCanonicalAccessAndOwnerValidationWithFeatureFlagUnneeded() {
        ClubMembershipInvoice row = invoice();
        when(payment.resolveBusinessOrderNo("CLM123")).thenReturn("CLM123");
        when(invoices.selectInvoiceByOutTradeNo("CLM123")).thenReturn(row);
        when(invoices.selectInvoiceById(21L)).thenReturn(row);
        when(clubs.resolveReadOnly(7L, 4L, null)).thenReturn(new ClubAccessContext(7L, 4L, 9L, null, Collections.emptySet()));
        assertSnapshot(controllerDetail("CLM123"), "CLM", "21", "CLM123", "1.23", "支付中");
        row.setMemberId(8L); assertCode(403, app.detail(7L, "CLM123"));
        row.setMemberId(7L);
        ClubMembershipInvoice foreignClub = invoice(); foreignClub.setClubId(5L);
        when(invoices.selectInvoiceById(21L)).thenReturn(foreignClub);
        assertCode(403, app.detail(7L, "CLM123"));
        verify(payment, times(3)).resolveBusinessOrderNo("CLM123"); verifyNoMoreInteractions(payment);
    }

    @Test void formerMembershipDenialPrecedesInvoiceDetailRead() {
        when(payment.resolveBusinessOrderNo("CLM123")).thenReturn("CLM123");
        when(invoices.selectInvoiceByOutTradeNo("CLM123")).thenReturn(invoice());
        when(clubs.resolveReadOnly(7L, 4L, null)).thenThrow(new ServiceException("拒绝", 403));
        assertCode(403, app.detail(7L, "CLM123"));
        verify(invoices).selectInvoiceByOutTradeNo("CLM123"); verifyNoMoreInteractions(invoices);
    }

    @Test void delegatedMerchantUsesOwnerAndMerchantWhileForeignRowsAndPermissionDenialsFail() {
        when(payment.resolveBusinessOrderNo("TPLTM_123")).thenReturn("TPLTM_123");
        when(merchants.require(7L, MerchantPermission.PROFILE_WRITE))
                .thenReturn(new MerchantAccessContext(7L, 31L, 9L, MerchantRoleCode.MERCHANT_MANAGER));
        OmsOrder row = new OmsOrder(); row.setId(41L); row.setOrderSn("TPLTM_123"); row.setMemberId(9L);
        row.setMmsMerchantId(31L); row.setBizType("premium_template"); row.setPayAmount(new BigDecimal("8.88")); row.setStatus(5L);
        when(orders.selectOmsOrderByOrderSn("TPLTM_123")).thenReturn(row);
        assertSnapshot(controllerDetail("TPLTM_123"), "COMMERCE", "41", "TPLTM_123", "8.88", "已关闭");
        row.setMemberId(7L); assertCode(403, app.detail(7L, "TPLTM_123"));
        row.setMemberId(9L); row.setMmsMerchantId(32L); assertCode(403, app.detail(7L, "TPLTM_123"));
        clearInvocations(orders);
        when(merchants.require(7L, MerchantPermission.PROFILE_WRITE)).thenThrow(new ServiceException("拒绝", 403));
        assertCode(403, app.detail(7L, "TPLTM_123")); verifyNoInteractions(orders);
    }

    @Test void depositUsesExactOwedPartyAndPendingReadHasNoSdkOrMutation() {
        CoopInvite row = deposit();
        when(payment.resolveBusinessOrderNo("DEP123")).thenReturn("DEP123");
        when(invites.selectByDepositOutTradeNo("DEP123")).thenReturn(row);
        when(merchants.require(7L, MerchantPermission.COOP_MANAGE)).thenThrow(new ServiceException("普通用户", 403));
        assertSnapshot(controllerDetail("DEP123"), "DEP", "51", "DEP123", "50.00", "支付中");
        row.setToId(8L); assertCode(403, app.detail(7L, "DEP123"));
        row.setToId(7L); row.setDelFlag(2); assertCode(403, app.detail(7L, "DEP123"));
        verify(invites, times(3)).selectByDepositOutTradeNo("DEP123"); verifyNoMoreInteractions(invites);
        verify(payment, times(3)).resolveBusinessOrderNo("DEP123"); verifyNoMoreInteractions(payment);
    }

    @Test void depositOriginApplicationUsesInviterAndDisabledMerchantCannotDowngrade() {
        CoopInvite row = deposit(); row.setInviteType(1); row.setOriginApplyId(61L);
        row.setFromType("merchant"); row.setFromId(7L); row.setToId(8L);
        when(payment.resolveBusinessOrderNo("DEP123")).thenReturn("DEP123");
        when(invites.selectByDepositOutTradeNo("DEP123")).thenReturn(row);
        when(merchants.require(7L, MerchantPermission.COOP_MANAGE)).thenThrow(new ServiceException("停用", 403));
        when(merchantRows.selectMmsMerchantByMemberId(7L)).thenReturn(new MmsMerchant());
        assertCode(403, app.detail(7L, "DEP123"));
        verifyNoInteractions(topics); verify(invites).selectByDepositOutTradeNo("DEP123"); verifyNoMoreInteractions(invites);
    }

    @Test void depositActiveMerchantGuardAndDeletedTopicArePreserved() {
        CoopInvite row = deposit(); row.setToType("merchant"); row.setTopicId(71L);
        when(payment.resolveBusinessOrderNo("DEP123")).thenReturn("DEP123");
        when(invites.selectByDepositOutTradeNo("DEP123")).thenReturn(row);
        when(merchants.require(7L, MerchantPermission.COOP_MANAGE))
                .thenReturn(new MerchantAccessContext(7L, 31L, 7L, MerchantRoleCode.MERCHANT_OWNER));
        MmsMerchant merchant = new MmsMerchant(); merchant.setStatus(1L); merchant.setAccountStatus(1L); merchant.setDelFlag(0);
        when(merchantRows.selectMmsMerchantByMemberId(7L)).thenReturn(merchant);
        assertCode(403, app.detail(7L, "DEP123"));
        CmsTopic topic = new CmsTopic(); when(topics.selectCmsTopicById(71L)).thenReturn(topic);
        assertCode(200, app.detail(7L, "DEP123"));
        merchant.setAccountStatus(0L);
        assertCode(403, app.detail(7L, "DEP123"));
    }

    @Test void depositOriginApplicationAndClubRecipientUseCanonicalPayerVariants() {
        CoopInvite row = deposit(); row.setInviteType(1); row.setOriginApplyId(61L);
        row.setFromType("merchant"); row.setFromId(7L); row.setToId(8L);
        when(payment.resolveBusinessOrderNo("DEP123")).thenReturn("DEP123");
        when(invites.selectByDepositOutTradeNo("DEP123")).thenReturn(row);
        when(merchants.require(7L, MerchantPermission.COOP_MANAGE))
                .thenReturn(new MerchantAccessContext(7L, 31L, 7L, MerchantRoleCode.MERCHANT_OWNER));
        MmsMerchant merchant = new MmsMerchant(); merchant.setStatus(1L); merchant.setAccountStatus(1L); merchant.setDelFlag(0);
        when(merchantRows.selectMmsMerchantByMemberId(7L)).thenReturn(merchant);
        assertSnapshot(controllerDetail("DEP123"), "DEP", "51", "DEP123", "50.00", "支付中");
        row.setOriginApplyId(null); row.setToType("club"); row.setToId(4L);
        Club club = new Club(); club.setId(4L); club.setMemberId(7L);
        when(clubRows.selectClubById(4L)).thenReturn(club);
        assertSnapshot(controllerDetail("DEP123"), "DEP", "51", "DEP123", "50.00", "支付中");
        club.setMemberId(8L); assertCode(403, app.detail(7L, "DEP123"));
        verify(invites, times(3)).selectByDepositOutTradeNo("DEP123"); verifyNoMoreInteractions(invites);
        verify(payment, times(3)).resolveBusinessOrderNo("DEP123"); verifyNoMoreInteractions(payment);
    }

    @Test void missingAmountNeverDefaultsToZeroAndUnknownStatusNeverClaimsPaid() {
        when(payment.resolveBusinessOrderNo("REG123")).thenReturn("REG123");
        CmsRegistration row = registration("REG123"); row.setPayableAmount(null);
        when(registrations.selectByRegistrationNo("REG123")).thenReturn(row);
        when(registrationQuery.getRegistrationDetail(7L, "11")).thenReturn(AjaxResult.success(row));
        assertCode(409, app.detail(7L, "REG123"));
        row.setPayableAmount(new BigDecimal("1.001")); assertCode(409, app.detail(7L, "REG123"));
        row.setPayableAmount(BigDecimal.ZERO); row.setPaymentStatus(null);
        assertSnapshot(app.detail(7L, "REG123"), "REGISTRATION", "11", "REG123", "0.00", "状态未知");
    }

    @Test void applicationAndDepositBoundaryAreReadOnly() throws Exception {
        assertTrue(OrderCenterQueryApplication.class.getAnnotation(Transactional.class).readOnly());
        assertTrue(CoopInviteServiceImpl.class.getMethod("readOwnedDeposit", Long.class, String.class)
                .getAnnotation(Transactional.class).readOnly());
    }

    @Test void missingClassesNeverYieldSnapshotsOrPaymentMutations() {
        when(merchants.require(7L, MerchantPermission.PROFILE_WRITE))
                .thenReturn(new MerchantAccessContext(7L, 31L, 9L, MerchantRoleCode.MERCHANT_MANAGER));
        for (String number : new String[]{"REG123", "CLM123", "TPLTM_123", "DEP123"}) {
            when(payment.resolveBusinessOrderNo(number)).thenReturn(number);
            AjaxResult result = controllerDetail(number);
            assertFalse(result.containsKey("data"));
            assertNotEquals(200, result.get("code"));
            verify(payment).resolveBusinessOrderNo(number);
        }
        verifyNoMoreInteractions(payment);
        verify(registrations).selectByRegistrationNo("REG123"); verifyNoMoreInteractions(registrations);
        verify(invoices).selectInvoiceByOutTradeNo("CLM123"); verifyNoMoreInteractions(invoices);
        verify(orders).selectOmsOrderByOrderSn("TPLTM_123"); verifyNoMoreInteractions(orders);
        verify(invites).selectByDepositOutTradeNo("DEP123"); verifyNoMoreInteractions(invites);
    }

    private AjaxResult controllerDetail(String number) {
        ApiOrderCenterController controller = new ApiOrderCenterController() {
            @Override public Long getAppUserId() { return 7L; }
        };
        field(controller, "orderCenterQueryApplication", app);
        return controller.detail(number);
    }

    private static CmsRegistration registration(String number) {
        CmsRegistration row = new CmsRegistration(); row.setId(11L); row.setMemberId(7L); row.setRegistrationNo(number);
        row.setPaymentStatus(2); row.setPayableAmount(new BigDecimal("12.30")); return row;
    }
    private static ClubMembershipInvoice invoice() {
        ClubMembershipInvoice row = new ClubMembershipInvoice(); row.setId(21L); row.setClubId(4L); row.setMemberId(7L);
        row.setOutTradeNo("CLM123"); row.setAmountFen(123L); row.setStatus("PAYING"); row.setPlanNameSnapshot("月度会费"); return row;
    }
    private static CoopInvite deposit() {
        CoopInvite row = new CoopInvite(); row.setId(51L); row.setInviteType(0); row.setToType("talent");
        row.setToId(7L); row.setFromId(9L); row.setDelFlag(0); row.setDepositStatus(1);
        row.setDepositOutTradeNo("DEP123"); row.setDepositAmount(new BigDecimal("50.00")); return row;
    }
    private static void field(Object target, String name, Object value) { ReflectionTestUtils.setField(target, name, value); }
    private static void assertCode(int code, AjaxResult result) { assertEquals(code, result.get("code")); }
    private static void assertSnapshot(AjaxResult result, String kind, String id, String number, String amount, String status) {
        assertCode(200, result); Map<?, ?> data = (Map<?, ?>) result.get("data");
        assertEquals(6, data.size()); assertEquals(kind, data.get("kind")); assertEquals(id, data.get("id"));
        assertEquals(number, data.get("orderNo")); assertEquals(amount, data.get("amountText"));
        assertEquals(status, data.get("statusText")); assertNotNull(data.get("title"));
    }
}
