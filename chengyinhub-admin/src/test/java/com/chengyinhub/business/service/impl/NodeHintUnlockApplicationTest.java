package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.NodeHintUnlockCommand;
import com.chengyinhub.business.domain.vo.NodeHintUnlockResult;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.PlayNodeAccessService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Direct real application and Spring transaction boundary; leaves are explicit doubles.
 * No Controller and no database: only the separate MySQL IT proves storage rollback/locking. */
class NodeHintUnlockApplicationTest {
    private IPuzzlePlayService application;
    private CmsTopicNode node;
    private CmsMemberTemplate template;
    private CmsPointsSetting rule;
    private ICmsTopicNodeService nodes;
    private ICmsMemberTemplateService templates;
    private ICmsPointsSettingService settings;
    private IUmsMemberService members;
    private IUmsMemberPointsDetailService details;
    private ClubLeadMapper leads;
    private ITopicRouteRuntimeService routes;
    private AtomicLong balance;
    private Map<String,UmsMemberPointsDetail> ledger;
    private BoundaryTransactions transactions;
    private AtomicInteger completion;
    private CountDownLatch concurrentInserts;

    @BeforeEach void fixture() {
        nodes=mock(ICmsTopicNodeService.class); templates=mock(ICmsMemberTemplateService.class);
        settings=mock(ICmsPointsSettingService.class); members=mock(IUmsMemberService.class);
        details=mock(IUmsMemberPointsDetailService.class); leads=mock(ClubLeadMapper.class);
        routes=mock(ITopicRouteRuntimeService.class);
        node=new CmsTopicNode(); node.setId(401L);node.setTopicId(300L);node.setTemplateId(39L);node.setStatus(1L);node.setSortId(1L);
        doAnswer(call -> { writable();return node; }).when(nodes).selectCmsTopicNodeById(401L);
        template=new CmsMemberTemplate();template.setId(39L);template.setHint1("one");template.setHint2("two");
        when(templates.selectCmsMemberTemplateById(39L)).thenReturn(template);
        rule=new CmsPointsSetting();rule.setPointsNum(10);when(settings.selectCmsPointsSettingList(any())).thenReturn(List.of(rule));
        ICmsActivityService activities=mock(ICmsActivityService.class);
        CmsActivityMapper activityMapper=mock(CmsActivityMapper.class);
        CmsActivity activity=new CmsActivity();activity.setId(500L);activity.setTopicId(300L);
        activity.setStartDate(new Date(0));activity.setEndDate(new Date(System.currentTimeMillis()+86400000L));
        when(activities.selectCmsActivityById(500L)).thenReturn(activity);
        when(activityMapper.selectCmsActivityByIdForUpdate(500L)).thenReturn(activity);
        CmsRegistrationMapper registrations=mock(CmsRegistrationMapper.class);
        CmsRegistration paid=registration(91L,2L,500L,1),self=registration(92L,1L,300L,2);
        when(registrations.selectCmsRegistrationList(any())).thenAnswer(call -> {
            CmsRegistration probe=call.getArgument(0);
            if(!Long.valueOf(7L).equals(probe.getMemberId()))return List.of();
            if(Long.valueOf(2L).equals(probe.getOwnerType())&&Long.valueOf(500L).equals(probe.getOwnerId()))return List.of(paid);
            if(Long.valueOf(1L).equals(probe.getOwnerType())&&Long.valueOf(300L).equals(probe.getOwnerId()))return List.of(self);
            return List.of();
        });
        when(registrations.selectCmsRegistrationByIdForUpdate(91L)).thenReturn(paid);
        when(registrations.selectCmsRegistrationByIdForUpdate(92L)).thenReturn(self);
        ICmsTopicService topics=mock(ICmsTopicService.class);CmsTopic topic=new CmsTopic();topic.setId(300L);topic.setProductType(1);
        when(topics.selectCmsTopicById(300L)).thenReturn(topic);
        when(routes.canPlay(eq(7L),eq(300L),anyLong(),eq(401L),isNull())).thenAnswer(call -> {writable();return true;});
        PlayNodeAccessService access=new PlayNodeAccessService(activities,activityMapper,registrations,nodes,
                mock(IPlayerNodeProgressService.class),topics,mock(IOmsTicketService.class),leads,
                mock(IGameSessionRuntimeService.class),mock(ICmsTopicChapterService.class),routes);
        balance=new AtomicLong(100);ledger=new ConcurrentHashMap<>();completion=new AtomicInteger(-1);concurrentInserts=null;
        when(members.selectUmsMemberById(7L)).thenAnswer(call -> {writable();UmsMember member=new UmsMember();member.setId(7L);member.setPoint(balance.get());return member;});
        doAnswer(call -> insert(call.getArgument(0))).when(details).insertUmsMemberPointsDetail(any());
        doAnswer(call -> {writable();balance.addAndGet(-call.<Integer>getArgument(1));return true;}).when(members).deductPoints(eq(7L),anyInt());
        PuzzlePlayServiceImpl target=new PuzzlePlayServiceImpl(templates,mock(PlayAttemptLogMapper.class),
                mock(PlayerNodeProgressMapper.class),mock(UmsMemberMapper.class),nodes,access,settings,members,details);
        org.springframework.test.util.ReflectionTestUtils.setField(target,"topicRouteRuntimeService",routes);
        transactions=new BoundaryTransactions();ProxyFactory proxy=new ProxyFactory(target);proxy.setInterfaces(IPuzzlePlayService.class);
        proxy.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        application=(IPuzzlePlayService)proxy.getProxy();
    }
    private CmsRegistration registration(long id,long ownerType,long owner,int kind) {
        CmsRegistration row=new CmsRegistration();row.setId(id);row.setMemberId(7L);row.setOwnerType(ownerType);row.setOwnerId(owner);
        row.setPurchaseKind(kind);row.setRegistrationStatus(2);row.setPaymentStatus(2);
        row.setExpiresAt(new Date(System.currentTimeMillis()+86400000L));return row;
    }
    private int insert(UmsMemberPointsDetail row) throws Exception {
        writable();
        if(concurrentInserts!=null) {concurrentInserts.countDown();assertTrue(concurrentInserts.await(5,TimeUnit.SECONDS));}
        String key=row.getMemberId()+":"+row.getEventType()+":"+row.getEventId();
        if(ledger.putIfAbsent(key,row)!=null)throw new DuplicateKeyException("original event identity");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {completion.set(status);}
        });
        return 1;
    }
    private void writable() {assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());}
    private NodeHintUnlockCommand activity() {return new NodeHintUnlockCommand(7L,401L,"500",null);}
    private NodeHintUnlockResult result(NodeHintUnlockCommand command,int cost) {
        NodeHintUnlockResult result=application.unlockNodeHint(command);assertNull(result.failure());assertEquals(cost,result.hints().cost());return result;
    }
    private void noEconomicCalls() {verifyNoInteractions(members,details);assertTrue(ledger.isEmpty());assertEquals(100,balance.get());}
    @AfterEach void noLeakedTransaction() {assertFalse(TransactionSynchronizationManager.isActualTransactionActive());}

    @Test void legalActivityUsesOriginalLedgerThenMemberOrder() {
        result(activity(),10);assertEquals(90,balance.get());assertEquals(Set.of("7:14:401"),ledger.keySet());
        UmsMemberPointsDetail row=ledger.get("7:14:401");assertEquals(100L,row.getCurrentPoints());assertEquals(90L,row.getAfterPoints());
        assertEquals(10L,row.getChangePoints());assertEquals(2L,row.getChangeType());assertEquals("解锁提示",row.getChangeReason());
        var order=inOrder(details,members);order.verify(members).selectUmsMemberById(7L);order.verify(details).insertUmsMemberPointsDetail(same(row));order.verify(members).deductPoints(7L,10);
        assertEquals(1,transactions.commits.get());assertEquals(0,transactions.rollbacks.get());
    }
    @Test void legalSelfPlayKeepsTopicScopedQualification() {
        result(new NodeHintUnlockCommand(7L,401L,null,"300"),10);
        verify(routes).canPlay(7L,300L,0L,401L,null);assertEquals(90,balance.get());
    }
    @Test void noScopeUsesOriginalNodeTopicButStillRequiresAnOwnedPass() {
        result(new NodeHintUnlockCommand(7L,401L,null,null),10);verify(routes).canPlay(7L,300L,0L,401L,null);
    }
    @Test void missingActorAndNodeRejectBeforeAnyCatalogueOrMoney() {
        assertEquals("请先登录",application.unlockNodeHint(new NodeHintUnlockCommand(0L,401L,"500",null)).failure().message());
        assertEquals("缺少节点ID",application.unlockNodeHint(new NodeHintUnlockCommand(7L,null,"500",null)).failure().message());
        verifyNoInteractions(nodes,templates,settings);noEconomicCalls();
    }
    @Test void wrongTopicRejectsBeforeLeadRouteOrHintBody() {
        node.setTopicId(301L);assertEquals("该节点不属于本次活动",application.unlockNodeHint(activity()).failure().message());
        verifyNoInteractions(leads,routes,templates,settings);noEconomicCalls();
    }
    @Test void replayStillRequiresCurrentActorQualification() {
        result(activity(),10);clearInvocations(details,members);
        assertEquals("请先报名并完成支付",application.unlockNodeHint(new NodeHintUnlockCommand(8L,401L,"500",null)).failure().message());
        verifyNoInteractions(details,members);assertEquals(1,ledger.size());assertEquals(90,balance.get());
    }
    @Test void gatheringRejectsBeforeRouteTemplatePricingOrMoney() {
        ClubLeadState gathering=new ClubLeadState();gathering.setStatus(1);when(leads.selectByActivity(500L)).thenReturn(gathering);
        assertEquals("队伍集合中,等队长开始行程",application.unlockNodeHint(activity()).failure().message());
        verifyNoInteractions(routes,templates,settings);noEconomicCalls();
    }
    @Test void leadDependencyFailureKeeps503NotRunAndRollsBackApplicationTransaction() {
        when(leads.selectByActivity(500L)).thenThrow(new DataAccessResourceFailureException("private SQL details"));
        ServiceException failure=assertThrowsExactly(ServiceException.class,()->application.unlockNodeHint(activity()));
        assertEquals(Integer.valueOf(503),failure.getCode());assertEquals("带领状态暂时不可用，请稍后重试",failure.getMessage());
        assertEquals(Map.of("outcome","NOT_RUN"),failure.getData());assertNull(failure.getCause());
        verifyNoInteractions(routes,templates,settings);noEconomicCalls();assertEquals(1,transactions.rollbacks.get());
    }
    @Test void lead403IsTheOriginalExceptionAndNeverBecomesDependency503() {
        ServiceException forbidden=new ServiceException("原权限拒绝",403);when(leads.selectByActivity(500L)).thenThrow(forbidden);
        assertSame(forbidden,assertThrowsExactly(ServiceException.class,()->application.unlockNodeHint(activity())));
        noEconomicCalls();assertEquals(1,transactions.rollbacks.get());
    }
    @Test void freePriceAndNullableHintKeepOriginalValuesWithoutMemberRead() {
        rule.setPointsNum(0);template.setHint1(null);NodeHintUnlockResult result=result(activity(),0);
        assertNull(result.hints().hint1());assertEquals("two",result.hints().hint2());noEconomicCalls();
    }
    @Test void priceGetterUsesSameRulesWithoutOpeningATransaction() {
        rule.setPointsNum(12);rule.setValue(new java.math.BigDecimal("99"));assertEquals(12,application.hintUnlockCost());
        rule.setStatus(0L);assertEquals(0,application.hintUnlockCost());assertEquals(0,transactions.commits.get());
        verifyNoInteractions(nodes,members,details);
    }
    @Test void originalReplayCostsZeroAndReadsTheCurrentTemplateWithoutAnotherDebit() {
        result(activity(),10);template.setHint1("current hint");
        assertEquals("current hint",result(activity(),0).hints().hint1());
        verify(members,times(1)).deductPoints(7L,10);assertEquals(90,balance.get());assertEquals(1,ledger.size());
    }
    @Test void publishedHintAfterUnbindKeepsOriginalFeeReceiptScopeAndRetry() {
        var content=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        content.put("contentSnapshotVersion",1).put("templateId",39L);
        content.putObject("nodePresentation").put("id",401L).put("topicId",300L).put("templateId",39L);
        content.putObject("templatePresentation");
        content.put("hint1","original one").put("hint2","original two");
        when(routes.nodeAnswers(7L,300L,500L,401L)).thenReturn(content);
        node.setTemplateId(null);template.setHint1("later author edit");
        assertEquals("original one",result(activity(),10).hints().hint1());
        assertEquals("original two",result(activity(),0).hints().hint2());
        assertEquals(Set.of("7:14:401"),ledger.keySet());assertEquals(90,balance.get());
        var row=ledger.get("7:14:401");assertEquals(100L,row.getCurrentPoints());assertEquals(90L,row.getAfterPoints());
        verify(members,times(1)).deductPoints(7L,10);verify(templates,never()).selectCmsMemberTemplateById(any());
        clearInvocations(members,details);
        assertNotNull(application.unlockNodeHint(new NodeHintUnlockCommand(8L,401L,"500",null)).failure());
        verifyNoInteractions(members,details);assertEquals(90,balance.get());assertEquals(1,ledger.size());
    }
    @Test void existingBalancePrecheckP2IsCharacterizedWithoutReorderingLocks() {
        result(activity(),10);balance.set(0);
        assertEquals("积分不足",application.unlockNodeHint(activity()).failure().message());
        verify(details,times(1)).insertUmsMemberPointsDetail(any());verify(members,times(1)).deductPoints(7L,10);
    }
    @Test void insertFailurePropagatesAndNoMemberDebitOccurs() {
        doThrow(new IllegalStateException("fixture insert failed")).when(details).insertUmsMemberPointsDetail(any());
        assertEquals("fixture insert failed",assertThrowsExactly(IllegalStateException.class,()->application.unlockNodeHint(activity())).getMessage());
        verify(members,never()).deductPoints(anyLong(),anyInt());assertEquals(1,transactions.rollbacks.get());
    }
    @Test void debitWriteThenThrowReturnsOriginalFailureAndMarksActualSpringRollback() {
        doAnswer(call -> {writable();balance.addAndGet(-10);throw new IllegalStateException("fixture after debit");}).when(members).deductPoints(7L,10);
        assertEquals("积分不足,请重试",application.unlockNodeHint(activity()).failure().message());
        assertEquals(1,transactions.rollbacks.get());assertEquals(0,transactions.commits.get());
        assertEquals(TransactionSynchronization.STATUS_ROLLED_BACK,completion.get());
        // Doubles deliberately do not emulate DB undo; real balance/ledger rollback remains the root-owned MySQL test.
    }
    @Test void concurrentOriginalIdentityHasOneLogicalDebit() throws Exception {
        concurrentInserts=new CountDownLatch(2);ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<NodeHintUnlockResult> first=pool.submit(()->application.unlockNodeHint(activity()));
            Future<NodeHintUnlockResult> second=pool.submit(()->application.unlockNodeHint(activity()));
            List<Integer> costs=new ArrayList<>(List.of(first.get(10,TimeUnit.SECONDS).hints().cost(),second.get(10,TimeUnit.SECONDS).hints().cost()));
            Collections.sort(costs);assertEquals(List.of(0,10),costs);assertEquals(90,balance.get());assertEquals(1,ledger.size());
            verify(members,times(1)).deductPoints(7L,10);
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    private static class BoundaryTransactions extends AbstractPlatformTransactionManager {
        final AtomicInteger commits=new AtomicInteger(),rollbacks=new AtomicInteger();
        @Override protected Object doGetTransaction() {return new Object();}
        @Override protected void doBegin(Object tx,TransactionDefinition definition) {
            assertEquals(TransactionDefinition.ISOLATION_DEFAULT,definition.getIsolationLevel());assertFalse(definition.isReadOnly());
        }
        @Override protected void doCommit(DefaultTransactionStatus status) {commits.incrementAndGet();}
        @Override protected void doRollback(DefaultTransactionStatus status) {rollbacks.incrementAndGet();}
    }
}
