package com.chengyinhub.business.appleiap;

import com.apple.itunes.storekit.model.*;
import com.chengyinhub.business.appleiap.WorkshopAppleOrderPort.Expectation;
import com.chengyinhub.business.appleiap.WorkshopAppleOrderPort.VerifiedTransaction;
import com.chengyinhub.business.appleiap.mapper.AppleIapMapper;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.web.controller.api.ApiWorkshopAppleIapController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Business tests inject decoded SDK results; they do not prove a valid Apple signature or sandbox purchase. */
class WorkshopAppleIapServiceTest {
    static final UUID ACCOUNT = UUID.fromString("c86278d4-c1eb-4db9-a024-e7c35954b5ef");
    static AppleIapProperties config() {
        var c = new AppleIapProperties(); c.setEnabled(true); c.setBundleId("org.chengyin.app");
        c.setSkuPrices(Map.of("template.v1", Map.of("CNY", 1230L))); return c;
    }
    static JWSTransactionDecodedPayload transaction() {
        return new JWSTransactionDecodedPayload().transactionId("1001").originalTransactionId("1000")
                .bundleId("org.chengyin.app").environment(Environment.SANDBOX).productId("template.v1")
                .appAccountToken(ACCOUNT).type(Type.NON_CONSUMABLE).inAppOwnershipType(InAppOwnershipType.PURCHASED)
                .quantity(1).price(1230L).currency("CNY").purchaseDate(100L).signedDate(200L);
    }
    static Expectation expectation(String original) {
        return new Expectation("WKO_one", 7L, "org.chengyin.app", "Sandbox", "template.v1", ACCOUNT, original, 1230L, "CNY");
    }
    static ResponseBodyV2DecodedPayload notification(NotificationTypeV2 type, String uuid) {
        return new ResponseBodyV2DecodedPayload().notificationType(type).notificationUUID(uuid)
                .data(new Data().bundleId("org.chengyin.app").environment(Environment.SANDBOX).signedTransactionInfo("inner"));
    }

    @Test void disabledAndMissingRootsAndProductionQueryFailClosedWithoutPort() {
        var disabled = new AppleSignedData(new AppleIapProperties());
        assertFalse(disabled.isReady()); assertEquals("APPLE_IAP_UNAVAILABLE", assertThrows(ServiceException.class, () -> disabled.transaction("invalid")).getMessage());
        var c = config(); var configured = new AppleSignedData(c); assertFalse(configured.isReady());
        assertEquals("APPLE_TRUST_ROOTS_UNAVAILABLE", assertThrows(ServiceException.class, () -> configured.transaction("invalid")).getMessage());
        c.setEnvironment("Production"); c.setAppAppleId(99L); c.setServerApiEnabled(true);
        assertEquals("APPLE_SERVER_QUERY_UNAVAILABLE", assertThrows(ServiceException.class, () -> configured.query("1001")).getMessage());
    }

    @Test void productionControllerCallsVerifierThenRealOrderPortAndDoesNotTrustClientSuccess() {
        var h = new Harness();
        var controller = new ApiWorkshopAppleIapController(h.service) { @Override public Long getAppUserId() { return 7L; } };
        var result = controller.confirm(new ApiWorkshopAppleIapController.Transaction("WKO_one", "purchase"));
        assertEquals(200, result.get("code")); verify(h.sdk).transaction("purchase"); verify(h.port).activate(eq(expectation(null)), any());
        assertEquals(1, h.originals.size()); assertEquals(1, h.transactions.size());
        when(h.sdk.transaction("paid=true")).thenThrow(new ServiceException("APPLE_TRANSACTION_VERIFICATION_FAILED",422));
        assertThrows(ServiceException.class, () -> controller.confirm(new ApiWorkshopAppleIapController.Transaction("WKO_one", "paid=true")));
        verify(h.port, times(1)).activate(any(), any());
    }

