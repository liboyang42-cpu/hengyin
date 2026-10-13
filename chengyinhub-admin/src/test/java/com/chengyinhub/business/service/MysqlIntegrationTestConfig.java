package com.chengyinhub.business.service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 资金 MyBatis IT 共用的 MySQL 连接配置；数据库标识只允许安全字符。
 *
 * <p>2026-08-17 起改为 public：探店日联盟局两套 IT（com.chengyinhub.business.it）原本硬编码
 * 连本机 3306 的长期库，绕开了门禁的唯一临时 schema 与 8.0.45 验收档；把它们接回这份配置
 * 需要跨包可见。只有测试代码引用它。
 */
public final class MysqlIntegrationTestConfig {
    static final String HOST = "CHENGYIN_TEST_MYSQL_HOST";
    static final String PORT = "CHENGYIN_TEST_MYSQL_PORT";
    static final String DATABASE_BASE = "CHENGYIN_TEST_MYSQL_DATABASE_BASE";
    static final String DATABASE_SUFFIX = "CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX";
    static final String USER = "CHENGYIN_TEST_MYSQL_USER";
    static final String PASSWORD = "CHENGYIN_TEST_MYSQL_PASSWORD";

    private final String host;
    private final int port;
    private final String databaseName;
    private final String user;
    private final String password;

    private MysqlIntegrationTestConfig(String host, int port, String databaseName,
                                       String user, String password) {
        this.host = host;
        this.port = port;
        this.databaseName = databaseName;
        this.user = user;
        this.password = password;
    }

    public static MysqlIntegrationTestConfig fromEnvironment(String suite) {
        return from(System.getenv(), suite);
    }

    public static MysqlIntegrationTestConfig from(Map<String, String> values, String suite) {
        String host = value(values, HOST, "127.0.0.1");
        if (!host.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("MySQL host 包含非法字符");
        }
        int port;
        try {
            port = Integer.parseInt(value(values, PORT, "3306"));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("MySQL port 非法", invalid);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("MySQL port 超出范围");
        }
        String base = value(values, DATABASE_BASE, "chengyin");
        String suffix = value(values, DATABASE_SUFFIX, "");
        requireIdentifierPart("database base", base, false);
        requireIdentifierPart("suite", suite, false);
        requireIdentifierPart("database suffix", suffix, true);
        String databaseName = base + "_" + suite + suffix;
        if (databaseName.length() > 64) {
            throw new IllegalArgumentException("MySQL 测试库名长度不能超过64");
        }
        String user = value(values, USER, "root");
        if (user.trim().isEmpty()) {
            throw new IllegalArgumentException("MySQL user 不能为空");
        }
        return new MysqlIntegrationTestConfig(host, port, databaseName, user,
                value(values, PASSWORD, ""));
    }

    // ⚠️ 不要写死 serverTimezone。它是「告诉驱动:服务器的时区是什么」,写死就等于**断言**
    // 跑测试的这台 MySQL 一定在 Asia/Shanghai —— 而这个前提没有任何东西保证。
    // 2026-08-01 实证:self-hosted runner 那台 Mac 的时区变成 America/Los_Angeles 后,
    // 驱动仍按 Shanghai 解释服务器写下的 DATETIME,所有「DB 存的时间 ↔ JVM 当前时间」
    // 的比较整体错位 15 小时:订单被判恒过期、派发租约被判恒陈旧。
    // connectionTimeZone=SERVER 让驱动去问服务器实际时区,不再假设。
    private static final String CONN_PARAMS =
            "useSSL=false&allowPublicKeyRetrieval=true"
            // connectionTimeZone=LOCAL:驱动按 **JVM 默认时区** 解释 DATETIME;
            // 即与 MySQL 服务器跟随的 OS 时区一致(两者同机)。
            // 不用 SERVER:服务器会回 'CDT' 这种歧义缩写,驱动直接拒连。
            // 也不用 forceConnectionTimeZoneToSession:它要 SET time_zone='America/Los_Angeles',
            // 而这台 MySQL 没装时区表(mysql.time_zone_name 空),只认数字偏移。
            + "&connectionTimeZone=LOCAL";

    /**
     * 真库 IT 的版本前提:MySQL 主版本 ≥ 8(本机容器 8.0.45,CI runner 实跑 9.7.1)。
     * 2026-09-16:四套暗测试写成 startsWith("8.") / startsWith("8.0.45"),在 CI 的 9.7.1 上全红。
     * 解析不出主版本、或是 MariaDB(10.x 冒充 ≥8),一律判不满足。
     */
    public static boolean isMysql8OrNewer(String version) {
        if (version == null || version.contains("MariaDB")) {
            return false;
        }
        Matcher major = Pattern.compile("^(\\d+)\\.").matcher(version);
        return major.find() && Integer.parseInt(major.group(1)) >= 8;
    }

    public String adminUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/?" + CONN_PARAMS;
    }

    public String databaseUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + databaseName + "?" + CONN_PARAMS;
    }

    public String databaseName() {
        return databaseName;
    }

    public String user() {
        return user;
    }

    public String password() {
        return password;
    }

    private static String value(Map<String, String> values, String key, String fallback) {
        String value = values.get(key);
        return value == null ? fallback : value;
    }

    private static void requireIdentifierPart(String label, String value, boolean allowEmpty) {
        if ((value == null || value.isEmpty()) && allowEmpty) {
            return;
        }
        if (value == null || !value.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException(label + " 只允许字母、数字和下划线");
        }
    }
}
