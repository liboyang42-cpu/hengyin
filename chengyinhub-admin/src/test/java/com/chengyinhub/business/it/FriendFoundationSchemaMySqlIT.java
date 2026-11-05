package com.chengyinhub.business.it;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import static org.junit.jupiter.api.Assertions.*;

/** Real schema constraints, not proof that an API or transaction service is wired. */
class FriendFoundationSchemaMySqlIT
{
    private static String url;
    private static Path migration;

    @BeforeAll static void createIsolatedSchema() throws Exception
    {
        // This suite must never create/drop a schema outside GitHub's job-local MySQL.
        if (!"true".equals(System.getenv("GITHUB_ACTIONS"))
                || !"127.0.0.1".equals(MybatisItDatabase.host()))
            throw new IllegalStateException("Requires GitHub Actions job-local MySQL on 127.0.0.1");
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(
                "sql/migration_20261105_l9_friend_foundation.sql"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Friend foundation migration not found");
        migration = root.resolve("sql/migration_20261105_l9_friend_foundation.sql");
        url = MybatisItDatabase.url("l9_friend_foundation");
        try (Connection c = connection()) { migrate(c); }
    }

    @BeforeEach void clearOnlyThisSuiteTables() throws Exception
    {
        try (Connection c = connection(); Statement s = c.createStatement())
        {
            s.executeUpdate("DELETE FROM member_friend_meta");
            s.executeUpdate("DELETE FROM member_friend");
            s.executeUpdate("DELETE FROM member_friend_group");
        }
    }

    @Test void repeatedMigrationPreservesRowsAndVersion() throws Exception
    {
        try (Connection c = connection(); Statement s = c.createStatement())
        {
            s.executeUpdate(pending(1, 2, 1));
            s.executeUpdate("UPDATE member_friend SET version=7 WHERE low_member_id=1 AND high_member_id=2");
            migrate(c);
            assertEquals(1, scalar(s, "SELECT COUNT(*) FROM member_friend"));
            assertEquals(7, scalar(s, "SELECT version FROM member_friend"));
        }
    }

    @Test void pairOrderUniquenessAndRequesterAreEnforced() throws Exception
    {
        try (Connection c = connection(); Statement s = c.createStatement())
        {
            s.executeUpdate(pending(1, 2, 1));
            assertThrows(SQLException.class, () -> s.executeUpdate(pending(1, 2, 2)));
            assertThrows(SQLException.class, () -> s.executeUpdate(pending(2, 1, 2)));
            assertThrows(SQLException.class, () -> s.executeUpdate(pending(3, 3, 3)));
            assertThrows(SQLException.class, () -> s.executeUpdate(pending(3, 4, 5)));
            assertThrows(SQLException.class, () -> s.executeUpdate(pending(0, 4, 4)));
            assertEquals(1, scalar(s, "SELECT COUNT(*) FROM member_friend"));
        }
    }

    @Test void friendTimestampAndVersionConstraintsRejectInvalidTransitions() throws Exception
    {
        try (Connection c = connection(); Statement s = c.createStatement())
        {
            s.executeUpdate(pending(1, 2, 1));
            assertThrows(SQLException.class, () -> s.executeUpdate("UPDATE member_friend SET status=1"));
            assertThrows(SQLException.class, () -> s.executeUpdate("UPDATE member_friend SET version=-1"));
            assertThrows(SQLException.class, () -> s.executeUpdate("UPDATE member_friend SET status=4"));
            assertThrows(SQLException.class, () -> s.executeUpdate("UPDATE member_friend SET source_type=6"));
            assertEquals(1, s.executeUpdate("UPDATE member_friend SET status=1, friend_since=CURRENT_TIMESTAMP, version=version+1 WHERE version=0"));
            assertEquals(1, scalar(s, "SELECT requester_id FROM member_friend"));
        }
    }

    @Test void staleCompareAndSetCannotOverwriteCommittedPair() throws Exception
    {
        try (Connection c = connection(); Statement first = c.createStatement();
             Connection other = connection(); Statement stale = other.createStatement())
        {
            first.executeUpdate(pending(1, 2, 1));
            assertEquals(1, first.executeUpdate("UPDATE member_friend SET status=1, friend_since=CURRENT_TIMESTAMP, version=version+1 WHERE low_member_id=1 AND high_member_id=2 AND version=0"));
            assertEquals(0, stale.executeUpdate("UPDATE member_friend SET status=2, version=version+1 WHERE low_member_id=1 AND high_member_id=2 AND version=0"));
            assertEquals(1, scalar(first, "SELECT status FROM member_friend"));
        }
    }

    @Test void directionalMetadataIsIndependentAndFlagsAreConstrained() throws Exception
    {
        try (Connection c = connection(); Statement s = c.createStatement())
        {
            s.executeUpdate("INSERT INTO member_friend_meta(member_id,friend_member_id,remark) VALUES(1,2,'owner one'),(2,1,'owner two')");
            assertEquals(2, scalar(s, "SELECT COUNT(*) FROM member_friend_meta"));
            assertThrows(SQLException.class, () -> s.executeUpdate("INSERT INTO member_friend_meta(member_id,friend_member_id) VALUES(1,2)"));
            assertThrows(SQLException.class, () -> s.executeUpdate("INSERT INTO member_friend_meta(member_id,friend_member_id) VALUES(1,1)"));
            assertThrows(SQLException.class, () -> s.executeUpdate("UPDATE member_friend_meta SET starred=2 WHERE member_id=1"));
            assertThrows(SQLException.class, () -> s.executeUpdate("UPDATE member_friend_meta SET muted=2 WHERE member_id=1"));
            assertThrows(SQLException.class, () -> s.executeUpdate("UPDATE member_friend_meta SET progress_scope=2 WHERE member_id=1"));
            assertEquals(1, s.executeUpdate("UPDATE member_friend_meta SET remark=NULL WHERE member_id=1 AND friend_member_id=2"));
            assertEquals(1, scalar(s, "SELECT COUNT(*) FROM member_friend_meta WHERE member_id=2 AND remark='owner two'"));
        }
    }

    @Test void groupNamesAreUniquePerOwnerRatherThanGlobally() throws Exception
    {
        try (Connection c = connection(); Statement s = c.createStatement())
        {
            s.executeUpdate("INSERT INTO member_friend_group(member_id,name) VALUES(1,'cycling'),(2,'cycling')");
            assertThrows(SQLException.class, () -> s.executeUpdate("INSERT INTO member_friend_group(member_id,name) VALUES(1,'cycling')"));
            assertThrows(SQLException.class, () -> s.executeUpdate("UPDATE member_friend_group SET is_default=2 WHERE member_id=1"));
            assertEquals(2, scalar(s, "SELECT COUNT(*) FROM member_friend_group"));
        }
    }

    private static Connection connection() throws SQLException
    {
        return DriverManager.getConnection(url, MybatisItDatabase.user(), MybatisItDatabase.password());
    }

    private static void migrate(Connection c)
    {
        ScriptUtils.executeSqlScript(c, new FileSystemResource(migration));
    }

    private static String pending(long low, long high, long requester)
    {
        return "INSERT INTO member_friend(low_member_id,high_member_id,requester_id,request_msg,expire_time) VALUES("
                + low + "," + high + "," + requester + ",'hello',DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 7 DAY))";
    }

    private static long scalar(Statement s, String sql) throws SQLException
    {
        try (ResultSet result = s.executeQuery(sql))
        {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }
}
