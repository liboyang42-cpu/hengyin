package com.chengyinhub.business.navigation;

import com.chengyinhub.business.mapper.WalkingTargetMapper;
import com.chengyinhub.business.navigation.WalkingTargetContracts.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WalkingTargetEvidenceProducerTest {
    static final long NOW=1_800_000_000_000L;
    static CityTargetRow row() {
        CityTargetRow r=new CityTargetRow();
        r.currentPoiId=11L; r.poiId=11L; r.poiType=2; r.poiStatus=1; r.applicationType=1; r.auditStatus=1;
        r.currentLatitude=new BigDecimal("35.000000"); r.currentLongitude=new BigDecimal("135.000000");
        r.latitude=r.currentLatitude; r.longitude=r.currentLongitude; r.merchantId=7L;
        r.merchantStatus=1; r.merchantAccountStatus=1; r.merchantDeleted=0;
        r.provenanceRevision=1L; r.reviewState="REVIEWED"; r.coordinateDatum="WGS84"; r.countryRegion="JP";
        r.sourceKind="SURVEY_RECORD"; r.sourceReference="synthetic-survey-1"; r.sourceRevision="v1";
        r.reviewReference="synthetic-review-1"; r.reviewedAt=NOW-1_000;
        return r;
    }
    static WalkingTargetEvidenceProducer producer(WalkingTargetMapper mapper, Clock clock) {
        org.springframework.transaction.PlatformTransactionManager tm=mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        return new WalkingTargetEvidenceProducer(mapper,tm,clock);
    }
    static Evidence read(CityTargetRow row) {
        WalkingTargetMapper m=mock(WalkingTargetMapper.class);when(m.selectCurrentPublicCityTarget(11L)).thenReturn(row);
        return producer(m,Clock.fixed(Instant.ofEpochMilli(NOW),ZoneOffset.UTC)).readCityTarget(11);
    }
    static void rejected(CityTargetRow row, Failure failure) { assertEquals(failure,assertThrows(Unavailable.class,()->read(row)).getFailure()); }

    @Test void exactTypedServerContractIsBoundToPublicPoiAndShortServerTime() throws Exception {
        JsonNode json=new ObjectMapper().valueToTree(read(row()));
        assertEquals("roam_poi",json.get("namespace").asText());assertEquals(11,json.get("targetId").asLong());
        assertEquals("CURRENT_PUBLIC",json.get("publicVisibility").asText());assertEquals("WGS84",json.get("coordinateDatum").asText());
        assertEquals("JP",json.get("countryRegion").asText());assertEquals(NOW,json.get("navigationIssuedAt").asLong());
        assertEquals(NOW+120_000,json.get("navigationExpiresAt").asLong());assertEquals(10,json.size());
        for(String key:Arrays.asList("sourceReference","reviewReference","releaseId","playerUnlocked","arrivalProof","memberId")) assertFalse(json.has(key));
    }
    @Test void revisionIsDeterministicAndDecimalScaleIndependentButNotTimeDependent() {
        CityTargetRow a=row(),b=row();b.latitude=new BigDecimal("35.0");b.longitude=new BigDecimal("135.00");
        assertEquals(read(a).getAuthorityRevision(),read(b).getAuthorityRevision());
        assertTrue(read(a).getAuthorityRevision().matches("walking-public-city-v1:[0-9a-f]{64}"));
    }
    @Test void everyBoundProvenanceAndCurrentVisibilityFieldChangesRevision() {
        String original=read(row()).getAuthorityRevision();
        List<Consumer<CityTargetRow>> changes=Arrays.asList(r->{r.latitude=new BigDecimal("35.1");r.currentLatitude=r.latitude;},
                r->{r.longitude=new BigDecimal("135.1");r.currentLongitude=r.longitude;},r->r.coordinateDatum="GCJ02",
                r->r.countryRegion="US",r->r.provenanceRevision=2L,r->r.sourceReference="synthetic-survey-2",
                r->r.sourceRevision="v2",r->r.reviewReference="synthetic-review-2",r->r.reviewedAt=NOW-500,
                r->r.sourceKind="PROVIDER_DATASET",r->r.merchantId=8L,r->r.applicationType=2,r->r.applicationType=null);
        for(Consumer<CityTargetRow> change:changes){CityTargetRow r=row();change.accept(r);assertNotEquals(original,read(r).getAuthorityRevision());}
    }
    @Test void nullUnknownAndUnreviewedProvenanceNeverGuessDatumOrCountry() {
        List<Consumer<CityTargetRow>> changes=Arrays.asList(r->r.poiId=null,r->r.provenanceRevision=null,
                r->r.provenanceRevision=0L,r->r.reviewState=null,r->r.reviewState="UNREVIEWED",r->r.reviewState="REVOKED",
                r->r.coordinateDatum=null,r->r.coordinateDatum="gcj02",r->r.coordinateDatum="BD09",r->r.countryRegion=null,
                r->r.countryRegion="ZZ",r->r.countryRegion="jp",r->r.sourceKind=null,r->r.sourceReference=null,
                r->r.sourceRevision="",r->r.reviewReference="missing\nreview",r->r.reviewedAt=null,r->r.reviewedAt=NOW+1);
        for(Consumer<CityTargetRow> change:changes){CityTargetRow r=row();change.accept(r);rejected(r,Failure.PROVENANCE_UNAVAILABLE);}
    }
    @Test void historicalPendingRejectedHiddenDeletedAndMissingMerchantAreUnavailable() {
        List<Consumer<CityTargetRow>> changes=Arrays.asList(r->r.poiStatus=0,r->r.poiStatus=null,r->r.auditStatus=0,
                r->r.auditStatus=2,r->r.applicationType=99,r->r.merchantId=null,r->r.merchantStatus=0,
                r->r.merchantStatus=null,r->r.merchantAccountStatus=2,r->r.merchantAccountStatus=null,r->r.merchantDeleted=2,r->r.merchantDeleted=null);
        for(Consumer<CityTargetRow> change:changes){CityTargetRow r=row();change.accept(r);rejected(r,Failure.TARGET_UNAVAILABLE);}
        rejected(null,Failure.TARGET_UNAVAILABLE);
    }
    @Test void namespaceCannotBeSubstitutedWithLandmarkRegistrationMerchantOrAnotherPoi() {
        CityTargetRow r=row();r.poiType=1;rejected(r,Failure.TARGET_UNAVAILABLE);
        r=row();r.currentPoiId=7L;rejected(r,Failure.TARGET_UNAVAILABLE);
        r=row();r.poiId=7L;rejected(r,Failure.COORDINATES_CHANGED);
    }
    @Test void coordinateEditsInvalidateReviewedSnapshotRatherThanRelabelOrRound() {
        CityTargetRow r=row();r.currentLatitude=new BigDecimal("35.000001");rejected(r,Failure.COORDINATES_CHANGED);
        r=row();r.currentLongitude=new BigDecimal("135.000001");rejected(r,Failure.COORDINATES_CHANGED);
        r=row();r.latitude=new BigDecimal("35.00000001");rejected(r,Failure.PROVENANCE_UNAVAILABLE);
        r=row();r.currentLatitude=new BigDecimal("90.1");rejected(r,Failure.TARGET_UNAVAILABLE);
        r=row();r.currentLongitude=null;rejected(r,Failure.TARGET_UNAVAILABLE);
    }
    @Test void gcj02PreservedWithoutConversionAndNoGeographicHeuristic() {
        CityTargetRow r=row();r.coordinateDatum="GCJ02";r.countryRegion="CN";
        assertEquals(CoordinateDatum.GCJ02,read(r).getCoordinateDatum());assertEquals(r.latitude,read(r).getLatitude());
        r.currentLatitude=BigDecimal.ZERO;r.latitude=BigDecimal.ZERO;r.currentLongitude=BigDecimal.ZERO;r.longitude=BigDecimal.ZERO;
        assertEquals("CN",read(r).getCountryRegion()); // Explicit synthetic provenance, not latitude inference.
    }
    @Test void eachReadRequeriesVisibilityAndRemovalCannotReusePriorEvidence() {
        WalkingTargetMapper m=mock(WalkingTargetMapper.class);when(m.selectCurrentPublicCityTarget(11L)).thenReturn(row(),null);
        WalkingTargetEvidenceProducer p=producer(m,Clock.fixed(Instant.ofEpochMilli(NOW),ZoneOffset.UTC));
        assertNotNull(p.readCityTarget(11));assertThrows(Unavailable.class,()->p.readCityTarget(11));verify(m,times(2)).selectCurrentPublicCityTarget(11L);
    }
    @Test void slowBackwardAndOverflowingClocksCannotIssueFreshLookingEvidence() {
        for(long end:new long[]{NOW-1,NOW+120_000}) {
            WalkingTargetMapper m=mock(WalkingTargetMapper.class);when(m.selectCurrentPublicCityTarget(11)).thenReturn(row());
            Clock c=mock(Clock.class);when(c.millis()).thenReturn(NOW,end);
            assertEquals(Failure.CLOCK_UNAVAILABLE,assertThrows(Unavailable.class,()->producer(m,c).readCityTarget(11)).getFailure());
        }
        WalkingTargetMapper m=mock(WalkingTargetMapper.class);Clock c=Clock.fixed(Instant.ofEpochMilli(Long.MAX_VALUE),ZoneOffset.UTC);
        assertThrows(Unavailable.class,()->producer(m,c).readCityTarget(11));verifyNoInteractions(m);
    }
    @Test void ingestionKeepsUnknownFieldsUnsetAndCannotAcceptInvalidShapes() {
        Provenance p=new Provenance();p.poiId=11L;p.latitude=BigDecimal.ZERO;p.longitude=BigDecimal.ZERO;
        assertDoesNotThrow(()->NavigationProvenanceValidator.requireStageable(p));assertNull(p.coordinateDatum);assertNull(p.countryRegion);
        p.countryRegion="ZZ";assertThrows(Unavailable.class,()->NavigationProvenanceValidator.requireStageable(p));
        p.countryRegion="CN";p.coordinateDatum="wgs84";assertThrows(Unavailable.class,()->NavigationProvenanceValidator.requireStageable(p));
    }
    @Test void producerRequiresNewReadOnlyReadCommittedTransaction() {
        WalkingTargetMapper m=mock(WalkingTargetMapper.class);when(m.selectCurrentPublicCityTarget(11)).thenReturn(row());
        org.springframework.transaction.PlatformTransactionManager tm=mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenAnswer(invocation->{
            org.springframework.transaction.TransactionDefinition d=invocation.getArgument(0);
            assertTrue(d.isReadOnly());assertEquals(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW,d.getPropagationBehavior());
            assertEquals(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED,d.getIsolationLevel());
            return new org.springframework.transaction.support.SimpleTransactionStatus();
        });
        assertNotNull(new WalkingTargetEvidenceProducer(m,tm,Clock.fixed(Instant.ofEpochMilli(NOW),ZoneOffset.UTC)).readCityTarget(11));
        verify(tm).commit(any());
    }
    @Test void invalidIdsAndInvalidIngestionDoNotTouchStorageAndCodeHasNoSpringActivation() {
        WalkingTargetMapper m=mock(WalkingTargetMapper.class);
        assertThrows(Unavailable.class,()->producer(m,Clock.systemUTC()).readCityTarget(0));
        assertThrows(Unavailable.class,()->new NavigationProvenanceIngestion(m).stage(new Provenance()));verifyNoInteractions(m);
        assertEquals(0,WalkingTargetEvidenceProducer.class.getAnnotations().length);
        assertEquals(0,NavigationProvenanceIngestion.class.getAnnotations().length);
    }
}
