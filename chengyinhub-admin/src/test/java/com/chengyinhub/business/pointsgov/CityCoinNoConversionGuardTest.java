package com.chengyinhub.business.pointsgov;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.chengyinhub.business.citycoin.CityCoinService;
import com.chengyinhub.business.mapper.CityCoinMapper;
import com.chengyinhub.web.controller.api.ApiCityCoinController;

/**
 * R7 MKT-12 guard: city coin can never be converted into points, cash, coupons or orders.
 *
 * <ol>
 *   <li>Only the reviewed files below may mention city coin at all (backend, Quartz jobs, admin console and
 *       mini-program sources are all scanned). A new route, job or admin action that touches city coin fails here
 *       until it is reviewed and, if it really never converts, added to the allowlist.</li>
 *   <li>The city coin mapper only credits and reads its own two tables: no DELETE, no balance decrease, no other table.</li>
 *   <li>The city coin service, mapper and read controller expose only credit/read operations and never reference
 *       points, balance, coupon, order, payment or withdrawal code.</li>
 * </ol>
 */
class CityCoinNoConversionGuardTest {

    private static final Path ROOT = Paths.get("..");
    private static final Pattern MENTION = Pattern.compile(
            "city_coin_|CityCoin|cityCoinService|/api/city-coin|city\\.coin\\.", Pattern.CASE_INSENSITIVE);
    private static final Set<String> ALLOWED = Set.of(
            "chengyinhub-system/src/main/java/com/chengyinhub/business/citycoin/CityCoinService.java",
            "chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/CityCoinMapper.java",
            "chengyinhub-system/src/main/resources/mapper/business/CityCoinMapper.xml",
            // admin dashboard: reads totals only (CityCoinService.dashboard)
            "chengyinhub-system/src/main/java/com/chengyinhub/business/pointsgov/PointsGovernanceService.java",
            "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiCityCoinController.java",
            // growth loops (reviewed 2026-11-15 at integration): a credit-only invite hook, its caller, a read-only
            // summary field and notification copy. None converts city coin into points, cash, coupons or orders.
            "chengyinhub-system/src/main/java/com/chengyinhub/business/growthloops/InviteCityCoinHook.java",
            "chengyinhub-system/src/main/java/com/chengyinhub/business/growthloops/InviteRewardService.java",
            "chengyinhub-system/src/main/java/com/chengyinhub/business/growthloops/CityEventNotifier.java",
            "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiGrowthLoopsController.java",
            // CITY/roam territory holder yield (reviewed 2026-11-15 at the channels integration): calls only
            // CityCoinService.creditHolderAfterCommit; never reads, spends or converts city coin.
            "chengyinhub-system/src/main/java/com/chengyinhub/business/citygame/RoamTerritoryService.java");
    private static final List<String> SCANNED = List.of(
            "chengyinhub-admin/src/main", "chengyinhub-system/src/main", "chengyinhub-common/src/main",
            "chengyinhub-framework/src/main", "chengyinhub-quartz/src/main", "chengyinhub-generator/src/main",
            "chengyinhub-ui/src", "chengyinhub-xcx");
    private static final Set<String> EXTENSIONS = Set.of("java", "xml", "js", "vue", "ts", "wxml", "json", "sql", "yml", "properties");
    /** Points, member balance, cash, coupons, orders, payment and withdrawal symbols and tables (case-sensitive). */
    private static final Pattern FORBIDDEN_DEPENDENCY = Pattern.compile(
            "addPoints|UmsMember|ums_member|PointsAward|PointsValue|PointsRule|points_|Coupon|coupon|"
                    + "Order[A-Z]|[a-z]Order\\b|_order\\b|\\border_|Payment|payment|Pay[A-Z]|Withdraw|withdraw|Refund|refund|"
                    + "Wallet|wallet|Balance(Service|Mapper|Detail)|Exchange|exchange|Redeem|redeem|Convert|convert|Cash[A-Z]|cash_");

