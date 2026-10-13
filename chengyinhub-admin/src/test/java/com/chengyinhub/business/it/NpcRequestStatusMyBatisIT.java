package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.NpcChatRequest;
import com.chengyinhub.business.mapper.NpcChatRequestMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.NpcChatService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class NpcRequestStatusMyBatisIT {
    @Test
    void recreatedServiceReadsExactOriginalPersistedOutcomeWithoutWritesAndIsolatesOwnerAndScope() throws Exception {
        assertEquals("127.0.0.1", System.getenv("CHENGYIN_TEST_MYSQL_HOST"));
        MysqlIntegrationTestConfig target = MysqlIntegrationTestConfig.fromEnvironment("w2_npc_status");
        try (Connection admin = DriverManager.getConnection(target.adminUrl(), target.user(), target.password());
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE `" + target.databaseName() + "` CHARACTER SET utf8mb4");
            try {
                DriverManagerDataSource ds = new DriverManagerDataSource(target.databaseUrl(), target.user(), target.password());
                try (Connection db = ds.getConnection(); Statement ddl = db.createStatement()) {
                    assertEquals(target.databaseName(), db.getCatalog());
                    String schema = new String(Files.readAllBytes(Paths.get("../sql/base_schema_20260818.sql")), StandardCharsets.UTF_8);
                    int start = schema.indexOf("CREATE TABLE `npc_chat_request`");
                    int end = schema.indexOf("\n) ENGINE=", start);
                    assertTrue(start >= 0 && end > start);
                    ddl.execute(schema.substring(start, schema.indexOf(';', end)));
                    ItSchema.applyMigrations(db, "../sql/migration_npc_chat_request_hash_20261013.sql");
                }
                Configuration config = new Configuration(new Environment("npc-status", new JdbcTransactionFactory(), ds));
                String resource = "mapper/business/NpcChatRequestMapper.xml";
                try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                    new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
                }
                SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(config);
                String rid = "abcdef01-abcd-4abc-8abc-abcdefabcdef";
                try (SqlSession first = factory.openSession(true)) {
                    NpcChatRequestMapper mapper = first.getMapper(NpcChatRequestMapper.class);
                    NpcChatRequest row = new NpcChatRequest();
                    row.setUserId(9L); row.setRequestId(rid); row.setSessionId(-11L); row.setStatus("PROCESSING");
                    row.setSafetyDecision("NOT_RUN"); row.setActiveSlot(1); row.setLeaseToken("npc-status-fixture");
                    row.setLeaseUntil(new java.util.Date(System.currentTimeMillis() + 60000));
                    row.setAi(true); row.setRetryable(false);
                    assertEquals(1, mapper.insertNpcChatRequest(row));
                    NpcChatService service = new NpcChatService();
                    ReflectionTestUtils.setField(service, "npcChatRequestMapper", mapper);
                    assertEquals("PROCESSING", service.requestStatus(rid, 9L, "shop", 11L).getOutcomeStatus());
                    row.setStatus("SUCCEEDED"); row.setSafetyDecision("PASS"); row.setSafeText("原审核回答");
                    assertEquals(1, mapper.finishIfLeaseOwned(row));
                }
                try (SqlSession recreated = factory.openSession(true)) {
                    NpcChatRequestMapper mapper = recreated.getMapper(NpcChatRequestMapper.class);
                    NpcChatService service = new NpcChatService();
                    ReflectionTestUtils.setField(service, "npcChatRequestMapper", mapper);
                    assertEquals(rid, service.requestStatus(rid, 9L, "shop", 11L).getRequestId());
                    assertEquals("原审核回答", service.requestStatus(rid, 9L, "shop", 11L).getSafeText());
                    assertNull(service.requestStatus(rid, 9L, "shop", 11L).getAudioUrl());
                    assertEquals("REQUEST_NOT_FOUND", service.requestStatus(rid, 10L, "shop", 11L).getErrorCode());
                    assertEquals("REQUEST_NOT_FOUND", service.requestStatus(rid, 9L, "shop", 12L).getErrorCode());
                    assertEquals("REQUEST_NOT_FOUND", service.requestStatus(rid, 9L, "merchant", 11L).getErrorCode());
                    try (Statement count = recreated.getConnection().createStatement(); ResultSet rows = count.executeQuery("select count(*) from npc_chat_request")) {
                        assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
                    }
                    assertEquals("SUCCEEDED", mapper.selectByUserAndRequestId(9L, rid).getStatus());
                }
            } finally { statement.execute("DROP DATABASE `" + target.databaseName() + "`"); }
        }
    }
}
