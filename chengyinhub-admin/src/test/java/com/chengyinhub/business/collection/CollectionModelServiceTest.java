package com.chengyinhub.business.collection;

import com.chengyinhub.business.collection.mapper.CollectionModelMapper;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.vo.PlatformSettlementAccountVO;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.moneyswitch.MoneyArea;
import com.chengyinhub.business.moneyswitch.MoneyGateTestSupport;
import com.chengyinhub.business.moneyswitch.MoneySwitchService;
import com.chengyinhub.business.moneyswitch.mapper.MoneySwitchMapper;
import com.chengyinhub.business.payout.EcommerceSellerResolver;
import com.chengyinhub.business.payout.PayoutSwitches;
import com.chengyinhub.business.payout.SellerOfRecordPolicy.Facts;
import com.chengyinhub.business.payout.SellerOfRecordPolicy.ProductKind;
import com.chengyinhub.business.payout.SellerOfRecordPolicy.SellerType;
import com.chengyinhub.business.service.ISettlementRuleService;
import com.chengyinhub.business.service.PaymentPreorderException;
import com.chengyinhub.common.exception.BusinessRefusal;
import com.chengyinhub.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** S2 collection model service: gate, refusals, the platform-path guard and club dues. */
class CollectionModelServiceTest {
    static final String SP = "1900000001";
    static final String SUB = "1900000999";
    static final PayoutSwitches RAILS_ON = new PayoutSwitches(true, true, true, SP, "wx_mini", "", 30, false, "none");

    EcommerceSellerResolver sellers;
    CmsActivityMapper activities;
    ISettlementRuleService rules;
    CollectionModelMapper mapper;

    @BeforeEach
    void setUp() {
        sellers = mock(EcommerceSellerResolver.class);
        activities = mock(CmsActivityMapper.class);
        rules = mock(ISettlementRuleService.class);
        mapper = mock(CollectionModelMapper.class);
    }

    /** {@code on}: the S2 model flag on and the Q5 tickets area effectively open (ceiling on, row on). */
    CollectionModelService service(boolean on, PayoutSwitches switches) {
        return service(on, on, switches);
    }

    CollectionModelService service(boolean modelEnabled, boolean ticketsOpen, PayoutSwitches switches) {
        MoneySwitchService money = ticketsOpen ? MoneyGateTestSupport.moneySwitch(true, MoneyArea.TICKETS)
                : new MoneySwitchService(mock(MoneySwitchMapper.class), false);
        CollectionModelService model = new CollectionModelService(money, switches, sellers,
                activities, rules, mapper, "SPLIT", "SPLIT", "SPLIT");
        model.setModelEnabled(modelEnabled);
        return model;
    }

    static CmsRegistration registration(long ownerType, long ownerId) {
        CmsRegistration r = new CmsRegistration();
        r.setId(11L);
        r.setRegistrationNo("R-S2-1");
        r.setOwnerType(ownerType);
        r.setOwnerId(ownerId);
        r.setPayableAmount(new BigDecimal("69.00"));
        r.setFeePlatformRate(new BigDecimal("0.03"));
        return r;
    }

    void merchant(String state, String sub) {
        when(sellers.factsFor(eq(1L), anyLong())).thenReturn(
                new Facts(ProductKind.TOPIC_TICKET, false, SellerType.MERCHANT, 7L, state, sub));
    }

    // ---- switch closed: exactly today's behaviour ----

    @Test
    void modelFlagOffIsANoOpThatReadsNothing() {
        CollectionModelService model = service(false, RAILS_ON);
        assertFalse(model.active());
        CollectionDecision d = model.decide(1L, 9L);
        assertEquals(CollectionPolicy.PLATFORM, d.policy());
        assertEquals(CollectionDecision.MODEL_OFF, d.reason());
        assertDoesNotThrow(() -> model.requireCollectible(1L, 9L, new BigDecimal("69.00")));
        assertDoesNotThrow(() -> model.requirePlatformMayCollect(registration(1L, 9L), false));
        assertDoesNotThrow(() -> model.requirePlatformMayCollect(registration(2L, 9L), true));
        verifyNoInteractions(sellers, activities, rules, mapper);
    }