    @Test
    void onlyReviewedFilesMentionCityCoin() throws IOException {
        Set<String> offenders = new TreeSet<>();
        for (String dir : SCANNED) {
            Path base = ROOT.resolve(dir);
            if (!Files.isDirectory(base)) continue;
            try (Stream<Path> walk = Files.walk(base)) {
                for (Path file : walk.filter(Files::isRegularFile).collect(Collectors.toList())) {
                    String rel = ROOT.relativize(file).toString().replace('\\', '/');
                    if (rel.contains("/node_modules/") || rel.contains("/target/") || rel.contains("/dist/")) continue;
                    if (!EXTENSIONS.contains(extension(rel))) continue;
                    if (rel.startsWith("chengyinhub-ui/src/views/business/pointsgov/")
                            || rel.equals("chengyinhub-ui/src/api/business/pointsgov.js")) {
                        // admin dashboard view: may show city coin totals, must not call any city coin route
                        assertFalse(read(file).contains("/api/city-coin"), rel + " calls a city coin route");
                        continue;
                    }
                    if (MENTION.matcher(read(file)).find() && !ALLOWED.contains(rel)) offenders.add(rel);
                }
            }
        }
        assertTrue(offenders.isEmpty(), "city coin is mentioned outside the reviewed files (a conversion path?): " + offenders);
    }

    @Test
    void mapperOnlyCreditsAndReadsItsOwnTables() throws IOException {
        String xml = read(ROOT.resolve(
                "chengyinhub-system/src/main/resources/mapper/business/CityCoinMapper.xml")).toLowerCase(Locale.ROOT);
        assertFalse(xml.contains("<delete"), "city coin mapper must not delete");
        assertFalse(Pattern.compile("balance\\s*=\\s*balance\\s*-").matcher(xml).find(), "no balance decrease");
        assertFalse(Pattern.compile("balance\\s*=\\s*#\\{").matcher(xml).find(), "no balance overwrite");
        Matcher updates = Pattern.compile("<update[^>]*>(.*?)</update>", Pattern.DOTALL).matcher(xml);
        int count = 0;
        while (updates.find()) {
            count++;
            String body = updates.group(1);
            assertTrue(body.contains("balance = balance + #{amount}") && body.contains("#{amount} &gt; 0"),
                    "the only update allowed is a guarded credit: " + body.trim());
        }
        assertEquals(1, count, "exactly one update statement (the credit)");
        Matcher tables = Pattern.compile("\\b(?:from|into|(?<!key |for )update|join)\\s+([a-z_]+)").matcher(
                xml.replaceAll("(?s)<!--.*?-->", " ").replaceAll("<[^>]+>", " "));
        while (tables.find()) {
            assertTrue(tables.group(1).startsWith("city_coin_"), "city coin mapper touches " + tables.group(1));
        }
    }

    @Test
    void cityCoinCodeNeverReachesPointsCashCouponsOrOrders() throws IOException {
        for (String rel : List.of(
                "chengyinhub-system/src/main/java/com/chengyinhub/business/citycoin/CityCoinService.java",
                "chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/CityCoinMapper.java",
                "chengyinhub-system/src/main/resources/mapper/business/CityCoinMapper.xml",
                "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiCityCoinController.java")) {
            String code = stripComments(read(ROOT.resolve(rel)));
            Matcher m = FORBIDDEN_DEPENDENCY.matcher(code);
            if (m.find()) fail(rel + " references " + m.group() + " (city coin must never reach points/cash/coupons/orders)");
        }
    }

    @Test
    void apiSurfaceIsCreditAndReadOnly() {
        Set<String> serviceOps = Arrays.stream(CityCoinService.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && !m.isSynthetic())
                .map(Method::getName).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(new TreeSet<>(List.of("creditHolder", "creditHolderAfterCommit", "dashboard", "summary")), serviceOps,
                "city coin service operations changed: review for a conversion path before updating this list");
        for (Method m : CityCoinMapper.class.getDeclaredMethods()) {
            String n = m.getName();
            assertTrue(n.startsWith("select") || n.startsWith("sum") || n.equals("ensureAccount")
                    || n.equals("insertCredit") || n.equals("creditBalance"), "unexpected mapper operation " + n);
        }
        for (Method m : ApiCityCoinController.class.getDeclaredMethods()) {
            assertFalse(m.isAnnotationPresent(PostMapping.class) || m.isAnnotationPresent(PutMapping.class)
                    || m.isAnnotationPresent(DeleteMapping.class) || m.isAnnotationPresent(PatchMapping.class)
                    || m.isAnnotationPresent(RequestMapping.class), "city coin route must be read only: " + m.getName());
        }
        assertTrue(Arrays.stream(ApiCityCoinController.class.getDeclaredMethods()).anyMatch(m -> m.isAnnotationPresent(GetMapping.class)));
    }

    private static String stripComments(String code) {
        return code.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?s)<!--.*?-->", "").replaceAll("(?m)//.*$", "");
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1);
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

}
