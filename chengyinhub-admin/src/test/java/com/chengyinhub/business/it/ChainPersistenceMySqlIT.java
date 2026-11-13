package com.chengyinhub.business.it;

import com.chengyinhub.business.mapper.channel.interaction.ChainPersistenceMapper;
import com.chengyinhub.business.service.channel.interaction.ChainCommandExecutor;
import com.chengyinhub.business.service.channel.interaction.ChainCommandIdentity;
import com.chengyinhub.business.service.channel.interaction.ChainParticipationTransition;
import com.chengyinhub.business.service.channel.interaction.ChainReceiptRecord;
import java.io.InputStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
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
class ChainPersistenceMySqlIT
{
    private static String url;
    private static SqlSessionFactory sessions;
    private static Path migration;

    @BeforeAll static void createIsolatedSchema() throws Exception
    {
        // This suite must never create/drop a schema outside GitHub's job-local MySQL.
        if (!"true".equals(System.getenv("GITHUB_ACTIONS"))
                || !"127.0.0.1".equals(MybatisItDatabase.host()))
            throw new IllegalStateException("Requires GitHub Actions job-local MySQL on 127.0.0.1");
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(
                "chengyinhub-admin/sql/migration_20261105_l8_chain_storage.sql"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Chain storage migration not found");
        migration = root.resolve("chengyinhub-admin/sql/migration_20261105_l8_chain_storage.sql");
        url = MybatisItDatabase.url("l8_chain_storage");
        try (Connection c = connection()) { migrate(c); }
        UnpooledDataSource source = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", url,
                MybatisItDatabase.user(), MybatisItDatabase.password());
        Configuration config = new Configuration(new Environment("chain-it", new JdbcTransactionFactory(), source));
        String resource = "mapper/business/channel/interaction/ChainPersistenceMapper.xml";
        try (InputStream in = ChainPersistenceMySqlIT.class.getClassLoader().getResourceAsStream(resource))
        {
            if (in == null) throw new IllegalStateException("Chain mapper XML not found");
            new XMLMapperBuilder(in, config, resource, config.getSqlFragments()).parse();
        }
        sessions = new SqlSessionFactoryBuilder().build(config);
    }

    @BeforeEach void clearOnlyThisSuiteTables() throws Exception
    {
        try (Connection c = connection(); Statement s = c.createStatement())
        {
            s.executeUpdate("DELETE FROM im_chain_entry_event");
            s.executeUpdate("DELETE FROM im_chain_command_receipt");
            s.executeUpdate("DELETE FROM im_chain_entry");
            s.executeUpdate("DELETE FROM im_chain");
            s.executeUpdate("INSERT INTO im_chain(id,conversation_id,message_id,creator_member_id,title,max_slots,deadline_at) VALUES(1,10,100,20,'test chain',2,DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))");
        }
    }

    @Test void originalJoinSurvivesWithdrawAndRejoinWithoutReactivationOnRead() throws Exception
    {
        var original = receipt("r-join", "join-1", 20, "JOIN", "INSERT_RELATION", 0, 0, 0, 1);
        persist(original);
        persist(receipt("r-withdraw", "withdraw-1", 20, "WITHDRAW", "WITHDRAW_RELATION", 1, 1, 1, 0));
        try (SqlSession session = sessions.openSession(true))
        {
            var mapper = session.getMapper(ChainPersistenceMapper.class);
            assertEquals(original, mapper.findOriginal(1, "join-1").toReceipt());
            assertEquals("WITHDRAWN", mapper.lockEntry(1, 20).get("status"));
        }
        persist(receipt("r-rejoin", "join-2", 20, "JOIN", "REACTIVATE_RELATION", 2, 2, 0, 1));
        try (SqlSession session = sessions.openSession(true))
        {
            var mapper = session.getMapper(ChainPersistenceMapper.class);
            assertEquals(original, mapper.findOriginal(1, "join-1").toReceipt());
            assertEquals("ACTIVE", mapper.lockEntry(1, 20).get("status"));
            assertEquals(1, ((Number) mapper.lockEntry(1, 20).get("position")).intValue());
        }
        assertCounts(1, 1, 3, 3);
    }

