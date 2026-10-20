package com.chengyinhub.business.service.support.release;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.common.constant.ProductSlot;
import com.chengyinhub.common.constant.RoleEntityType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual migration + InnoDB + production snapshot mapper/transaction coordinator.
 * Source-domain reads are explicit fixtures: this does not certify concurrent source editing,
 * production routing/permissions, process-crash durability, approval or runtime activation.
 */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT", matches="1")
class ContentCaptureSnapshotMySqlIT {
    private static MysqlIntegrationTestConfig mysql;
    private static DriverManagerDataSource source;
    private static JdbcTemplate jdbc;
    private static boolean created;
    private static List<String> statements;

    @BeforeAll static void createScratch() throws Exception {
        Map<String,String> values=new HashMap<>(System.getenv());
        String host=values.getOrDefault("CHENGYIN_TEST_MYSQL_HOST","127.0.0.1");
        assertTrue("127.0.0.1".equals(host)||"localhost".equals(host),"Only explicit localhost scratch MySQL is allowed");
        values.put("CHENGYIN_TEST_MYSQL_DATABASE_BASE","cy_capture_it");
        values.put("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX","_"+UUID.randomUUID().toString().replace("-",""));
        mysql=MysqlIntegrationTestConfig.from(values,"snapshot");
        // Use the same delimiter-aware splitter as the migration runner, on the actual migration bytes.
        Path root=Paths.get("..").toAbsolutePath().normalize();
        String code="import json,sys; from pathlib import Path; sys.path.insert(0,'ci'); "
                +"from sql_splitter import split_statements,delimiter_error,unterminated_token; "
                +"s=Path('chengyinhub-admin/sql/migration_20261002_content_capture_snapshot.sql').read_text(encoding='utf-8'); "
                +"assert not delimiter_error(s) and not unterminated_token(s); print(json.dumps(split_statements(s)))";
        Process process=new ProcessBuilder("python3","-c",code).directory(root.toFile()).redirectErrorStream(true).start();
        java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
        try(InputStream input=process.getInputStream()){byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);}
        assertTrue(process.waitFor(15,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),"Repository SQL splitter must succeed");
        JsonNode parsed=new ObjectMapper().readTree(output.toByteArray());assertTrue(parsed.isArray());assertEquals(3,parsed.size());
        statements=new ArrayList<>();for(JsonNode statement:parsed)statements.add(statement.asText());
        try(Connection c=DriverManager.getConnection(mysql.adminUrl(),mysql.user(),mysql.password());Statement st=c.createStatement()) {
            try(ResultSet rs=st.executeQuery("SELECT VERSION()")){assertTrue(rs.next());assertTrue(rs.getString(1).startsWith("8.0.45"),"Pinned Oracle MySQL 8.0.45 required");}
            st.execute("CREATE DATABASE `"+mysql.databaseName()+"` CHARACTER SET utf8mb4");created=true;
        }
        source=new DriverManagerDataSource(mysql.databaseUrl(),mysql.user(),mysql.password());jdbc=new JdbcTemplate(source);
    }
    @AfterAll static void dropScratchAndVerify() throws Exception {
        if(!created)return;
        try(Connection c=DriverManager.getConnection(mysql.adminUrl(),mysql.user(),mysql.password());Statement st=c.createStatement()) {
            st.execute("DROP DATABASE `"+mysql.databaseName()+"`");
            try(ResultSet rs=st.executeQuery("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='"+mysql.databaseName()+"'")){assertTrue(rs.next());assertEquals(0,rs.getInt(1));}
        }
        System.out.println("CONTENT_CAPTURE_MYSQL_IT MYSQL_VERSION=8.0.45 ACTUAL_MIGRATION_STATEMENTS=3 SCRATCH_DROPPED=true");
    }
    @BeforeEach void applyActualMigration() throws Exception {
        try(Connection c=source.getConnection();Statement st=c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS w18_cms_template_provenance");
            st.execute("DROP TABLE IF EXISTS cms_member_template");
            st.execute("CREATE TABLE cms_member_template (id BIGINT PRIMARY KEY, w18_provenance_kind VARCHAR(32) NULL) ENGINE=InnoDB");
            st.execute("CREATE TABLE w18_cms_template_provenance (template_id BIGINT PRIMARY KEY) ENGINE=InnoDB");
            st.execute("INSERT INTO cms_member_template(id) VALUES (101)");
            st.execute("DROP TABLE IF EXISTS content_capture_snapshot");
            for(String sql:statements)st.execute(sql);
        }
        assertEquals("InnoDB",jdbc.queryForObject("SELECT ENGINE FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='content_capture_snapshot'",String.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND event_object_table='content_capture_snapshot'",Integer.class));
    }
    @Test void canonicalBytesAndFrozenIdentitySurviveNewConnectionAndFactory() throws Exception {
        ContentCaptureRows rows=sourceRows();rows.templates.get(101L).setQuestionAnswer("答案 中文 😀 exact bytes");
        ContentCaptureCandidate candidate=new ContentCaptureBuilder().build(rows);
        ContentCaptureReceipt first=service(rows,null,false).persistOwnedDraft(10,7,candidate.draftConfigVersion(),"durable",candidate.contentHash());
        rows.templates.get(101L).setQuestionAnswer("edited draft");
        ContentCaptureReceipt replay=service(rows,null,false).persistOwnedDraft(10,7,candidate.draftConfigVersion(),"durable",candidate.contentHash());
        assertEquals(first.snapshotId(),replay.snapshotId());assertEquals(candidate.serverOnlyJson(),replay.serverOnlyJson());
        byte[] stored=jdbc.queryForObject("SELECT CAST(canonical_json AS BINARY) FROM content_capture_snapshot",byte[].class);
        assertArrayEquals(candidate.serverOnlyJson().getBytes(StandardCharsets.UTF_8),stored);
        assertEquals("FROZEN_INCOMPLETE",jdbc.queryForObject("SELECT state FROM content_capture_snapshot",String.class));
        assertFalse(replay.isCompleteRelease());assertThrows(IllegalStateException.class,replay::requireCompleteRelease);
    }
    @Test void actualMigrationUpdateTriggerRejectsContentAndMetadataChanges() throws Exception {
        insert("update");
        for(String assignment:Arrays.asList("canonical_json='{}'","content_hash=REPEAT('a',64)","state='APPROVED'","captured_by_member_id=8","request_id='other'")) {
            DataAccessException denied=assertThrows(DataAccessException.class,()->jdbc.update("UPDATE content_capture_snapshot SET "+assignment));
            assertSqlState45000(denied);
        }
        assertEquals(1,count());assertEquals("FROZEN_INCOMPLETE",jdbc.queryForObject("SELECT state FROM content_capture_snapshot",String.class));
    }
    @Test void actualMigrationDeleteTriggerRejectsRemoval() throws Exception {
        insert("delete");assertSqlState45000(assertThrows(DataAccessException.class,()->jdbc.update("DELETE FROM content_capture_snapshot")));assertEquals(1,count());
    }
    @Test void asciiBinaryRequestKeysAndOwnedLookupAreExact() throws Exception {
        ContentCaptureCandidate c=candidate();ContentCaptureSnapshotMapper mapper=new SqlSessionTemplate(factory(null,null,false)).getMapper(ContentCaptureSnapshotMapper.class);
        assertEquals("ascii_bin",jdbc.queryForObject("SELECT collation_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='content_capture_snapshot' AND column_name='request_id'",String.class));
        mapper.insertFrozen(row(c,"Request"));mapper.insertFrozen(row(c,"request"));
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->mapper.insertFrozen(row(c,"Request")));
        assertNull(mapper.selectOwnedRequest(10,8,"Request"));assertNull(mapper.selectOwnedRequest(11,7,"Request"));
        assertNotEquals(mapper.selectOwnedRequest(10,7,"Request").getId(),mapper.selectOwnedRequest(10,7,"request").getId());assertEquals(2,count());
    }
    @Test void transactionRollbackLeavesNoDurableRow() throws Exception {
        ContentCaptureSnapshotMapper mapper=new SqlSessionTemplate(factory(null,null,false)).getMapper(ContentCaptureSnapshotMapper.class);
        new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status->{mapper.insertFrozen(row(candidate(),"rollback"));status.setRollbackOnly();return null;});
        assertEquals(0,count());
    }
    @Test void postInsertReceiptFailureRollsBackActualMySqlInsert() throws Exception {
        ContentCaptureRows rows=sourceRows();ContentCaptureCandidate c=new ContentCaptureBuilder().build(rows);
        assertThrows(IllegalStateException.class,()->service(rows,null,true).persistOwnedDraft(10,7,c.draftConfigVersion(),"corrupt",c.contentHash()));assertEquals(0,count());
    }
    @Test void previewStartsReadOnlyTransactionAndServerRejectsWriteWithoutJdbcHint() throws Exception {
        DriverManagerDataSource original=source;AtomicInteger denied=new AtomicInteger();
        // Test-only negative control: clearing the client hint must not clear server transaction mode.
        // This option is never added to the production datasource or ordinary IT connections.
        source=new DriverManagerDataSource(mysql.databaseUrl()+"&readOnlyPropagatesToServer=false",mysql.user(),mysql.password());
        try {
            ContentCaptureCandidate preview=new ConsistentContentCapture(factory(sourceRows(),null,false,denied)).captureOwnedDraft(10,7);
            assertFalse(preview.isCompleteRelease());assertEquals(1,denied.get());assertEquals(0,count());
        } finally {source=original;}
    }

    @Test void concurrentSameRequestAndContentReplayOneImmutableReceipt() throws Exception { race(false); }
    @Test void concurrentSameRequestWithDifferentContentRejectsLoser() throws Exception { race(true); }

    private void race(boolean changed) throws Exception {
        ContentCaptureRows a=sourceRows(),b=sourceRows();if(changed)b.templates.get(101L).setQuestionAnswer("different canonical source");
        ContentCaptureCandidate ca=new ContentCaptureBuilder().build(a),cb=new ContentCaptureBuilder().build(b);
        Race race=new Race();ConsistentContentCapture sa=service(a,race,false),sb=service(b,race,false);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        List<Object> results=new ArrayList<>();
        try {
            Future<Object> fa=pool.submit(()->attempt(sa,ca));Future<Object> fb=pool.submit(()->attempt(sb,cb));
            results.add(fa.get(25,TimeUnit.SECONDS));results.add(fb.get(25,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
        String diagnostic=outcomeTypes(results);
        System.out.println("CONTENT_CAPTURE_RACE differentContent="+changed+" outcomes="+diagnostic);
        assertEquals(2,race.emptyReads.get(),"Both transactions must observe absence before either insert; "+diagnostic);
        assertEquals(1,race.recoveryReads.get(),"The duplicate loser must reopen a read-only transaction after rollback; "+diagnostic);
        assertEquals(1,count());
        List<ContentCaptureReceipt> success=new ArrayList<>();List<Throwable> errors=new ArrayList<>();
        for(Object result:results){if(result instanceof ContentCaptureReceipt)success.add((ContentCaptureReceipt)result);else errors.add((Throwable)result);}
        assertEquals(changed?1:2,success.size());assertEquals(changed?1:0,errors.size());
        ContentCaptureReceipt winner=success.get(0);assertEquals(winner.contentHash(),jdbc.queryForObject("SELECT content_hash FROM content_capture_snapshot",String.class));
        if(changed){assertTrue(errors.get(0) instanceof IllegalStateException);assertNotEquals(ca.contentHash(),cb.contentHash());}
        else {assertEquals(success.get(0).snapshotId(),success.get(1).snapshotId());assertEquals(ca.serverOnlyJson(),winner.serverOnlyJson());}
    }
    private static String outcomeTypes(List<Object> results) {
        List<String> types=new ArrayList<>();
        for(Object value:results) {
            if(!(value instanceof Throwable)){types.add(value.getClass().getSimpleName());continue;}
            StringBuilder chain=new StringBuilder();Throwable failure=(Throwable)value;
            for(int i=0;failure!=null && i<12;i++,failure=failure.getCause()) {
                if(i>0)chain.append(" -> ");chain.append(failure.getClass().getName());
                if(failure instanceof SQLException)chain.append("[state=").append(((SQLException)failure).getSQLState()).append(",code=").append(((SQLException)failure).getErrorCode()).append("]");
            }
            types.add(chain.toString());
        }
        return types.toString();
    }
    private static Object attempt(ConsistentContentCapture service,ContentCaptureCandidate c) {
        try{return service.persistOwnedDraft(10,7,c.draftConfigVersion(),"racing-request",c.contentHash());}
        catch(RuntimeException failure){return failure;}
        finally {assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());}
    }
    private static int count(){return jdbc.queryForObject("SELECT COUNT(*) FROM content_capture_snapshot",Integer.class);}
    private static void assertSqlState45000(Throwable error){while(error!=null){if(error instanceof SQLException && "45000".equals(((SQLException)error).getSQLState()))return;error=error.getCause();}fail("Actual SIGNAL SQLSTATE 45000 required");}
    private static void insert(String request)throws Exception {new SqlSessionTemplate(factory(null,null,false)).getMapper(ContentCaptureSnapshotMapper.class).insertFrozen(row(candidate(),request));}
    private static ConsistentContentCapture service(ContentCaptureRows rows,Race race,boolean corrupt)throws Exception{return new ConsistentContentCapture(factory(rows,race,corrupt));}
    private static SqlSessionFactory factory(ContentCaptureRows rows,Race race,boolean corrupt)throws Exception {
        return factory(rows,race,corrupt,null);
    }
    private static SqlSessionFactory factory(ContentCaptureRows rows,Race race,boolean corrupt,AtomicInteger readOnlyProbe)throws Exception {
        Configuration config=new Configuration(new Environment("mysql-candidate",new SpringManagedTransactionFactory(),source));
        String resource="mapper/business/ContentCaptureSnapshotMapper.xml";
        try(InputStream in=ContentCaptureSnapshotMySqlIT.class.getClassLoader().getResourceAsStream(resource)){assertNotNull(in);new XMLMapperBuilder(in,config,resource,config.getSqlFragments()).parse();}
        String provenanceResource="mapper/business/LegacyCmsProvenanceMapper.xml";
        try(InputStream in=ContentCaptureSnapshotMySqlIT.class.getClassLoader().getResourceAsStream(provenanceResource)){assertNotNull(in);new XMLMapperBuilder(in,config,provenanceResource,config.getSqlFragments()).parse();}
        if(rows!=null){
            Class<?>[] types={CmsTopicMapper.class,ProductRoleMapper.class,CmsTopicNodeMapper.class,CmsTopicChapterMapper.class,ThemeStateKeyMapper.class,CmsMemberTemplateMapper.class,CmsRegistrationMerchantMapper.class,SmsCouponMapper.class,TopicSaleSnapshotMapper.class,NpcProfileMapper.class,MmsMerchantMapper.class};
            String[] methods={"selectCmsTopicById","selectActiveByTopicAndSlot","selectCmsTopicNodeList","selectCmsTopicChapterList","selectByTopicId","selectCmsMemberTemplateByIds","selectCmsRegistrationMerchantList","selectSmsCouponById","selectSnapshotByTopicId","selectEnabledByScope","selectMmsMerchantList"};
            for(int i=0;i<types.length;i++){config.addMapper(types[i]);config.addMappedStatement(new MappedStatement.Builder(config,types[i].getName()+"."+methods[i],new StaticSqlSource(config,"SELECT 1"),SqlCommandType.SELECT).build());}
            config.addInterceptor(new Sources(rows,race,corrupt,readOnlyProbe));
        }
        return new SqlSessionFactoryBuilder().build(config);
    }
    private static ContentCaptureRows sourceRows() {
        ContentCaptureRows rows=TopicPublicationValidationContextTest.clean();rows.npcScopesCaptured=true;
        for(CmsTopicNode node:rows.nodes)rows.nodeNpcs.put(node.getId(),Collections.emptyList());return rows;
    }
    private static ContentCaptureCandidate candidate() { return new ContentCaptureBuilder().build(sourceRows()); }
    private static ContentCaptureSnapshot row(ContentCaptureCandidate c,String request) {
        ContentCaptureSnapshot r=new ContentCaptureSnapshot();r.setTopicId(c.topicId());r.setCapturedByMemberId(7L);r.setRequestId(request);
        r.setSourceConfigVersion(c.draftConfigVersion());r.setContentHash(c.contentHash());r.setCanonicalJson(c.serverOnlyJson());
        r.setState("FROZEN_INCOMPLETE");r.setContractVersion("questify.content-capture-candidate.v1");r.setCanonicalizationVersion("questify.canonical-content-json.v1");return r;
    }
    static class Race {final CyclicBarrier absent=new CyclicBarrier(2);final AtomicInteger emptyReads=new AtomicInteger(),recoveryReads=new AtomicInteger();}
    @Intercepts(@Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}))
    static class Sources implements Interceptor {
        final ContentCaptureRows rows;final Race race;final boolean corrupt;final AtomicInteger readOnlyProbe;
        final ThreadLocal<Boolean> waited=ThreadLocal.withInitial(()->false);
        Sources(ContentCaptureRows rows,Race race,boolean corrupt,AtomicInteger readOnlyProbe){this.rows=rows;this.race=race;this.corrupt=corrupt;this.readOnlyProbe=readOnlyProbe;}
        public Object intercept(Invocation invocation)throws Throwable {
            String id=((MappedStatement)invocation.getArgs()[0]).getId();
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            if(id.startsWith(ContentCaptureSnapshotMapper.class.getName())) {
                Object result=invocation.proceed();
                if(race!=null && ((List<?>)result).isEmpty() && !waited.get()) {waited.set(true);race.emptyReads.incrementAndGet();race.absent.await(10,TimeUnit.SECONDS);}
                if(corrupt)for(Object row:(List<?>)result)((ContentCaptureSnapshot)row).setState("APPROVED");
                return result;
            }
            if(id.equals(LegacyCmsProvenanceMapper.class.getName()+".selectLegacyBlockState")) return invocation.proceed();
            if(id.endsWith(".selectCmsTopicById")){if(readOnlyProbe!=null)assertServerReadOnly();if(race!=null && TransactionSynchronizationManager.isCurrentTransactionReadOnly())race.recoveryReads.incrementAndGet();return Collections.singletonList(rows.topic);}
            if(id.endsWith(".selectActiveByTopicAndSlot")){ProductRole p=new ProductRole();p.setTopicId(10L);p.setSlot(ProductSlot.CONTENT);p.setStatus(1);p.setEntityType(RoleEntityType.MEMBER);p.setEntityId(7L);return Collections.singletonList(p);}
            if(id.endsWith(".selectCmsTopicNodeList"))return rows.nodes;if(id.endsWith(".selectCmsTopicChapterList"))return rows.chapters;
            if(id.endsWith(".selectByTopicId"))return rows.keys;if(id.endsWith(".selectCmsMemberTemplateByIds"))return new ArrayList<>(rows.templates.values());
            if(id.endsWith(".selectEnabledByScope")||id.endsWith(".selectCmsRegistrationMerchantList")||id.endsWith(".selectSnapshotByTopicId"))return Collections.emptyList();
            throw new AssertionError("Unexpected source fixture read: "+id);
        }
        private void assertServerReadOnly() throws SQLException {
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            Connection c=DataSourceUtils.getConnection(source);
            try {
                assertFalse(c.getAutoCommit());assertEquals(Connection.TRANSACTION_REPEATABLE_READ,c.getTransactionIsolation());assertTrue(c.isReadOnly());
                c.setReadOnly(false);assertFalse(c.isReadOnly());
                try(Statement statement=c.createStatement()) {
                    SQLException rejected=assertThrows(SQLException.class,()->statement.executeUpdate("INSERT INTO content_capture_snapshot (topic_id,captured_by_member_id,request_id,source_config_version,content_hash,canonical_json,state,contract_version,canonicalization_version) VALUES (10,7,'forbidden',0,REPEAT('a',64),'{}','FROZEN_INCOMPLETE','test','test')"));
                    assertEquals("25006",rejected.getSQLState());assertEquals(1792,rejected.getErrorCode());readOnlyProbe.incrementAndGet();
                } finally {c.setReadOnly(true);}
            } finally {DataSourceUtils.releaseConnection(c,source);}
        }
        public Object plugin(Object target){return Plugin.wrap(target,this);}public void setProperties(Properties ignored){}
    }
}
