package com.chengyinhub.business.it;

import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * AC1 发布验收 P0 链的真库 IT 共用建库工具。
 *
 * <p>只读仓库里已有的生产 DDL:{@code sql/base_schema_20260818.sql} 快照按表名截取,
 * 其后的结构变化执行 {@code chengyinhub-admin/sql/migration_*.sql} 原文件。不新增任何 schema 文件,
 * 因此不进 ci/migration_ledger 的 TEST_FIXTURE 名单。库由 {@link MybatisItDatabase} 一类一库建出、跑完即删。
 */
public final class P0AcceptanceSchema {

    private P0AcceptanceSchema() { }

    static Path repoRoot() {
        return Files.exists(Paths.get("sql/base_schema_20260818.sql")) ? Paths.get("") : Paths.get("..");
    }

    static DriverManagerDataSource dataSource(String suite) {
        return new DriverManagerDataSource(MybatisItDatabase.url(suite), MybatisItDatabase.user(),
                MybatisItDatabase.password());
    }

    /** 从 base schema 快照截取这些表的原样 CREATE TABLE 并执行。 */
    public static void baseTables(JdbcTemplate db, String... tables) throws Exception {
        String snapshot = new String(Files.readAllBytes(repoRoot().resolve("sql/base_schema_20260818.sql")),
                StandardCharsets.UTF_8);
        for (String table : tables) {
            String start = "CREATE TABLE `" + table + "` (";
            int from = snapshot.indexOf(start);
            int closing = snapshot.indexOf("\n) ENGINE", from);
            int to = snapshot.indexOf(";\n", closing);
            if (from < 0 || closing < from || to < closing) {
                throw new IllegalStateException("base schema 快照缺表:" + table);
            }
            db.execute(snapshot.substring(from, to + 1));
        }
    }

    /** 原样执行仓库 migration 文件(路径相对 chengyinhub-admin/sql)。 */
    static void migrations(DriverManagerDataSource dataSource, String... files) {
        FileSystemResource[] resources = Arrays.stream(files)
                .map(file -> new FileSystemResource(repoRoot().resolve("chengyinhub-admin/sql").resolve(file).toFile()))
                .toArray(FileSystemResource[]::new);
        for (FileSystemResource resource : resources) {
            if (!resource.exists()) {
                throw new IllegalStateException("migration 不存在:" + resource.getPath());
            }
        }
        new ResourceDatabasePopulator(resources).execute(dataSource);
    }

    /** 截取 migration 文件里第一条 CREATE TABLE 原文(文件含 DELIMITER/菜单数据等无法整体执行时用)。 */
    static void createTableFromMigration(JdbcTemplate db, String file) throws Exception {
        String sql = new String(Files.readAllBytes(repoRoot().resolve("chengyinhub-admin/sql").resolve(file)),
                StandardCharsets.UTF_8);
        int start = sql.indexOf("CREATE TABLE");
        int end = sql.indexOf(";\n", start);
        if (start < 0 || end < start) {
            throw new IllegalStateException("migration 里没有 CREATE TABLE:" + file);
        }
        db.execute(sql.substring(start, end));
    }

    static SqlSessionTemplate sql(DriverManagerDataSource dataSource, String... mapperNames) throws Exception {
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(Arrays.stream(mapperNames)
                .map(name -> new ClassPathResource("mapper/business/" + name + ".xml"))
                .toArray(ClassPathResource[]::new));
        return new SqlSessionTemplate(factory.getObject());
    }

    /** 删库并回读确认已不存在。 */
    static void drop(JdbcTemplate db, String suite) {
        String database = MybatisItDatabase.databaseName(suite);
        db.execute("drop database `" + database + "`");
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource("jdbc:mysql://" + MybatisItDatabase.host() + ":"
                + MybatisItDatabase.port() + "/?useSSL=false&allowPublicKeyRetrieval=true",
                MybatisItDatabase.user(), MybatisItDatabase.password()));
        Integer left = admin.queryForObject(
                "select count(*) from information_schema.schemata where schema_name=?", Integer.class, database);
        if (left == null || left != 0) {
            throw new IllegalStateException("IT 库未删除:" + database);
        }
    }
}
