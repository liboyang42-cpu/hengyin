package com.chengyinhub.business.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.mapper.ImConversationMemberMapper;
import com.chengyinhub.business.mapper.ImMessageMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;

/** 真实 MySQL/InnoDB 上验证 IM 发送与已读的行锁顺序。 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
class ImMessageRecoveryMySqlIT
{
    private static final MysqlIntegrationTestConfig MYSQL =
            MysqlIntegrationTestConfig.fromEnvironment("im_message_recovery");
    private static PooledDataSource dataSource;
    private static SqlSessionFactory factory;

    @BeforeAll
    static void setUp() throws Exception
    {
        try (Connection admin = DriverManager.getConnection(MYSQL.adminUrl(), MYSQL.user(), MYSQL.password());
             Statement statement = admin.createStatement())
        {
            statement.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
            statement.execute("CREATE DATABASE " + MYSQL.databaseName() + " CHARACTER SET utf8mb4");
        }
        dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver",
                MYSQL.databaseUrl(), MYSQL.user(), MYSQL.password());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement())
        {
            statement.execute("CREATE TABLE im_conversation (id BIGINT PRIMARY KEY, status INT NOT NULL) ENGINE=InnoDB");
            statement.execute("CREATE TABLE im_conversation_member (id BIGINT PRIMARY KEY, conversation_id BIGINT NOT NULL,"
                    + " member_id BIGINT NOT NULL, role INT, unread_count INT NOT NULL, last_read_msg_id BIGINT NOT NULL,"
                    + " muted INT, deleted INT, create_time DATETIME, UNIQUE KEY uk_member(conversation_id,member_id)) ENGINE=InnoDB");
            statement.execute("CREATE TABLE im_message (id BIGINT AUTO_INCREMENT PRIMARY KEY, conversation_id BIGINT NOT NULL,"
                    + " sender_id BIGINT NOT NULL, receiver_id BIGINT, msg_type INT, content VARCHAR(255), extra_json TEXT,"
                    + " status INT, check_status INT, trace_id VARCHAR(128), client_message_id VARCHAR(64),"
                    + " request_hash VARCHAR(64), create_time DATETIME) ENGINE=InnoDB");
            statement.execute("INSERT INTO im_conversation VALUES (100,0)");
            statement.execute("INSERT INTO im_conversation_member VALUES (1,100,2,0,0,5,0,0,NOW())");
            statement.execute("INSERT INTO im_message(id,conversation_id,sender_id,status,client_message_id,create_time)"
                    + " VALUES (10,100,1,0,'send_0123456789012345',NOW())");
        }
        factory = mapperFactory();
    }

    @AfterAll
    static void tearDown() throws Exception
    {
        if (dataSource != null) dataSource.forceCloseAll();
        try (Connection admin = DriverManager.getConnection(MYSQL.adminUrl(), MYSQL.user(), MYSQL.password());
             Statement statement = admin.createStatement())
        {
            statement.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
        }
    }

    @BeforeEach
    void resetOwnedConversation() throws Exception
    {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement())
        {
            connection.setAutoCommit(true);
            statement.execute("DELETE FROM im_message WHERE conversation_id=100 AND id>10");
            statement.execute("UPDATE im_conversation_member SET unread_count=0,last_read_msg_id=5 WHERE id=1");
        }
    }

    @Test
    void previewReadsTheActualLastMessageIncludingBlockedStatusAndNeverAnotherConversation() throws Exception
    {
        try (SqlSession session = factory.openSession(true))
        {
            ImMessageMapper mapper = session.getMapper(ImMessageMapper.class);
            ImMessage visible = new ImMessage();
            visible.setConversationId(100L);
            visible.setSenderId(1L);
            visible.setMsgType(ImMessage.MSG_CARD);
            visible.setContent("[路线] earlier");
            visible.setExtraJson("{\"cardType\":\"route\",\"topicId\":7}");
            visible.setStatus(ImMessage.STATUS_NORMAL);
            mapper.insertImMessage(visible);
            ImMessage blocked = new ImMessage();
            blocked.setConversationId(100L);
            blocked.setSenderId(1L);
            blocked.setMsgType(ImMessage.MSG_CARD);
            blocked.setContent("[路线] blocked");
            blocked.setExtraJson("{\"cardType\":\"route\",\"topicId\":8}");
            blocked.setStatus(ImMessage.STATUS_BLOCKED);
            mapper.insertImMessage(blocked);
            ImMessage another = new ImMessage();
            another.setConversationId(200L);
            another.setSenderId(9L);
            another.setStatus(ImMessage.STATUS_NORMAL);
            mapper.insertImMessage(another);
            try
            {
                ImMessage latest = mapper.selectLatestMessage(100L);
                assertEquals(blocked.getId(), latest.getId());
                assertEquals(ImMessage.STATUS_BLOCKED, latest.getStatus());
                assertEquals(visible.getId(), mapper.selectPage(100L, null, 1).get(0).getId());
                assertEquals(another.getId(), mapper.selectLatestMessage(200L).getId());
                assertEquals(null, mapper.selectLatestMessage(300L));
            }
            finally
            {
                try (Statement statement = session.getConnection().createStatement())
                {
                    statement.execute("DELETE FROM im_message WHERE conversation_id=200");
                }
            }
        }
    }

    @Test
    void earlierReadSnapshotMustNotHideASendCommittedBeforeMarkRead() throws Exception
    {
        try (SqlSession reader = factory.openSession(false))
        {
            reader.getConnection().setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try (Statement statement = reader.getConnection().createStatement();
                 java.sql.ResultSet initial = statement.executeQuery("SELECT COUNT(*) FROM im_message"))
            {
                initial.next();
                assertEquals(1, initial.getInt(1));
            }
            try (SqlSession sender = factory.openSession(false))
            {
                ImMessage message = new ImMessage();
                message.setConversationId(100L);
                message.setSenderId(1L);
                message.setReceiverId(2L);
                message.setMsgType(1);
                message.setContent("committed after reader snapshot");
                message.setStatus(0);
                message.setCheckStatus(1);
                message.setClientMessageId("send_0123456789012347");
                sender.getMapper(ImMessageMapper.class).insertImMessage(message);
                sender.getMapper(ImConversationMemberMapper.class).addUnread(100L, 2L, 1);
                sender.commit();
            }
            assertEquals(1, reader.getMapper(ImConversationMemberMapper.class).advanceReadTo(100L, 2L, 10L));
            reader.commit();
        }
        try (SqlSession verify = factory.openSession(true))
        {
            assertEquals(1, verify.getMapper(ImConversationMemberMapper.class)
                    .selectByConvAndMember(100L, 2L).getUnreadCount(),
                    "authentication/read snapshots before a concurrent send must not erase that unread message");
        }
    }

    @Test
    void sendWaitingBehindMarkReadRemainsUnreadAndOldReadCannotRegress() throws Exception
    {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (SqlSession reader = factory.openSession(false))
        {
            ImConversationMemberMapper members = reader.getMapper(ImConversationMemberMapper.class);
            assertEquals(1, members.advanceReadTo(100L, 2L, 10L));
            Future<?> sending = pool.submit(() -> {
                try (SqlSession session = factory.openSession(false))
                {
                    ImMessage message = new ImMessage();
                    message.setConversationId(100L);
                    message.setSenderId(1L);
                    message.setReceiverId(2L);
                    message.setMsgType(1);
                    message.setContent("concurrent");
                    message.setStatus(0);
                    message.setCheckStatus(1);
                    message.setClientMessageId("send_0123456789012346");
                    session.getMapper(ImMessageMapper.class).insertImMessage(message);
                    session.getMapper(ImConversationMemberMapper.class).addUnread(100L, 2L, 1);
                    session.commit();
                }
            });
            Thread.sleep(150L);
            assertFalse(sending.isDone(), "send 的未读更新必须等待 markRead 持有的成员行锁");
            reader.commit();
            sending.get(3, TimeUnit.SECONDS);
        }
        finally
        {
            pool.shutdownNow();
        }
        try (SqlSession verify = factory.openSession(true))
        {
            ImConversationMemberMapper members = verify.getMapper(ImConversationMemberMapper.class);
            assertEquals(0, members.advanceReadTo(100L, 2L, 5L), "旧设备 readTo 不得回退");
            com.chengyinhub.business.domain.ImConversationMember state = members.selectByConvAndMember(100L, 2L);
            assertEquals(10L, state.getLastReadMsgId());
            assertEquals(1, state.getUnreadCount(), "markRead 期间发送的消息不得丢未读");
        }
    }

    private static SqlSessionFactory mapperFactory() throws Exception
    {
        Environment environment = new Environment("mysql-it", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.getTypeAliasRegistry().registerAlias("ImMessage", ImMessage.class);
        configuration.getTypeAliasRegistry().registerAlias("ImConversationMember",
                com.chengyinhub.business.domain.ImConversationMember.class);
        for (String resource : new String[] { "mapper/business/ImMessageMapper.xml",
                "mapper/business/ImConversationMemberMapper.xml" })
        {
            try (InputStream input = Resources.getResourceAsStream(resource))
            {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
