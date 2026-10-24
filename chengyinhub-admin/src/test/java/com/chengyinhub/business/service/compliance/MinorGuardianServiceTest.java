package com.chengyinhub.business.service.compliance;

import com.chengyinhub.business.domain.MinorGuardianAuthorization;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.MinorGuardianAuthorizationMapper;
import com.chengyinhub.business.service.IMinorProtectionService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.impl.MinorGuardianServiceImpl;
import com.chengyinhub.business.service.minor.AgeBand;
import com.chengyinhub.business.service.minor.MinorGuardianException;
import com.chengyinhub.business.service.minor.MinorPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** DOC2 F7 监护授权：双方本人账号确认、用途/政策版本/有效期绑定、撤回归属。 */
class MinorGuardianServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);
    private static final Instant NOW = ZonedDateTime.of(TODAY.atTime(10, 0), MinorPolicy.ZONE).toInstant();
    private static final long PARENT = 1L, KID = 2L, TEEN = 3L, ADULT = 4L, STRANGER = 9L;
    private static final String V = MinorPolicy.POLICY_VERSION;

    private MinorGuardianAuthorizationMapper mapper;
    private IMinorProtectionService protection;
    private IUmsMemberService members;
    private MinorGuardianServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(MinorGuardianAuthorizationMapper.class);
        protection = mock(IMinorProtectionService.class);
        members = mock(IUmsMemberService.class);
        service = new MinorGuardianServiceImpl(mapper, protection, members);
        service.setClock(Clock.fixed(NOW, MinorPolicy.ZONE));
        when(protection.ageBand(PARENT)).thenReturn(AgeBand.ADULT);
        when(protection.ageBand(ADULT)).thenReturn(AgeBand.ADULT);
        when(protection.ageBand(KID)).thenReturn(AgeBand.AGE_8_15);
        when(protection.ageBand(TEEN)).thenReturn(AgeBand.AGE_16_17);
        when(protection.ageBand(STRANGER)).thenReturn(AgeBand.UNKNOWN);
        when(mapper.insert(any())).thenAnswer(inv -> {
            ((MinorGuardianAuthorization) inv.getArgument(0)).setId(10L);
            return 1;
        });
    }

    @Test
    void guardianMustBeAnAdultAccountAndBindPurposeVersionAndValidity() {
        assertCode(MinorGuardianException.GUARDIAN_NOT_ADULT,
                () -> service.createInvite(KID, "r1", "PURCHASE", "PARENT", V, 30));
        assertCode(MinorGuardianException.GUARDIAN_NOT_ADULT,
                () -> service.createInvite(STRANGER, "r1", "PURCHASE", "PARENT", V, 30));
        assertCode(MinorGuardianException.POLICY_VERSION_MISMATCH,
                () -> service.createInvite(PARENT, "r1", "PURCHASE", "PARENT", "minor-old", 30));
        assertCode(MinorGuardianException.PURPOSE_INVALID,
                () -> service.createInvite(PARENT, "r1", "ALL", "PARENT", V, 30));
        assertCode(MinorGuardianException.RELATION_INVALID,
                () -> service.createInvite(PARENT, "r1", "PURCHASE", "FRIEND", V, 30));
        assertCode(MinorGuardianException.ARGUMENT_INVALID,
                () -> service.createInvite(PARENT, "r1", "PURCHASE", "PARENT", V, 366));
        verify(mapper, never()).insert(any());

        Map<String, Object> view = service.createInvite(PARENT, "r1", "PURCHASE", "PARENT", V, 30);

        ArgumentCaptor<MinorGuardianAuthorization> row = ArgumentCaptor.forClass(MinorGuardianAuthorization.class);
        verify(mapper).insert(row.capture());
        String token = (String) view.get("inviteToken");
        assertNotNull(token);
        assertEquals(sha256(token), row.getValue().getInviteTokenHash(), "库里只存邀请码摘要");
        assertNotEquals(token, row.getValue().getInviteTokenHash());
        assertEquals("PURCHASE", row.getValue().getPurpose());
        assertEquals(V, row.getValue().getPolicyVersion());
        assertEquals("MUTUAL_ACCOUNT_CONFIRM", row.getValue().getEvidenceMethod());
        assertEquals("PENDING", row.getValue().getStatus());
        assertEquals(30, row.getValue().getValidDays());
        assertFalse(view.containsKey("inviteTokenHash"));
    }

    @Test
    void replayWhilePendingRotatesTheInviteAndMismatchedReplayIsRejected() {
        MinorGuardianAuthorization pending = row("PENDING", "PURCHASE");
        when(mapper.selectByRequest(PARENT, "r1")).thenReturn(pending);
        when(mapper.rotateInvite(eq(10L), eq(PARENT), anyString(), any(), any())).thenReturn(1);
        when(mapper.selectById(10L)).thenReturn(pending);

        Map<String, Object> view = service.createInvite(PARENT, "r1", "PURCHASE", "PARENT", V, 30);
        assertNotNull(view.get("inviteToken"));
        verify(mapper, never()).insert(any());

        assertCode(MinorGuardianException.ARGUMENT_INVALID,
                () -> service.createInvite(PARENT, "r1", "INFO_PROCESS", "PARENT", V, 30));
    }

    @Test
    void wardConfirmsWithTheirOwnAccountAndValidityStopsAtEighteen() {
        MinorGuardianAuthorization pending = row("PENDING", "PURCHASE");
        pending.setValidDays(365);
        when(mapper.selectByInviteHashForUpdate(sha256("tok"))).thenReturn(pending);
        when(mapper.activate(eq(10L), eq(sha256("tok")), eq(TEEN), any(), any(), any())).thenReturn(1);
        when(mapper.selectById(10L)).thenReturn(pending);
        UmsMember teen = new UmsMember();
        teen.setBirthday(java.sql.Date.valueOf(TODAY.minusYears(18).plusDays(40)));
        when(members.selectUmsMemberById(TEEN)).thenReturn(teen);

        service.accept(TEEN, "tok", V);

        ArgumentCaptor<Date> until = ArgumentCaptor.forClass(Date.class);
        verify(mapper).activate(eq(10L), eq(sha256("tok")), eq(TEEN), any(), until.capture(), any());
        Instant adulthood = TODAY.plusDays(40).atStartOfDay(MinorPolicy.ZONE).toInstant();
        assertEquals(adulthood, until.getValue().toInstant(), "有效期不晚于满 18 周岁");
        verify(mapper).supersedeActive(TEEN, PARENT, "PURCHASE", 10L, Date.from(NOW));
    }

    @Test
    void acceptRefusesSelfAdultsUnknownAgeExpiredAndWrongPurposeForAge() {
        MinorGuardianAuthorization pending = row("PENDING", "PURCHASE");
        when(mapper.selectByInviteHashForUpdate(sha256("tok"))).thenReturn(pending);

        assertCode(MinorGuardianException.SELF_GUARDIAN, () -> service.accept(PARENT, "tok", V));
        assertCode(MinorGuardianException.WARD_NOT_MINOR, () -> service.accept(ADULT, "tok", V));
        assertCode(MinorGuardianException.WARD_AGE_UNKNOWN, () -> service.accept(STRANGER, "tok", V));
        assertCode(MinorGuardianException.POLICY_VERSION_MISMATCH, () -> service.accept(KID, "tok", "minor-old"));
        assertCode(MinorGuardianException.INVITE_INVALID, () -> service.accept(KID, "other", V));

        MinorGuardianAuthorization solo = row("PENDING", "SOLO_DAYTIME_PLAY");
        when(mapper.selectByInviteHashForUpdate(sha256("solo"))).thenReturn(solo);
        assertCode(MinorGuardianException.PURPOSE_INVALID, () -> service.accept(KID, "solo", V));

        MinorGuardianAuthorization expired = row("PENDING", "PURCHASE");
        expired.setInviteExpiresAt(Date.from(NOW.minusSeconds(1)));
        when(mapper.selectByInviteHashForUpdate(sha256("old"))).thenReturn(expired);
        assertCode(MinorGuardianException.INVITE_INVALID, () -> service.accept(KID, "old", V));

        MinorGuardianAuthorization oldPolicy = row("PENDING", "PURCHASE");
        oldPolicy.setPolicyVersion("minor-old");
        when(mapper.selectByInviteHashForUpdate(sha256("v0"))).thenReturn(oldPolicy);
        assertCode(MinorGuardianException.POLICY_VERSION_MISMATCH, () -> service.accept(KID, "v0", V));

        verify(mapper, never()).activate(anyLong(), anyString(), anyLong(), any(), any(), any());
    }

    @Test
    void onlyTheGuardianOrTheWardCanRevoke() {
        MinorGuardianAuthorization active = row("ACTIVE", "PURCHASE");
        active.setWardMemberId(KID);
        when(mapper.selectById(10L)).thenReturn(active);

        assertCode(MinorGuardianException.NOT_FOUND, () -> service.revoke(STRANGER, 10L));
        verify(mapper, never()).revoke(anyLong(), anyLong(), any());

        when(mapper.revoke(eq(10L), eq(KID), any())).thenReturn(1);
        service.revoke(KID, 10L);
        when(mapper.revoke(eq(10L), eq(PARENT), any())).thenReturn(0);
        assertCode(MinorGuardianException.STATE_CONFLICT, () -> service.revoke(PARENT, 10L));
    }

    @Test
    void policyExposesTheVersionThatConsentBindsTo() {
        Map<String, Object> policy = service.policy();
        assertEquals(V, policy.get("policyVersion"));
        assertTrue(String.valueOf(policy.get("purposes")).contains("INFO_PROCESS"));
    }

    private static MinorGuardianAuthorization row(String status, String purpose) {
        MinorGuardianAuthorization r = new MinorGuardianAuthorization();
        r.setId(10L);
        r.setGuardianMemberId(PARENT);
        r.setPurpose(purpose);
        r.setRelation("PARENT");
        r.setPolicyVersion(V);
        r.setStatus(status);
        r.setValidDays(30);
        r.setInviteExpiresAt(Date.from(NOW.plusSeconds(3600)));
        return r;
    }

    private static String sha256(String v) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertCode(String code, org.junit.jupiter.api.function.Executable call) {
        MinorGuardianException e = assertThrows(MinorGuardianException.class, call);
        assertEquals(code, e.getErrorCode(), e.getMessage());
    }
}
