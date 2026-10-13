package com.chengyinhub.business.it;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MyBatis 真库 IT 的**一类一库**:每个 IT 类拿自己的 schema,本 JVM 内首次取用时 DROP + CREATE。
 *
 * <p><b>为什么要有它。</b>在这之前,十六个 IT 类共用一个长期库 {@code chengyin_mybatis_it}。
 * 那不是"省事",是把两个方向的风险都留着:
 * <ul>
 *   <li>该绿却红:A 类建的表挡住 B 类的建表(2026-08-19 资金 IT 那次撞的就是这个形状,
 *       只是它撞在 merge 结果里);</li>
 *   <li>★ 该红却绿:A 类留下的**数据**让 B 类的 fail-closed 前置意外满足 —— 闸被喂饱,一声不吭。
 *       共用库里这条永远只差一个执行顺序。</li>
 * </ul>
 * 「现在是绿的」不能当作「现在是对的」。
 *
 * <p><b>为什么是 DROP + CREATE,不是 CREATE DATABASE IF NOT EXISTS。</b>
 * IF NOT EXISTS 会把**上一次崩在半路的现场**当成合法初始态接着用 —— 那正是
 * {@link ItSchema} 类注释里写过的同一个坑(它在表这一层已经这么做了,这里补上库这一层)。
 *
 * <p><b>建库需要的权限。</b>本地历史约定是 {@code ituser/itpass} + 只授 {@code chengyin_mybatis_it} 一个库,
 * 那个账号建不了新库。改成默认走 {@code root}(与 ci/funds-mysql-it-gate.sh 和 ci.yml 里
 * atomic-migration 那步一致);连接信息可用环境变量覆盖:
 * {@code CHENGYIN_MYBATIS_IT_HOST/PORT/USER/PASSWORD/DB_SUFFIX}。
 * 本机 root 默认空口令,GitHub 的 mysql service 是 root/root —— 所以 ci.yml 的 backend 步显式注入密码。
 *
 * <p>⚠️ 只管建库。表由各 IT 自己建(多数走 {@link ItSchema} 从仓库 migration 灌真实 DDL)。
 */
public final class MybatisItDatabase {

    private static final String PARAMS =
            "?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true";
    /** 库名前缀保持 chengyin_mybatis_,几个类里的「只许连专用 fixture 库」守卫仍然按这个前缀认。 */
    private static final String PREFIX = "chengyin_mybatis_";

    private static final Set<String> CREATED = ConcurrentHashMap.newKeySet();

    private MybatisItDatabase() { }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }

    public static String host() { return env("CHENGYIN_MYBATIS_IT_HOST", "127.0.0.1"); }

    public static String port() { return env("CHENGYIN_MYBATIS_IT_PORT", "3306"); }

    public static String user() { return env("CHENGYIN_MYBATIS_IT_USER", "root"); }

    /** 口令允许显式为空(本机 root 就是空口令),所以这里用 getenv 原值,不做 isEmpty 回退。 */
    public static String password() {
        String value = System.getenv("CHENGYIN_MYBATIS_IT_PASSWORD");
        return value == null ? "" : value;
    }

    /** 一类一库的库名;后缀留给「同一台库上并行跑多份」的场景(CI 可注入 run 级唯一值)。 */
    public static String databaseName(String suite) {
        if (suite == null || !suite.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("suite 只允许小写字母、数字和下划线:" + suite);
        }
        return PREFIX + suite + env("CHENGYIN_MYBATIS_IT_DB_SUFFIX", "");
    }

    /**
     * 取该类专属库的 JDBC URL;本 JVM 内**首次**取用时把库 DROP 掉重建。
     *
     * <p>为什么只在首次:一个类在一次运行里会多次建 DataSource/取连接,每次都重建库
     * 会把同一个测试前半段插进去的数据抹掉。库的归属是「一个类一次运行」,不是「一次调用」。
     */
    public static String url(String suite) {
        String database = databaseName(suite);
        if (CREATED.add(database)) {
            recreate(database);
        }
        return "jdbc:mysql://" + host() + ":" + port() + "/" + database + PARAMS;
    }

    /** 与 {@link #url(String)} 同样的建库语义,但只回库名 —— 给自己拼 URL(时区参数不同)的类用。 */
    public static String databaseNameAfterCreate(String suite) {
        String database = databaseName(suite);
        if (CREATED.add(database)) {
            recreate(database);
        }
        return database;
    }

    private static void recreate(String database) {
        String adminUrl = "jdbc:mysql://" + host() + ":" + port() + "/" + PARAMS;
        try (Connection admin = DriverManager.getConnection(adminUrl, user(), password());
             Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS `" + database + "`");
            st.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
        } catch (Exception failure) {
            throw new IllegalStateException("建 IT 专属库失败(" + database + "):" + failure.getMessage(), failure);
        }
    }
}
