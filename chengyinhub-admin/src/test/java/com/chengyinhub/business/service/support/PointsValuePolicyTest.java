package com.chengyinhub.business.service.support;

import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * owner 2026-10-23 D1(审计 P0-01):报名与积分商城读同一份积分汇率与单笔上限;
 * 缺省值 = 报名侧原常量(0.02 元/分、50 元/单),落新 jar 后报名价格不变。
 */
class PointsValuePolicyTest {

    private static PointsValuePolicy policy(String rate, String max) {
        ISysConfigService config = mock(ISysConfigService.class);
        when(config.selectConfigByKey(PointsValuePolicy.CONFIG_RATE)).thenReturn(rate);
        when(config.selectConfigByKey(PointsValuePolicy.CONFIG_MAX_PER_ORDER)).thenReturn(max);
        PointsValuePolicy policy = new PointsValuePolicy();
        ReflectionTestUtils.setField(policy, "configService", config);
        return policy;
    }

    @Test
    void defaultsEqualTheSignupSideConstantsWhenKeysAreMissing() {
        for (PointsValuePolicy p : new PointsValuePolicy[] {
                PointsValuePolicy.codeDefaults(), policy("", ""), policy(null, null)}) {
            assertEquals(0, new BigDecimal("0.02").compareTo(p.rate()));
            assertEquals(0, new BigDecimal("50.00").compareTo(p.maxPerOrder()));
        }
    }

    @Test
    void invalidOrNonPositiveConfigFallsBackToDefaultsInsteadOfFreeOrUncapped() {
        for (String bad : new String[] {"abc", "0", "-0.01", " ", "1e", "NaN"}) {
            PointsValuePolicy p = policy(bad, bad);
            assertEquals(0, new BigDecimal("0.02").compareTo(p.rate()), bad);
            assertEquals(0, new BigDecimal("50.00").compareTo(p.maxPerOrder()), bad);
        }
    }

    @Test
    void configuredValuesAreUsed() {
        PointsValuePolicy p = policy(" 0.01 ", "30");
        assertEquals(0, new BigDecimal("0.01").compareTo(p.rate()));
        assertEquals(0, new BigDecimal("30").compareTo(p.maxPerOrder()));
        assertEquals(new BigDecimal("12.35"), p.yuanOf(1235));
    }

    @Test
    void mallCapAtDefaultsIs2500PointsPerOrder() {
        PointsValuePolicy p = PointsValuePolicy.codeDefaults();
        p.requireMallOrderWithinCap(0);
        p.requireMallOrderWithinCap(2500);
        ServiceException e = assertThrows(ServiceException.class, () -> p.requireMallOrderWithinCap(2501));
        assertEquals("单笔订单最多使用 2500 积分(折合 ¥50.00)", e.getMessage());
    }

    @Test
    void mallCapFollowsTheSameConfigAsSignup() {
        PointsValuePolicy p = policy("0.01", "30");
        p.requireMallOrderWithinCap(3000);
        assertThrows(ServiceException.class, () -> p.requireMallOrderWithinCap(3001));
    }

    // ---- 报名侧:同一 policy 驱动 computePrice,缺省时与改造前逐分一致 ----

    private static Object price(PointsValuePolicy policy, String ticketPrice, long userPoint) {
        CmsRegistrationServiceImpl service = new CmsRegistrationServiceImpl();
        if (policy != null) ReflectionTestUtils.setField(service, "pointsValuePolicy", policy);
        return ReflectionTestUtils.invokeMethod(service, "computePrice",
                new BigDecimal(ticketPrice), 1, userPoint, Integer.valueOf(1));
    }

    private static BigDecimal field(Object calc, String name) {
        return (BigDecimal) ReflectionTestUtils.getField(calc, name);
    }

    @Test
    void signupPriceIsUnchangedAtDefaults() {
        // 100 元票、10000 积分:旧逻辑 = 抵 50 元(上限)、用 2500 分、实付 50 元
        Object calc = price(null, "100.00", 10_000L);
        assertEquals(0, new BigDecimal("50.00").compareTo(field(calc, "pointDeductAmount")));
        assertEquals(2500, ReflectionTestUtils.getField(calc, "pointUsed"));
        assertEquals(0, new BigDecimal("50.00").compareTo(field(calc, "payableAmount")));
        assertEquals(0, new BigDecimal("0.02").compareTo(field(calc, "pointDeductRate")));
        // 30 元票、500 积分:抵 10 元、用 500 分
        calc = price(null, "30.00", 500L);
        assertEquals(0, new BigDecimal("10.00").compareTo(field(calc, "pointDeductAmount")));
        assertEquals(500, ReflectionTestUtils.getField(calc, "pointUsed"));
    }

    @Test
    void signupReadsTheSharedConfig() {
        Object calc = price(policy("0.01", "30"), "100.00", 10_000L);
        assertEquals(0, new BigDecimal("30").compareTo(field(calc, "pointDeductAmount")));
        assertEquals(3000, ReflectionTestUtils.getField(calc, "pointUsed"));
        assertEquals(0, new BigDecimal("0.01").compareTo(field(calc, "pointDeductRate")));
    }

    // ---- 落种迁移:值 = 代码缺省,只在缺失时插入 ----

    private static final Pattern SEED_ROW = Pattern.compile(
            "select\\s+'[^']*',\\s*'([^']+)',\\s*'([^']*)',\\s*'n'", Pattern.CASE_INSENSITIVE);

    @Test
    void seedMigrationSeedsBothKeysWithCurrentDefaultsIdempotently() throws Exception {
        String sql = new String(Files.readAllBytes(Paths.get("sql",
                "migration_20261023_points_value_config_seed.sql")), StandardCharsets.UTF_8);
        String flat = sql.toLowerCase().replaceAll("\\s+", " ");
        Map<String, String> seeded = new HashMap<>();
        Matcher m = SEED_ROW.matcher(sql);
        while (m.find()) seeded.put(m.group(1), m.group(2));

        assertEquals(2, seeded.size(), seeded.toString());
        assertEquals(0, PointsValuePolicy.DEFAULT_RATE.compareTo(new BigDecimal(seeded.get(PointsValuePolicy.CONFIG_RATE))));
        assertEquals(0, PointsValuePolicy.DEFAULT_MAX_PER_ORDER.compareTo(
                new BigDecimal(seeded.get(PointsValuePolicy.CONFIG_MAX_PER_ORDER))));
        for (String key : seeded.keySet()) {
            assertTrue(flat.contains("where not exists (select 1 from sys_config where config_key = '" + key + "')"), key);
        }
        assertFalse(flat.matches(".*\\bupdate\\s+sys_config\\b.*"));
        assertFalse(flat.matches(".*\\bdelete\\s+from\\b.*"));
        assertTrue(flat.contains("runner: assert=scalar-true"));
    }
}
