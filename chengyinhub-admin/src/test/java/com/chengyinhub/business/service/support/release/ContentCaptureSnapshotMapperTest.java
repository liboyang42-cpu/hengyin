package com.chengyinhub.business.service.support.release;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.common.constant.ProductSlot;
import com.chengyinhub.common.constant.RoleEntityType;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real H2 SQL/storage tests; deliberately not MySQL trigger, InnoDB or concurrency certification. */
class ContentCaptureSnapshotMapperTest {
    @TempDir Path directory;

    @Test void canonicalBytesHashAndIdentitySurviveClosedConnectionsAndNewFactory() throws Exception {
        DataSource source=dataSource("durable"); schema(source); ContentCaptureCandidate candidate=candidate();
        ContentCaptureSnapshot row=row(candidate,"capture-1"); long id;
        try(SqlSession session=factory(source).openSession(true)) {
            ContentCaptureSnapshotMapper mapper=session.getMapper(ContentCaptureSnapshotMapper.class);
            // SQL constants, not mutable caller state, determine persisted state/contracts.
            row.setState("APPROVED"); row.setContractVersion("caller-forged");
            assertEquals(1,mapper.insertFrozen(row)); id=row.getId();
            assertEquals(candidate.serverOnlyJson(),mapper.selectOwnedRequest(10,7,"capture-1").getCanonicalJson());
        }
        // No DB_CLOSE_DELAY and no retained connection. Reopen the file-backed database/factory.
        try(SqlSession session=factory(dataSource("durable")).openSession(true)) {
            ContentCaptureSnapshot stored=session.getMapper(ContentCaptureSnapshotMapper.class).selectOwnedRequest(10,7,"capture-1");
            ContentCaptureReceipt receipt=ContentCaptureReceipt.verify(stored,10,7,"capture-1",candidate.draftConfigVersion(),candidate.contentHash());
            assertEquals(id,receipt.snapshotId());assertEquals(candidate.serverOnlyJson(),receipt.serverOnlyJson());
            assertFalse(receipt.isCompleteRelease());assertThrows(IllegalStateException.class,receipt::requireCompleteRelease);
        }
    }
    @Test void databaseUniquenessAndOwnedLookupAreExactIncludingRequestCase() throws Exception {
        DataSource source=dataSource("keys");schema(source);ContentCaptureCandidate candidate=candidate();
        try(SqlSession session=factory(source).openSession(true)) {
            ContentCaptureSnapshotMapper mapper=session.getMapper(ContentCaptureSnapshotMapper.class);
            assertEquals(1,mapper.insertFrozen(row(candidate,"Request-1")));
            assertThrows(org.apache.ibatis.exceptions.PersistenceException.class,()->mapper.insertFrozen(row(candidate,"Request-1")));
            assertEquals(1,mapper.insertFrozen(row(candidate,"request-1")));
            assertNull(mapper.selectOwnedRequest(10,8,"Request-1"));assertNull(mapper.selectOwnedRequest(11,7,"Request-1"));
            assertNotEquals(mapper.selectOwnedRequest(10,7,"Request-1").getId(),mapper.selectOwnedRequest(10,7,"request-1").getId());
        }
    }
    @Test void rollbackLeavesNoDurableCandidateOrReceipt() throws Exception {
        DataSource source=dataSource("rollback");schema(source);SqlSessionFactory factory=factory(source);
        TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.execute(status->{new SqlSessionTemplate(factory).getMapper(ContentCaptureSnapshotMapper.class).insertFrozen(row(candidate(),"rollback"));status.setRollbackOnly();return null;});
        try(SqlSession session=factory.openSession(true)) { assertNull(session.getMapper(ContentCaptureSnapshotMapper.class).selectOwnedRequest(10,7,"rollback")); }
    }
    @Test void sameExpectedContentUnderNewRequestGetsSnapshotIdentityWithoutReleaseIdentity() throws Exception {
        DataSource source=dataSource("identity");schema(source);ContentCaptureCandidate candidate=candidate();
        try(SqlSession session=factory(source).openSession(true)) {
            ContentCaptureSnapshotMapper mapper=session.getMapper(ContentCaptureSnapshotMapper.class);
            ContentCaptureSnapshot first=row(candidate,"first"),second=row(candidate,"second");mapper.insertFrozen(first);mapper.insertFrozen(second);
            assertNotEquals(first.getId(),second.getId());assertEquals(first.getContentHash(),second.getContentHash());
            assertTrue(Arrays.stream(ContentCaptureReceipt.class.getMethods()).noneMatch(m->m.getName().toLowerCase(Locale.ROOT).contains("releaseid")));
        }
    }
    @Test void storedEnvelopeAndHashTamperingIsRejected() {
        ContentCaptureCandidate candidate=candidate();
        for(int fault=0;fault<14;fault++) {
            ContentCaptureSnapshot row=row(candidate,"capture");row.setId(1L);
            if(fault==0)row.setContentHash(repeat('a',64));if(fault==1)row.setSourceConfigVersion(candidate.draftConfigVersion()+1);
            if(fault==2)row.setTopicId(99L);if(fault==3)row.setCapturedByMemberId(99L);if(fault==4)row.setRequestId("other");
            if(fault==5)row.setState("APPROVED");if(fault==6)row.setContractVersion("v2");if(fault==7)row.setCanonicalizationVersion("v2");
            if(fault==8)row.setCanonicalJson(row.getCanonicalJson()+" ");if(fault==9)row.setCanonicalJson("{}");
            if(fault==10)row.setId(0L);if(fault==11)row.setCanonicalJson(row.getCanonicalJson().replace("\"completeRelease\":false","\"completeRelease\":true"));

            if(fault==13)row.setCanonicalJson(row.getCanonicalJson().replace("\"topicId\":10","\"topicId\":11"));
            // Field 12 does not depend on the fixture's incidental config version.
            if(fault==12)row.setCanonicalJson(row.getCanonicalJson().replace("\"draftConfigVersion\":"+candidate.draftConfigVersion(),"\"draftConfigVersion\":"+(candidate.draftConfigVersion()+1)));
            final ContentCaptureSnapshot invalid=row;
            assertThrows(RuntimeException.class,()->ContentCaptureReceipt.verify(invalid,10,7,"capture",candidate.draftConfigVersion(),candidate.contentHash()),"fault "+fault);
        }
    }
    @Test void ordinarySerializationCannotExposeFrozenAnswersOrNpcKnowledge() throws Exception {
        ContentCaptureSnapshot row=row(candidate(),"secret");
        assertFalse(CanonicalContentJson.JSON.writeValueAsString(row).contains("canonicalJson"));
        assertFalse(com.alibaba.fastjson2.JSON.toJSONString(row).contains("canonicalJson"));
        row.setId(1L);ContentCaptureReceipt receipt=ContentCaptureReceipt.verify(row,10,7,"secret",row.getSourceConfigVersion(),row.getContentHash());
        assertFalse(CanonicalContentJson.JSON.writeValueAsString(receipt).contains("components"));
        assertFalse(com.alibaba.fastjson2.JSON.toJSONString(receipt).contains("components"));
    }
    @Test void additiveMigrationDeclaresImmutableGuardsAndMapperHasNoMutationRoutes() throws Exception {
        String ddl=migration();
        assertTrue(ddl.contains("ENGINE=InnoDB"));assertTrue(ddl.contains("COLLATE ascii_bin"));
        assertTrue(ddl.contains("content_capture_snapshot_no_update BEFORE UPDATE"));
        assertTrue(ddl.contains("content_capture_snapshot_no_delete BEFORE DELETE"));
        assertEquals(2,ddl.split("FOR EACH ROW SIGNAL SQLSTATE '45000'",-1).length-1);
        assertTrue(ddl.contains("DELIMITER $$"));assertTrue(ddl.contains("cannot be updated'$$"));
        assertTrue(ddl.contains("cannot be deleted'$$\nDELIMITER ;"));
        assertEquals(2,ContentCaptureSnapshotMapper.class.getDeclaredMethods().length);
        assertTrue(Arrays.stream(ContentCaptureSnapshotMapper.class.getMethods()).noneMatch(m->m.getName().startsWith("update")||m.getName().startsWith("delete")));
        // H2 cannot execute MySQL SIGNAL triggers. This assertion is structural only, never a live guard test.
    }
    @Test void inactiveServiceUsesRealSnapshotMapperAndCommitsOrReplaysAcrossFactories() throws Exception {
        DataSource source=dataSource("service");schema(source);ContentCaptureRows rows=sourceRows();
        ContentCaptureCandidate expected=new ContentCaptureBuilder().build(rows);
        ConsistentContentCapture service=new ConsistentContentCapture(sourceFactory(source,rows));
        ContentCaptureReceipt receipt=service.persistOwnedDraft(10,7,expected.draftConfigVersion(),"service-1",expected.contentHash());
        assertEquals(expected.serverOnlyJson(),receipt.serverOnlyJson());
        rows.templates.get(101L).setQuestionAnswer("changed after commit");
        ContentCaptureReceipt replay=new ConsistentContentCapture(sourceFactory(dataSource("service"),rows))
                .persistOwnedDraft(10,7,expected.draftConfigVersion(),"service-1",expected.contentHash());
        assertEquals(receipt.snapshotId(),replay.snapshotId());assertEquals(receipt.serverOnlyJson(),replay.serverOnlyJson());
        assertEquals(1,new JdbcTemplate(source).queryForObject("select count(*) from content_capture_snapshot",Integer.class));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
    }
    @Test void inactiveServiceHashMismatchDoesNotWriteRealDatabase() throws Exception {
        DataSource source=dataSource("stale");schema(source);ContentCaptureRows rows=sourceRows();
        ContentCaptureCandidate expected=new ContentCaptureBuilder().build(rows);rows.templates.get(101L).setQuestionAnswer("new child");
        assertThrows(IllegalStateException.class,()->new ConsistentContentCapture(sourceFactory(source,rows))
                .persistOwnedDraft(10,7,expected.draftConfigVersion(),"stale",expected.contentHash()));
        assertEquals(0,new JdbcTemplate(source).queryForObject("select count(*) from content_capture_snapshot",Integer.class));
    }

