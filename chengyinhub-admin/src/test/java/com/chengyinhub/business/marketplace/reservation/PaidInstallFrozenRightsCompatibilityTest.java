package com.chengyinhub.business.marketplace.reservation;
import com.chengyinhub.business.marketplace.contract.*;
import com.chengyinhub.business.marketplace.paid.PaidPurchaseSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** Serializer-only synthetic value fixture. It does not stand in for a FREE issuer/authority test. */
class PaidInstallFrozenRightsCompatibilityTest {
    @Test void exactLegacyFreeV1BytesAreUnchanged() throws Exception {
        byte[] content="{}".getBytes(StandardCharsets.UTF_8),terms="Synthetic FREE wire-compatibility terms".getBytes(StandardCharsets.UTF_8);
        BuyerPrincipal buyer=new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL,7),seller=new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL,8);
        LicenseTermsSnapshot policy=new LicenseTermsSnapshot("terms-legacy",terms,PaidPurchaseSnapshot.sha(terms),"synthetic:legacy-terms",
                LicenseTermsSnapshot.Permission.PROHIBITED,LicenseTermsSnapshot.Adaptation.LOCAL_ADAPTATION,LicenseTermsSnapshot.Permission.PROHIBITED,
                LicenseTermsSnapshot.Updates.EXACT_PURCHASED_VERSION,LicenseTermsSnapshot.Redistribution.PROHIBITED,Collections.singleton("synthetic-region"),
                LicenseTermsSnapshot.Limit.atMost(1),LicenseTermsSnapshot.Limit.atMost(0),LicenseTermsSnapshot.Limit.atMost(10));
        LicenseOfferSnapshot offer=new LicenseOfferSnapshot("legacy-offer","offer-v1","module-legacy",seller,Collections.singleton(BuyerPrincipal.Kind.INDIVIDUAL),LicenseOfferSnapshot.Acquisition.FREE,0,"CNY",policy);
        ModuleVersionSnapshot version=new ModuleVersionSnapshot(seller,"module-legacy","version-legacy","v1",1,ModuleVersionSnapshot.SourceKind.MEMBER_TEMPLATE,12,"source-r1",content,PaidPurchaseSnapshot.sha(content),"synthetic:review-legacy",Collections.emptyMap());
        ModuleLicenseGrant grant=new ModuleLicenseGrant("claim-legacy",buyer,offer,"version-legacy",Collections.singletonMap("version-legacy",version.getContentHash()),ModuleLicenseGrant.Status.ACTIVE,ModuleLicenseGrant.EvidenceKind.VERIFIED_FREE_CLAIM,"synthetic:free-claim","synthetic:free-event",Instant.parse("2026-10-05T00:00:00Z"),Instant.parse("2026-11-05T00:00:00Z"));
        ModuleInstallRequest request=new ModuleInstallRequest(buyer,"wire-compat","claim-legacy","module-legacy","version-legacy",version.getContentHash(),"offer-v1",policy.getTermsHash(),91,1,false,Collections.emptySet(),Collections.emptyMap());
        ModuleInstallAuthority.OwnedDraft target=new ModuleInstallAuthority.OwnedDraft(91,buyer,1,"synthetic-region",ModuleInstallAuthority.OwnedDraft.State.DRAFT);
        java.lang.reflect.Constructor<DraftInstallPlan> ctor=DraftInstallPlan.class.getDeclaredConstructor(long.class,ModuleInstallRequest.class,ModuleVersionSnapshot.class,ModuleLicenseGrant.class,ModuleInstallAuthority.OwnedDraft.class,Map.class,long.class,Instant.class);ctor.setAccessible(true);
        DraftInstallPlan plan=ctor.newInstance(7L,request,version,grant,target,Collections.emptyMap(),1L,Instant.parse("2026-10-05T00:00:00Z"));
        String encoded=TextModuleDraftCodec.frozenRights(plan,terms);
        assertTrue(encoded.contains("\"schema\":\"w18-frozen-install-rights-v1\""));assertFalse(encoded.contains("useDuration"));
        assertEquals("5d4b0e269be7d18654f496d24b4277ab00ff875825290990c9046a3fe6a98bb1",PaidPurchaseSnapshot.sha(encoded.getBytes(StandardCharsets.UTF_8)));
    }
}
