package com.chengyinhub.business.it;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * IT 库建表:把 test/resources/it-schema/*.sql 里的**生产真实 DDL**灌进本次连接的库。
 *
 * <p>为什么不用 {@code CREATE TABLE IF NOT EXISTS} 而是先 DROP:
 * IF NOT EXISTS 会**静默沿用库里已存在的旧表**。2026-08-14 实测,本机那个遗留的
 * chengyin_mybatis_it 里躺着旧形状的表,于是 IT 报 “Unknown column 'member_id'” ——
 * 表面像产品缺陷,其实是测试悄悄接受了一张过期的表。测试必须自己决定 schema,
 * 不能由环境里恰好剩下什么决定。</p>
 *
 * <p>⚠️ 这里只建结构、不塞数据;各 IT 自己插自己的 fixture。</p>
 */
public final class ItSchema {

    private ItSchema() { }

    /**
     * 按顺序执行仓库里的 migration 脚本(路径相对 chengyinhub-admin 模块根,如 sql/migration_x.sql)。
     *
     * <p>为什么用仓库脚本而不是从生产 dump:这几张表的 migration 本来就在库里躺着,
     * 它才是真源,而且会跟着后续演进走。dump 出来的快照会过期。
     * (coop 那几张没有可用的建表脚本,才退而求其次用生产 DDL 快照。)</p>
     *
     * <p>先 DROP 掉脚本里出现的表,保证不沿用环境里的旧表。</p>
     */
    public static void applyMigrations(Connection connection, String... paths) throws Exception {
        java.util.List<String> all = new ArrayList<>();
        for (String path : paths) {
            java.io.File file = new java.io.File(path);
            if (!file.isFile()) {
                throw new IllegalStateException("migration 脚本不存在:" + file.getAbsolutePath());
            }
            all.add(new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        }
        java.util.List<String> statements = new ArrayList<>();
        for (String text : all) {
            statements.addAll(splitStatements(stripComments(text)));
        }
        try (Statement st = connection.createStatement()) {
            for (String ddl : statements) {
                String table = tableNameOf(ddl);
                if (table != null) st.execute("DROP TABLE IF EXISTS `" + table + "`");
            }
            for (String ddl : statements) st.execute(ddl);
        }
    }

    /**
     * 按分号切语句,但**跳过单引号内的分号**。
     *
     * <p>2026-08-14 实测:migration_npc_profile.sql 里有
     * {@code COMMENT 'activityId 或 merchantId;全局NPC固定0'} —— 天真的 split(";")
     * 会把这条 CREATE TABLE 从注释中间劈成两半,报一个看不懂的语法错。</p>
     */
    private static java.util.List<String> splitStatements(String text) {
        java.util.List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                // 连续两个单引号是转义的字面量引号,不切换状态
                if (inQuote && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    cur.append(c).append(text.charAt(++i));
                    continue;
                }
                inQuote = !inQuote;
            }
            if (c == ';' && !inQuote) {
                String ddl = cur.toString().trim();
                if (!ddl.isEmpty()) out.add(ddl);
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        String tail = cur.toString().trim();
        if (!tail.isEmpty()) out.add(tail);
        return out;
    }

    private static String stripComments(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--") || trimmed.startsWith("#")) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    /** 读 classpath 下的 DDL 脚本,逐条 DROP 后重建。 */
    public static void apply(Connection connection, String resource) throws Exception {
        List<String> statements = readStatements(resource);
        try (Statement st = connection.createStatement()) {
            for (String ddl : statements) {
                String table = tableNameOf(ddl);
                if (table != null) {
                    st.execute("DROP TABLE IF EXISTS `" + table + "`");
                }
                st.execute(ddl);
            }
        }
    }

    private static List<String> readStatements(String resource) throws Exception {
        try (InputStream in = ItSchema.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("IT 建表脚本不存在:" + resource);
            }
            StringBuilder buffer = new StringBuilder();
            try (Scanner scanner = new Scanner(in, StandardCharsets.UTF_8.name())) {
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine().trim();
                    if (line.isEmpty() || line.startsWith("--")) {
                        continue;
                    }
                    buffer.append(line).append('\n');
                }
            }
            List<String> statements = new ArrayList<>();
            for (String chunk : buffer.toString().split(";\\s*\\n")) {
                String ddl = chunk.trim();
                if (!ddl.isEmpty()) {
                    statements.add(ddl);
                }
            }
            if (statements.isEmpty()) {
                throw new IllegalStateException("IT 建表脚本是空的:" + resource);
            }
            return statements;
        }
    }

    private static String tableNameOf(String ddl) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("CREATE TABLE (?:IF NOT EXISTS )?`?([a-zA-Z0-9_]+)`?").matcher(ddl);
        return m.find() ? m.group(1) : null;
    }
}
