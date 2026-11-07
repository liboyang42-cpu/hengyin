package com.chengyinhub.framework.config;

import com.alibaba.druid.wall.WallCheckResult;
import com.alibaba.druid.wall.WallConfig;
import com.alibaba.druid.wall.spi.MySqlWallProvider;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
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
 * check-1104 p2 F-1:商家「管理优惠券」整页空白。/api/coupon/mypublishlist 先跑
 * SmsCouponMapper.updateExpiredCouponsByMemberId,那条 update 每行带 {@code -- 注释},
 * 应用数据源的 Druid wall 以 「comment not allow」 拒绝 ⇒ 500 ⇒ 列表页进 error 态画空。
 * MySQL IT 走裸数据源看不到 wall,所以这里用与应用同配置的 wall 直接验 mapper 生成的 SQL,
 * 再扫全部 mapper:SQL 体内(XML 注释之外)不许出现 {@code --} / {@code #} 行注释或块注释。
 */
class DruidWallSqlCommentContractTest {

    private static final Pattern XML_COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern CDATA = Pattern.compile("(?s)<!\\[CDATA\\[(.*?)]]>");
    private static final Pattern SQL_COMMENT = Pattern.compile("--|/\\*|(?<![#$A-Za-z0-9_'\"])#(?!\\{)");

    private static MySqlWallProvider appWall() {
        WallConfig config = new WallConfig(MySqlWallProvider.DEFAULT_CONFIG_DIR);
        config.setMultiStatementAllow(false);
        return new MySqlWallProvider(config);
    }

    @Test
    void wallRejectsCommentedUpdateAndAcceptsTheCouponExpiryStatementAsMapped() throws Exception {
        MySqlWallProvider wall = appWall();
        // 原 mapper 生成的语句形状:每行尾 `-- 注释`,最后一行的注释落在语句末尾 ⇒ wall 判注入式尾注释拒绝。
        WallCheckResult commented = wall.check("update sms_coupon\n set\n status = 2,  -- 状态改为已结束\n"
                + " update_time = now()  -- 更新时间\n where\n member_id = ?  -- 指定用户ID\n"
                + " and status = 1\n and del_flag = 0\n and end_time < now()  -- 注意:转义\n");
        assertFalse(commented.getViolations().isEmpty(), "负控:wall 必须仍拒绝带 -- 注释的语句,否则本合同失去意义");

        Configuration configuration = new Configuration();
        String resource = "mapper/business/SmsCouponMapper.xml";
        configuration.getTypeAliasRegistry().registerAlias("SmsCoupon", com.chengyinhub.business.domain.SmsCoupon.class);
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        String sql = configuration.getMappedStatement(
                "com.chengyinhub.business.mapper.SmsCouponMapper.updateExpiredCouponsByMemberId")
                .getBoundSql(11L).getSql();
        assertTrue(wall.check(sql).getViolations().isEmpty(),
                "「我发布的优惠券」列表前必跑的过期更新必须过 wall:" + wall.check(sql).getViolations());
    }

    @Test
    void mapperSqlBodiesCarryNoSqlComments() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path root : new Path[]{Paths.get("..", "chengyinhub-system", "src", "main", "resources", "mapper"),
                Paths.get("..", "chengyinhub-quartz", "src", "main", "resources", "mapper"),
                Paths.get("..", "chengyinhub-generator", "src", "main", "resources", "mapper"),
                Paths.get("src", "main", "resources", "mapper")}) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".xml"))::iterator) {
                    String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                    text = blankKeepingLines(XML_COMMENT, text);
                    Matcher cdata = CDATA.matcher(text);
                    StringBuilder sb = new StringBuilder();
                    while (cdata.find()) cdata.appendReplacement(sb, Matcher.quoteReplacement(cdata.group(1)));
                    cdata.appendTail(sb);
                    String[] lines = sb.toString().split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i];
                        if (line.contains("<!DOCTYPE") || line.contains("dtd")) continue;
                        if (SQL_COMMENT.matcher(stripQuoted(line)).find()) {
                            offenders.add(file.getFileName() + ":" + (i + 1) + " " + line.trim());
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), offenders, "mapper SQL 体内不要写 --、# 或 /* */ 注释(Druid wall 会拒),说明放 XML 注释里");
    }

    private static String blankKeepingLines(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String kept = m.group().replaceAll("[^\n]", "");
            m.appendReplacement(sb, Matcher.quoteReplacement(kept));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String stripQuoted(String line) {
        return line.replaceAll("'(?:[^'\\\\]|\\\\.)*'", "''").replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
    }
}
