package com.chengyinhub.business.access.club;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ClubRoleGovernanceMetadataTest {
    @Test
    void displayLevelIsExplicitMetadataNotAuthorizationTruth() {
        ClubRoleGovernanceMetadata profile = ClubRoleGovernanceMetadata.forRole(ClubRoleCode.CLUB_CO_OWNER);

        assertEquals(ClubRoleCode.CLUB_CO_OWNER, profile.getRoleId());
        assertEquals(ClubRoleGovernanceMetadata.LEVEL_L3, profile.getPermissionLevel());
        assertFalse(profile.isAuthorizationSource());
    }
}