    @Test void duplicateEventFailureRollsBackCountRelationAndOriginalReceipt() throws Exception
    {
        var receipt = receipt("r-failed", "join-failed", 20, "JOIN", "INSERT_RELATION", 0, 0, 0, 1);
        try (SqlSession session = sessions.openSession(false))
        {
            var mapper = session.getMapper(ChainPersistenceMapper.class);
            write(mapper, receipt);
            assertThrows(RuntimeException.class, () -> mapper.insertEvent(1, "r-failed"));
            session.rollback();
        }
        assertCounts(0, 0, 0, 0);
    }

    @Test void sameRequestCannotBeReboundToAnotherMemberOrPayload() throws Exception
    {
        var original = receipt("r-one", "same-key", 20, "JOIN", "INSERT_RELATION", 0, 0, 0, 1);
        persist(original);
        var conflicting = receipt("r-other", "same-key", 21, "JOIN", "INSERT_RELATION", 1, 0, 1, 2);
        try (SqlSession session = sessions.openSession(false))
        {
            var mapper = session.getMapper(ChainPersistenceMapper.class);
            assertEquals(1, mapper.updateCount(conflicting.proposal()));
            assertEquals(1, mapper.insertEntry(conflicting.command(), 2));
            assertThrows(RuntimeException.class, () -> mapper.insertReceipt(ChainReceiptRecord.from(conflicting)));
            session.rollback();
        }
        try (SqlSession session = sessions.openSession(true))
        {
            var originalCommand = session.getMapper(ChainPersistenceMapper.class).findOriginal(1, "same-key").toReceipt().command();
            assertThrows(IllegalStateException.class, () -> conflicting.command().matchesOriginal(originalCommand));
            var changedContent = new ChainCommandIdentity(1, 10, 20, "same-key", ChainParticipationTransition.Action.JOIN, "changed");
            assertThrows(IllegalStateException.class, () -> changedContent.matchesOriginal(originalCommand));
        }
        assertCounts(1, 1, 1, 1);
    }

    @Test void noChangeStillPersistsIndependentOriginalReceiptAndEvent() throws Exception
    {
        persist(receipt("r-first", "first", 20, "JOIN", "INSERT_RELATION", 0, 0, 0, 1));
        var unchanged = receipt("r-nochange", "another", 20, "JOIN", "NO_CHANGE", 1, 1, 1, 1);
        persist(unchanged);
        try (SqlSession session = sessions.openSession(true))
        {
            var mapper = session.getMapper(ChainPersistenceMapper.class);
            assertEquals(unchanged, mapper.findOriginal(1, "another").toReceipt());
            assertEquals(1, ((Number) mapper.lockChain(1, 10).get("version")).longValue());
            assertEquals(1, ((Number) mapper.lockEntry(1, 20).get("version")).longValue());
        }
        assertCounts(1, 1, 2, 2);
    }

    @Test void staleCountAndEntryVersionsCannotConsumeAnotherSlot() throws Exception
    {
        persist(receipt("r-first", "first", 20, "JOIN", "INSERT_RELATION", 0, 0, 0, 1));
        try (SqlSession session = sessions.openSession(true))
        {
            var mapper = session.getMapper(ChainPersistenceMapper.class);
            var stale = receipt("r-stale", "stale", 21, "JOIN", "INSERT_RELATION", 0, 0, 0, 1);
            assertEquals(0, mapper.updateCount(stale.proposal()));
            var wrongEntry = receipt("r-wrong", "wrong", 20, "WITHDRAW", "WITHDRAW_RELATION", 1, 0, 1, 0);
            assertEquals(0, mapper.updateEntry(wrongEntry.command(), wrongEntry.proposal()));
            assertNull(mapper.lockChain(1, 11));
        }
        assertCounts(1, 1, 1, 1);
    }

    @Test void closedExpiredOrFullChainRejectsNewAllocation() throws Exception
    {
        var next = receipt("r-next", "next", 20, "JOIN", "INSERT_RELATION", 0, 0, 0, 1);
        try (Connection c = connection(); Statement s = c.createStatement();
             SqlSession session = sessions.openSession(true))
        {
            var mapper = session.getMapper(ChainPersistenceMapper.class);
            s.executeUpdate("UPDATE im_chain SET status='CLOSED'");
            assertEquals(0, mapper.updateCount(next.proposal()));
            s.executeUpdate("UPDATE im_chain SET status='OPEN', deadline_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND)");
            assertEquals(0, mapper.updateCount(next.proposal()));
            s.executeUpdate("UPDATE im_chain SET deadline_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY), active_count=2");
            var full = receipt("r-full", "full", 20, "JOIN", "INSERT_RELATION", 0, 0, 2, 3);
            assertEquals(0, mapper.updateCount(full.proposal()));
        }
    }