    @Test void serviceReadbackFailureRollsBackActualInsert() throws Exception {
        DataSource source=dataSource("readback-failure");schema(source);ContentCaptureRows rows=sourceRows();
        ContentCaptureCandidate expected=new ContentCaptureBuilder().build(rows);
        assertThrows(IllegalStateException.class,()->new ConsistentContentCapture(sourceFactory(source,rows,true))
                .persistOwnedDraft(10,7,expected.draftConfigVersion(),"broken-readback",expected.contentHash()));
        assertEquals(0,new JdbcTemplate(source).queryForObject("select count(*) from content_capture_snapshot",Integer.class));
    }

    @Test void largeUnderLimitNpcCandidatePersistsAndReplaysThroughRealMapper() throws Exception {
        DataSource source=dataSource("large-boundary");schema(source);ContentCaptureRows rows=CanonicalContentJsonBoundaryTest.npcRows(2400);
        ContentCaptureCandidate expected=new ContentCaptureBuilder().build(rows);
        ContentCaptureReceipt first=new ConsistentContentCapture(sourceFactory(source,rows))
                .persistOwnedDraft(10,7,expected.draftConfigVersion(),"large",expected.contentHash());
        ContentCaptureReceipt replay=new ConsistentContentCapture(sourceFactory(dataSource("large-boundary"),rows))
                .persistOwnedDraft(10,7,expected.draftConfigVersion(),"large",expected.contentHash());
        assertEquals(first.snapshotId(),replay.snapshotId());assertEquals(expected.serverOnlyJson(),replay.serverOnlyJson());
    }
    @Test void oversizedNpcAggregateNeverCreatesRowInRealStorage() throws Exception {
        DataSource source=dataSource("large-rejected");schema(source);ContentCaptureRows rows=CanonicalContentJsonBoundaryTest.npcRows(2450);
        assertThrows(IllegalArgumentException.class,()->new ConsistentContentCapture(sourceFactory(source,rows))
                .persistOwnedDraft(10,7,rows.topic.getConfigVersion(),"oversized",repeat('a',64)));
        assertEquals(0,new JdbcTemplate(source).queryForObject("select count(*) from content_capture_snapshot",Integer.class));
    }

