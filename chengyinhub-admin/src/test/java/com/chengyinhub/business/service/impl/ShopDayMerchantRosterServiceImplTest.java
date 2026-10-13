package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.domain.dto.ShopDayMerchantRosterFact;
import com.chengyinhub.business.mapper.ShopDayMerchantRosterMapper;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.common.config.ComplianceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopDayMerchantRosterServiceImplTest {

    @Test
    void twoArgumentOverloadFailsClosedWithoutPhoneButStillEnforcesConsent() {
        Fixture f = fixture();
        when(f.mapper.countLiveMerchantScope(23L, 2001L)).thenReturn(1);
        when(f.mapper.selectRoster(23L, 2001L))
                .thenReturn(Arrays.asList(fact(301L, 9001L, null)));

        Map<String, Object> out = f.service.list(2001L, 23L);

        assertNull(rows(out).get(0).get("phone"));
        assertEquals("HIDDEN_BY_PERMISSION", rows(out).get(0).get("consentState"));
        verify(f.consent).latest(9001L, "merchant_onsite_data_sharing", "merchant_redeem",
                "MERCHANT", 2001L);
    }

    @Test
    void ownStoreArrivalWithoutConsentShowsOnlyWhitelistedNonPhoneFields() {
        Fixture f = fixture();
        when(f.mapper.countLiveMerchantScope(23L, 2001L)).thenReturn(1);
        when(f.mapper.selectRoster(23L, 2001L)).thenReturn(Arrays.asList(fact(301L, 9001L, null)));
        when(f.consent.latest(9001L, "merchant_onsite_data_sharing", "merchant_redeem",
                "MERCHANT", 2001L)).thenReturn(null);

        Map<String, Object> out = f.service.list(2001L, 23L, true);
        List<Map<String, Object>> rows = rows(out);

        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals("到店玩家", row.get("name"));
        assertNull(row.get("phone"));
        assertEquals("NONE", row.get("consentState"));
        assertFalse(row.containsKey("memberId"));
        assertFalse(row.containsKey("totalAmount"));
        assertEquals(10, row.size());
    }

    @Test
    void consentIsScopedToOneMerchantAndExplicitRevokeRemovesWholeRow() {
        Fixture f = fixture();
        ShopDayMerchantRosterFact fact = fact(301L, 9001L, new Date(2000L));
        when(f.mapper.countLiveMerchantScope(23L, 2001L)).thenReturn(1);
        when(f.mapper.countLiveMerchantScope(23L, 2002L)).thenReturn(1);
        when(f.mapper.selectRoster(23L, 2001L)).thenReturn(Arrays.asList(fact));
        when(f.mapper.selectRoster(23L, 2002L)).thenReturn(Arrays.asList(fact));
        when(f.consent.latest(9001L, "merchant_onsite_data_sharing", "merchant_redeem",
                "MERCHANT", 2001L)).thenReturn(consent("AGREE", "v1.0", 2001L));
        when(f.consent.latest(9001L, "merchant_onsite_data_sharing", "merchant_redeem",
                "MERCHANT", 2002L)).thenReturn(null);

        assertEquals("13800138000", rows(f.service.list(2001L, 23L, true)).get(0).get("phone"));
        assertNull(rows(f.service.list(2002L, 23L, true)).get(0).get("phone"));

        when(f.consent.latest(9001L, "merchant_onsite_data_sharing", "merchant_redeem",
                "MERCHANT", 2001L)).thenReturn(consent("REVOKE", "v1.0", 2001L));
        assertTrue(rows(f.service.list(2001L, 23L, true)).isEmpty());
    }

    @Test
    void staleConsentOrReadFailureFailsClosedAndForeignMerchantScopeCannotRead() {
        Fixture f = fixture();
        when(f.mapper.countLiveMerchantScope(23L, 2001L)).thenReturn(1);
        when(f.mapper.selectRoster(23L, 2001L)).thenReturn(Arrays.asList(fact(301L, 9001L, null)));
        when(f.consent.latest(9001L, "merchant_onsite_data_sharing", "merchant_redeem",
                "MERCHANT", 2001L)).thenThrow(new RuntimeException("db unavailable"));

        assertTrue(rows(f.service.list(2001L, 23L, true)).isEmpty(),
                "同意真源读取失败时整行必须 fail-closed");
        assertThrows(RuntimeException.class, () -> f.service.list(2002L, 23L, true));
    }

    @Test
    void roleWithoutSensitivePermissionStillHonorsPlayerRevocation() {
        Fixture f = fixture();
        when(f.mapper.countLiveMerchantScope(23L, 2001L)).thenReturn(1);
        when(f.mapper.selectRoster(23L, 2001L))
                .thenReturn(Arrays.asList(fact(301L, 9001L, null)));
        when(f.consent.latest(9001L, "merchant_onsite_data_sharing", "merchant_redeem",
                "MERCHANT", 2001L)).thenReturn(consent("REVOKE", "v1.0", 2001L));

        Map<String, Object> out = f.service.list(2001L, 23L, false);

        assertTrue(rows(out).isEmpty(), "撤回同意必须删除整行，不能因角色无手机号权限而跳过同意真源");
        verify(f.consent).latest(9001L, "merchant_onsite_data_sharing", "merchant_redeem",
                "MERCHANT", 2001L);
    }

    private static Fixture fixture() {
        Fixture f = new Fixture();
        f.mapper = mock(ShopDayMerchantRosterMapper.class);
        f.consent = mock(IConsentRecordService.class);
        f.service = new ShopDayMerchantRosterServiceImpl();
        ReflectionTestUtils.setField(f.service, "rosterMapper", f.mapper);
        ReflectionTestUtils.setField(f.service, "consentRecordService", f.consent);
        ComplianceProperties props = new ComplianceProperties();
        Map<String, String> versions = new HashMap<>();
        versions.put("merchant_onsite_data_sharing", "v1.0");
        props.setDocVersions(versions);
        ReflectionTestUtils.setField(f.service, "complianceProperties", props);
        return f;
    }

    private static ShopDayMerchantRosterFact fact(Long registrationId, Long memberId, Date serviceStartedAt) {
        ShopDayMerchantRosterFact fact = new ShopDayMerchantRosterFact();
        fact.setRegistrationId(registrationId);
        fact.setMemberId(memberId);
        fact.setTicketNo("TD-301");
        fact.setRealName("到店玩家");
        fact.setAvatar("avatar.png");
        fact.setPhone("13800138000");
        fact.setEntitlementStatus("ARRIVED");
        fact.setCheckinAt(new Date(1000L));
        fact.setServiceStartedAt(serviceStartedAt);
        return fact;
    }

    private static ConsentRecord consent(String eventType, String version, Long merchantId) {
        ConsentRecord record = new ConsentRecord();
        record.setEventType(eventType);
        record.setDocVersion(version);
        record.setScopeType("MERCHANT");
        record.setScopeId(merchantId);
        return record;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("rows");
    }

    private static final class Fixture {
        private ShopDayMerchantRosterMapper mapper;
        private IConsentRecordService consent;
        private ShopDayMerchantRosterServiceImpl service;
    }
}
