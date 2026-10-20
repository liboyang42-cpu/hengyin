package com.chengyinhub.business.navigation;

import java.math.BigDecimal;

/** Dormant public-POI contract. These server-only rows are never request or response models. */
public final class WalkingTargetContracts {
    private WalkingTargetContracts() { }
    public enum CoordinateDatum { WGS84, GCJ02 }
    public enum Failure { TARGET_UNAVAILABLE, PROVENANCE_UNAVAILABLE, COORDINATES_CHANGED, CLOCK_UNAVAILABLE }

    /** Empty/legacy ingestion has no implied country, datum, review or provider. */
    public static class Provenance {
        public Long poiId;
        public Long provenanceRevision;
        public String reviewState;
        public BigDecimal latitude;
        public BigDecimal longitude;
        public String coordinateDatum;
        public String countryRegion;
        public String sourceKind;
        public String sourceReference;
        public String sourceRevision;
        public String reviewReference;
        public Long reviewedAt;
        @Override public String toString() { return "NavigationProvenance[redacted]"; }
    }

    /** One SQL statement captures current visibility and the exact reviewed coordinate binding. */
    public static final class CityTargetRow extends Provenance {
        public Long currentPoiId;
        public Integer poiType;
        public Integer poiStatus;
        public Integer applicationType;
        public Integer auditStatus;
        public BigDecimal currentLatitude;
        public BigDecimal currentLongitude;
        public Long merchantId;
        public Integer merchantStatus;
        public Integer merchantAccountStatus;
        public Integer merchantDeleted;
    }

    /** Candidate wire object only; no controller/VO currently emits it. No arrival/unlock grant. */
    public static final class Evidence {
        private final long targetId;
        private final BigDecimal latitude;
        private final BigDecimal longitude;
        private final CoordinateDatum coordinateDatum;
        private final String countryRegion;
        private final String authorityRevision;
        private final long navigationIssuedAt;
        private final long navigationExpiresAt;
        Evidence(long id, BigDecimal lat, BigDecimal lng, CoordinateDatum datum, String country,
                 String revision, long issuedAt, long expiresAt) {
            targetId=id; latitude=lat; longitude=lng; coordinateDatum=datum; countryRegion=country;
            authorityRevision=revision; navigationIssuedAt=issuedAt; navigationExpiresAt=expiresAt;
        }
        public String getNamespace() { return "roam_poi"; }
        public long getTargetId() { return targetId; }
        public BigDecimal getLatitude() { return latitude; }
        public BigDecimal getLongitude() { return longitude; }
        public CoordinateDatum getCoordinateDatum() { return coordinateDatum; }
        public String getCountryRegion() { return countryRegion; }
        public String getPublicVisibility() { return "CURRENT_PUBLIC"; }
        public String getAuthorityRevision() { return authorityRevision; }
        /** Epoch milliseconds from the server Clock, not a client fetch timestamp. */
        public long getNavigationIssuedAt() { return navigationIssuedAt; }
        public long getNavigationExpiresAt() { return navigationExpiresAt; }
    }

    public static final class Unavailable extends IllegalStateException {
        private final Failure failure;
        public Unavailable(Failure failure) { super(failure.name()); this.failure=failure; }
        public Failure getFailure() { return failure; }
    }
}
