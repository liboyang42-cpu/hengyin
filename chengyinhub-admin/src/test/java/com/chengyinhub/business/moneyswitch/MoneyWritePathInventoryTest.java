package com.chengyinhub.business.moneyswitch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W7 MERCHANT-3: every server call site that asks WeChat Pay to collect or pay out money is in this reviewed map, with
 * the gate in front of it. A new call site, or one more call in a listed file, fails here until it is reviewed and
 * given its Q5 area (see {@link MoneyGate}) or a written reason. The money switch runbook carries the same table.
 */
class MoneyWritePathInventoryTest {

    /** Collect / pay-out calls on payment services (declarations and javadoc lines are skipped). */
    static final Pattern MONEY_CALL = Pattern.compile(
            "\\.(createGenericOrder|createGenericAppOrder|createAppOrder|createDuesOrder|transferToBalance)\\("
                    + "|\\b(wechatPayService|payments|partner|transfers|c)\\.(createOrder|withdraw)\\("
                    + "|sharing\\(\\)\\.createOrder\\(");

    static final List<Path> ROOTS = List.of(
            Path.of("../chengyinhub-system/src/main/java"),
            Path.of("../chengyinhub-admin/src/main/java"));

    /** Reviewed call sites: file (from the package root) → expected count and the gate in front of it. */
    static final Map<String, Reviewed> REVIEWED = new TreeMap<>();

    record Reviewed(int calls, String gate) {
    }

    static void reviewed(String file, int calls, String gate) {
        REVIEWED.put(file, new Reviewed(calls, gate));
    }

    static {
        // Q5-gated money writes.
        reviewed("com/chengyinhub/business/collection/ClubDuesSplitService.java", 1,
                "club dues: Q5 tickets AND chengyin.collection-model.enabled AND SPLIT to the club sub-merchant (MERCHANT-1)");
        reviewed("com/chengyinhub/business/moneypayout/PayoutRequestService.java", 1,
                "withdrawal transfer: Q5 withdrawal AND withdrawal.fast-withdraw.enabled");
        reviewed("com/chengyinhub/business/service/impl/CashWithdrawServiceImpl.java", 1,
                "WeChat balance transfer. Callers: PayoutRequestService (Q5 withdrawal); and the existing mini-program"
                        + " wallet and club-settlement withdrawals (/api/wallet/withdraw, /api/club/settlement/withdraw),"
                        + " live before Q5 and not under a Q5 area (owner decision, see the W7 receipt)");
        reviewed("com/chengyinhub/business/service/impl/WorkshopService.java", 1,
                "template WeChat APP order: chengyin workshop app channel flag AND wx.pay.app-appid; app side Q5 pass");
        // Existing mini-program business, live in production before Q5; not under a Q5 area (owner decision, runbook).
        reviewed("com/chengyinhub/business/service/impl/RegistrationCheckoutServiceImpl.java", 2,
                "ticket registration JSAPI / APP order: wx.pay.enabled (APP also wx.pay.app-appid); S2 decides the seller"
                        + " while chengyin.collection-model.enabled is on. Live business, not under Q5");
        reviewed("com/chengyinhub/business/service/impl/MerchantCommerceService.java", 1,
                "merchant commerce (premium template / promotion slot / custom event) JSAPI order: wx.pay.enabled."
                        + " Live business, not under Q5");
        reviewed("com/chengyinhub/business/service/impl/MarketingProductPurchaseServiceImpl.java", 2,
                "marketing product cash: cashChannel.enabled() AND wx.pay ready. Live business, not under Q5");
        reviewed("com/chengyinhub/web/controller/api/ApiCoopController.java", 2,
                "co-op deposit JSAPI / APP order: wx.pay ready (APP: wx.pay.app-appid). Live business, not under Q5");
        // Gateways and adapters: they place the order a gated caller asked for.
        reviewed("com/chengyinhub/business/service/impl/WechatPayServiceImpl.java", 5,
                "facade delegating to WechatPaymentSdkGateway; gated at the callers above");
        reviewed("com/chengyinhub/business/service/impl/WechatPaymentSdkGateway.java", 1,
                "JSAPI ticket order hands third-party sellers to the partner adapter; CLM dues refused on the platform"
                        + " account in every mode (CollectionModelService.requirePlatformMayCollectDues)");
        reviewed("com/chengyinhub/business/service/impl/EcommercePartnerPaymentAdapter.java", 1,
                "S2 split order on the seller's sub-merchant; only while the collection model is active");
        reviewed("com/chengyinhub/business/payout/WechatEcommercePayGateway.java", 1,
                "service-provider (平台收付通) SDK call for the partner adapter above");
    }

    @Test
    void everyMoneyCallSiteIsReviewedWithItsGate() throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        for (Path root : ROOTS) {
            assertTrue(Files.isDirectory(root), "missing source root " + root.toAbsolutePath());
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    int calls = countCalls(Files.readAllLines(file, StandardCharsets.UTF_8));
                    if (calls > 0) found.put(root.relativize(file).toString().replace('\\', '/'), calls);
                }
            }
        }
        List<String> problems = new ArrayList<>();
        found.forEach((file, calls) -> {
            Reviewed reviewed = REVIEWED.get(file);
            if (reviewed == null) {
                problems.add("unreviewed money call site(s) in " + file + " (" + calls + "): give it a Q5 area via"
                        + " MoneyGate or a written reason here and in the runbook");
            } else if (reviewed.calls() != calls) {
                problems.add(file + " has " + calls + " money call(s), reviewed " + reviewed.calls());
            }
        });
        REVIEWED.keySet().stream().filter(f -> !found.containsKey(f))
                .forEach(f -> problems.add("reviewed file no longer has a money call, update the map: " + f));
        assertEquals(List.of(), problems);
    }

    @Test
    void everyReviewedEntryNamesItsGate() {
        REVIEWED.forEach((file, reviewed) -> assertTrue(reviewed.gate() != null && reviewed.gate().length() > 10, file));
    }

    static int countCalls(List<String> lines) {
        int calls = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("*") || line.startsWith("/*") || line.startsWith("//")) continue;
            Matcher m = MONEY_CALL.matcher(line);
            while (m.find()) calls++;
        }
        return calls;
    }
}
