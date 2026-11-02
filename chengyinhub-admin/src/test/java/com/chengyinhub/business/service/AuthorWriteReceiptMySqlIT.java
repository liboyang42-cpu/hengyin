package com.chengyinhub.business.service;

import com.chengyinhub.business.it.MybatisItDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.junit.jupiter.api.Assertions.*;

/** 同一组事务/重放/并发负控在CI独立MySQL库运行，实际迁移连续执行两遍。 */
class AuthorWriteReceiptMySqlIT extends AuthorWriteReceiptServiceTest {
    @Override protected DataSource dataSource() {
        assertTrue("127.0.0.1".equals(MybatisItDatabase.host()) || "localhost".equals(MybatisItDatabase.host()));
        String suite = "author_receipt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        DriverManagerDataSource source = new DriverManagerDataSource(MybatisItDatabase.url(suite),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        Path migration = Path.of("sql/migration_20261101_author_write_receipt.sql");
        if (!Files.exists(migration)) migration = Path.of("chengyinhub-admin").resolve(migration);
        assertTrue(Files.exists(migration));
        try (var connection = source.getConnection()) {
            assertEquals(MybatisItDatabase.databaseName(suite), connection.getCatalog());
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(migration));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(migration));
        } catch (Exception failure) { throw new IllegalStateException("独立回执MySQL fixture失败", failure); }
        return source;
    }
}
