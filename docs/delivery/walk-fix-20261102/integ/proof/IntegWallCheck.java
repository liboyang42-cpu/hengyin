import com.alibaba.druid.wall.WallCheckResult;
import com.alibaba.druid.wall.WallConfig;
import com.alibaba.druid.wall.spi.MySqlWallProvider;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;

import java.io.InputStream;
import java.nio.file.*;
import java.util.*;

/**
 * Rig-only (integration 2026-11-03): every mapper statement this branch adds or changes against master, parsed by
 * MyBatis from the candidate's own mapper XML (exploded jar), rendered to SQL and checked by Druid's MySQL wall with
 * the app's config (META-INF/druid/wall/mysql, multi-statement off, same as application-druid.yml).
 * Dynamic SQL is rendered twice where it can be: once with every optional parameter present (all &lt;if&gt; branches,
 * one-element lists for &lt;foreach&gt;) and once with no parameters. Negative controls (l8's: BINARY col = BINARY col,
 * always-false 1 = 0, multi-statement) must be refused by the same wall. Note: the wall does not refuse
 * {@code BINARY col = BINARY 'literal'}; the rule is about the always-true/false column-to-itself shape.
 * Usage: java -cp ... IntegWallCheck <classes-dir> <namespace.id>...
 */
public class IntegWallCheck {
    /** Map that answers every key: list-ish names get a one-element list, everything else a small number/string. */
    static class Universal extends HashMap<String, Object> {
        @Override public boolean containsKey(Object k) { return true; }
        @Override public Object get(Object k) {
            String key = String.valueOf(k);
            if (super.containsKey(key)) return super.get(key);
            String low = key.toLowerCase(Locale.ROOT);
            if (low.endsWith("ids") || low.endsWith("list") || low.endsWith("s") && !low.endsWith("status")
                    && !low.endsWith("ss")) return new ArrayList<>(Collections.singletonList(1L));
            if (low.contains("time") || low.contains("date") || low.equals("now") || low.endsWith("at")) return new Date();
            if (low.contains("name") || low.contains("type") || low.contains("code") || low.contains("word")
                    || low.contains("text") || low.contains("keyword") || low.contains("state") || low.contains("action")
                    || low.contains("category") || low.contains("phone") || low.contains("reason") || low.contains("json")
                    || low.contains("body") || low.contains("url") || low.contains("key") || low.contains("hash"))
                return "x";
            return 1L;
        }
    }

    public static void main(String[] a) throws Exception {
        Path classes = Paths.get(a[0]);
        WallConfig wc = new WallConfig("META-INF/druid/wall/mysql");
        wc.setMultiStatementAllow(false);
        MySqlWallProvider wall = new MySqlWallProvider(wc);

        Configuration cfg = new Configuration();
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        cfg.getTypeAliasRegistry().registerAliases("com.chengyinhub.system.domain");
        List<Path> xmls = new ArrayList<>();
        try (var s = Files.walk(classes.resolve("mapper"))) { s.filter(p -> p.toString().endsWith(".xml")).forEach(xmls::add); }
        int parseFail = 0;
        for (Path x : xmls) {
            try (InputStream in = Files.newInputStream(x)) {
                new XMLMapperBuilder(in, cfg, x.toString(), cfg.getSqlFragments()).parse();
            } catch (Exception e) { parseFail++; }
        }
        System.out.println("parsed mapper files " + xmls.size() + " (skipped " + parseFail + " that need app-only types)");
        int pass = 0, fail = 0, notRendered = 0;
        for (int i = 1; i < a.length; i++) {
            String id = a[i];
            MappedStatement ms;
            try { ms = cfg.getMappedStatement(id); } catch (Exception e) {
                System.out.println("NOT-FOUND " + id + "  " + e.getMessage()); notRendered++; continue; }
            List<String> variants = new ArrayList<>();
            for (Object param : new Object[]{new Universal(), new HashMap<String, Object>()}) {
                try {
                    BoundSql b = ms.getBoundSql(param);
                    String sql = b.getSql().replaceAll("\\s+", " ").trim();
                    if (!variants.contains(sql)) variants.add(sql);
                } catch (Exception e) { /* this parameter shape cannot render this statement */ }
            }
            if (variants.isEmpty()) { System.out.println("NOT-RENDERED " + id); notRendered++; continue; }
            for (String sql : variants) {
                WallCheckResult r = wall.check(sql);
                boolean ok = r.getViolations().isEmpty();
                if (ok) pass++; else fail++;
                System.out.println((ok ? "PASS " : "FAIL ") + id + (ok ? "" : "  " + r.getViolations()) + "\n     " +
                        (sql.length() > 220 ? sql.substring(0, 220) + "…" : sql));
            }
        }
        String[][] neg = {
                {"BINARY word = BINARY word (l8's control)", "select id from content_sensitive_word where BINARY word = BINARY word"},
                {"always false 1 = 0", "select id from cms_topic where del_flag = 0 and 1 = 0"},
                {"multi statement", "select 1; delete from cms_topic"},
        };
        int negOk = 0;
        for (String[] n : neg) {
            boolean refused = !wall.check(n[1]).getViolations().isEmpty();
            if (refused) negOk++;
            System.out.println((refused ? "NEG-REFUSED " : "NEG-NOT-REFUSED ") + n[0]);
        }
        boolean castOk = wall.check("select id from content_sensitive_word where CAST(word AS BINARY) = CAST('x' AS BINARY)")
                .getViolations().isEmpty();
        System.out.println((castOk ? "POS-PASSES " : "POS-REFUSED ") + "CAST(x AS BINARY) = CAST(y AS BINARY)");
        System.out.println("SUMMARY pass=" + pass + " fail=" + fail + " notRendered=" + notRendered
                + " negativeControlsRefused=" + negOk + "/" + neg.length);
        System.exit(fail == 0 && negOk == neg.length && castOk ? 0 : 1);
    }
}