    @Test
    void theModelFlagIsIndependentOfTheAppMoneyCeiling() {
        // W7 MERCHANT-3: ceiling on (app money areas may open) does not turn S2 on, and S2 on needs no ceiling.
        CollectionModelService ceilingOnly = service(false, true, RAILS_ON);
        assertFalse(ceilingOnly.active());
        assertEquals(CollectionDecision.MODEL_OFF, ceilingOnly.decide(1L, 9L).reason());
        merchant("CHECKING", null);
        CollectionModelService modelOnly = service(true, false, RAILS_ON);
        assertTrue(modelOnly.active());
        assertThrows(BusinessRefusal.class, () -> modelOnly.requireCollectible(1L, 9L, new BigDecimal("69.00")));
    }

    // ---- W7 MERCHANT-1: club dues are never collected by the platform ----

    @Test
    void duesAreRefusedWhileTheModelIsOffAndNoSellerIsRead() {
        CollectionModelService model = service(false, true, RAILS_ON);
        BusinessRefusal refusal = assertThrows(BusinessRefusal.class, () -> model.requireClubDuesCollectible(3L));
        assertEquals(ErrorCode.CLUB_DUES_COLLECTION_UNAVAILABLE, refusal.getErrorCode());
        assertEquals(422, refusal.getHttpStatus());
        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) refusal.getData();
        assertEquals(CollectionDecision.MONEY_SWITCH_CLOSED, data.get("reason"));
        assertEquals("WAIT_FOR_PLATFORM", data.get("nextStep"));
        assertTrue(model.decideClubDues(3L).refused());
        verifyNoInteractions(sellers, activities, rules);
    }

    @Test
    void duesAreRefusedWhileTheTicketsAreaIsClosedEvenWithABoundClub() {
        when(sellers.clubDuesFacts(3L)).thenReturn(new Facts(ProductKind.CLUB_DUES, false, SellerType.CLUB, 3L, "FINISH", SUB));
        CollectionModelService model = service(true, false, RAILS_ON);
        BusinessRefusal refusal = assertThrows(BusinessRefusal.class, () -> model.requireDuesMoneyOpen(3L));
        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) refusal.getData();
        assertEquals(CollectionDecision.MONEY_SWITCH_CLOSED, data.get("reason"));
        assertThrows(BusinessRefusal.class, () -> model.requireClubDuesCollectible(3L));
        verify(sellers, never()).clubDuesFacts(anyLong());
    }

    @Test
    void platformGatewayRefusesClmOrdersInEveryMode() {
        for (boolean on : new boolean[] {false, true}) {
            CollectionModelService model = service(on, RAILS_ON);
            assertThrows(IllegalStateException.class, () -> model.requirePlatformMayCollectDues("CLM20261116001"));
            assertDoesNotThrow(() -> model.requirePlatformMayCollectDues("R-TICKET-1"));
            assertDoesNotThrow(() -> model.requirePlatformMayCollectDues(null));
        }
    }

    @Test
    void duesStatusNeverReportsThePlatformPathAsPayable() {
        for (boolean[] mode : new boolean[][] {{false, false}, {false, true}, {true, false}}) {
            Map<String, Object> status = service(mode[0], mode[1], RAILS_ON).clubDuesStatus(3L);
            assertEquals("WAIT_FOR_PLATFORM", status.get("state"));
            assertEquals(false, status.get("collectible"));
            assertEquals("WAIT_FOR_PLATFORM", status.get("nextStep"));
        }
        when(sellers.clubDuesFacts(3L)).thenReturn(new Facts(ProductKind.CLUB_DUES, false, SellerType.CLUB, 3L, "FINISH", SUB));
        Map<String, Object> open = service(true, RAILS_ON).clubDuesStatus(3L);
        assertEquals("SPLIT", open.get("state"));
        assertEquals(true, open.get("collectible"));
    }

    @Test
    void shippedConfigurationKeepsTheModelOff() throws Exception {
        String yml = new String(getClass().getResourceAsStream("/application.yml").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(yml.contains("server-ceiling: ${CHENGYIN_MONEY_SWITCH_SERVER_CEILING:false}"));
        assertTrue(yml.contains("enabled: ${CHENGYIN_COLLECTION_MODEL_ENABLED:false}"));
        assertTrue(yml.contains("merchant: ${CHENGYIN_COLLECTION_POLICY_MERCHANT:SPLIT}"));
        assertFalse(yml.matches("(?s).*CHENGYIN_COLLECTION_POLICY_[A-Z]+:PLATFORM.*"));
    }

    // ---- switch open ----

    @Test
    void unboundSellerIsRefusedBeforeAnyOrderWithAPathToFixIt() {
        merchant("CHECKING", null);
        CollectionModelService model = service(true, RAILS_ON);
        BusinessRefusal refusal = assertThrows(BusinessRefusal.class,
                () -> model.requireCollectible(1L, 9L, new BigDecimal("69.00")));
        assertEquals(ErrorCode.SELLER_NOT_BOUND, refusal.getErrorCode());
        assertEquals(422, refusal.getHttpStatus());
        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) refusal.getData();
        assertEquals("SELLER_NOT_BOUND", data.get("reason"));
        assertEquals("CONTACT_SELLER", data.get("nextStep"));
        verify(mapper).recordRefusal("MERCHANT", 7L, "SELLER_NOT_BOUND");
    }

    @Test
    void freeOrdersAreNeverRefused() {
        merchant(null, null);
        CollectionModelService model = service(true, RAILS_ON);
        assertDoesNotThrow(() -> model.requireCollectible(1L, 9L, BigDecimal.ZERO));
        assertDoesNotThrow(() -> model.requireCollectible(1L, 9L, null));
        verifyNoInteractions(mapper);
    }

    @Test
    void boundSellerWithRailsOffIsRefusedAsWaitForPlatform() {
        merchant("FINISH", SUB);
        CollectionModelService model = service(true, PayoutSwitches.allOff());
        BusinessRefusal refusal = assertThrows(BusinessRefusal.class,
                () -> model.requireCollectible(1L, 9L, new BigDecimal("1.00")));
        assertEquals(ErrorCode.SELLER_COLLECTION_UNAVAILABLE, refusal.getErrorCode());
    }

    @Test
    void boundSellerWithRailsOnIsCollectibleButNeverOnThePlatformAccount() {
        merchant("FINISH", SUB);
        CollectionModelService model = service(true, RAILS_ON);
        assertDoesNotThrow(() -> model.requireCollectible(1L, 9L, new BigDecimal("69.00")));
        assertTrue(model.decide(1L, 9L).split());
        // JSAPI fallback: the partner adapter must have taken it; the platform account never collects it.
        PaymentPreorderException jsapi = assertThrows(PaymentPreorderException.class,
                () -> model.requirePlatformMayCollect(registration(1L, 9L), false));
        assertFalse(jsapi.isResultUncertain());
        assertEquals(ErrorCode.SELLER_COLLECTION_UNAVAILABLE.defaultMessage(), jsapi.getMessage());
        // APP channel has no partner order: refused with an honest message.
        PaymentPreorderException app = assertThrows(PaymentPreorderException.class,
                () -> model.requirePlatformMayCollect(registration(1L, 9L), true));
        assertEquals(ErrorCode.APP_CHANNEL_UNAVAILABLE.defaultMessage(), app.getMessage());
        verify(mapper).recordRefusal("MERCHANT", 7L, CollectionDecision.APP_CHANNEL_SPLIT_NOT_BUILT);
    }

    @Test
    void officialActivityRunByThePlatformAccountIsPlatformCollected() {
        when(sellers.factsFor(eq(2L), eq(40L))).thenReturn(
                new Facts(ProductKind.ACTIVITY_TICKET, false, SellerType.MEMBER, 500L, null, null));
        CmsActivity activity = new CmsActivity();
        activity.setId(40L);
        activity.setMemberId(500L);
        when(activities.selectCmsActivityById(40L)).thenReturn(activity);
        PlatformSettlementAccountVO account = new PlatformSettlementAccountVO();
        account.setMemberId(500L);
        when(rules.getPlatformSettlementAccount()).thenReturn(account);
        CollectionModelService model = service(true, RAILS_ON);
        assertEquals(CollectionDecision.OFFICIAL, model.decide(2L, 40L).reason());
        assertDoesNotThrow(() -> model.requirePlatformMayCollect(registration(2L, 40L), true));

        account.setMemberId(501L); // someone else's independent activity: a third party (MEMBER) with no sub-merchant
        assertThrows(BusinessRefusal.class, () -> model.requireCollectible(2L, 40L, new BigDecimal("10.00")));
        assertThrows(PaymentPreorderException.class, () -> model.requirePlatformMayCollect(registration(2L, 40L), false));
    }

    @Test
    void unreadableOfficialCheckIsNotOfficial() {
        when(sellers.factsFor(eq(2L), eq(40L))).thenReturn(
                new Facts(ProductKind.ACTIVITY_TICKET, false, SellerType.MEMBER, 500L, null, null));
        when(activities.selectCmsActivityById(40L)).thenThrow(new IllegalStateException("db down"));
        assertTrue(service(true, RAILS_ON).decide(2L, 40L).refused());
    }

    @Test
    void clubDuesWithoutABoundClubSubMerchantAreRefusedWhileTheModelIsActive() {
        // K1 (club-dues) built the split path: dues are refused only when the club cannot be paid by split settlement.
        when(sellers.clubDuesFacts(3L)).thenReturn(new Facts(ProductKind.CLUB_DUES, false, SellerType.CLUB, 3L, null, null));
        CollectionModelService model = service(true, RAILS_ON);
        BusinessRefusal refusal = assertThrows(BusinessRefusal.class, () -> model.requireClubDuesCollectible(3L));
        assertEquals(ErrorCode.CLUB_DUES_COLLECTION_UNAVAILABLE, refusal.getErrorCode());
        verify(mapper).recordRefusal("DUES", 3L, CollectionDecision.SELLER_NOT_BOUND);
        when(sellers.clubDuesFacts(3L)).thenReturn(new Facts(ProductKind.CLUB_DUES, false, SellerType.CLUB, 3L, "FINISH", SUB));
        assertTrue(model.requireClubDuesCollectible(3L).split());
    }

    @Test
    void refusalCounterFailureStillRefusesTheBuyer() {
        merchant(null, null);
        when(mapper.recordRefusal(anyString(), anyLong(), anyString())).thenThrow(new IllegalStateException("no table"));
        assertThrows(BusinessRefusal.class, () -> service(true, RAILS_ON).requireCollectible(1L, 9L, BigDecimal.ONE));
    }

    @Test
    void splitOrderRecordsTheTakeRateFrozenByTheExistingPricingPath() {
        CollectionModelService model = service(true, RAILS_ON);
        CollectionDecision split = new CollectionDecision(CollectionPolicy.SPLIT, SellerType.CLUB, 3L, SUB, CollectionDecision.SUB_MERCHANT);
        model.recordSplitOrder(registration(2L, 40L), split, 6900L);
        verify(mapper).insertOrder("R-S2-1", 11L, 2L, 40L, "CLUB", 3L, SUB, 6900L, new BigDecimal("0.03"));
        model.recordSplitOrder(registration(2L, 40L), CollectionDecision.platform(CollectionDecision.OFFICIAL), 6900L);
        verify(mapper, never()).insertOrder(anyString(), eq(12L), anyLong(), anyLong(), anyString(), anyLong(), anyString(), anyLong(), eq(null));
    }
}