    @Test void realMarkerOrSidecarBlocksCaptureBeforeSnapshotPersistence() throws Exception {
        for(int fault=0;fault<4;fault++) {
            DataSource source=dataSource("provenance-block-"+fault);schema(source);
            JdbcTemplate jdbc=new JdbcTemplate(source);ContentCaptureRows rows=sourceRows();
            ContentCaptureCandidate expected=new ContentCaptureBuilder().build(rows);
            if(fault<3)jdbc.update("update cms_member_template set w18_provenance_kind=? where id=101",
                    new String[]{"W18_MANAGED","","FUTURE_KIND"}[fault]);
            else jdbc.update("insert into w18_cms_template_provenance(template_id) values(101)");
            assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                    ()->new ConsistentContentCapture(sourceFactory(source,rows))
                            .persistOwnedDraft(10,7,expected.draftConfigVersion(),"blocked",expected.contentHash()));
            assertEquals(0,jdbc.queryForObject("select count(*) from content_capture_snapshot",Integer.class));
        }
    }

    @Test void missingCanonicalTemplateCannotBecomeLegacyCaptureEvidence() throws Exception {
        DataSource source=dataSource("missing-canonical");schema(source);JdbcTemplate jdbc=new JdbcTemplate(source);
        ContentCaptureRows rows=sourceRows();ContentCaptureCandidate expected=new ContentCaptureBuilder().build(rows);
        jdbc.update("delete from cms_member_template where id=101");
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                ()->new ConsistentContentCapture(sourceFactory(source,rows))
                        .persistOwnedDraft(10,7,expected.draftConfigVersion(),"missing",expected.contentHash()));
        assertEquals(0,jdbc.queryForObject("select count(*) from content_capture_snapshot",Integer.class));
    }

    @Test void missingProvenanceSchemaFailsAsRealSqlAndLeavesNoSnapshot() throws Exception {
        for(int fault=0;fault<2;fault++) {
            DataSource source=dataSource("missing-schema-"+fault);schema(source);JdbcTemplate jdbc=new JdbcTemplate(source);
            ContentCaptureRows rows=sourceRows();ContentCaptureCandidate expected=new ContentCaptureBuilder().build(rows);
            String missing=fault==0?"W18_CMS_TEMPLATE_PROVENANCE":"W18_PROVENANCE_KIND";
            if(fault==0)jdbc.execute("drop table w18_cms_template_provenance");
            else jdbc.execute("alter table cms_member_template drop column w18_provenance_kind");
            org.springframework.jdbc.BadSqlGrammarException failure=assertThrows(
                    org.springframework.jdbc.BadSqlGrammarException.class,
                    ()->new ConsistentContentCapture(sourceFactory(source,rows))
                            .persistOwnedDraft(10,7,expected.draftConfigVersion(),"missing-schema",expected.contentHash()));
            assertTrue(failure.getMessage().toUpperCase(Locale.ROOT).contains(missing),failure.toString());
            assertNotNull(failure.getSQLException());
            assertTrue(failure.getSQLException().getSQLState().startsWith("42"));
            assertEquals(0,jdbc.queryForObject("select count(*) from content_capture_snapshot",Integer.class));
        }
    }

    private DataSource dataSource(String name) { return new DriverManagerDataSource("jdbc:h2:file:"+directory.resolve(name).toAbsolutePath()+";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE","sa",""); }
    private static String migration() throws Exception { Path path=Paths.get("sql/migration_20261002_content_capture_snapshot.sql");if(!Files.exists(path))path=Paths.get("chengyinhub-admin/sql/migration_20261002_content_capture_snapshot.sql");return new String(Files.readAllBytes(path),StandardCharsets.UTF_8); }
    private static void schema(DataSource source)throws Exception {
        String sql=migration();int start=sql.indexOf("CREATE TABLE");
        // Existing H2 1.4 lacks MySQL per-column charset/collation syntax. Remove only that
        // syntax for the local fixture; binary-collation behavior and triggers need actual MySQL.
        new JdbcTemplate(source).execute(sql.substring(start,sql.indexOf(';',start)+1)
                .replace(" CHARACTER SET ascii COLLATE ascii_bin", ""));
        // Minimal real provenance schema for the existing sourceRows template 101.
        // Source body readers remain the original fixture; provenance never uses that interceptor.
        JdbcTemplate jdbc=new JdbcTemplate(source);
        jdbc.execute("create table cms_member_template(id bigint primary key,w18_provenance_kind varchar(32) null)");
        jdbc.execute("create table w18_cms_template_provenance(template_id bigint primary key)");
        jdbc.update("insert into cms_member_template(id,w18_provenance_kind) values(101,null)");
    }
    private static Configuration configuration(DataSource source)throws Exception {
        Configuration config=new Configuration(new Environment("h2-candidate",new SpringManagedTransactionFactory(),source));
        for(Class<?> mapper:new Class<?>[]{ContentCaptureSnapshotMapper.class,LegacyCmsProvenanceMapper.class}) {
            String resource="mapper/business/"+mapper.getSimpleName()+".xml";
            try(InputStream stream=ContentCaptureSnapshotMapperTest.class.getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(stream);new XMLMapperBuilder(stream,config,resource,config.getSqlFragments()).parse();
            }
        }
        return config;
    }
    private static SqlSessionFactory factory(DataSource source)throws Exception { return new SqlSessionFactoryBuilder().build(configuration(source)); }
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
    private static String repeat(char value,int count) { char[] chars=new char[count];Arrays.fill(chars,value);return new String(chars); }
    private static SqlSessionFactory sourceFactory(DataSource source,ContentCaptureRows rows)throws Exception {
        return sourceFactory(source,rows,false);
    }
    private static SqlSessionFactory sourceFactory(DataSource source,ContentCaptureRows rows,boolean corruptReadback)throws Exception {
        Configuration config=configuration(source);
        Class<?>[] types={CmsTopicMapper.class,ProductRoleMapper.class,CmsTopicNodeMapper.class,CmsTopicChapterMapper.class,ThemeStateKeyMapper.class,
                CmsMemberTemplateMapper.class,CmsRegistrationMerchantMapper.class,SmsCouponMapper.class,TopicSaleSnapshotMapper.class,NpcProfileMapper.class,MmsMerchantMapper.class};
        String[] methods={"selectCmsTopicById","selectActiveByTopicAndSlot","selectCmsTopicNodeList","selectCmsTopicChapterList","selectByTopicId",
                "selectCmsMemberTemplateByIds","selectCmsRegistrationMerchantList","selectSmsCouponById","selectSnapshotByTopicId","selectEnabledByScope","selectMmsMerchantList"};
        for(int i=0;i<types.length;i++){config.addMapper(types[i]);config.addMappedStatement(new MappedStatement.Builder(config,types[i].getName()+"."+methods[i],new StaticSqlSource(config,"SELECT 1"),SqlCommandType.SELECT).build());}
        config.addInterceptor(new SourceFixtures(rows,corruptReadback));return new SqlSessionFactoryBuilder().build(config);
    }
    @Intercepts(@Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}))
    static class SourceFixtures implements Interceptor {
        private final ContentCaptureRows rows;private final boolean corruptReadback;
        SourceFixtures(ContentCaptureRows rows,boolean corruptReadback){this.rows=rows;this.corruptReadback=corruptReadback;}
        public Object intercept(Invocation invocation)throws Throwable {
            String id=((MappedStatement)invocation.getArgs()[0]).getId();
            if(id.startsWith(LegacyCmsProvenanceMapper.class.getName()+".")) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                return invocation.proceed();
            }
            if(id.startsWith(ContentCaptureSnapshotMapper.class.getName())) {
                Object result=invocation.proceed();
                if(corruptReadback)for(Object row:(List<?>)result)((ContentCaptureSnapshot)row).setState("APPROVED");
                return result;
            }
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            if(id.endsWith(".selectCmsTopicById"))return Collections.singletonList(rows.topic);
            if(id.endsWith(".selectActiveByTopicAndSlot")){ProductRole p=new ProductRole();p.setTopicId(10L);p.setSlot(ProductSlot.CONTENT);p.setStatus(1);p.setEntityType(RoleEntityType.MEMBER);p.setEntityId(7L);return Collections.singletonList(p);}
            if(id.endsWith(".selectCmsTopicNodeList"))return rows.nodes;if(id.endsWith(".selectCmsTopicChapterList"))return rows.chapters;
            if(id.endsWith(".selectByTopicId"))return rows.keys;if(id.endsWith(".selectCmsMemberTemplateByIds"))return new ArrayList<>(rows.templates.values());
            if(id.endsWith(".selectEnabledByScope")) {
                Map<?,?> args=(Map<?,?>)invocation.getArgs()[1];
                Map<Long,List<NpcProfile>> profiles=Integer.valueOf(4).equals(args.get("param1"))?rows.nodeNpcs:rows.merchantNpcs;
                return profiles.getOrDefault(args.get("param2"),Collections.emptyList());
            }
            if(id.endsWith(".selectMmsMerchantList")) {
                Long member=((MmsMerchant)invocation.getArgs()[1]).getMemberId();MmsMerchant merchant=rows.npcMerchants.get(member);
                return merchant==null?Collections.emptyList():Collections.singletonList(merchant);
            }
            if(id.endsWith(".selectCmsRegistrationMerchantList")||id.endsWith(".selectSnapshotByTopicId"))return Collections.emptyList();
            throw new AssertionError("Unexpected source fixture read: "+id);
        }
        public Object plugin(Object target){return Plugin.wrap(target,this);}public void setProperties(Properties ignored){}
    }
}