    @Test void ownerAccountProductAmountCurrencyAndOriginalMustAllMatch() {
        var h = new Harness();
        when(h.port.requireOrder(8L,"WKO_one")).thenThrow(new ServiceException("ORDER_NOT_OWNED",403));
        assertThrows(ServiceException.class, () -> h.service.confirm(8L,"WKO_one","purchase"));
        for (var bad : List.of(
                new Expectation("WKO_one",7L,"wrong","Sandbox","template.v1",ACCOUNT,null,1230L,"CNY"),
                new Expectation("WKO_one",7L,"org.chengyin.app","Production","template.v1",ACCOUNT,null,1230L,"CNY"),
                new Expectation("WKO_one",7L,"org.chengyin.app","Sandbox","other",ACCOUNT,null,1230L,"CNY"),
                new Expectation("WKO_one",7L,"org.chengyin.app","Sandbox","template.v1",UUID.randomUUID(),null,1230L,"CNY"),
                new Expectation("WKO_one",7L,"org.chengyin.app","Sandbox","template.v1",ACCOUNT,null,1240L,"CNY"),
                new Expectation("WKO_one",7L,"org.chengyin.app","Sandbox","template.v1",ACCOUNT,null,1230L,"USD"),
                expectation("9999"))) {
            when(h.port.requireOrder(7L,"WKO_one")).thenReturn(bad);
            assertThrows(ServiceException.class, () -> h.service.confirm(7L,"WKO_one","purchase"));
        }
        verify(h.port,never()).activate(any(),any());
    }

    @Test void unsupportedOrFrozenSkuConflictingFactsNeverActivate() {
        var h = new Harness();
        for (var bad : List.of(transaction().type(Type.CONSUMABLE), transaction().quantity(2),
                transaction().inAppOwnershipType(InAppOwnershipType.FAMILY_SHARED), transaction().appAccountToken(null),
                transaction().environment(Environment.LOCAL_TESTING), transaction().productId("missing"),
                transaction().price(1231L), transaction().currency("USD"))) {
            when(h.sdk.transaction("bad")).thenReturn(bad);
            assertThrows(ServiceException.class, () -> h.service.confirm(7L,"WKO_one","bad"));
        }
        verify(h.port,never()).activate(any(),any());
    }

    @Test void originalAndTransactionCannotBeReboundOrChangeImmutableFact() {
        var h = new Harness(); h.service.confirm(7L,"WKO_one","purchase");
        when(h.port.requireOrder(7L,"WKO_two")).thenReturn(new Expectation("WKO_two",7L,"org.chengyin.app","Sandbox","template.v1",ACCOUNT,null,1230L,"CNY"));
        assertEquals("APPLE_ORIGINAL_ORDER_CONFLICT", assertThrows(ServiceException.class, () -> h.service.confirm(7L,"WKO_two","purchase")).getMessage());
        when(h.sdk.transaction("changed")).thenReturn(transaction().purchaseDate(101L));
        assertEquals("APPLE_TRANSACTION_FACT_CONFLICT", assertThrows(ServiceException.class, () -> h.service.confirm(7L,"WKO_one","changed")).getMessage());
        when(h.sdk.transaction("other")).thenReturn(transaction().originalTransactionId("2000"));
        assertEquals("APPLE_TRANSACTION_ORIGINAL_CONFLICT", assertThrows(ServiceException.class, () -> h.service.confirm(7L,"WKO_one","other")).getMessage());
    }

    @Test void unmatchedRefundCommitsTombstoneAndOldPurchaseCannotReactivate() {
        var h = new Harness(); String uuid="4046e659-c699-4421-ad7e-73c257afca48";
        when(h.sdk.notification("refund")).thenReturn(notification(NotificationTypeV2.REFUND,uuid));
        when(h.sdk.transaction("inner")).thenReturn(transaction().revocationDate(250L).signedDate(300L));
        when(h.port.findOrder(any())).thenReturn(Optional.empty());
        assertEquals("APPLE_ORDER_NOT_MATCHED", assertThrows(ServiceException.class, () -> h.service.notification("refund")).getMessage());
        assertEquals(1, h.originals.get("1000").get("revoked")); assertEquals("PENDING",h.inbox.get(uuid).get("state"));
        assertEquals("REVOKED",h.service.confirm(7L,"WKO_one","purchase").get("state"));
        verify(h.port,never()).activate(any(),any()); verify(h.port).revoke(any(),any());
        when(h.port.findOrder(any())).thenReturn(Optional.of(expectation(null)));
        assertEquals("APPLIED",h.service.notification("refund").get("state"));
        int calls = mockingDetails(h.port).getInvocations().size();
        h.service.notification("refund"); assertEquals(calls,mockingDetails(h.port).getInvocations().size());
    }

