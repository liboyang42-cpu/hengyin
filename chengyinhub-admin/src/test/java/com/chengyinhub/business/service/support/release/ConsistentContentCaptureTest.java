package com.chengyinhub.business.service.support.release;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.common.constant.ProductSlot;
import com.chengyinhub.common.constant.RoleEntityType;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.session.*;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConsistentContentCaptureTest {
    private DataSource ds; private Connection connection; private SqlSessionFactory factory; private SqlSession sqlSession;
    private Configuration config; private ContentCaptureRows rows; private ProductRole owner;
    private final List<String> calls=new ArrayList<>();
    private boolean expectedReadOnly=true;
    private ContentCaptureSnapshot saved;
    private Integer provenanceState = 0;
    private final List<Long> guardedTemplateIds = new ArrayList<>();
    private final Map<String,List<NpcProfile>> npcResults=new HashMap<>();
    private List<MmsMerchant> merchantMatches=Collections.emptyList();
    private final List<String> npcQueries=new ArrayList<>();
    private AtomicInteger isolation; private AtomicBoolean autoCommit;

    @BeforeEach void setUp() throws Exception {
        ds=mock(DataSource.class); connection=mock(Connection.class); when(ds.getConnection()).thenReturn(connection);
        isolation=new AtomicInteger(Connection.TRANSACTION_READ_COMMITTED); autoCommit=new AtomicBoolean(true);
        when(connection.getTransactionIsolation()).thenAnswer(i->isolation.get());
        doAnswer(i->{isolation.set(i.getArgument(0)); return null;}).when(connection).setTransactionIsolation(anyInt());
        when(connection.getAutoCommit()).thenAnswer(i->autoCommit.get());
        doAnswer(i->{autoCommit.set(i.getArgument(0)); return null;}).when(connection).setAutoCommit(anyBoolean());
        when(connection.createStatement()).thenReturn(mock(Statement.class));
        config=new Configuration(new Environment("capture-test",new SpringManagedTransactionFactory(),ds));
        register(CmsTopicMapper.class,"selectCmsTopicById"); register(ProductRoleMapper.class,"selectActiveByTopicAndSlot");
        register(CmsTopicNodeMapper.class,"selectCmsTopicNodeList"); register(CmsTopicChapterMapper.class,"selectCmsTopicChapterList");
        register(ThemeStateKeyMapper.class,"selectByTopicId"); register(CmsMemberTemplateMapper.class,"selectCmsMemberTemplateByIds");
        register(CmsRegistrationMerchantMapper.class,"selectCmsRegistrationMerchantList"); register(SmsCouponMapper.class,"selectSmsCouponById");
        register(TopicSaleSnapshotMapper.class,"selectSnapshotByTopicId");
        register(LegacyCmsProvenanceMapper.class,"selectLegacyBlockState");
        register(NpcProfileMapper.class,"selectEnabledByScope"); register(MmsMerchantMapper.class,"selectMmsMerchantList");
        register(ContentCaptureSnapshotMapper.class,"selectOwnedRequest");
        config.addMappedStatement(new MappedStatement.Builder(config,ContentCaptureSnapshotMapper.class.getName()+".insertFrozen",new StaticSqlSource(config,"insert into capture values (1)"),SqlCommandType.INSERT).build());
        factory=mock(SqlSessionFactory.class); when(factory.getConfiguration()).thenReturn(config);
        sqlSession=mock(SqlSession.class); when(factory.openSession(any(ExecutorType.class))).thenReturn(sqlSession);
        when(sqlSession.getConnection()).thenReturn(connection);
        when(sqlSession.selectList(anyString(),any())).thenAnswer(i->read(i.getArgument(0),i.getArgument(1)));
        when(sqlSession.selectOne(anyString(),any())).thenAnswer(i->read(i.getArgument(0),i.getArgument(1)));
        when(sqlSession.insert(eq(ContentCaptureSnapshotMapper.class.getName()+".insertFrozen"),any())).thenAnswer(i->{
            saved=i.getArgument(1);saved.setId(71L);saved.setState("FROZEN_INCOMPLETE");
            saved.setContractVersion("questify.content-capture-candidate.v1");saved.setCanonicalizationVersion("questify.canonical-content-json.v1");return 1;
        });
        rows=ContentCaptureBuilderTest.rewardRows();
        owner=new ProductRole(); owner.setId(1L); owner.setTopicId(10L); owner.setSlot(ProductSlot.CONTENT); owner.setStatus(1);
        owner.setEntityType(RoleEntityType.MEMBER); owner.setEntityId(7L);
    }
    @AfterEach void cleaned() { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty()); }

    @Test void allMapperStatementsShareOneOwnedReadOnlyRepeatableReadSession() throws Exception {
        ContentCaptureCandidate c=new ConsistentContentCapture(factory).captureOwnedDraft(10,7);
        assertEquals(10,c.topicId()); assertFalse(c.isCompleteRelease());
        verify(ds,times(1)).getConnection(); verify(factory,times(1)).openSession(ExecutorType.SIMPLE);
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        verify(connection).setReadOnly(true); verify(connection).commit(); verify(connection).close();
        verify(connection.createStatement()).execute("SET TRANSACTION READ ONLY");
        verify(connection.createStatement(),never()).executeUpdate(anyString());
        assertEquals(1,calls.stream().filter(s->s.endsWith("selectCmsTopicChapterList")).count());
        assertTrue(calls.indexOf(CmsTopicMapper.class.getName()+".selectCmsTopicById")<calls.indexOf(CmsTopicNodeMapper.class.getName()+".selectCmsTopicNodeList"));
    }
    @Test void provenanceRunsOnSameSessionBeforeBodyBatch() throws Exception {
        new ConsistentContentCapture(factory).captureOwnedDraft(10,7);
        String guard=LegacyCmsProvenanceMapper.class.getName()+".selectLegacyBlockState";
        String body=CmsMemberTemplateMapper.class.getName()+".selectCmsMemberTemplateByIds";
        assertTrue(calls.contains(guard));
        assertTrue(calls.indexOf(guard)<calls.indexOf(body));
        verify(ds,times(1)).getConnection();
        verify(factory,times(1)).openSession(ExecutorType.SIMPLE);
    }
    @Test void positiveTemplateGuardsAreSortedAndDeduplicatedBeforeBatchRead() {
        List<CmsTopicNode> nodes=new ArrayList<>();long id=1;
        for(Long template:Arrays.asList(202L,101L,202L,0L,null)) {
            CmsTopicNode node=new CmsTopicNode();node.setId(id++);node.setTopicId(10L);node.setTemplateId(template);nodes.add(node);
        }
        rows.nodes=nodes;
        IllegalStateException stop=new IllegalStateException("synthetic stop at body read");
        when(sqlSession.selectList(eq(CmsMemberTemplateMapper.class.getName()+".selectCmsMemberTemplateByIds"),any())).thenThrow(stop);
        assertSame(stop,assertThrows(IllegalStateException.class,()->new ConsistentContentCapture(factory).captureOwnedDraft(10,7)));
        assertEquals(Arrays.asList(101L,202L),guardedTemplateIds);
    }
    @Test void blockedOrUnknownProvenanceRejectsPreviewBeforeBody() throws Exception {
        for(Integer state:Arrays.asList(1,null)) {
            provenanceState=state; calls.clear();
            assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                    ()->new ConsistentContentCapture(factory).captureOwnedDraft(10,7));
            assertFalse(calls.contains(CmsMemberTemplateMapper.class.getName()+".selectCmsMemberTemplateByIds"));
            assertNull(saved);
        }
        verify(connection,times(2)).rollback();
    }
    @Test void newPersistenceRejectsProtectedSourceBeforeBodyOrInsert() {
        ContentCaptureCandidate preview=new ConsistentContentCapture(factory).captureOwnedDraft(10,7);
        expectedReadOnly=false; provenanceState=1; calls.clear();
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,()->new ConsistentContentCapture(factory)
                .persistOwnedDraft(10,7,preview.draftConfigVersion(),"protected",preview.contentHash()));
        assertNull(saved);
        assertFalse(calls.contains(CmsMemberTemplateMapper.class.getName()+".selectCmsMemberTemplateByIds"));
        verify(sqlSession,never()).insert(anyString(),any());
    }
    @Test void frozenReceiptReplayDoesNotReReadCurrentProvenanceOrBody() {
        ConsistentContentCapture capture=new ConsistentContentCapture(factory);
        ContentCaptureCandidate preview=capture.captureOwnedDraft(10,7);
        expectedReadOnly=false;
        ContentCaptureReceipt first=capture.persistOwnedDraft(10,7,preview.draftConfigVersion(),"frozen",preview.contentHash());
        provenanceState=1; calls.clear();
        ContentCaptureReceipt replay=capture.persistOwnedDraft(10,7,preview.draftConfigVersion(),"frozen",preview.contentHash());
        assertEquals(first.snapshotId(),replay.snapshotId());
        assertEquals(first.serverOnlyJson(),replay.serverOnlyJson());
        assertFalse(calls.contains(LegacyCmsProvenanceMapper.class.getName()+".selectLegacyBlockState"));
        assertFalse(calls.contains(CmsMemberTemplateMapper.class.getName()+".selectCmsMemberTemplateByIds"));
    }
    @Test void mysqlReadOnlyExecuteUpdateRejectionDoesNotBlockServerReadOnlySetup() throws Exception {
        Statement statement=connection.createStatement();
        when(statement.executeUpdate("SET TRANSACTION READ ONLY")).thenThrow(new SQLException("Connection is read-only", "S1009"));
        assertFalse(new ConsistentContentCapture(factory).captureOwnedDraft(10,7).isCompleteRelease());
        verify(statement).execute("SET TRANSACTION READ ONLY");verify(statement,never()).executeUpdate(anyString());
        verify(connection).setReadOnly(true);verify(connection).commit();
    }
    @Test void serverReadOnlySetupFailureCannotDowngradeOrReadSource() throws Exception {
        when(connection.createStatement().execute("SET TRANSACTION READ ONLY")).thenThrow(new SQLException("read-only setup refused", "HY000"));
        assertThrows(org.springframework.transaction.CannotCreateTransactionException.class,()->new ConsistentContentCapture(factory).captureOwnedDraft(10,7));
        assertTrue(calls.isEmpty());verify(factory,never()).openSession(any(ExecutorType.class));verify(connection,never()).commit();
    }
    @Test void ambientReadCommittedIsRejectedBeforeAnyCaptureRead() {
        TransactionTemplate outer=new TransactionTemplate(new DataSourceTransactionManager(ds));
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outer.execute(status->{
            assertThrows(IllegalStateException.class,()->new ConsistentContentCapture(factory).captureOwnedDraft(10,7));
            assertTrue(calls.isEmpty()); return null;
        });
        verify(factory,never()).openSession(any(ExecutorType.class));
    }
    @Test void ambientRepeatableReadIsAlsoRejectedSoOwnershipIsExplicit() {
        TransactionTemplate outer=new TransactionTemplate(new DataSourceTransactionManager(ds));
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.execute(status->{ assertThrows(IllegalStateException.class,()->new ConsistentContentCapture(factory).captureOwnedDraft(10,7)); return null; });
        assertTrue(calls.isEmpty());
    }
    @Test void driverIgnoringIsolationFailsBeforeAnyMapperRead() throws Exception {
        doNothing().when(connection).setTransactionIsolation(anyInt());
        assertThrows(IllegalStateException.class,()->new ConsistentContentCapture(factory).captureOwnedDraft(10,7));
        assertTrue(calls.isEmpty()); verify(connection).rollback();
    }
    @Test void wrongOwnerFailsBeforeSensitiveContentReadsAndRollsBack() throws Exception {
        owner.setEntityId(88L);
        assertThrows(IllegalArgumentException.class,()->new ConsistentContentCapture(factory).captureOwnedDraft(10,7));
        assertEquals(2,calls.size()); verify(connection).rollback(); verify(connection,never()).commit();
    }
    @Test void missingContentSlotCannotFallbackToTopicMemberId() { owner=null; assertThrows(IllegalArgumentException.class,()->new ConsistentContentCapture(factory).captureOwnedDraft(10,7)); assertEquals(2,calls.size()); }
    @Test void wrongTopicSourceFailsBeforeOwnershipOrChildReads() { rows.topic.setId(11L); assertThrows(IllegalArgumentException.class,()->new ConsistentContentCapture(factory).captureOwnedDraft(10,7)); assertEquals(1,calls.size()); }
    @Test void crossTopicNodeFailsCaptureWithoutReturningPartialData() { rows.nodes.get(0).setTopicId(11L); assertThrows(IllegalArgumentException.class,()->new ConsistentContentCapture(factory).captureOwnedDraft(10,7)); }
    @Test void absentCouponCapturedAsMissingWithoutGrantOrInventoryMutation() {
        rows.coupons.put(52L,null); ContentCaptureCandidate c=new ConsistentContentCapture(factory).captureOwnedDraft(10,7);
        assertTrue(c.serverOnlyJson().contains("MISSING_COUPON:52")); verify(sqlSession,never()).update(anyString(),any()); verify(sqlSession,never()).insert(anyString(),any());
    }
    @Test void readsBingoOnlyCouponFromTheSameTransaction() {
        rows=ContentCaptureBuilderTest.rows(); rows.topic.setCompleteRuleJson(ContentCaptureBuilderTest.bingo("54"));
        SmsCoupon coupon=new SmsCoupon(); coupon.setId(54L); coupon.setCouponType(2); rows.coupons.put(54L,coupon);
        ContentCaptureCandidate c=new ConsistentContentCapture(factory).captureOwnedDraft(10,7);
        assertTrue(c.serverOnlyJson().contains("sms_coupon:54"));
        assertEquals(1,calls.stream().filter(s->s.endsWith("selectSmsCouponById")).count());
    }

    @Test void nonSpringFactoryAndSecondLevelCacheAreRejected() {
        config.setEnvironment(new Environment("wrong",new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),ds));
        assertThrows(IllegalArgumentException.class,()->new ConsistentContentCapture(factory));
        config.setEnvironment(new Environment("capture-test",new SpringManagedTransactionFactory(),ds));
        config.addCache(new org.apache.ibatis.cache.impl.PerpetualCache("untrusted-cache"));
        assertThrows(IllegalArgumentException.class,()->new ConsistentContentCapture(factory));
    }
    @Test void ambientSynchronizationOrResourceCannotMasqueradeAsCleanBoundary() {
        TransactionSynchronizationManager.initSynchronization();
        try { assertThrows(IllegalStateException.class,()->new ConsistentContentCapture(factory).captureOwnedDraft(10,7)); }
        finally { TransactionSynchronizationManager.clearSynchronization(); }
        Object otherSource=new Object(); TransactionSynchronizationManager.bindResource(otherSource,new Object());
        try { assertThrows(IllegalStateException.class,()->new ConsistentContentCapture(factory).captureOwnedDraft(10,7)); }
        finally { TransactionSynchronizationManager.unbindResource(otherSource); }
        assertTrue(calls.isEmpty());
    }
    @Test void ambiguousMerchantWinnerCannotBeCertified() throws Exception {
        when(sqlSession.selectList(eq(CmsRegistrationMerchantMapper.class.getName()+".selectCmsRegistrationMerchantList"),any()))
                .thenReturn(Arrays.asList(rows.merchants.get(1L),rows.merchants.get(1L)));
        assertThrows(IllegalArgumentException.class,()->new ConsistentContentCapture(factory).captureOwnedDraft(10,7));
        verify(connection).rollback();
    }

    @Test void validationUsesOneCapturedNpcObservationWithoutStateKeyWrites() throws Exception {
        rows=TopicPublicationValidationContextTest.clean();
        rows.templates.get(101L).setAdvancedConfigJson("{\"enter\":{\"npcId\":7},\"requires\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.remember\"}]}");
        ContentCaptureCandidate candidate=new ConsistentContentCapture(factory).captureOwnedDraft(10,7);
        assertTrue(candidate.serverOnlyJson().contains("PUBLICATION_REFERENCE_BLOCKERS"));
        assertEquals(1,calls.stream().filter(s->s.endsWith("selectByTopicId")).count());
        assertEquals(1,calls.stream().filter(s->s.endsWith("selectCmsTopicNodeList")).count());
        assertEquals(1,calls.stream().filter(s->s.endsWith("selectCmsMemberTemplateByIds")).count());
        assertEquals(rows.nodes.size(),calls.stream().filter(s->s.contains("NpcProfile")).count());
        verify(sqlSession,never()).update(anyString(),any()); verify(sqlSession,never()).insert(anyString(),any());
        verify(connection).commit();
    }
    @Test void foreignContentSlotAndUnsupportedOwnerTypeStillStopBeforeSourceReads() {
        owner.setTopicId(99L);
        assertThrows(IllegalArgumentException.class,()->new ConsistentContentCapture(factory).captureOwnedDraft(10,7));
        assertEquals(2,calls.size());calls.clear();owner.setTopicId(10L);owner.setEntityType(RoleEntityType.CLUB);
        assertThrows(IllegalArgumentException.class,()->new ConsistentContentCapture(factory).captureOwnedDraft(10,7));
        assertEquals(2,calls.size());
    }

    @Test void nullOrInvalidRequestedIdentityReadsNothing() {
        assertThrows(IllegalArgumentException.class,()->new ConsistentContentCapture(null));
        assertThrows(IllegalArgumentException.class,()->new ConsistentContentCapture(factory).captureOwnedDraft(0,7));
        assertTrue(calls.isEmpty());
    }

    @Test void merchantMemberIdIsResolvedToExactMerchantRowIdRatherThanNumericAlias() throws Exception {
        rows=TopicPublicationValidationContextTest.clean(); rows.nodes.get(0).setMerchantMemberId(70L);
        merchantMatches=Collections.singletonList(merchant(700L,70L));
        npcResults.put("2:70",Collections.singletonList(npc(900L,2,70L))); // unrelated merchant happens to equal member ID
        npcResults.put("2:700",Collections.singletonList(npc(901L,2,700L)));
        rows.templates.get(101L).setAdvancedConfigJson("{\"enter\":{\"npcId\":901}}");
        ContentCaptureCandidate c=new ConsistentContentCapture(factory).captureOwnedDraft(10,7);
        assertTrue(npcQueries.contains("2:700")); assertFalse(npcQueries.contains("2:70"));
        assertTrue(c.serverOnlyJson().contains("npc_profile:901")); assertFalse(c.serverOnlyJson().contains("npc_profile:900"));
        assertEquals("CHECKED",TopicPublicationValidationContextTest.gate(TopicPublicationValidationContextTest.report(c),"NARRATIVE_NPC_REFERENCES").path("status").asText());
        rows.templates.get(101L).setAdvancedConfigJson("{\"enter\":{\"npcId\":900}}");
        assertTrue(new ConsistentContentCapture(factory).captureOwnedDraft(10,7).serverOnlyJson().contains("PUBLICATION_REFERENCE_BLOCKERS"));
    }
    @Test void equalMemberAndMerchantIdsCanHideLegacyAliasButStillRequireOwnedIdentity() {
        rows=TopicPublicationValidationContextTest.clean(); rows.nodes.get(0).setMerchantMemberId(70L);
        merchantMatches=Collections.singletonList(merchant(70L,70L)); npcResults.put("2:70",Collections.singletonList(npc(901L,2,70L)));
        assertTrue(new ConsistentContentCapture(factory).captureOwnedDraft(10,7).serverOnlyJson().contains("npc_profile:901"));
        assertEquals(1,calls.stream().filter(s->s.endsWith(".selectMmsMerchantList")).count());
        merchantMatches=Collections.singletonList(merchant(70L,71L)); npcQueries.clear();
        assertThrows(IllegalArgumentException.class,()->new ConsistentContentCapture(factory).captureOwnedDraft(10,7));
        assertFalse(npcQueries.contains("2:70"));
    }
    @Test void ambiguousMerchantIdentityCannotChooseFirstRow() {
        rows.nodes.get(0).setMerchantMemberId(70L); merchantMatches=Arrays.asList(merchant(700L,70L),merchant(701L,70L));
        assertThrows(IllegalArgumentException.class,()->new ConsistentContentCapture(factory).captureOwnedDraft(10,7));
        assertFalse(npcQueries.stream().anyMatch(s->s.startsWith("2:")));
    }
    @Test void missingOrDisabledMerchantNeverAuthorizesScopeTwo() {
        rows.nodes.get(0).setMerchantMemberId(70L);
        assertTrue(new ConsistentContentCapture(factory).captureOwnedDraft(10,7).serverOnlyJson().contains("\"merchantAvailability\":\"MISSING\""));
        MmsMerchant m=merchant(700L,70L);m.setAccountStatus(2L);merchantMatches=Collections.singletonList(m);
        assertTrue(new ConsistentContentCapture(factory).captureOwnedDraft(10,7).serverOnlyJson().contains("\"merchantAvailability\":\"UNAVAILABLE\""));
        assertFalse(npcQueries.stream().anyMatch(s->s.startsWith("2:")));
    }
    @Test void sharedMerchantScopeReadOnceAndEveryEnabledMerchantProfileCaptured() {
        rows.nodes.forEach(n->n.setMerchantMemberId(70L)); merchantMatches=Collections.singletonList(merchant(700L,70L));
        npcResults.put("2:700",Arrays.asList(npc(901L,2,700L),npc(902L,2,700L)));
        String json=new ConsistentContentCapture(factory).captureOwnedDraft(10,7).serverOnlyJson();
        assertEquals(1,Collections.frequency(npcQueries,"2:700"));
        assertTrue(json.contains("npc_profile:901"));assertTrue(json.contains("npc_profile:902"));
    }
    @Test void foreignDisabledUnreviewedAndDeletedNpcResultsFailClosed() {
        for(int fault=0;fault<7;fault++) {
            NpcProfile p=npc(900L,4,1L);
            if(fault==0)p.setScopeId(999L);if(fault==1)p.setScopeType(2);if(fault==2)p.setEnabled(0);
            if(fault==3)p.setAuditStatus(0);if(fault==4)p.setDelFlag("2");if(fault==5)p.setId(0L);if(fault==6)p.setId(null);
            npcResults.put("4:1",Collections.singletonList(p));
            assertThrows(IllegalArgumentException.class,()->new ConsistentContentCapture(factory).captureOwnedDraft(10,7),"fault "+fault);
        }
    }
    @Test void ambiguousNodeProfilesAndRepeatedIdentityAcrossScopesAreRejected() {
        npcResults.put("4:1",Arrays.asList(npc(900L,4,1L),npc(901L,4,1L)));
        assertThrows(IllegalArgumentException.class,()->new ConsistentContentCapture(factory).captureOwnedDraft(10,7));
        npcResults.put("4:1",Collections.singletonList(npc(900L,4,1L)));
        npcResults.put("4:2",Collections.singletonList(npc(900L,4,2L)));
        assertThrows(IllegalArgumentException.class,()->new ConsistentContentCapture(factory).captureOwnedDraft(10,7));
    }
    @Test void npcKnowledgeAndMediaAreFrozenButNeverBecomeRightsOrAiAuthority() {
        NpcProfile p=npc(900L,4,1L);p.setKnowledge("captured facts");p.setPersona("a character");p.setVoiceId("voice-reference");
        npcResults.put("4:1",Collections.singletonList(p));
        ContentCaptureCandidate first=new ConsistentContentCapture(factory).captureOwnedDraft(10,7); String frozen=first.serverOnlyJson();
        p.setKnowledge("edited facts"); ContentCaptureCandidate second=new ConsistentContentCapture(factory).captureOwnedDraft(10,7);
        assertEquals(frozen,first.serverOnlyJson());assertNotEquals(first.contentHash(),second.contentHash());
        assertTrue(frozen.contains("captured facts")); assertTrue(frozen.contains("NOT_ESTABLISHED"));
        assertTrue(frozen.contains("\"aiEnablementInferred\":false"));assertThrows(IllegalStateException.class,first::requireCompleteRelease);
    }
    @Test void malformedSourceStopsBeforeNpcReadsAndNpcReadFailureRollsBack() throws Exception {
        rows.chapters.get(0).setTopicId(99L);
        assertThrows(IllegalArgumentException.class,()->new ConsistentContentCapture(factory).captureOwnedDraft(10,7));assertTrue(npcQueries.isEmpty());
        rows=ContentCaptureBuilderTest.rewardRows();
        when(sqlSession.selectList(eq(NpcProfileMapper.class.getName()+".selectEnabledByScope"),any())).thenThrow(new IllegalStateException("source unavailable"));
        assertThrows(IllegalStateException.class,()->new ConsistentContentCapture(factory).captureOwnedDraft(10,7));
        verify(connection,times(2)).rollback();verify(connection,never()).commit();
    }
    @Test void persistenceCapturesAndInsertsOnSameOwnedWritableTransaction() throws Exception {
        ContentCaptureCandidate preview=new ConsistentContentCapture(factory).captureOwnedDraft(10,7);
        calls.clear();clearInvocations(connection,ds,factory,sqlSession);expectedReadOnly=false;
        ContentCaptureReceipt receipt=new ConsistentContentCapture(factory).persistOwnedDraft(10,7,preview.draftConfigVersion(),"capture-1",preview.contentHash());
        assertEquals(71,receipt.snapshotId());assertEquals(preview.serverOnlyJson(),receipt.serverOnlyJson());assertFalse(receipt.isCompleteRelease());
        assertThrows(IllegalStateException.class,receipt::requireCompleteRelease);
        verify(ds,times(1)).getConnection();verify(factory,times(1)).openSession(ExecutorType.SIMPLE);
        verify(connection).commit();verify(connection,never()).setReadOnly(true);verify(connection,never()).rollback();
        verify(sqlSession,times(1)).insert(eq(ContentCaptureSnapshotMapper.class.getName()+".insertFrozen"),any());
        assertEquals(2,calls.stream().filter(s->s.endsWith(".selectOwnedRequest")).count());
    }
    @Test void committedRequestReplaysFrozenBytesAfterDraftChangeWithoutRecapture() {
        ContentCaptureCandidate preview=new ConsistentContentCapture(factory).captureOwnedDraft(10,7);expectedReadOnly=false;
        ConsistentContentCapture capture=new ConsistentContentCapture(factory);
        ContentCaptureReceipt first=capture.persistOwnedDraft(10,7,preview.draftConfigVersion(),"capture-1",preview.contentHash());
        rows.templates.get(101L).setQuestionAnswer("later answer");rows.topic.setConfigVersion(preview.draftConfigVersion()+1);calls.clear();
        ContentCaptureReceipt again=capture.persistOwnedDraft(10,7,preview.draftConfigVersion(),"capture-1",preview.contentHash());
        assertEquals(first.snapshotId(),again.snapshotId());assertEquals(first.serverOnlyJson(),again.serverOnlyJson());
        assertEquals(3,calls.size());verify(sqlSession,times(1)).insert(anyString(),any());
    }
    @Test void reusedRequestWithDifferentHashOrVersionRejectsInsteadOfChangingSnapshot() {
        ContentCaptureCandidate preview=new ConsistentContentCapture(factory).captureOwnedDraft(10,7);expectedReadOnly=false;
        ConsistentContentCapture capture=new ConsistentContentCapture(factory);capture.persistOwnedDraft(10,7,preview.draftConfigVersion(),"capture-1",preview.contentHash());
        assertThrows(IllegalStateException.class,()->capture.persistOwnedDraft(10,7,preview.draftConfigVersion()+1,"capture-1",preview.contentHash()));
        assertThrows(IllegalStateException.class,()->capture.persistOwnedDraft(10,7,preview.draftConfigVersion(),"capture-1",repeat('a',64)));
        verify(sqlSession,times(1)).insert(anyString(),any());verify(sqlSession,never()).update(anyString(),any());
    }
    @Test void changedChildWithoutTopicVersionBumpRejectsExpectedHashBeforeInsert() throws Exception {
        ContentCaptureCandidate preview=new ConsistentContentCapture(factory).captureOwnedDraft(10,7);expectedReadOnly=false;
        rows.templates.get(101L).setQuestionAnswer("changed child without config bump");
        assertThrows(IllegalStateException.class,()->new ConsistentContentCapture(factory).persistOwnedDraft(10,7,preview.draftConfigVersion(),"capture-1",preview.contentHash()));
        verify(sqlSession,never()).insert(anyString(),any());verify(connection).rollback();
    }
    @Test void changedVersionRejectsBeforeSensitiveChildRead() {
        expectedReadOnly=false;
        assertThrows(IllegalStateException.class,()->new ConsistentContentCapture(factory).persistOwnedDraft(10,7,rows.topic.getConfigVersion()+1,"capture-1",repeat('a',64)));
        assertEquals(3,calls.size());verify(sqlSession,never()).insert(anyString(),any());
    }
    @Test void revokedOrForeignOwnerCannotReadEvenExistingReceipt() {
        ContentCaptureCandidate preview=new ConsistentContentCapture(factory).captureOwnedDraft(10,7);expectedReadOnly=false;
        ConsistentContentCapture capture=new ConsistentContentCapture(factory);capture.persistOwnedDraft(10,7,preview.draftConfigVersion(),"capture-1",preview.contentHash());
        owner.setEntityId(99L);calls.clear();
        assertThrows(IllegalArgumentException.class,()->capture.persistOwnedDraft(10,7,preview.draftConfigVersion(),"capture-1",preview.contentHash()));
        assertEquals(2,calls.size());
    }
    @Test void insertFailureRollsBackAndNeverReturnsAnAllocatedReceipt() throws Exception {
        ContentCaptureCandidate preview=new ConsistentContentCapture(factory).captureOwnedDraft(10,7);expectedReadOnly=false;
        when(sqlSession.insert(anyString(),any())).thenThrow(new IllegalStateException("storage failure"));
        assertThrows(IllegalStateException.class,()->new ConsistentContentCapture(factory).persistOwnedDraft(10,7,preview.draftConfigVersion(),"capture-1",preview.contentHash()));
        verify(connection).rollback();assertNull(saved);
    }
    @Test void corruptReadbackAfterInsertRollsBackRatherThanReturningSuccess() throws Exception {
        ContentCaptureCandidate preview=new ConsistentContentCapture(factory).captureOwnedDraft(10,7);expectedReadOnly=false;
        when(sqlSession.insert(anyString(),any())).thenAnswer(i->{saved=i.getArgument(1);saved.setId(71L);saved.setState("APPROVED");return 1;});
        assertThrows(IllegalStateException.class,()->new ConsistentContentCapture(factory).persistOwnedDraft(10,7,preview.draftConfigVersion(),"capture-1",preview.contentHash()));
        verify(connection).rollback();
    }
    @Test void duplicateRequestRaceRollsBackBeforeFreshAuthorizedReceiptRead() throws Exception {
        ContentCaptureCandidate preview=new ConsistentContentCapture(factory).captureOwnedDraft(10,7);expectedReadOnly=false;clearInvocations(connection,ds);
        when(sqlSession.insert(anyString(),any())).thenAnswer(i->{
            saved=i.getArgument(1);saved.setId(72L);saved.setState("FROZEN_INCOMPLETE");saved.setContractVersion("questify.content-capture-candidate.v1");
            saved.setCanonicalizationVersion("questify.canonical-content-json.v1");expectedReadOnly=true;
            throw new org.springframework.dao.DuplicateKeyException("same request won elsewhere");
        });
        ContentCaptureReceipt receipt=new ConsistentContentCapture(factory).persistOwnedDraft(10,7,preview.draftConfigVersion(),"capture-1",preview.contentHash());
        assertEquals(72,receipt.snapshotId());verify(connection).rollback();verify(connection).commit();verify(ds,times(2)).getConnection();
        org.mockito.InOrder order=inOrder(connection);order.verify(connection).rollback();order.verify(connection).commit();
    }
    @Test void unrelatedDuplicateFailureCannotBecomeSuccessfulReplay() throws Exception {
        ContentCaptureCandidate preview=new ConsistentContentCapture(factory).captureOwnedDraft(10,7);expectedReadOnly=false;
        when(sqlSession.insert(anyString(),any())).thenAnswer(i->{expectedReadOnly=true;throw new org.springframework.dao.DuplicateKeyException("unrelated collision");});
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->new ConsistentContentCapture(factory).persistOwnedDraft(10,7,preview.draftConfigVersion(),"capture-1",preview.contentHash()));
        verify(connection,times(2)).rollback();
    }
    @Test void persistenceRejectsAmbientTransactionAndInvalidRequestBeforeReading() {
        ConsistentContentCapture capture=new ConsistentContentCapture(factory);
        for(String key:Arrays.asList("", " trailing", "trailing ","unicode-é",repeat('a',129))) {
            assertThrows(IllegalArgumentException.class,()->capture.persistOwnedDraft(10,7,0,key,repeat('a',64)));
        }
        assertThrows(IllegalArgumentException.class,()->capture.persistOwnedDraft(10,7,-1,"capture-1",repeat('a',64)));
        assertThrows(IllegalArgumentException.class,()->capture.persistOwnedDraft(10,7,0,"capture-1",repeat('A',64)));
        TransactionTemplate outer=new TransactionTemplate(new DataSourceTransactionManager(ds));
        outer.execute(status->{assertThrows(IllegalStateException.class,()->capture.persistOwnedDraft(10,7,0,"capture-1",repeat('a',64)));return null;});
        assertTrue(calls.isEmpty());
    }
    @Test void oversizedNpcAggregateFailsPreviewAndPersistenceBeforeAnyInsert() throws Exception {
        ContentCaptureRows source=CanonicalContentJsonBoundaryTest.npcRows(2450);
        rows=source;merchantMatches=Collections.singletonList(source.npcMerchants.get(70L));
        npcResults.put("2:700",source.merchantNpcs.get(700L));
        ConsistentContentCapture capture=new ConsistentContentCapture(factory);
        assertThrows(IllegalArgumentException.class,()->capture.captureOwnedDraft(10,7));
        expectedReadOnly=false;
        assertThrows(IllegalArgumentException.class,()->capture.persistOwnedDraft(10,7,rows.topic.getConfigVersion(),"oversized",repeat('a',64)));
        verify(sqlSession,never()).insert(anyString(),any());verify(connection,times(2)).rollback();verify(connection,never()).commit();
    }

    private static String repeat(char value,int count) { char[] chars=new char[count];Arrays.fill(chars,value);return new String(chars); }

    static MmsMerchant merchant(long id,long member) { MmsMerchant m=new MmsMerchant();m.setId(id);m.setMemberId(member);m.setStatus(1L);m.setAccountStatus(1L);m.setDelFlag(0);return m; }
    static NpcProfile npc(long id,int scope,long scopeId) { NpcProfile p=new NpcProfile();p.setId(id);p.setScopeType(scope);p.setScopeId(scopeId);p.setEnabled(1);p.setAuditStatus(1);p.setDelFlag("0");p.setName("Character");return p; }

    private void register(Class<?> type,String method) {
        if(!config.hasMapper(type)) config.addMapper(type);
        config.addMappedStatement(new MappedStatement.Builder(config,type.getName()+"."+method,new StaticSqlSource(config,"select 1"),SqlCommandType.SELECT).build());
    }
    private Object read(String statement,Object param) throws Exception {
        calls.add(statement);
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(expectedReadOnly,TransactionSynchronizationManager.isCurrentTransactionReadOnly());
        assertEquals(Connection.TRANSACTION_REPEATABLE_READ,connection.getTransactionIsolation());
        Connection bound=DataSourceUtils.getConnection(ds); assertSame(connection,bound); DataSourceUtils.releaseConnection(bound,ds);
        assertFalse(connection.getAutoCommit());
        if(statement.endsWith(".selectCmsTopicById")) return rows.topic;
        if(statement.endsWith(".selectActiveByTopicAndSlot")) return owner==null?Collections.emptyList():Collections.singletonList(owner);
        if(statement.endsWith(".selectCmsTopicNodeList")) { assertEquals(10L,((CmsTopicNode)param).getTopicId()); assertEquals(1L,((CmsTopicNode)param).getStatus()); return rows.nodes; }
        if(statement.endsWith(".selectCmsTopicChapterList")) return rows.chapters;
        if(statement.endsWith(".selectByTopicId")) return rows.keys;
        if(statement.endsWith(".selectLegacyBlockState")) { guardedTemplateIds.add((Long)((Map<?,?>)param).get("templateId")); return provenanceState; }
        if(statement.endsWith(".selectCmsMemberTemplateByIds")) return new ArrayList<>(rows.templates.values());
        if(statement.endsWith(".selectCmsRegistrationMerchantList")) { CmsRegistrationMerchant p=(CmsRegistrationMerchant)param; assertEquals(1L,p.getAuditStatus()); assertEquals(0,p.getDelFlag()); CmsRegistrationMerchant m=rows.merchants.get(p.getNodeId()); return m==null?Collections.emptyList():Collections.singletonList(m); }
        if(statement.endsWith(".selectSmsCouponById")) return rows.coupons.get(param);
        if(statement.endsWith(".selectSnapshotByTopicId")) return rows.sale;
        if(statement.endsWith(".selectOwnedRequest")) return saved;
        if(statement.endsWith(".selectMmsMerchantList")) { assertEquals(70L,((MmsMerchant)param).getMemberId()); return merchantMatches; }
        if(statement.endsWith(".selectEnabledByScope")) {
            Map<?,?> p=(Map<?,?>)param; String scope=p.get("param1")+":"+p.get("param2"); npcQueries.add(scope);
            return npcResults.getOrDefault(scope,Collections.emptyList());
        }
        throw new AssertionError(statement);
    }
}