    @Test void originalRequestKeysAreCaseAndTrailingSpaceSensitive() throws Exception
    {
        try (SqlSession session = sessions.openSession(false))
        {
            var mapper = session.getMapper(ChainPersistenceMapper.class);
            for (String key : new String[]{"Key", "key", "key "})
            {
                var receipt = receipt("r-" + key, key, 20, "WITHDRAW", "NO_CHANGE", 0, 0, 0, 0);
                assertEquals(1, mapper.insertReceipt(ChainReceiptRecord.from(receipt)));
                assertEquals(1, mapper.insertEvent(1, receipt.receiptId()));
                assertEquals(receipt, mapper.findOriginal(1, key).toReceipt());
            }
            session.commit();
        }
        assertCounts(0, 0, 3, 3);
    }

    @Test void migrationRerunPreservesFactsAndDatabaseRejectsScopeDrift() throws Exception
    {
        persist(receipt("r-first", "first", 20, "JOIN", "INSERT_RELATION", 0, 0, 0, 1));
        try (Connection c = connection(); Statement s = c.createStatement())
        {
            migrate(c);
            assertThrows(SQLException.class, () -> s.executeUpdate("UPDATE im_chain SET active_count=3"));
            assertThrows(SQLException.class, () -> s.executeUpdate("UPDATE im_chain_entry SET member_id=0"));
            assertThrows(SQLException.class, () -> s.executeUpdate("UPDATE im_chain_command_receipt SET conversation_id=11"));
            assertThrows(SQLException.class, () -> s.executeUpdate("INSERT INTO im_chain_entry_event(chain_id,receipt_id,effect) VALUES(2,'r-first','INSERT_RELATION')"));
        }
        assertCounts(1, 1, 1, 1);
    }

    private static ChainCommandExecutor.Receipt receipt(String id, String requestId, long memberId,
            String action, String effect, long chainVersion, long entryVersion, int before, int after)
    {
        return new ChainCommandExecutor.Receipt(id,
                new ChainCommandIdentity(1, 10, memberId, requestId,
                        ChainParticipationTransition.Action.valueOf(action), "content"),
                new ChainParticipationTransition.Proposal(1, 10, memberId, chainVersion, entryVersion,
                        before, after, ChainParticipationTransition.Effect.valueOf(effect)));
    }

    /** Test transaction only, not a production Store or an authorization substitute. */
    private static void persist(ChainCommandExecutor.Receipt receipt)
    {
        try (SqlSession session = sessions.openSession(false))
        {
            write(session.getMapper(ChainPersistenceMapper.class), receipt);
            session.commit();
        }
    }

    private static void write(ChainPersistenceMapper mapper, ChainCommandExecutor.Receipt receipt)
    {
        assertNotNull(mapper.lockChain(receipt.command().chainId(), receipt.command().conversationId()));
        mapper.lockEntry(receipt.command().chainId(), receipt.command().memberId());
        var effect = receipt.proposal().effect();
        if (effect != ChainParticipationTransition.Effect.NO_CHANGE)
        {
            assertEquals(1, mapper.updateCount(receipt.proposal()));
            if (effect == ChainParticipationTransition.Effect.INSERT_RELATION)
                assertEquals(1, mapper.insertEntry(receipt.command(), 1));
            else assertEquals(1, mapper.updateEntry(receipt.command(), receipt.proposal()));
        }
        assertEquals(1, mapper.insertReceipt(ChainReceiptRecord.from(receipt)));
        assertEquals(1, mapper.insertEvent(receipt.command().chainId(), receipt.receiptId()));
    }

    private static void assertCounts(int active, int entries, int receipts, int events) throws Exception
    {
        try (Connection c = connection(); Statement s = c.createStatement())
        {
            assertEquals(active, scalar(s, "SELECT active_count FROM im_chain WHERE id=1"));
            assertEquals(entries, scalar(s, "SELECT COUNT(*) FROM im_chain_entry"));
            assertEquals(receipts, scalar(s, "SELECT COUNT(*) FROM im_chain_command_receipt"));
            assertEquals(events, scalar(s, "SELECT COUNT(*) FROM im_chain_entry_event"));
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

    private static long scalar(Statement s, String sql) throws SQLException
    {
        try (ResultSet result = s.executeQuery(sql))
        {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }
}
