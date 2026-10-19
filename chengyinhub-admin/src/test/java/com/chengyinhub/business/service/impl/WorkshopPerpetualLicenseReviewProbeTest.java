package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.marketplace.paid.PersistedPaidLicenseService;
import com.chengyinhub.business.marketplace.contract.ModuleLicenseGrant;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Reviewer-only ordinary persistence probe. No SDK signature, crypto, provider or network call.
 * The documented existing OMS status4 is completed; actual business routing into it is separate. */
class WorkshopPerpetualLicenseReviewProbeTest {
    WorkshopPaidLicenseTransactionTest fixture;JdbcTemplate jdbc;PersistedPaidLicenseService service;
    @BeforeEach void setup() throws Exception {
        fixture=new WorkshopPaidLicenseTransactionTest();fixture.setup();
        jdbc=(JdbcTemplate)ReflectionTestUtils.getField(fixture,"jdbc");
        service=(PersistedPaidLicenseService)ReflectionTestUtils.getField(fixture,"service");
        VerifiedWorkshopPayment event=ReflectionTestUtils.invokeMethod(fixture,"event");
        assertTrue(service.accept(event));assertEquals(ModuleLicenseGrant.Status.ACTIVE,service.readOwned(7,"w18-paid-11").getStatus());
    }
    @Test void documentedCompletedOrderMustNotByItselfSuspendPerpetualPurchasedVersion(){
        assertEquals(1,jdbc.update("UPDATE oms_order SET status=4 WHERE id=1"));
        ModuleLicenseGrant actual=service.readOwned(7,"w18-paid-11");
        System.out.println("W18_REVIEW_OMS_COMPLETED status=4 persisted_license="+jdbc.queryForObject("SELECT state FROM w18_paid_license",String.class)+" effective_license="+actual.getStatus()+" permanent="+(actual.getValidUntil()==null));
        assertEquals(ModuleLicenseGrant.Status.ACTIVE,actual.getStatus(),"Normal documented completion alone must not erase purchased-version use authority");
    }
    @Test void actualRefundFlagStillSuspendsUseWithoutDeletingPermanentReceipt(){
        jdbc.update("UPDATE oms_order SET aftersale_status=4 WHERE id=1");
        assertEquals(ModuleLicenseGrant.Status.SUSPENDED,service.readOwned(7,"w18-paid-11").getStatus());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM w18_paid_license",Integer.class));
    }
}
