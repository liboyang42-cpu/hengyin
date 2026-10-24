package com.chengyinhub.business.it;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/** Only the G disposable MySQL UUID schema; no production entry or data repair. */
public final class GCashEvidenceEmptyRecovery {
    private static final String TARGET = "chengyin_mybatis_g_cash_expand_[a-f0-9]{12}[A-Za-z0-9_]*";
    private static final String[] NEW = {"coop_settlement_cash_close", "coop_settlement_cash_contribution", "coop_settlement_cash_event"};
    private static final List<String> BASE = List.of("ums_member", "cms_topic", "cms_registration", "coop_settlement",
            "coop_settlement_adjustment", "settlement_claim", "coop_settlement_detail", "refund_application");
    private static final String AFFECTED = "('coop_settlement','coop_settlement_adjustment','coop_settlement_cash_close',"
            + "'coop_settlement_cash_contribution','coop_settlement_cash_event')";
    private GCashEvidenceEmptyRecovery() {}
    public record Result(int stage, String referenceSchema) {}

    /** Run only with all writers stopped. DDL auto-commits; every inverse step returns to an accepted prefix. */
    public static Result recoverEmpty(DriverManagerDataSource source, Path repository) throws Exception {
        repository = Objects.requireNonNull(repository, "public repository path required").toAbsolutePath().normalize();
        try (Connection connection = source.getConnection()) {
            String target = connection.getCatalog();
            if (target == null || !target.matches(TARGET) || !connection.getAutoCommit()
                    || !connection.getMetaData().getURL().matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/.*")) {
                throw new IllegalStateException("G_CASH_RECOVERY_OWN_LOCAL_UUID_SCHEMA_REQUIRED");
            }
            String reference = "g_cash_recovery_shape_" + UUID.randomUUID().toString().replace("-", "");
            boolean created = false;
            try {
                List<List<String>> schema = rows(connection,
                        "SELECT DEFAULT_CHARACTER_SET_NAME,DEFAULT_COLLATION_NAME FROM information_schema.schemata WHERE schema_name=?", target);
                if (schema.size() != 1 || schema.get(0).stream().anyMatch(v -> v == null || !v.matches("[A-Za-z0-9_]+")))
                    throw new IllegalStateException("G_CASH_RECOVERY_SCHEMA_ENCODING_UNKNOWN");
                execute(connection, "CREATE DATABASE `" + reference + "` CHARACTER SET " + schema.get(0).get(0)
                        + " COLLATE " + schema.get(0).get(1));
                created = true;
                connection.setCatalog(reference);
                String baseline = Files.readString(repository.resolve("sql/base_schema_20260818.sql"));
                for (String table : BASE) {
                    java.util.regex.Matcher ddl = Pattern.compile("(?s)CREATE TABLE `" + table + "` \\(.*?\\) ENGINE=.*?;").matcher(baseline);
                    if (!ddl.find()) throw new IllegalStateException("G_CASH_RECOVERY_BASELINE_MISSING");
                    execute(connection, ddl.group());
                }
                Path sql = repository.resolve("chengyinhub-admin/sql");
                List<String> forward = statements(sql.resolve("migration_20261023_g_cash_evidence_expand.sql"));
                String[] starts = {"SELECT CAST(IF(", "CREATE TABLE coop_settlement_cash_close", "CREATE TABLE coop_settlement_cash_contribution",
                        "CREATE TABLE coop_settlement_cash_event", "ALTER TABLE coop_settlement\n", "ALTER TABLE coop_settlement_adjustment\n", "SELECT CAST(IF("};
                if (forward.size() != starts.length) throw new IllegalStateException("G_CASH_RECOVERY_FORWARD_LAYOUT_CHANGED");
                for (int i = 0; i < starts.length; i++)
                    if (!forward.get(i).startsWith(starts[i])) throw new IllegalStateException("G_CASH_RECOVERY_FORWARD_LAYOUT_CHANGED");
                // Same-server canonical metadata, including full CHECK/generation clauses: no guessed format/digest.
                List<Map<String, List<List<String>>>> expected = new ArrayList<>();
                execute(connection, forward.get(0));
                expected.add(shape(connection, reference));
                for (int step = 1; step <= 5; step++) {
                    execute(connection, forward.get(step));
                    expected.add(shape(connection, reference));
                }
                execute(connection, forward.get(6));
                connection.setCatalog(target);
                Map<String, List<List<String>>> actual = shape(connection, target);
                int stage = -1;
                for (int i = 0; i < expected.size(); i++) if (expected.get(i).equals(actual)) stage = i;
                if (stage < 0) throw new IllegalStateException("G_CASH_RECOVERY_SHAPE_MISMATCH");
                requireEmpty(connection, stage);
                if (stage == 0) return new Result(0, reference);
                List<String> rollback = statements(sql.resolve("_deploy_g_cash_evidence_empty_rollback_20261023.sql"));
                List<String> inverses = rollback.stream().filter(s -> s.startsWith("ALTER TABLE ") || s.startsWith("DROP TABLE ")).toList();
                if (inverses.size() != 5 || !inverses.get(0).startsWith("ALTER TABLE coop_settlement_adjustment\n")
                        || !inverses.get(1).startsWith("ALTER TABLE coop_settlement\n")
                        || !inverses.get(2).equals("DROP TABLE " + NEW[2]) || !inverses.get(3).equals("DROP TABLE " + NEW[1])
                        || !inverses.get(4).equals("DROP TABLE " + NEW[0]))
                    throw new IllegalStateException("G_CASH_RECOVERY_ROLLBACK_LAYOUT_CHANGED");
                // All shape and empty checks finished before the FIRST target DDL; no DROP by prefix/wildcard.
                int remaining = stage;
                if (remaining == 5) { execute(connection, inverses.get(0)); remaining--; }
                if (remaining == 4) { execute(connection, inverses.get(1)); remaining--; }
                for (int i = remaining - 1; i >= 0; i--) execute(connection, "DROP TABLE " + NEW[i]);
                if (!shape(connection, target).equals(expected.get(0)))
                    throw new IllegalStateException("G_CASH_RECOVERY_FINAL_SHAPE_MISMATCH");
                return new Result(stage, reference);
            } finally {
                connection.setCatalog(target);
                if (created) {
                    // Only this invocation's successfully created UUID reference; never DROP target database.
                    execute(connection, "DROP DATABASE `" + reference + "`");
                    if (scalar(connection, "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", reference) != 0)
                        throw new IllegalStateException("G_CASH_RECOVERY_REFERENCE_CLEANUP_FAILED");
                }
            }
        }
    }

