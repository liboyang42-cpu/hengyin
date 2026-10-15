package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantCrmNote;
import com.chengyinhub.business.domain.MerchantCrmTagAssignment;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantCrmLegacyChildNamespaceMyBatisTest {
    private static final String APP_XML = "mapper/business/MerchantCrmAppMapper.xml";

    @Test
    void legacyOwnerNamespaceNotesAndAssignmentsNeverLeakIntoSameNumericMerchantRow() throws Exception {
        try (SqlSession session = factory().openSession(false)) {
            createSchema(session);
            seedCollision(session);
            MerchantCrmAppMapper mapper = session.getMapper(MerchantCrmAppMapper.class);

            MerchantCrmTagAssignment assignment = mapper.selectTagAssignmentByRequestId(77L, "shared-tag");
            assertEquals(Long.valueOf(4L), assignment.getTagId(),
                    "B 店不得把 owner A 的 version=0 assignment 当成本店幂等回执");

            List<MerchantCrmNote> notes = mapper.selectCustomerNotes(77L, 41L, 20);
            assertEquals(1, notes.size(), "B 店列表不得读到 owner A 的 version=0 note");
            assertEquals("B店备注", notes.get(0).getContent());
            assertEquals("B店备注", mapper.selectNoteByRequestId(77L, "shared-note").getContent());
            assertNull(mapper.selectNoteById(77L, 41L, 1L),
                    "即使客户端猜到 legacy note id，也必须被 version=2 资源边界挡住");
            assertEquals(0, mapper.hideNoteCas(77L, 41L, 1L, 0, 88L, "hide-legacy"));

            MerchantCrmTagAssignment createdAssignment = new MerchantCrmTagAssignment();
            createdAssignment.setMerchantId(77L);
            createdAssignment.setCustomerMemberId(41L);
            createdAssignment.setTagId(5L);
            createdAssignment.setAction(MerchantCrmTagAssignment.ASSIGN);
            createdAssignment.setActorMemberId(88L);
            createdAssignment.setRequestId("new-tag");
            assertEquals(1, mapper.insertTagAssignment(createdAssignment));

            MerchantCrmNote createdNote = new MerchantCrmNote();
            createdNote.setMerchantId(77L);
            createdNote.setCustomerMemberId(41L);
            createdNote.setActorMemberId(88L);
            createdNote.setRequestId("new-note");
            createdNote.setContent("new");
            assertEquals(1, mapper.insertNote(createdNote));

            try (Statement statement = session.getConnection().createStatement();
                 ResultSet versions = statement.executeQuery(
                         "select merchant_scope_version from merchant_customer_tag_assignment "
                                 + "where request_id='new-tag' union all "
                                 + "select merchant_scope_version from merchant_customer_note "
                                 + "where request_id='new-note'")) {
                assertTrue(versions.next());
                assertEquals(2, versions.getInt(1));
                assertTrue(versions.next());
                assertEquals(2, versions.getInt(1));
            }

            String migration = normalized(new String(Files.readAllBytes(Paths.get(
                    "sql/migration_merchant_operator_crm_followup_20260823.sql")), StandardCharsets.UTF_8));
            assertTrue(migration.contains("merchant_customer_tag_assignment_scope_column_stmt"));
            assertTrue(migration.contains("merchant_customer_note_scope_column_stmt"));
            assertTrue(migration.contains("cols = 'merchant_id,merchant_scope_version,request_id'"));
            assertTrue(migration.contains("assignment.merchant_scope_version = 0")
                            && migration.contains("note.merchant_scope_version = 0"),
                    "只允许 version=0 且 owner 唯一可映射的旧行升级");
            session.rollback();
        }
    }

    @Test
    void projectedV2RequestCollisionsFailBeforeLegacyRowsAreUpdated() throws Exception {
        try (SqlSession session = factory().openSession(false)) {
            createSchema(session);
            try (Statement statement = session.getConnection().createStatement()) {
                statement.execute("insert into mms_merchant values (77,900,1,1,0),(78,900,0,1,0)");
                statement.execute("insert into merchant_customer_tag values (3,77,2)");
                statement.execute("insert into merchant_customer_tag_assignment "
                        + "(merchant_id,merchant_scope_version,member_id,tag_id,action,actor_member_id,request_id,create_time) "
                        + "values (900,0,41,3,'ASSIGN',900,'collision-tag',current_timestamp),"
                        + "(77,2,41,3,'ASSIGN',88,'collision-tag',current_timestamp)");
                statement.execute("insert into merchant_customer_note "
                        + "(merchant_id,merchant_scope_version,member_id,actor_member_id,request_id,content,hidden,version,create_time) "
                        + "values (900,0,41,900,'collision-note','legacy',0,0,current_timestamp),"
                        + "(77,2,41,88,'collision-note','exact',0,0,current_timestamp)");

                String migration = new String(Files.readAllBytes(Paths.get(
                        "sql/migration_merchant_operator_crm_followup_20260823.sql")),
                        StandardCharsets.UTF_8);
                String collisionAssertion = assertionQuery(migration, "-- 可唯一映射的 v0 行");
                assertFalse(scalarBoolean(statement, collisionAssertion),
                        "1 个有效门店加 1 个停用门店仍是唯一有效归属，assignment 冲突必须阻断");
                statement.execute("delete from merchant_customer_tag_assignment where merchant_scope_version=2");
                assertFalse(scalarBoolean(statement, collisionAssertion),
                        "note 的 projected v2 requestId 冲突必须阻断");
                statement.execute("delete from merchant_customer_note where merchant_scope_version=2");
                assertTrue(scalarBoolean(statement, collisionAssertion));
            }
            session.rollback();
        }
    }

    private SqlSessionFactory factory() throws Exception {
        Configuration configuration = new Configuration();
        UnpooledDataSource dataSource = new UnpooledDataSource("org.h2.Driver",
                // H2 2.4 reuses this nested NOT EXISTS result across direct DELETEs.
                // Disable only its result reuse in this fixture; preserve the unchanged migration SQL/assertions.
                "jdbc:h2:mem:merchant_crm_child_namespace;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;OPTIMIZE_REUSE_RESULTS=FALSE",
                "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        try (InputStream input = Resources.getResourceAsStream(APP_XML)) {
            new XMLMapperBuilder(input, configuration, APP_XML, configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private void createSchema(SqlSession session) throws Exception {
        try (Statement statement = session.getConnection().createStatement()) {
            statement.execute("drop all objects");
            statement.execute("create table merchant_customer_tag_assignment("
                    + "id bigint auto_increment primary key, merchant_id bigint not null, "
                    + "merchant_scope_version int not null default 0, member_id bigint not null, "
                    + "tag_id bigint not null, action varchar(16) not null, actor_member_id bigint not null, "
                    + "request_id varchar(64) not null, create_time timestamp not null, "
                    + "unique(merchant_id,merchant_scope_version,request_id))");
            statement.execute("create table merchant_customer_note("
                    + "id bigint auto_increment primary key, merchant_id bigint not null, "
                    + "merchant_scope_version int not null default 0, member_id bigint not null, "
                    + "actor_member_id bigint not null, request_id varchar(64) not null, "
                    + "corrects_note_id bigint, content varchar(500) not null, hidden int not null, "
                    + "deleted_by bigint, deleted_at timestamp, hidden_request_id varchar(64), "
                    + "version int not null, create_time timestamp not null, "
                    + "unique(merchant_id,merchant_scope_version,request_id), "
                    + "unique(merchant_id,merchant_scope_version,hidden_request_id))");
            statement.execute("create table mms_merchant(id bigint primary key,member_id bigint not null,"
                    + "status int not null,account_status int not null,del_flag int not null)");
            statement.execute("create table merchant_customer_tag(id bigint primary key,merchant_id bigint not null,"
                    + "merchant_scope_version int not null)");
        }
    }

    private void seedCollision(SqlSession session) throws Exception {
        try (Statement statement = session.getConnection().createStatement()) {
            statement.execute("insert into merchant_customer_tag_assignment "
                    + "(merchant_id,merchant_scope_version,member_id,tag_id,action,actor_member_id,request_id,create_time) "
                    + "values (77,0,41,3,'ASSIGN',77,'shared-tag',current_timestamp),"
                    + "(77,2,41,4,'ASSIGN',88,'shared-tag',current_timestamp)");
            statement.execute("insert into merchant_customer_note "
                    + "(merchant_id,merchant_scope_version,member_id,actor_member_id,request_id,content,hidden,version,create_time) "
                    + "values (77,0,41,77,'shared-note','A店历史备注',0,0,current_timestamp),"
                    + "(77,2,41,88,'shared-note','B店备注',0,0,current_timestamp)");
        }
    }

    private String normalized(String value) {
        return value.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    private String assertionQuery(String migration, String marker) {
        int markerIndex = migration.indexOf(marker);
        int start = migration.indexOf("SELECT NOT EXISTS", markerIndex);
        int end = migration.indexOf(" AS assertion_ok;", start);
        assertTrue(markerIndex >= 0 && start >= 0 && end > start, "未找到迁移冲突预断言");
        return migration.substring(start, end + " AS assertion_ok".length());
    }

    private boolean scalarBoolean(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getBoolean(1);
        }
    }
}
