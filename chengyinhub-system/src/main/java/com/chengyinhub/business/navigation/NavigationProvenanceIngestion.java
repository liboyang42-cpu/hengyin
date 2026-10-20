package com.chengyinhub.business.navigation;

import com.chengyinhub.business.mapper.WalkingTargetMapper;
import com.chengyinhub.business.navigation.WalkingTargetContracts.*;
import java.util.Objects;

/**
 * Bounded durable staging for a future authorized dataset importer, not a public API or bean.
 * It cannot review/promote evidence. The SQL binds the exact current city-POI coordinates and
 * inserts UNREVIEWED regardless of caller review fields. Repeated IDs fail rather than overwrite.
 */
public final class NavigationProvenanceIngestion {
    private final WalkingTargetMapper mapper;
    public NavigationProvenanceIngestion(WalkingTargetMapper mapper) { this.mapper=Objects.requireNonNull(mapper); }
    public void stage(Provenance candidate) {
        NavigationProvenanceValidator.requireStageable(candidate);
        if (mapper.insertUnreviewed(candidate) != 1) throw new Unavailable(Failure.COORDINATES_CHANGED);
    }
}