    private static void requireEmpty(Connection connection, int stage) throws SQLException {
        for (int i = 0; i < Math.min(stage, 3); i++)
            if (scalar(connection, "SELECT COUNT(*) FROM " + NEW[i]) != 0)
                throw new IllegalStateException("G_CASH_RECOVERY_NONEMPTY_EVIDENCE");
        if (stage >= 4 && scalar(connection, "SELECT COUNT(*) FROM coop_settlement WHERE cash_close_id IS NOT NULL"
                + " OR cash_head_kind<>'MEMBER' OR merchant_id IS NULL") != 0)
            throw new IllegalStateException("G_CASH_RECOVERY_HEAD_IN_USE");
        if (stage >= 5 && scalar(connection, "SELECT COUNT(*) FROM coop_settlement_adjustment WHERE cash_close_id IS NOT NULL OR cash_event_id IS NOT NULL") != 0)
            throw new IllegalStateException("G_CASH_RECOVERY_ADJUSTMENT_IN_USE");
    }
    private static Map<String, List<List<String>>> shape(Connection c, String schema) throws SQLException {
        Map<String, List<List<String>>> out = new TreeMap<>();
        out.put("tables", rows(c, "SELECT table_name,table_type,engine,row_format,create_options,table_collation,table_comment FROM information_schema.tables"
                + " WHERE table_schema=? AND table_name IN " + AFFECTED + " ORDER BY table_name", schema));
        out.put("columns", rows(c, "SELECT table_name,ordinal_position,column_name,column_type,is_nullable,column_default,extra,generation_expression,"
                + "character_set_name,collation_name,column_comment FROM information_schema.columns WHERE table_schema=? AND table_name IN "
                + AFFECTED + " ORDER BY table_name,ordinal_position", schema));
        out.put("indexes", rows(c, "SELECT table_name,index_name,non_unique,seq_in_index,column_name,sub_part,collation,index_type,index_comment,is_visible,`expression`"
                + " FROM information_schema.statistics WHERE table_schema=? AND table_name IN " + AFFECTED + " ORDER BY table_name,index_name,seq_in_index", schema));
        out.put("constraints", rows(c, "SELECT t.table_name,t.constraint_name,t.constraint_type,t.enforced,k.check_clause"
                + " FROM information_schema.table_constraints t LEFT JOIN information_schema.check_constraints k"
                + " ON k.constraint_schema=t.constraint_schema AND k.constraint_name=t.constraint_name AND t.constraint_type='CHECK'"
                + " WHERE t.constraint_schema=? AND t.table_name IN " + AFFECTED + " ORDER BY t.table_name,t.constraint_name", schema));
        out.put("keys", normalized(rows(c, "SELECT table_name,constraint_name,ordinal_position,column_name,position_in_unique_constraint,"
                + "referenced_table_schema,referenced_table_name,referenced_column_name FROM information_schema.key_column_usage"
                + " WHERE constraint_schema=? AND table_name IN " + AFFECTED + " ORDER BY table_name,constraint_name,ordinal_position", schema), schema));
        out.put("references", normalized(rows(c, "SELECT table_name,constraint_name,unique_constraint_schema,unique_constraint_name,match_option,update_rule,delete_rule,referenced_table_name"
                + " FROM information_schema.referential_constraints WHERE constraint_schema=? AND table_name IN " + AFFECTED
                + " ORDER BY table_name,constraint_name", schema), schema));
        out.put("inbound", normalized(rows(c, "SELECT constraint_schema,table_name,constraint_name,referenced_table_name FROM information_schema.referential_constraints"
                + " WHERE unique_constraint_schema=? AND referenced_table_name IN " + AFFECTED + " ORDER BY constraint_schema,table_name,constraint_name", schema), schema));
        out.put("triggers", rows(c, "SELECT event_object_table,trigger_name,action_timing,event_manipulation,action_statement FROM information_schema.triggers"
                + " WHERE trigger_schema=? AND event_object_table IN " + AFFECTED + " ORDER BY event_object_table,trigger_name", schema));
        return out;
    }
    private static List<List<String>> normalized(List<List<String>> rows, String schema) {
        for (List<String> row : rows) for (int i = 0; i < row.size(); i++) if (schema.equals(row.get(i))) row.set(i, "OWN_SCHEMA");
        return rows;
    }
    private static List<List<String>> rows(Connection c, String query, String schema) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(query)) {
            statement.setString(1, schema);
            try (ResultSet result = statement.executeQuery()) {
                List<List<String>> rows = new ArrayList<>();
                while (result.next()) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) row.add(result.getString(i));
                    rows.add(row);
                }
                return rows;
            }
        }
    }
    private static long scalar(Connection c, String query, String... parameters) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(query)) {
            for (int i = 0; i < parameters.length; i++) statement.setString(i + 1, parameters[i]);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("G_CASH_RECOVERY_READBACK_MISSING");
                return result.getLong(1);
            }
        }
    }
    private static void execute(Connection c, String sql) throws SQLException {
        try (Statement statement = c.createStatement()) { statement.execute(sql); }
    }
    private static List<String> statements(Path file) throws Exception {
        String text = Files.readString(file).replaceAll("(?m)^\\s*--.*$", "");
        return Arrays.stream(text.split(";")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** Optional approved-CI entry: args[0]=public repository path; credentials only from explicit IT env. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("public repository path required");
        Map<String, String> env = System.getenv();
        for (String key : List.of("HOST", "PORT", "USER", "PASSWORD", "DB_SUFFIX", "RECOVERY_DATABASE"))
            if (!env.containsKey("CHENGYIN_MYBATIS_IT_" + key)) throw new IllegalArgumentException("explicit IT connection required");
        String host = env.get("CHENGYIN_MYBATIS_IT_HOST");
        String database = env.get("CHENGYIN_MYBATIS_IT_RECOVERY_DATABASE");
        int port = Integer.parseInt(env.get("CHENGYIN_MYBATIS_IT_PORT"));
        if (!("127.0.0.1".equals(host) || "localhost".equals(host)) || !database.matches(TARGET)
                || port < 1 || port > 65535 || env.get("CHENGYIN_MYBATIS_IT_USER").isBlank())
            throw new IllegalArgumentException("owned local IT UUID connection required");
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC", env.get("CHENGYIN_MYBATIS_IT_USER"), env.get("CHENGYIN_MYBATIS_IT_PASSWORD"));
        Result result = recoverEmpty(source, Path.of(args[0]));
        System.out.println("G_CASH_EMPTY_RECOVERY_STAGE=" + result.stage() + " REFERENCE_SCHEMA_CLEANED=" + result.referenceSchema());
    }
}
