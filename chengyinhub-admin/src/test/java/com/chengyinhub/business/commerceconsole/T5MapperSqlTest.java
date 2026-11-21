package com.chengyinhub.business.commerceconsole;

import com.chengyinhub.business.commerceconsole.mapper.CommerceConsoleMapper;
import com.chengyinhub.business.platformpass.mapper.PlatformPassAppleMapper;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** T5: the new annotation SQL and the two PlatformPassMapper.xml statements parse and pass the production Druid wall. */
class T5MapperSqlTest {
    @Test
    void newStatementsParseAndPassTheWall() throws Exception {
        Configuration configuration = new Configuration();
        configuration.addMapper(CommerceConsoleMapper.class);
        configuration.addMapper(PlatformPassAppleMapper.class);
        // PlatformPassMapper.xml includes fragments of other mappers; its two new statements are plain SQL, read as text.
        String xml;
        try (InputStream input = Resources.getResourceAsStream("mapper/business/PlatformPassMapper.xml")) {
            xml = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        com.alibaba.druid.wall.WallConfig config = new com.alibaba.druid.wall.WallConfig(
                com.alibaba.druid.wall.spi.MySqlWallProvider.DEFAULT_CONFIG_DIR);
        config.setMultiStatementAllow(false);
        com.alibaba.druid.wall.spi.MySqlWallProvider wall = new com.alibaba.druid.wall.spi.MySqlWallProvider(config);
        Map<String, Object> params = new HashMap<>();
        for (String k : List.of("status", "kind", "limit")) params.put(k, 1);
        for (String k : List.of("id", "productId", "topicId", "clubId", "orderId")) params.put(k, 1L);
        for (String k : List.of("state", "orderNo", "transactionId", "operator")) params.put(k, "x");
        params.put("validFrom", new Date());
        params.put("validTo", new Date());
        List<String> mine = List.of("CommerceConsoleMapper.", "PlatformPassAppleMapper.selectByState",
                "PlatformPassAppleMapper.selectNotificationsByTransaction", "PlatformPassAppleMapper.selectByOrderNoForUpdate",
                "PlatformPassAppleMapper.resolveReview", "PlatformPassMapper.reinstateRefundedOrder",
                "PlatformPassMapper.reopenMembership");
        int checked = 0;
        for (Object o : configuration.getMappedStatements()) {
            if (!(o instanceof MappedStatement ms) || mine.stream().noneMatch(ms.getId()::contains)) continue;
            String sql = ms.getBoundSql(params).getSql();
            com.alibaba.druid.wall.WallCheckResult result = wall.check(sql);
            assertTrue(result.getViolations().isEmpty(), ms.getId() + " rejected by the Druid wall: " + result.getViolations() + "\n" + sql);
            checked++;
        }
        for (String id : List.of("reinstateRefundedOrder", "reopenMembership")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("<update id=\"" + id + "\">(.*?)</update>",
                    java.util.regex.Pattern.DOTALL).matcher(xml);
            assertTrue(m.find(), id + " missing from PlatformPassMapper.xml");
            String sql = m.group(1).replaceAll("#\\{[^}]+}", "?").trim();
            assertTrue(wall.check(sql).getViolations().isEmpty(), id + " rejected by the Druid wall\n" + sql);
            checked++;
        }
        assertTrue(checked >= 15, "expected every new statement, checked " + checked);
    }
}
