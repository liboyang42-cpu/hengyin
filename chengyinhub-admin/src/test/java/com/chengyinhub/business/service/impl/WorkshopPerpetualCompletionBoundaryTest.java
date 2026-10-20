package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.marketplace.paid.PersistedPaidLicenseService;
import com.chengyinhub.business.marketplace.contract.ModuleLicenseGrant;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Ordinary typed-event/H2 persistence only. No signed callback, SDK verification or provider call. */
class WorkshopPerpetualCompletionBoundaryTest {
    WorkshopPaidLicenseTransactionTest fixture; JdbcTemplate jdbc; PersistedPaidLicenseService service;
    VerifiedWorkshopPayment payment;
    @BeforeEach void setup() throws Exception {
        fixture=new WorkshopPaidLicenseTransactionTest();fixture.setup();
        jdbc=(JdbcTemplate)ReflectionTestUtils.getField(fixture,"jdbc");
        service=(PersistedPaidLicenseService)ReflectionTestUtils.getField(fixture,"service");
        payment=ReflectionTestUtils.invokeMethod(fixture,"event");assertTrue(service.accept(payment));
        jdbc.update("UPDATE oms_order SET status=4 WHERE id=1");
    }
    private ModuleLicenseGrant grant(){return service.readOwned(7,"w18-paid-11");}
    private Map<String,Object> receipt(){return jdbc.queryForMap("SELECT * FROM w18_paid_license WHERE order_line_id=11");}
    private void state(ModuleLicenseGrant.Status expected){assertEquals(expected,grant().getStatus());}
    @Test void completionRetainsExactFrozenVersionTermsAndUnlimitedDurationWithoutWrites(){
        Map<String,Object> before=receipt();ModuleLicenseGrant completed=grant();
        assertNull(completed.getValidUntil());assertTrue(completed.isActiveAt(payment.getPaidAt().plusSeconds(86400L*365*100)));
        jdbc.update("UPDATE oms_order SET status=1 WHERE id=1");ModuleLicenseGrant paid=grant();
        assertEquals(paid.getGrantedVersionHashes(),completed.getGrantedVersionHashes());
        assertEquals(paid.getOffer().getTerms().getTermsHash(),completed.getOffer().getTerms().getTermsHash());
        assertEquals(paid.getEvidenceKind(),completed.getEvidenceKind());assertEquals(before,receipt());
    }
    @Test void knownCompletedStateDoesNotAuthorizeUnknownOrUnregisteredStates(){
        for(Long value:Arrays.asList(0L,2L,3L,5L,6L,-1L,99L,null)){
            jdbc.update("UPDATE oms_order SET status=? WHERE id=1",value);state(ModuleLicenseGrant.Status.SUSPENDED);
            assertEquals("ACTIVE",receipt().get("state"));
        }
    }
    @Test void completedOrderStillRejectsAllNonCleanOrderAfterSaleFlags(){
        for(Long value:Arrays.asList(0L,2L,3L,4L,99L,null)){
            jdbc.update("UPDATE oms_order SET aftersale_status=? WHERE id=1",value);state(ModuleLicenseGrant.Status.SUSPENDED);
        }
        jdbc.update("UPDATE oms_order SET aftersale_status=1 WHERE id=1");state(ModuleLicenseGrant.Status.ACTIVE);
    }
    @Test void completedOrderStillRejectsLineAfterSaleStateOrReference(){
        for(Long value:Arrays.asList(1L,2L,3L,4L,99L,null)){
            jdbc.update("UPDATE oms_order_item SET aftersale_status=? WHERE id=11",value);state(ModuleLicenseGrant.Status.SUSPENDED);
        }
        jdbc.update("UPDATE oms_order_item SET aftersale_status=0,aftersale_order_id=9 WHERE id=11");state(ModuleLicenseGrant.Status.SUSPENDED);
    }
    @Test void completedCannotReactivateSuspendedOrRevokedPersistentReceipt(){
        for(String value:Arrays.asList("SUSPENDED","REVOKED")){
            jdbc.update("UPDATE w18_paid_license SET state=? WHERE order_line_id=11",value);
            Map<String,Object> before=receipt();state(ModuleLicenseGrant.Status.valueOf(value));
            assertTrue(service.accept(payment));state(ModuleLicenseGrant.Status.valueOf(value));assertEquals(before,receipt());
        }
    }
    @Test void completedCannotBypassSuspendedOrRevokedFrozenSource(){
        for(String value:Arrays.asList("SUSPENDED","REVOKED")){
            jdbc.update("UPDATE w18_paid_order_line_snapshot SET state=? WHERE order_line_id=11",value);
            state(ModuleLicenseGrant.Status.valueOf(value));assertEquals("ACTIVE",receipt().get("state"));
        }
    }
    @Test void completedCannotBypassOrderSoftDeletionOrInactiveBuyer(){
        for(Integer value:Arrays.asList(1,2,99,null)){
            jdbc.update("UPDATE oms_order SET del_flag=? WHERE id=1",value);assertThrows(IllegalArgumentException.class,this::grant);
        }
        jdbc.update("UPDATE oms_order SET del_flag=0 WHERE id=1");jdbc.update("UPDATE ums_member SET status=0 WHERE id=7");
        assertThrows(IllegalArgumentException.class,this::grant);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM w18_paid_license",Integer.class));
    }
    @Test void completedStatusCannotCreateMissingLicenseOrBypassOwner(){
        assertThrows(IllegalArgumentException.class,()->service.readOwned(8,"w18-paid-11"));
        jdbc.update("DELETE FROM w18_paid_license WHERE order_line_id=11");
        assertThrows(IllegalArgumentException.class,this::grant);assertFalse(service.accept(payment));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM w18_paid_license",Integer.class));
    }
    @Test void completedStillRequiresExactCommittedProviderTransactionAndAmount(){
        Map<String,Object> before=receipt();jdbc.update("UPDATE oms_order SET payment_transaction_id='synthetic-other' WHERE id=1");
        assertThrows(IllegalArgumentException.class,this::grant);assertEquals(before,receipt());
        jdbc.update("UPDATE oms_order SET payment_transaction_id=?,pay_amount=9 WHERE id=1",payment.getTransactionId());
        assertThrows(IllegalArgumentException.class,this::grant);assertEquals(before,receipt());
    }
    @Test void exactDuplicateTypedEventAfterCompletionDoesNotChangeLicenseOrFulfillment(){
        Map<String,Object> before=receipt();assertTrue(service.accept(payment));assertTrue(service.accept(payment));
        assertEquals(before,receipt());assertEquals(4L,jdbc.queryForObject("SELECT status FROM oms_order WHERE id=1",Long.class));
        state(ModuleLicenseGrant.Status.ACTIVE);
    }
}
