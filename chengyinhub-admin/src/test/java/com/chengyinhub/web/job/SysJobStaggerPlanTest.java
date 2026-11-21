package com.chengyinhub.web.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.quartz.CronExpression;

/**
 * R9 BE-28: no two scheduled jobs start in the same second.
 *
 * <p>The plan lives in {@code sql/migration_20261115_stagger_all_sys_jobs.sql} ("-- plan:" lines). This test
 * simulates 24 hours second by second with Quartz's own cron parser and fails on any shared second. It also fails
 * when any migration registers a sys_job invoke target that the plan does not cover, so a new job must get its
 * own free second in a new stagger migration. Such a later migration ({@code migration_<date>_stagger_*.sql})
 * carries its own "-- plan:" rows, and they join the plan here.
 */
class SysJobStaggerPlanTest
{
    private static final Path SQL = Paths.get("sql");
    private static final Path PLAN = SQL.resolve("migration_20261115_stagger_all_sys_jobs.sql");
    private static final Pattern PLAN_LINE = Pattern.compile("^-- plan: (\\S+) = (.+)$");
    private static final Pattern UPDATE_LINE =
            Pattern.compile("^UPDATE sys_job SET cron_expression = '([^']+)'.*WHERE invoke_target = '([^']+)'");
    private static final Pattern TARGET = Pattern.compile("'([a-zA-Z]\\w*Task\\.\\w+\\([^')]*\\))'");
    private static final Pattern LATER_PLAN_FILE = Pattern.compile("migration_\\d{8}_stagger_\\w+\\.sql");

    /** The full plan file first, then every later stagger migration that adds "-- plan:" rows. */
    private static List<Path> planFiles() throws Exception
    {
        List<Path> files = new ArrayList<>();
        files.add(PLAN);
        try (Stream<Path> all = Files.list(SQL))
        {
            for (Path f : (Iterable<Path>) all.sorted()::iterator)
            {
                if (f.equals(PLAN) || !LATER_PLAN_FILE.matcher(f.getFileName().toString()).matches()) continue;
                if (!rows(f).isEmpty()) files.add(f);
            }
        }
        return files;
    }

    private static Map<String, String> rows(Path file) throws Exception
    {
        Map<String, String> rows = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8))
        {
            Matcher m = PLAN_LINE.matcher(line.trim());
            if (m.matches()) assertEquals(null, rows.put(m.group(1), m.group(2).trim()), "duplicate plan row " + m.group(1));
        }
        return rows;
    }

    private static Map<String, String> plan() throws Exception
    {
        Map<String, String> plan = new LinkedHashMap<>();
        for (Path file : planFiles())
        {
            for (Map.Entry<String, String> row : rows(file).entrySet())
            {
                assertEquals(null, plan.put(row.getKey(), row.getValue()), "duplicate plan row " + row.getKey() + " in " + file);
            }
        }
        return plan;
    }

    @Test
    void everyPlannedJobOwnsADistinctSecondOverAWholeDay() throws Exception
    {
        Map<String, String> plan = plan();
        assertEquals(48, rows(PLAN).size(), "the full plan keeps its 48 rows; later jobs go into new stagger migrations");
        TimeZone zone = TimeZone.getTimeZone("Asia/Shanghai");
        Map<Long, List<String>> bySecond = new HashMap<>();
        long start = 1_763_136_000_000L; // 2025-11-15 00:00 +08:00, any midnight works
        for (Map.Entry<String, String> e : plan.entrySet())
        {
            CronExpression cron = new CronExpression(e.getValue());
            cron.setTimeZone(zone);
            Date t = cron.getNextValidTimeAfter(new Date(start - 1000));
            int fires = 0;
            while (t != null && t.getTime() < start + 86_400_000L)
            {
                bySecond.computeIfAbsent(t.getTime() / 1000, k -> new ArrayList<>()).add(e.getKey());
                fires++;
                t = cron.getNextValidTimeAfter(t);
            }
            assertTrue(fires > 0, e.getKey() + " never fires");
        }
        List<String> clashes = new ArrayList<>();
        bySecond.forEach((second, jobs) -> { if (jobs.size() > 1) clashes.add(new Date(second * 1000) + " " + jobs); });
        assertTrue(clashes.isEmpty(), "jobs share a start second: " + clashes.subList(0, Math.min(5, clashes.size())));

        Set<String> seconds = new TreeSet<>();
        for (String cron : plan.values()) seconds.add(cron.split(" ")[0]);
        assertEquals(plan.size(), seconds.size(), "each job has its own second field");
    }

    @Test
    void updateStatementsApplyExactlyThePlan() throws Exception
    {
        Map<String, String> plan = plan();
        int updates = 0;
        for (Path file : planFiles())
        {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8))
            {
                Matcher m = UPDATE_LINE.matcher(line);
                if (!m.find()) continue;
                updates++;
                assertEquals(plan.get(m.group(2)), m.group(1), "UPDATE for " + m.group(2) + " in " + file + " differs from its plan row");
            }
        }
        assertTrue(updates >= 40, "expected an UPDATE for nearly every planned job, got " + updates);
    }

    @Test
    void everyJobRegisteredByAMigrationIsInThePlan() throws Exception
    {
        Map<String, String> plan = plan();
        Set<String> registered = new TreeSet<>();
        try (Stream<Path> files = Files.list(SQL))
        {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".sql"))::iterator)
            {
                String sql = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                for (String stmt : sql.split(";"))
                {
                    if (!stmt.contains("sys_job") || stmt.contains("sys_job_log")) continue;
                    Matcher m = TARGET.matcher(stmt);
                    while (m.find()) registered.add(m.group(1));
                }
            }
        }
        registered.removeAll(plan.keySet());
        for (Path file : planFiles())
        {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8))
            {
                if (line.startsWith("-- retired: ")) registered.remove(line.substring("-- retired: ".length()).split("\\s+")[0]);
            }
        }
        assertTrue(registered.isEmpty(), "sys_job targets without a stagger second: " + registered);
    }
}
