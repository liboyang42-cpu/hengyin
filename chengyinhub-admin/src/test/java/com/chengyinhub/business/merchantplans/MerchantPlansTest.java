package com.chengyinhub.business.merchantplans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.mapper.SettlementRuleMapper;
import com.chengyinhub.business.merchantgap.MerchantGapException;
import com.chengyinhub.business.merchantproof.MerchantWeeklyProof;
import com.chengyinhub.business.merchantproof.MerchantWeeklyProofService;
import com.chengyinhub.business.moneyswitch.MoneySwitchService;
import com.chengyinhub.business.moneyswitch.mapper.MoneySwitchMapper;
import com.chengyinhub.business.opsconsole.OpsConsoleAudit;

/** S3a merchant-plans: defaults unchanged, settings audited, money paths closed while the switch is closed. */
class MerchantPlansTest {

    // ------------------------------------------------------------------ fixtures

    private static MerchantPlansMoneyGate gate(boolean open) {
        MoneySwitchMapper mapper = mock(MoneySwitchMapper.class);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (open) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("area", "merchantMoney");
            row.put("enabled", 1);
            rows.add(row);
        }
        when(mapper.selectAll()).thenReturn(rows);
        return new MerchantPlansMoneyGate(new MoneySwitchService(mapper, open));
    }

    private static SettlementRule rule(long merchant, String bizType, String platform, String merchantShare) {
        SettlementRule r = new SettlementRule();
        r.setMerchantId(merchant);
        r.setBizType(bizType);
        r.setPlatformCommissionRate(new BigDecimal(platform));
        r.setMerchantRate(new BigDecimal(merchantShare));
        return r;
    }

    private static Map<String, Object> takeRow(String product, String source, int bps) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("product_type", product);
        m.put("source", source);
        m.put("rate_bps", bps);
        return m;
    }

    private static TakeRateService takes(JdbcTemplate jdbc, SettlementRuleMapper rules, OpsConsoleAudit audit) {
        return new TakeRateService(jdbc, rules, audit, 7000);
    }

    private static Map<String, Object> planRow(String code, int price, Integer visit, int offered) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name_zh", code);
        m.put("name_en", code);
        m.put("monthly_price_fen", price);
        m.put("visit_fee_fen", visit);
        m.put("features_json", "{\"weeklyProofDetail\":true,\"extraNodeGames\":2,\"dealsOffersPerMonth\":2}");
        m.put("offered", offered);
        m.put("sort_order", 0);
        return m;
    }

    private static JdbcTemplate planJdbc() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        List<Map<String, Object>> plans = List.of(planRow("FREE", 0, null, 1), planRow("BASIC", 19900, null, 1),
                planRow("PRO", 49900, null, 1), planRow("PER_VISIT", 0, 300, 1));
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.startsWith("SELECT code, name_zh"))).thenReturn(plans);
        return jdbc;
    }

    private static IMerchantAccessService owner() {
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(anyLong(), anyString())).thenReturn(new MerchantAccessContext(7L, 70L, 7L, "OWNER"));
        return access;
    }

    // ------------------------------------------------------------------ take rate: today's values are the defaults

    @Test
    void noConfiguredTakeLeavesEverySettlementRuleUnchanged() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        TakeRateService takes = takes(jdbc, mock(SettlementRuleMapper.class), mock(OpsConsoleAudit.class));
        for (String biz : List.of("topic", "topic_club", "activity", "merchant_goods")) {
            SettlementRule r = rule(0L, biz, "0.0300", "0.9700");
            assertSame(r, takes.applyTo(r));
            assertEquals(new BigDecimal("0.0300"), r.getPlatformCommissionRate(), biz);
            assertEquals(new BigDecimal("0.9700"), r.getMerchantRate(), biz);
        }
        assertEquals(new BigDecimal("0.0500"), takes.bundleRate(new BigDecimal("0.0500")));
        assertEquals(7000, takes.templateCreatorShareBps(7000));
        assertNull(takes.applyTo(null));
    }

    @Test
    void readFailureMeansTodaysValues() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenThrow(new RuntimeException("db down"));
        TakeRateService takes = takes(jdbc, mock(SettlementRuleMapper.class), mock(OpsConsoleAudit.class));
        SettlementRule r = rule(0L, "topic", "0.0300", "0.9700");
        takes.applyTo(r);
        assertEquals(new BigDecimal("0.0300"), r.getPlatformCommissionRate());
        assertEquals(7000, takes.templateCreatorShareBps(7000));
    }

    @Test
    void configuredTakeAppliesPerProductAndSourceAndKeepsTheSum() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(takeRow("official_ticket", "", 500),
                takeRow("third_party_ticket", "club", 800), takeRow("third_party_ticket", "", 600),
                takeRow("bundle", "", 400), takeRow("template", "", 2500)));
        TakeRateService takes = takes(jdbc, mock(SettlementRuleMapper.class), mock(OpsConsoleAudit.class));

        SettlementRule official = takes.applyTo(rule(0L, "topic", "0.0300", "0.9700"));
        assertEquals(0, new BigDecimal("0.0500").compareTo(official.getPlatformCommissionRate()));
        assertEquals(0, new BigDecimal("0.9500").compareTo(official.getMerchantRate()));

        SettlementRule club = takes.applyTo(rule(0L, "topic_club", "0.0300", "0.9700"));
        assertEquals(0, new BigDecimal("0.0800").compareTo(club.getPlatformCommissionRate()));
        // organiser has no own row: the product-wide row applies
        SettlementRule organiser = takes.applyTo(rule(0L, "activity", "0.0300", "0.9700"));
        assertEquals(0, new BigDecimal("0.0600").compareTo(organiser.getPlatformCommissionRate()));

        // negotiated per-merchant contracts are never touched
        SettlementRule contract = takes.applyTo(rule(42L, "topic", "0.0100", "0.9900"));
        assertEquals(new BigDecimal("0.0100"), contract.getPlatformCommissionRate());

        assertEquals(0, new BigDecimal("0.0400").compareTo(takes.bundleRate(new BigDecimal("0.0300"))));
        assertEquals(7500, takes.templateCreatorShareBps(7000));
    }

    @Test
    void aTakeThatWouldMakeTheMerchantShareNegativeIsIgnored() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(takeRow("official_ticket", "", 9000)));
        TakeRateService takes = takes(jdbc, mock(SettlementRuleMapper.class), mock(OpsConsoleAudit.class));
        SettlementRule r = takes.applyTo(rule(0L, "topic", "0.0300", "0.0500"));
        assertEquals(new BigDecimal("0.0300"), r.getPlatformCommissionRate());
    }

    @Test
    void settingATakeNeedsAReasonAndWritesOneAuditRow() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SettlementRuleMapper rules = mock(SettlementRuleMapper.class);
        when(rules.selectActiveRule(0L, "topic")).thenReturn(rule(0L, "topic", "0.0300", "0.9700"));
        when(jdbc.queryForList(anyString(), eq(Integer.class), any(), any())).thenReturn(List.of());
        OpsConsoleAudit audit = mock(OpsConsoleAudit.class);
        TakeRateService takes = takes(jdbc, rules, audit);

        assertThrows(IllegalArgumentException.class, () -> takes.set("official_ticket", "", 500, 1L, "ops", " "));
        assertThrows(IllegalArgumentException.class, () -> takes.set("official_ticket", "", 10001, 1L, "ops", "why"));
        assertThrows(IllegalArgumentException.class, () -> takes.set("season_pass", "", 500, 1L, "ops", "why"));
        assertThrows(IllegalArgumentException.class, () -> takes.set("third_party_ticket", "nobody", 500, 1L, "ops", "why"));
        verifyNoInteractions(audit);

        assertTrue(takes.set("official_ticket", "", 500, 1L, "ops", "S3a pilot"));
        verify(audit).record(eq(1L), eq("TAKE_RATE_SET"), eq("TAKE_RATE"), eq(0L), any(), any(), eq("S3a pilot"));
    }

    @Test
    void consoleViewShowsTodaysValueWhenNothingIsConfigured() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        SettlementRuleMapper rules = mock(SettlementRuleMapper.class);
        when(rules.selectActiveRule(0L, "topic")).thenReturn(rule(0L, "topic", "0.0300", "0.9700"));
        TakeRateService takes = takes(jdbc, rules, mock(OpsConsoleAudit.class));
        Map<String, Object> official = takes.view().stream().filter(r -> "official_ticket".equals(r.get("product")))
                .findFirst().orElseThrow();
        assertEquals(300, official.get("todayBps"));
        assertNull(official.get("configuredBps"));
        assertEquals(300, official.get("effectiveBps"));
        Map<String, Object> template = takes.view().stream().filter(r -> "template".equals(r.get("product")))
                .findFirst().orElseThrow();
        assertEquals(3000, template.get("effectiveBps"));
    }

    @Test
    void migrationStartsWithNoTakeRowsAndTouchesNoMoneySwitch() throws Exception {
        String sql = Files.readString(Path.of("sql/migration_20261115_merchant_plans.sql"), StandardCharsets.UTF_8);
        assertFalse(sql.contains("INSERT INTO cy_take_rate"), "take table must start empty");
        assertFalse(sql.matches("(?is).*(insert\\s+into|update|delete\\s+from|replace\\s+into)\\s+cy_money_switch.*"),
                "must not touch the money switch");
        assertTrue(sql.contains("'FREE', '免费版', 'Free', 0, NULL,\n       '{\"weeklyProofDetail\":false,\"extraNodeGames\":0,\"dealsOffersPerMonth\":0}'"));
    }

    // ------------------------------------------------------------------ plans

    @Test
    void freeIsTodaysFeaturesAndUnknownFeaturesAreRefused() {
        assertEquals(Map.of("weeklyProofDetail", false, "extraNodeGames", 0, "dealsOffersPerMonth", 0),
                MerchantPlan.todaysFeatures());
        assertEquals(MerchantPlan.todaysFeatures(), MerchantPlan.normaliseFeatures(null));
        assertThrows(IllegalArgumentException.class, () -> MerchantPlan.normaliseFeatures(Map.of("fogBoost", true)));
        assertThrows(IllegalArgumentException.class, () -> MerchantPlan.normaliseFeatures(Map.of("extraNodeGames", 101)));
        assertThrows(IllegalArgumentException.class, () -> MerchantPlan.normaliseFeatures(Map.of("weeklyProofDetail", "yes")));
    }

    @Test
    void merchantWithoutASubscriptionIsOnFreeAndTheViewSaysAvailableSoonWhileClosed() {
        JdbcTemplate jdbc = planJdbc();
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.startsWith("SELECT id, merchant_id, plan_code"), any(), any(), any()))
                .thenReturn(List.of());
        MerchantPlanService plans = new MerchantPlanService(jdbc, gate(false), owner(), mock(OpsConsoleAudit.class), true);
        Map<String, Object> view = plans.merchantView(7L);
        assertEquals(false, view.get("salesOpen"));
        assertEquals("AVAILABLE_SOON", view.get("salesState"));
        @SuppressWarnings("unchecked")
        Map<String, Object> current = (Map<String, Object>) view.get("current");
        assertEquals("FREE", current.get("code"));
    }

    @Test
    void checkoutAndPaidGrantsAreRefusedWhileTheSwitchIsClosed() {
        JdbcTemplate jdbc = planJdbc();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(1);
        OpsConsoleAudit audit = mock(OpsConsoleAudit.class);
        MerchantPlanService plans = new MerchantPlanService(jdbc, gate(false), owner(), audit, true);

        MerchantGapException closed = assertThrows(MerchantGapException.class, () -> plans.checkout(7L,
                Map.of("planCode", "BASIC", "months", 1, "requestId", "abcdefghijklmnop")));
        assertEquals(409, closed.getStatus());
        assertThrows(IllegalStateException.class, () -> plans.grant(70L, "PRO", 1, 1L, "ops", "pilot"));
        assertThrows(IllegalStateException.class, () -> plans.confirmPaid("MPLAN_1", 1L, "ops", "paid"));
        verify(jdbc, never()).update(org.mockito.ArgumentMatchers.startsWith("INSERT INTO cy_merchant_plan_order"),
                any(Object[].class));
        verify(jdbc, never()).update(org.mockito.ArgumentMatchers.startsWith("INSERT INTO cy_merchant_plan_subscription"),
                any(Object[].class));
        verifyNoInteractions(audit);
    }

    @Test
    void checkoutStaysClosedWithTheSwitchOpenUntilSelfCheckoutIsEnabled() {
        MerchantPlanService plans = new MerchantPlanService(planJdbc(), gate(true), owner(), mock(OpsConsoleAudit.class), false);
        MerchantGapException closed = assertThrows(MerchantGapException.class, () -> plans.checkout(7L,
                Map.of("planCode", "BASIC", "months", 1, "requestId", "abcdefghijklmnop")));
        assertEquals("PLAN_SALES_CLOSED", closed.getErrorCode());
    }

    @Test
    void freeCannotBecomePaidOrLoseFeatures() {
        MerchantPlanService plans = new MerchantPlanService(planJdbc(), gate(true), owner(), mock(OpsConsoleAudit.class), false);
        assertThrows(IllegalArgumentException.class,
                () -> plans.updatePlan("FREE", Map.of("monthlyPriceFen", 100), 1L, "ops", "x"));
        assertThrows(IllegalArgumentException.class,
                () -> plans.updatePlan("FREE", Map.of("offered", false), 1L, "ops", "x"));
        assertThrows(IllegalArgumentException.class,
                () -> plans.updatePlan("BASIC", Map.of("monthlyPriceFen", 100), 1L, "ops", " "));
    }

    // ------------------------------------------------------------------ verified-visit billing

    @Test
    void billableVisitsAreDistinctStaffVerifiedPlayersPerDay() {
        Instant from = Instant.parse("2026-11-01T16:00:00Z"), to = Instant.parse("2026-11-02T16:00:00Z");
        List<MerchantWeeklyProof.Touch> touches = List.of(
                new MerchantWeeklyProof.Touch(1L, Instant.parse("2026-11-02T01:00:00Z"), MerchantWeeklyProof.Kind.TICKET_REDEMPTION),
                new MerchantWeeklyProof.Touch(1L, Instant.parse("2026-11-02T05:00:00Z"), MerchantWeeklyProof.Kind.COUPON_REDEMPTION),
                new MerchantWeeklyProof.Touch(2L, Instant.parse("2026-11-02T05:00:00Z"), MerchantWeeklyProof.Kind.NODE_CHECKIN),
                new MerchantWeeklyProof.Touch(3L, Instant.parse("2026-11-02T17:00:00Z"), MerchantWeeklyProof.Kind.NODE_REDEMPTION));
        assertEquals(1, MerchantVisitBillingService.billableVisits(touches, from, to).size());
    }

    @Test
    void accrualAndInvoicesWriteNothingWhileTheSwitchIsClosed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OpsConsoleAudit audit = mock(OpsConsoleAudit.class);
        MerchantPlansMoneyGate closed = gate(false);
        MerchantPlanService plans = new MerchantPlanService(jdbc, closed, owner(), audit, false);
        MerchantVisitBillingService billing = new MerchantVisitBillingService(jdbc, closed, plans,
                mock(MerchantWeeklyProofService.class), owner(), audit);

        Map<String, Object> result = billing.accrueDay(LocalDate.of(2026, 11, 1));
        assertEquals("CLOSED", result.get("status"));
        assertEquals(0, result.get("written"));
        assertThrows(IllegalStateException.class,
                () -> billing.issueInvoices(YearMonth.of(2026, 10), 1L, "ops", "month end"));
        verifyNoInteractions(jdbc);
        verifyNoInteractions(audit);
    }

    @Test
    void gateReadsClosedOnAnyError() {
        MoneySwitchService broken = mock(MoneySwitchService.class);
        when(broken.snapshot()).thenThrow(new RuntimeException("db down"));
        MerchantPlansMoneyGate gate = new MerchantPlansMoneyGate(broken);
        assertFalse(gate.open());
        assertThrows(IllegalStateException.class, gate::requireOpen);
        assertTrue(gate(true).open());
        assertFalse(gate(false).open());
    }
}