    @Test void refundFenceSurvivesUnmappedSkuAndPortFailure() {
        var h = new Harness(); String uuid="d37ed0c4-d51a-4c5c-a916-a0a4943ab366";
        when(h.sdk.notification("refund")).thenReturn(notification(NotificationTypeV2.REVOKE,uuid));
        when(h.sdk.transaction("inner")).thenReturn(transaction().revocationDate(250L));
        h.config.setSkuPrices(Map.of("other",Map.of("CNY",1230L)));
        when(h.port.findOrder(any())).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(ServiceException.class,()->h.service.notification("refund"));
        assertEquals(1,h.originals.get("1000").get("revoked"));
        verify(h.port).revokeBoundOriginal("org.chengyin.app","Sandbox","1000",250L);
        h.config.setSkuPrices(Map.of("template.v1",Map.of("CNY",1230L)));
        assertThrows(ServiceException.class,()->h.service.notification("refund"));
        assertEquals(1,h.originals.get("1000").get("revoked"));
        h.service.confirm(7L,"WKO_one","purchase"); verify(h.port,never()).activate(any(),any());
        doReturn(Optional.of(expectation(null))).when(h.port).findOrder(any());
        assertEquals("APPLIED",h.service.notification("refund").get("state"));
    }

    @Test void retiredSkuRestoreUsesFrozenOrderMapping() {
        var h=new Harness(); h.config.setSkuPrices(Map.of());
        when(h.port.requireOrder(7L,"WKO_one")).thenReturn(expectation("1000"));
        when(h.port.restore(any(),any())).thenReturn(Map.of("licenseId","WKO_one","status","ACTIVE"));
        assertEquals("RESTORED",h.service.restore(7L,"WKO_one","purchase").get("state"));
        verify(h.port,never()).activate(any(),any());
    }

    @Test void verifiedRefundWithIncompleteLegacyAccountStillBlocksOldPurchase() {
        var h=new Harness(); String uuid="a1b95403-ea4d-4666-9af6-060f5e0b3a01";
        when(h.sdk.notification("refund")).thenReturn(notification(NotificationTypeV2.REFUND,uuid));
        when(h.sdk.transaction("inner")).thenReturn(transaction().appAccountToken(null).price(null).revocationDate(250L));
        assertThrows(ServiceException.class,()->h.service.notification("refund"));
        assertEquals(1,h.originals.get("1000").get("revoked"));
        assertEquals("REVOKED",h.service.confirm(7L,"WKO_one","purchase").get("state"));
        verify(h.port,never()).activate(any(),any());
        verify(h.port).revokeBoundOriginal("org.chengyin.app","Sandbox","1000",250L);
        verify(h.port).revoke(any(),argThat(f->Long.valueOf(250L).equals(f.revocationDate())));
    }

    @Test void outerVerificationFailureIsNotStoredAndInnerFailureIsDurableAndNon2xx() {
        var h = new Harness();
        when(h.sdk.notification("invalid")).thenThrow(new ServiceException("APPLE_NOTIFICATION_VERIFICATION_FAILED",422));
        assertThrows(ServiceException.class,()->h.service.notification("invalid")); assertTrue(h.inbox.isEmpty());
        String uuid="f6cd4037-ce5f-4092-b911-d7ecc34dddca";
        when(h.sdk.notification("outer")).thenReturn(notification(NotificationTypeV2.REFUND,uuid));
        when(h.sdk.transaction("inner")).thenThrow(new ServiceException("APPLE_TRANSACTION_VERIFICATION_FAILED",422));
        var controller = new ApiWorkshopAppleIapController(h.service);
        var error=assertThrows(ServiceException.class,()->controller.notification(new ApiWorkshopAppleIapController.Notification("outer")));
        assertEquals(503,controller.rejected(error).getStatusCode().value()); assertEquals("PENDING",h.inbox.get(uuid).get("state"));
        verifyNoInteractions(h.port);
    }

    @Test void duplicateUuidConflictsAndUnsupportedNotificationsRemainRetryable() {
        var h = new Harness(); String uuid="1b98c444-d2bd-4d52-8882-a36875f49d54";
        when(h.sdk.notification(anyString())).thenReturn(notification(NotificationTypeV2.REFUND_REVERSED,uuid));
        assertThrows(ServiceException.class,()->h.service.notification("first"));
        assertEquals("PENDING",h.inbox.get(uuid).get("state"));
        assertEquals("APPLE_NOTIFICATION_UUID_CONFLICT",assertThrows(ServiceException.class,()->h.service.notification("different")).getMessage());
        verifyNoInteractions(h.port);
    }

