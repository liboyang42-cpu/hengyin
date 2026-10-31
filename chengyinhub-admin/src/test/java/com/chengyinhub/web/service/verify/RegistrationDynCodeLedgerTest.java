package com.chengyinhub.web.service.verify;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.QrcodeServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import com.chengyinhub.web.service.registration.RegistrationRedemptionApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** V-02 走查:签发新动态入场码即作废同报名单同类型的旧码;只读回执与无记录的码保持原行为。 */
class RegistrationDynCodeLedgerTest {
    private static final String SECRET = "dyncode-ledger-fixture-signing-value";

    /** 内存版 RedisCache,只覆盖本台账用到的两个方法。 */
    static class MemoryRedis extends RedisCache {
        final Map<String, Object> store = new HashMap<>();
        final Map<String, Long> ttl = new HashMap<>();
        boolean broken;
        @Override public <T> void setCacheObject(String key, T value, Integer timeout, TimeUnit unit) {
            if (broken) throw new IllegalStateException("redis down");
            store.put(key, value); ttl.put(key, unit.toMillis(timeout));
        }
        @SuppressWarnings("unchecked")
        @Override public <T> T getCacheObject(String key) {
            if (broken) throw new IllegalStateException("redis down");
            return (T) store.get(key);
        }
    }

    private MemoryRedis redis;
    private RegistrationDynCodeLedger ledger;
    private RegistrationTicketApplication issuer;
    private QrcodeServiceImpl qr;
    private RegistrationRedemptionApplication scanner;

    @BeforeEach void setup() {
        redis = new MemoryRedis();
        ledger = new RegistrationDynCodeLedger(redis);
        ICmsRegistrationService orders = mock(ICmsRegistrationService.class);
        ICmsActivityService activities = mock(ICmsActivityService.class);
        qr = mock(QrcodeServiceImpl.class);
        issuer = new RegistrationTicketApplication(orders, mock(RegistrationSeatService.class), activities,
                mock(IPerkLedgerService.class), qr, SECRET, ledger);
        CmsRegistration order = new CmsRegistration(); order.setId(41L); order.setMemberId(51L); order.setOwnerType(2L);
        order.setOwnerId(61L); order.setPaymentStatus(2); order.setRegistrationStatus(2); order.setOrderNum(1L); order.setPurchaseKind(1);
        when(orders.selectCmsRegistrationById(41L)).thenReturn(order);
        CmsActivity activity = new CmsActivity(); activity.setId(61L); activity.setDelFlag(0);
        when(activities.selectCmsActivityById(61L)).thenReturn(activity);
        when(qr.generateInlineQrcode(anyString(), eq(300), eq(300))).thenReturn("fixture-inline-png");
        scanner = new RegistrationRedemptionApplication();
        ReflectionTestUtils.setField(scanner, "dynSecret", SECRET);
        ReflectionTestUtils.setField(scanner, "dynCodeLedger", ledger);
    }

    private String issue() {
        AjaxResult response = issuer.issueDynCode(51L, 41L, null);
        assertEquals(200, response.get("code"));
        return (String) ((Map<?, ?>) response.get("data")).get("code");
    }

    private VerifyDynCodeUtil.Result current(String code) {
        return ReflectionTestUtils.invokeMethod(scanner, "rejectSuperseded",
                VerifyDynCodeUtil.verify(code, System.currentTimeMillis(), SECRET), code);
    }

    @Test void newCodeInvalidatesThePreviousOneOnTheScanSide() {
        String first = issue();
        assertTrue(current(first).valid, "刚签发的码可用");
        String second = issue();
        assertNotEquals(first, second);
        assertTrue(current(second).valid, "最新码可用");
        VerifyDynCodeUtil.Result old = current(first);
        assertFalse(old.valid);
        assertEquals(RegistrationDynCodeLedger.SUPERSEDED, old.reason);
        AjaxResult scanned = scanner.scanDynamicCode(new RegistrationRedemptionApplication.Actor(71L, false), first);
        assertEquals(500, scanned.get("code"));
        assertEquals(RegistrationDynCodeLedger.SUPERSEDED, scanned.get("msg"));
        assertTrue(VerifyDynCodeUtil.verifyForReceipt(first, SECRET).valid, "只读回执仍能认出旧码身份");
    }

    @Test void latestRecordLivesExactlyAsLongAsTheCode() {
        issue();
        assertEquals(1, redis.store.size());
        assertEquals(RegistrationTicketApplication.DYN_TTL_MS, redis.ttl.values().iterator().next());
        assertTrue(redis.store.keySet().iterator().next().startsWith("verify:dyncode:latest:41:activity"));
    }

    @Test void failedQrRenderingKeepsTheOldCodeUsable() {
        String first = issue();
        when(qr.generateInlineQrcode(anyString(), eq(300), eq(300))).thenThrow(new IllegalStateException("png"));
        issuer.issueDynCode(51L, 41L, null);
        assertTrue(current(first).valid, "没把新码交到玩家手里就不作废旧码");
    }

    @Test void differentTypesAndUnrecordedCodesAreNotSuperseded() {
        long now = System.currentTimeMillis();
        String seatOne = VerifyDynCodeUtil.issue(41L, "activity_seat_1", now, 300_000L, 1L, SECRET);
        ledger.recordIssued(seatOne, 300_000L);
        ledger.recordIssued(VerifyDynCodeUtil.issue(41L, "activity_seat_2", now, 300_000L, 2L, SECRET), 300_000L);
        assertFalse(ledger.superseded(seatOne), "别的席位出新码不影响本席位");
        assertFalse(ledger.superseded(VerifyDynCodeUtil.issue(42L, "activity", now, 300_000L, 3L, SECRET)), "无记录按原逻辑放行");
        assertFalse(ledger.superseded("legacy-static-code"));
    }

    @Test void redisOutageKeepsTheOriginalStatelessBehaviour() {
        redis.broken = true;
        String code = issue();
        assertTrue(current(code).valid);
    }
}
