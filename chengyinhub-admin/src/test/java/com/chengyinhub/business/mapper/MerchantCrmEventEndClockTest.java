package com.chengyinhub.business.mapper;

import org.apache.ibatis.builder.xml.XMLMapperEntityResolver;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.parsing.XPathParser;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MerchantCrmEventEndClockTest {
    @Test
    void shanghaiActivityAndThemeBoundariesDoNotDependOnDatabaseSessionZone() throws Exception {
        String fragment;
        try (Reader reader = Resources.getResourceAsReader("mapper/business/MerchantCrmAppMapper.xml")) {
            fragment = new XPathParser(reader, true, null, new XMLMapperEntityResolver())
                    .evalString("/mapper/sql[@id='RegistrationEventEnded']").toLowerCase(Locale.ROOT);
        }
        Instant now = Instant.parse("2026-11-05T16:30:00Z"); // 上海已跨到次日，UTC 仍是前一天。
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:crm_end_clock;MODE=MySQL")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("create table cms_activity(id bigint, end_date timestamp)");
                statement.execute("create table cms_topic(id bigint, end_date timestamp)");
                statement.execute("insert into cms_activity values"
                        + "(1,'2026-11-06 00:20:00'),(2,'2026-11-06 00:30:00'),(3,'2026-11-06 00:31:00')");
                statement.execute("insert into cms_topic values(4,'2026-11-05 18:00:00'),(5,'2026-11-06 00:00:00')");
            }
            for (String zone : new String[] { "UTC", "Asia/Shanghai" }) {
                LocalDateTime localNow = LocalDateTime.ofInstant(now, ZoneId.of(zone));
                // 固定同一时刻，同时保留 UTC 钟与会话墙钟的差异，执行真实 Mapper 的边界表达式。
                String sql = fragment.replace("utc_timestamp()", timestamp(LocalDateTime.ofInstant(now, ZoneId.of("UTC"))))
                        .replace("current_timestamp", timestamp(localNow))
                        .replace("current_date", "date '" + localNow.toLocalDate() + "'");
                assertEnded(connection, sql, 2, 1, true, zone);
                assertEnded(connection, sql, 2, 2, true, zone);
                assertEnded(connection, sql, 2, 3, false, zone);
                assertEnded(connection, sql, 1, 4, true, zone);
                assertEnded(connection, sql, 1, 5, false, zone);
            }
        }
    }

    private static String timestamp(LocalDateTime value) {
        return "timestamp '" + value.toString().replace('T', ' ') + "'";
    }

    private static void assertEnded(Connection connection, String fragment, int ownerType, long ownerId,
            boolean expected, String zone) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select " + fragment + " from (select cast(? as integer) as owner_type, cast(? as bigint) as owner_id) r")) {
            statement.setInt(1, ownerType);
            statement.setLong(2, ownerId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                assertEquals(expected, result.getBoolean(1), zone + " ownerType=" + ownerType + " ownerId=" + ownerId);
            }
        }
    }
}
