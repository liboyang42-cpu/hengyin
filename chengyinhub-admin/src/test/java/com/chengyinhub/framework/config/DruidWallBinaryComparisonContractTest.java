package com.chengyinhub.framework.config;

import com.alibaba.druid.wall.WallCheckResult;
import com.alibaba.druid.wall.WallConfig;
import com.alibaba.druid.wall.spi.MySqlWallProvider;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 应用数据源开着 Druid wall(application-druid.yml: wall.enabled=true)。wall 会把一部分
 * {@code BINARY a = BINARY b} 判成恒真/双常量条件直接拒绝,MySQL IT 走裸数据源看不到;
 * 2026-10-30 链路验收里报名支付消费 insertConsumed 就这样抛 UncategorizedSQLException,
 * 回调失败、订单停在未支付。等价写法 {@code CAST(a AS BINARY) = CAST(b AS BINARY)} 能过 wall,语义不变。
 */
class DruidWallBinaryComparisonContractTest {

    private static final Pattern BINARY_OPERAND =
            Pattern.compile("(?i)(?<![A-Za-z0-9_])(?<!as )binary\\s+[A-Za-z_#'(]");

    private static MySqlWallProvider appWall() {
        WallConfig config = new WallConfig(MySqlWallProvider.DEFAULT_CONFIG_DIR);
        config.setMultiStatementAllow(false);
        return new MySqlWallProvider(config);
    }

    @Test
    void wallRejectsTheBinaryKeywordFormAndAcceptsTheCastForm() {
        MySqlWallProvider wall = appWall();
        String join = "insert ignore into cms_registration_payment_fact (registration_id, registration_no, payment_fact_id, payment_fact_hash, transaction_id) "
                + "select r.id, r.registration_no, f.id, f.fact_hash, f.transaction_id from cms_registration r join wx_verified_payment_fact f on f.id=? "
                + "where r.id=? and %s and r.payment_status=2 and r.registration_status=2 and %s and %s";
        WallCheckResult keyword = wall.check(String.format(join, "binary f.fact_hash=binary ?",
                "binary r.registration_no=binary f.business_out_trade_no", "binary r.transaction_id=binary f.transaction_id"));
        WallCheckResult cast = wall.check(String.format(join, "cast(f.fact_hash as binary)=cast(? as binary)",
                "cast(r.registration_no as binary)=cast(f.business_out_trade_no as binary)",
                "cast(r.transaction_id as binary)=cast(f.transaction_id as binary)"));
        assertFalse(keyword.getViolations().isEmpty(), "负控:wall 必须仍拒绝 BINARY 关键字写法,否则本合同失去意义");
        assertTrue(cast.getViolations().isEmpty(), "CAST 写法必须过 wall:" + cast.getViolations());
    }

    @Test
    void mapperXmlNeverUsesTheBinaryKeywordComparison() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path root : new Path[]{Paths.get("..", "chengyinhub-system", "src", "main", "resources", "mapper"),
                Paths.get("src", "main", "resources", "mapper")}) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".xml"))::iterator) {
                    String[] lines = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        Matcher m = BINARY_OPERAND.matcher(lines[i]);
                        if (m.find()) offenders.add(file.getFileName() + ":" + (i + 1) + " " + lines[i].trim());
                    }
                }
            }
        }
        assertEquals(List.of(), offenders, "mapper 里用 CAST(x AS BINARY),不要用 BINARY x(Druid wall 会拒)");
    }
}
