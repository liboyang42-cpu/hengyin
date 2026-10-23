package com.chengyinhub.business.service.ai;

import com.chengyinhub.business.domain.PhotoCheckIntentRow;
import com.chengyinhub.business.mapper.PhotoCheckIntentMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** One isolated first-level-cache regression; does not claim complete RR correctness. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(45)
class PhotoCheckIntentLockReadMySqlIT {
    private static final Long ID=77L;
    private static final String HASH="a".repeat(64), KEY="lock-read-original-key", NONCE="c".repeat(32);
    private PhotoCheckIntentMySqlIT fixture;
    private PhotoCheckIntentMapper mapper;
    private TransactionTemplate transactions;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeAll void connect() throws Exception {
        fixture=new PhotoCheckIntentMySqlIT();fixture.connect();
        mapper=(PhotoCheckIntentMapper)ReflectionTestUtils.getField(fixture,"mapper");
        transactions=(TransactionTemplate)ReflectionTestUtils.getField(fixture,"transaction");
        dataSource=(DriverManagerDataSource)ReflectionTestUtils.getField(fixture,"dataSource");
        jdbc=(JdbcTemplate)ReflectionTestUtils.getField(fixture,"jdbc");
    }
    @BeforeEach void cleanScratch() { fixture.cleanOwnedScratchAndStub(); }
    @AfterAll void close() throws Exception { if(fixture!=null) fixture.close(); }

    private Connection transactionConnection() {
        return assertInstanceOf(ConnectionHolder.class,TransactionSynchronizationManager.getResource(dataSource)).getConnection();
    }

    @Test void sameTransactionLockReadRefreshesCachedNullAfterIndependentCommit() throws Exception {
        String context=new ObjectMapper().writeValueAsString(PhotoCheckIntentServiceTest.command(KEY,"media-asset:42",HASH));
        String reservation="ai:gen:limit:photo:7:20261023|"+NONCE;
        TransactionTemplate reader=new TransactionTemplate(transactions.getTransactionManager());
        reader.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        TransactionTemplate writer=new TransactionTemplate(transactions.getTransactionManager());
        writer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reader.execute(status->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            Connection readerConnection=transactionConnection();
            try {assertFalse(readerConnection.getAutoCommit());assertEquals(Connection.TRANSACTION_READ_COMMITTED,readerConnection.getTransactionIsolation());}
            catch(SQLException failure) {throw new IllegalStateException(failure);}
            // RC deliberately removes absent-row gap locking so an independent insert can commit.
            // An existing-row FOR UPDATE lock would block that writer, hiding the cache regression.
            assertNull(mapper.selectForUpdate(ID,HASH));
            Map.Entry<Object,Object> bound=TransactionSynchronizationManager.getResourceMap().entrySet().stream()
                    .filter(entry->entry.getKey() instanceof SqlSessionFactory).findFirst().orElseThrow();
            SqlSessionHolder holder=assertInstanceOf(SqlSessionHolder.class,bound.getValue());
            assertEquals(LocalCacheScope.SESSION,holder.getSqlSession().getConfiguration().getLocalCacheScope());
            writer.execute(write->{
                assertNotSame(readerConnection,transactionConnection());
                assertEquals(1,jdbc.update("INSERT INTO photo_check_intent "
                        +"(id,member_id,session_id,action_key,request_fingerprint,context_json,image_reference,reservation,original_day,nonce,state,created_at,updated_at) "
                        +"VALUES (?,7,8,?,?,?,'media-asset:42',?,'20261023',?,'PREPARED',NOW(3),NOW(3))",
                        ID,KEY,HASH,context,reservation,NONCE));return null;
            });
            // Independent JDBC proves the row is committed before the repeated Mapper read.
            try(Connection independent=dataSource.getConnection();PreparedStatement query=independent.prepareStatement(
                    "SELECT state,nonce,reservation FROM photo_check_intent WHERE id=?")) {
                query.setLong(1,ID);try(ResultSet row=query.executeQuery()) {
                    assertTrue(row.next());assertEquals("PREPARED",row.getString(1));
                    assertEquals(NONCE,row.getString(2));assertEquals(reservation,row.getString(3));assertFalse(row.next());
                }
            } catch(SQLException failure) {throw new IllegalStateException(failure);}
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertSame(readerConnection,transactionConnection());
            assertSame(holder,TransactionSynchronizationManager.getResource(bound.getKey()));
            // Same actual transaction, SqlSession, statement, ID and hash; no manual cache clear.
            PhotoCheckIntentRow current=mapper.selectForUpdate(ID,HASH);
            assertNotNull(current,"first-level cached null must not hide the independently committed row");
            assertEquals(ID,current.getId());assertEquals(NONCE,current.getNonce());assertEquals(reservation,current.getReservation());
            return null;
        });
    }
}
