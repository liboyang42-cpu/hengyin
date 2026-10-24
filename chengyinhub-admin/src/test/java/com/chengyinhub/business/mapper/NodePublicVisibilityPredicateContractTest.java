package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-078:「节点对玩家公开」的判据在多个 Mapper 里各写一份。列表/计数那两份排除了
 * 商家已退出(merchant_chapter_offer 停用且带退出理由)的章节节点,完成度/开局建站那几份没排除 ——
 * 玩家看不到那一站,却被算进「全部完成」的分母、被建成本局站点。
 *
 * <p>本测试要求:每一处「主办方节点 或 已过审」判据,紧跟着必须有同一别名的退出供给排除。
 */
class NodePublicVisibilityPredicateContractTest {

    private static final String DIR = "chengyinhub-system/src/main/resources/mapper/business";
    private static final Pattern PREDICATE = Pattern.compile(
            "\\((?:(\\w+)\\.)?merchant_member_id is null or (?:\\w+\\.)?node_audit_status\\s*=\\s*1\\)");

    @Test
    void everyPublicNodePredicateAlsoExcludesWithdrawnMerchantOffers() throws IOException {
        Path dir = Paths.get(DIR);
        if (!Files.exists(dir)) dir = Paths.get("..").resolve(DIR);
        int seen = 0;
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".xml")).sorted()::iterator) {
                String xml = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                List<String> fileMissing = violations(xml);
                seen += count(xml);
                for (String m : fileMissing) missing.add(file.getFileName() + ": " + m);
            }
        }
        assertTrue(seen >= 6, "判据至少有 6 处(审计口径),现在只找到 " + seen + " 处 —— 正则失效会让本测试恒绿");
        assertEquals(List.of(), missing, "这些公开节点判据缺少商家退出供给排除");
    }

    @Test
    void negativeControlDetectsAMissingWithdrawnGate() {
        String bad = "from cms_topic_node n where n.status = 1 and (n.merchant_member_id is null or n.node_audit_status = 1)";
        assertEquals(1, violations(bad).size());
        String good = bad + " and not exists (select 1 from merchant_chapter_offer withdrawn"
                + " where withdrawn.chapter_id = n.chapter_id and withdrawn.merchant_id = n.merchant_member_id"
                + " and withdrawn.status = 0 and withdrawn.withdraw_reason is not null)";
        assertEquals(0, violations(good).size());
    }

    private static int count(String xml) {
        Matcher m = PREDICATE.matcher(xml);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private static List<String> violations(String xml) {
        List<String> out = new ArrayList<>();
        Matcher m = PREDICATE.matcher(xml);
        while (m.find()) {
            String alias = m.group(1) == null ? "cms_topic_node" : m.group(1);
            String tail = xml.substring(m.end(), Math.min(xml.length(), m.end() + 700)).replaceAll("\\s+", " ");
            boolean gated = tail.contains("merchant_chapter_offer withdrawn")
                    && tail.contains("withdrawn.chapter_id = " + alias + ".chapter_id")
                    && tail.contains("withdrawn.merchant_id = " + alias + ".merchant_member_id")
                    && tail.contains("withdrawn.status = 0")
                    && tail.contains("withdrawn.withdraw_reason is not null");
            if (!gated) out.add(m.group());
        }
        return out;
    }
}