    @Test void restoreOnlyReturnsThisBuyersPreviouslyBoundOriginalLicense() {
        var h=new Harness();
        assertEquals("APPLE_RESTORE_ORIGINAL_REQUIRED",assertThrows(ServiceException.class,()->h.service.restore(7L,"WKO_one","purchase")).getMessage());
        when(h.port.requireOrder(7L,"WKO_one")).thenReturn(expectation("1000"));
        when(h.port.restore(any(),any())).thenReturn(Map.of("licenseId","WKO_one"));
        assertEquals("RESTORED",h.service.restore(7L,"WKO_one","purchase").get("state"));
        verify(h.port,never()).activate(any(),any());
    }

    @Test void queryAuthorizesOrderBeforeSdkAndRejectsDifferentReturnedTransaction() {
        var h=new Harness();
        when(h.port.requireOrder(8L,"WKO_one")).thenThrow(new ServiceException("ORDER_NOT_OWNED",403));
        assertThrows(ServiceException.class,()->h.service.reconcile(8L,"WKO_one","1001")); verify(h.sdk,never()).query(anyString());
        when(h.sdk.query("1001")).thenReturn("purchase");
        assertEquals("VERIFIED",h.service.reconcile(7L,"WKO_one","1001").get("state"));
        when(h.sdk.query("1002")).thenReturn("purchase");
        assertThrows(ServiceException.class,()->h.service.reconcile(7L,"WKO_one","1002"));
    }

    static class Harness {
        final AppleIapProperties config=config();
        final AppleSignedData sdk=mock(AppleSignedData.class);
        final AppleIapMapper mapper=mock(AppleIapMapper.class);
        final WorkshopAppleOrderPort port=mock(WorkshopAppleOrderPort.class);
        final Map<String,Map<String,Object>> originals=new HashMap<>(),transactions=new HashMap<>(),inbox=new HashMap<>();
        final WorkshopAppleIapService service;
        @SuppressWarnings("unchecked") Harness() {
            ObjectProvider<WorkshopAppleOrderPort> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(port);
            PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenAnswer(c->new SimpleTransactionStatus());
            service=new WorkshopAppleIapService(config,sdk,mapper,provider,manager);
            when(sdk.transaction("purchase")).thenAnswer(c->transaction());
            when(port.requireOrder(7L,"WKO_one")).thenAnswer(c->expectation(null));
            when(port.findOrder(any())).thenReturn(Optional.of(expectation(null)));
            when(mapper.insertOriginal(anyString(),anyString())).thenAnswer(c->{originals.putIfAbsent(c.getArgument(0),new HashMap<>(Map.of("revoked",0)));originals.get(c.getArgument(0)).putIfAbsent("identity_hash",c.getArgument(1));return 1;});
            when(mapper.insertRevocation(anyString(),anyLong())).thenAnswer(c->{originals.putIfAbsent(c.getArgument(0),new HashMap<>());originals.get(c.getArgument(0)).put("revoked",1);originals.get(c.getArgument(0)).put("revoked_at",c.getArgument(1));return 1;});
            when(mapper.lockOriginal(anyString())).thenAnswer(c->originals.get(c.getArgument(0)));
            when(mapper.bindOriginal(anyString(),anyString())).thenAnswer(c->{originals.get(c.getArgument(0)).put("order_reference",c.getArgument(1));return 1;});
            when(mapper.revokeOriginal(anyString(),anyLong())).thenAnswer(c->{originals.get(c.getArgument(0)).put("revoked",1);originals.get(c.getArgument(0)).put("revoked_at",c.getArgument(1));return 1;});
            when(mapper.insertTransaction(anyString(),anyString(),anyString())).thenAnswer(c->{transactions.putIfAbsent(c.getArgument(0),new HashMap<>(Map.of("original_id",c.getArgument(1),"fact_hash",c.getArgument(2))));return 1;});
            when(mapper.lockTransaction(anyString())).thenAnswer(c->transactions.get(c.getArgument(0)));
            when(mapper.insertNotification(anyString(),anyString(),anyString())).thenAnswer(c->{inbox.putIfAbsent(c.getArgument(0),new HashMap<>(Map.of("payload_hash",c.getArgument(1),"signed_payload",c.getArgument(2),"state","PENDING")));return 1;});
            when(mapper.lockNotification(anyString())).thenAnswer(c->inbox.get(c.getArgument(0)));
            when(mapper.notificationState(anyString(),anyString(),nullable(String.class))).thenAnswer(c->{inbox.get(c.getArgument(0)).put("state",c.getArgument(1));return 1;});
        }
    }
}
