package com.chengyinhub.business.navigation;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import com.chengyinhub.business.navigation.WalkingTargetContracts.*;

/** Validation boundary for a future trusted, reviewed dataset importer. Does not approve or write. */
public final class NavigationProvenanceValidator {
    private static final Set<String> COUNTRIES = new HashSet<>(Arrays.asList(Locale.getISOCountries()));
    private NavigationProvenanceValidator() { }

    public static CoordinateDatum requireReviewed(Provenance row, long serverNow) {
        if (row == null || row.poiId == null || row.poiId <= 0
                || row.provenanceRevision == null || row.provenanceRevision <= 0
                || !"REVIEWED".equals(row.reviewState)
                || !coordinate(row.latitude, 90) || !coordinate(row.longitude, 180)
                || !COUNTRIES.contains(row.countryRegion)
                || !("PROVIDER_DATASET".equals(row.sourceKind) || "SURVEY_RECORD".equals(row.sourceKind))
                || !reference(row.sourceReference) || !reference(row.sourceRevision)
                || !reference(row.reviewReference) || row.reviewedAt == null
                || row.reviewedAt <= 0 || row.reviewedAt > serverNow) {
            throw new Unavailable(Failure.PROVENANCE_UNAVAILABLE);
        }
        try { return CoordinateDatum.valueOf(row.coordinateDatum); }
        catch (IllegalArgumentException | NullPointerException invalid) {
            throw new Unavailable(Failure.PROVENANCE_UNAVAILABLE);
        }
    }

    /** Staging accepts unknown source fields as null, never substitutes a guessed value. */
    public static void requireStageable(Provenance row) {
        if (row == null || row.poiId == null || row.poiId <= 0
                || !coordinate(row.latitude,90) || !coordinate(row.longitude,180)
                || (row.countryRegion != null && !COUNTRIES.contains(row.countryRegion))
                || (row.coordinateDatum != null && !"WGS84".equals(row.coordinateDatum) && !"GCJ02".equals(row.coordinateDatum))
                || (row.sourceKind != null && !"PROVIDER_DATASET".equals(row.sourceKind) && !"SURVEY_RECORD".equals(row.sourceKind))
                || (row.sourceReference != null && !reference(row.sourceReference))
                || (row.sourceRevision != null && !reference(row.sourceRevision))) {
            throw new Unavailable(Failure.PROVENANCE_UNAVAILABLE);
        }
    }

    static boolean coordinate(BigDecimal value, int bound) {
        return value != null && value.stripTrailingZeros().scale() <= 7
                && value.abs().compareTo(BigDecimal.valueOf(bound)) <= 0;
    }
    private static boolean reference(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,191}");
    }
}
