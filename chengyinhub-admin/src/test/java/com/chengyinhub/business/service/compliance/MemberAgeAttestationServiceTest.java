package com.chengyinhub.business.service.compliance;

import com.chengyinhub.business.mapper.MemberAgeAttestationMapper;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P1b SEC-14:年龄确认只记「已满 18 岁」与时间;未满 18 岁被拒且不落库。 */
class MemberAgeAttestationServiceTest {

    private final MemberAgeAttestationMapper mapper = mock(MemberAgeAttestationMapper.class);
    private final ISysConfigService config = mock(ISysConfigService.class);
    private final MemberAgeAttestationService service =
            new MemberAgeAttestationService(mapper, new AgeGatePolicy(config));

    @Test
    void under_18_is_refused_with_a_plain_message_and_nothing_is_stored() {
        MemberAgeAttestationService.UnderAgeException e = assertThrows(
                MemberAgeAttestationService.UnderAgeException.class,
                () -> service.confirm(7L, false, "sign_in", "1.2.3.4"));
        assertEquals(MemberAgeAttestationService.UNDER_AGE_MESSAGE, e.getMessage());
        verify(mapper, never()).insertIfAbsent(anyLong(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void adult_confirmation_is_stored_with_time_and_read_back() {
        Map<String, Object> row = new HashMap<>();
        Date at = new Date();
        row.put("confirmedAt", at);
        when(mapper.selectByMember(7L)).thenReturn(row);
        Map<String, Object> data = service.confirm(7L, true, "sign_in", "1.2.3.4");
        verify(mapper).insertIfAbsent(eq(7L), eq("adults"), any(Date.class), eq("sign_in"), eq("1.2.3.4"));
        assertEquals(true, data.get("confirmed"));
        assertEquals(at, data.get("confirmedAt"));
        assertEquals("adults", data.get("agePolicy"));
        assertEquals(18, data.get("minimumAge"));
    }

    @Test
    void unsaved_confirmation_is_reported_not_assumed() {
        when(mapper.selectByMember(7L)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> service.confirm(7L, true, null, null));
    }

    @Test
    void guardian_policy_is_reported_but_not_enforced_until_built() {
        when(config.selectConfigByKey(AgeGatePolicy.CONFIG_KEY)).thenReturn("guardian");
        Map<String, Object> data = service.status(7L);
        assertEquals("guardian", data.get("configuredPolicy"));
        assertEquals("adults", data.get("agePolicy"));
        assertEquals(false, data.get("guardianFlowAvailable"));
        assertEquals(false, data.get("confirmed"));
    }

    @Test
    void unknown_or_missing_policy_falls_back_to_adults() {
        assertEquals("adults", AgeGatePolicy.normalize(null));
        assertEquals("adults", AgeGatePolicy.normalize("teens"));
        assertEquals("guardian", AgeGatePolicy.normalize(" GUARDIAN "));
        when(config.selectConfigByKey(AgeGatePolicy.CONFIG_KEY)).thenThrow(new IllegalStateException("down"));
        assertEquals("adults", new AgeGatePolicy(config).configured());
    }

    @Test
    void source_is_whitelisted_shape() {
        assertEquals("sign_in", MemberAgeAttestationService.normalizeSource(" SIGN_IN "));
        assertEquals("app", MemberAgeAttestationService.normalizeSource("<script>"));
        assertEquals("app", MemberAgeAttestationService.normalizeSource(null));
    }
}
