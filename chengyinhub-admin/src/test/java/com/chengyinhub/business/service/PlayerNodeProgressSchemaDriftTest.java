package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B:P2-023:player_node_progress 的 Mapper 列必须都能由「基线 base_schema + 台账迁移 ADD COLUMN」建出来。
 * 基线快照(2026-08-18)缺 play_instance_id / puzzle_score / completion_mode,由迁移补齐;
 * 以后 Mapper 加列却没写迁移,本用例红。
 */
class PlayerNodeProgressSchemaDriftTest {

    private static final String TABLE = "player_node_progress";
    private static final Path ROOT = Paths.get(System.getProperty("user.dir"), "..");

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    /** 去掉引号内文本(COMMENT 里可能含分号)。 */
    private static String stripQuoted(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        boolean quoted = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (quoted) {
                if (ch == '\\') { i++; continue; }
                if (ch == '\'') quoted = false;
                continue;
            }
            if (ch == '\'') { quoted = true; out.append("''"); continue; }
            out.append(ch);
        }
        return out.toString();
    }

    private static Set<String> baselineColumns() throws IOException {
        String base = read(ROOT.resolve("sql/base_schema_20260818.sql"));
        Matcher m = Pattern.compile("CREATE TABLE `?" + TABLE + "`?\\s*\\((.*?)\\n\\)\\s*ENGINE",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(base);
        assertTrue(m.find(), "基线里找不到 " + TABLE);
        Set<String> cols = new TreeSet<>();
        Matcher c = Pattern.compile("^\\s*`?(\\w+)`?\\s+[a-z]+", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE)
                .matcher(m.group(1));
        while (c.find()) {
            String name = c.group(1).toLowerCase();
            if (!name.matches("primary|unique|key|index|constraint|fulltext")) cols.add(name);
        }
        return cols;
    }

    private static Set<String> migrationAddedColumns() throws IOException {
        Set<String> cols = new TreeSet<>();
        Pattern alter = Pattern.compile("ALTER\\s+TABLE\\s+`?" + TABLE + "`?\\b([^;]*)", Pattern.CASE_INSENSITIVE);
        Pattern add = Pattern.compile("ADD\\s+(?:COLUMN\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?`?(\\w+)`?", Pattern.CASE_INSENSITIVE);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(ROOT.resolve("chengyinhub-admin/sql"), "migration_*.sql")) {
            for (Path f : files) {
                Matcher a = alter.matcher(stripQuoted(read(f)));
                while (a.find()) {
                    Matcher c = add.matcher(a.group(1));
                    while (c.find()) cols.add(c.group(1).toLowerCase());
                }
            }
        }
        return cols;
    }

    private static Set<String> mapperColumns() throws IOException {
        String xml = read(ROOT.resolve("chengyinhub-system/src/main/resources/mapper/business/PlayerNodeProgressMapper.xml"));
        Set<String> cols = new TreeSet<>();
        Matcher m = Pattern.compile("column=\"(\\w+)\"").matcher(xml);
        while (m.find()) cols.add(m.group(1).toLowerCase());
        return cols;
    }

    @Test
    void mapperColumnsAreBuildableFromBaselinePlusMigrations() throws IOException {
        Set<String> available = baselineColumns();
        available.addAll(migrationAddedColumns());
        Set<String> missing = mapperColumns();
        missing.removeAll(available);
        assertTrue(missing.isEmpty(), "Mapper 列在基线+迁移里都建不出来:" + missing);
    }

    @Test
    void knownBaselineGapsAreCoveredByMigrations() throws IOException {
        Set<String> added = migrationAddedColumns();
        for (String col : new String[]{"play_instance_id", "puzzle_score", "completion_mode"})
            assertTrue(added.contains(col), col + " 必须由迁移补齐");
    }
}
