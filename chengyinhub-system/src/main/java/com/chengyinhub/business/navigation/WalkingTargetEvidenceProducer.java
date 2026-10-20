package com.chengyinhub.business.navigation;

import com.chengyinhub.business.mapper.WalkingTargetMapper;
import com.chengyinhub.business.navigation.WalkingTargetContracts.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Objects;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dormant candidate, deliberately not a Spring bean and not called by any endpoint.
 * Fresh read on every call. A revision is change detection, never a signature or arrival grant.
 */
public final class WalkingTargetEvidenceProducer {
    public static final long TTL_MILLIS = 120_000L;
    private final WalkingTargetMapper mapper;
    private final Clock clock;
    private final TransactionTemplate freshRead;
    public WalkingTargetEvidenceProducer(WalkingTargetMapper mapper, PlatformTransactionManager transactions, Clock clock) {
        this.mapper=Objects.requireNonNull(mapper); this.clock=Objects.requireNonNull(clock);
        freshRead=new TransactionTemplate(Objects.requireNonNull(transactions));
        freshRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        freshRead.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        freshRead.setReadOnly(true);
    }
    public Evidence readCityTarget(long poiId) {
        if (poiId <= 0) throw new Unavailable(Failure.TARGET_UNAVAILABLE);
        // Query start bounds expiry: a slow/stalled read must not gain another full TTL.
        long startedAt=clock.millis();
        if (startedAt <= 0 || startedAt > Long.MAX_VALUE - TTL_MILLIS) throw new Unavailable(Failure.CLOCK_UNAVAILABLE);
        CityTargetRow row=freshRead.execute(status -> mapper.selectCurrentPublicCityTarget(poiId));
        long now=clock.millis();
        if (now < startedAt || now >= startedAt + TTL_MILLIS) throw new Unavailable(Failure.CLOCK_UNAVAILABLE);
        requireCurrentPublic(row, poiId);
        CoordinateDatum datum=NavigationProvenanceValidator.requireReviewed(row, now);
        if (!Long.valueOf(poiId).equals(row.poiId)
                || row.latitude.compareTo(row.currentLatitude) != 0
                || row.longitude.compareTo(row.currentLongitude) != 0) {
            throw new Unavailable(Failure.COORDINATES_CHANGED);
        }
        return new Evidence(poiId,row.latitude,row.longitude,datum,row.countryRegion,
                revision(row),startedAt,startedAt+TTL_MILLIS);
    }
    private static void requireCurrentPublic(CityTargetRow r, long id) {
        boolean approved=r != null && (r.applicationType == null || Integer.valueOf(2).equals(r.applicationType)
                || (Integer.valueOf(1).equals(r.applicationType) && Integer.valueOf(1).equals(r.auditStatus)));
        if (r == null || !Long.valueOf(id).equals(r.currentPoiId) || !Integer.valueOf(2).equals(r.poiType)
                || !Integer.valueOf(1).equals(r.poiStatus) || !approved || r.merchantId == null || r.merchantId <= 0
                || !Integer.valueOf(1).equals(r.merchantStatus) || !Integer.valueOf(1).equals(r.merchantAccountStatus)
                || !Integer.valueOf(0).equals(r.merchantDeleted)
                || !NavigationProvenanceValidator.coordinate(r.currentLatitude,90)
                || !NavigationProvenanceValidator.coordinate(r.currentLongitude,180)) {
            throw new Unavailable(Failure.TARGET_UNAVAILABLE);
        }
    }
    private static String revision(CityTargetRow r) {
        String canonical=String.join("\n", "walking-public-city-v1", "roam_poi", r.poiId.toString(),
                decimal(r.latitude),decimal(r.longitude),r.coordinateDatum,r.countryRegion,
                r.provenanceRevision.toString(),r.reviewState,r.sourceKind,r.sourceReference,r.sourceRevision,
                r.reviewReference,r.reviewedAt.toString(),r.poiType.toString(),r.poiStatus.toString(),
                String.valueOf(r.applicationType),String.valueOf(r.auditStatus),r.merchantId.toString(),
                r.merchantStatus.toString(),r.merchantAccountStatus.toString(),r.merchantDeleted.toString());
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder("walking-public-city-v1:");
            for(byte b:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",b & 0xff));
            return hex.toString();
        } catch(NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable",unavailable); }
    }
    private static String decimal(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
}
