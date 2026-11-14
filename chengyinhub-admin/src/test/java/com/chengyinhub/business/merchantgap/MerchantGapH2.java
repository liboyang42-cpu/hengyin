package com.chengyinhub.business.merchantgap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** H2 (MySQL mode) loaded with the merchant-gap migrations (20261119 + 20261120), for the merchant-gap unit tests. */
public final class MerchantGapH2 {
    public final SingleConnectionDataSource dataSource;
    public final JdbcTemplate jdbc;
    public final DataSourceTransactionManager transactions;

    private MerchantGapH2(SingleConnectionDataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new DataSourceTransactionManager(dataSource);
    }

    public static MerchantGapH2 create(String... extraDdl) throws Exception {
        SingleConnectionDataSource source = new SingleConnectionDataSource(
                "jdbc:h2:mem:mgap_" + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "", true);
        source.setAutoCommit(true);
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            load(statement);
            for (String ddl : extraDdl) statement.execute(ddl);
        }
        return new MerchantGapH2(source);
    }

    /** Runs the migration with MySQL-only table options and plain secondary keys removed. */
    public static void load(Statement statement) throws Exception {
        for (String name : MIGRATIONS) load(statement, Files.readString(migration(name)));
    }

    static final String[] MIGRATIONS = {"migration_20261119_merchant_gap.sql", "migration_20261120_merchant_authoring_candidate.sql"};

    private static void load(Statement statement, String sql) throws Exception {
        StringBuilder kept = new StringBuilder();
        for (String line : sql.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--") || trimmed.startsWith("KEY ")) continue;
            kept.append(line.replace("CHARACTER SET ascii", "")).append('\n');
        }
        for (String command : kept.toString().split(";")) {
            if (command.isBlank()) continue;
            String ddl = command.replaceAll("(?s"+")\\)\\s*ENGINE=.*$", ")").replaceAll(",(\\s*)\\)$", "$1)");
            statement.execute(ddl);
        }
    }

    static Path migration(String name) {
        Path module = Path.of("sql", name);
        if (Files.exists(module)) return module;
        return Path.of("chengyinhub-admin/sql", name);
    }
}
