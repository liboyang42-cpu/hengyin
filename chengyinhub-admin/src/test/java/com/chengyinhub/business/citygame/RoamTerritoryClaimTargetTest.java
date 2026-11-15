package com.chengyinhub.business.citygame;

import com.chengyinhub.business.mapper.CityGameMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** MED-S8: a legacy claim with no POI or board target must not store the claimer's own GPS fix. */
class RoamTerritoryClaimTargetTest
{
    @Test
    void claimWithoutResolvedTargetIsRejectedAndNothingIsStored()
    {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RoamTerritoryService service = new RoamTerritoryService(jdbc, mock(CityGameMapper.class), null,
                () -> 1_700_000_000_000L);

        CityGameException ex = assertThrows(CityGameException.class, () -> service.claim(42L, Map.of(
                "requestId", "claim-home-1", "lat", 31.2304, "lng", 121.4737, "accuracyM", 10.0,
                "photoUrl", "https://example.com/p.jpg")));

        assertEquals("TERRITORY_TARGET_REQUIRED", ex.getErrorCode());
        verify(jdbc, never()).update(contains("INSERT INTO roam_territory "), any(Object[].class));
        verify(jdbc, never()).update(startsWith("INSERT INTO roam_territory("), any(Object[].class));
    }
}
