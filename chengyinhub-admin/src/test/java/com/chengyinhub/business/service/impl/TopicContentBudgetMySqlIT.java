package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.NodeDTO;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.ITopicContentGraphAuthoringService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.service.support.TopicContentWriteGuard;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyLong;

/** Real production mappers and Spring graph proxy; requires the existing controlled MySQL scratch configuration. */
class TopicContentBudgetMySqlIT {
    private static final long TOPIC = TopicContentGraphMySqlFixture.TOPIC;

    @Test
    void hiddenAndPendingPhysicalNodesCountAtTheBoundaryAndOneMoreRejects() throws Exception {
        try (var f = open()) {
            fillNodes(f, 300);
            f.db.update("update cms_topic_node set status=0,merchant_member_id=88,node_audit_status=0 where id>=2000");
            var s = services(f);
            f.tx.executeWithoutResult(tx -> s.guard.assertTopicSizeWithinLimits(TOPIC));
            f.db.update("insert into cms_topic_node(id,topic_id,chapter_id,name,status,merchant_member_id,node_audit_status) values(9000,100,10,'pending',0,88,0)");
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> f.tx.executeWithoutResult(tx -> s.guard.assertTopicSizeWithinLimits(TOPIC)));
            assertTrue(error.getMessage().contains("当前 301 个"));
            assertEquals(301, nodeCount(f));
        }
    }

    @Test
    void physicalDeletionFreesBudgetWhileHidingDoesNot() throws Exception {
        try (var f = open()) {
            fillNodes(f, 301);
            var s = services(f);
            assertThrows(IllegalStateException.class,
                    () -> f.tx.executeWithoutResult(tx -> s.guard.assertTopicSizeWithinLimits(TOPIC)));
            f.db.update("delete from cms_topic_node where id=2000");
            f.tx.executeWithoutResult(tx -> s.guard.assertTopicSizeWithinLimits(TOPIC));
            assertEquals(300, nodeCount(f));
        }
    }

    @Test
    void thirtyOneStoredChaptersRejectEvenWithFewNodes() throws Exception {
        try (var f = open()) {
            for (int i=0;i<29;i++) f.db.update("insert into cms_topic_chapter(id,topic_id,name,sort_id) values(?,100,'retained',?)",1000+i,i+3);
            var s = services(f);
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> f.tx.executeWithoutResult(tx -> s.guard.assertTopicSizeWithinLimits(TOPIC)));
            assertTrue(error.getMessage().contains("当前 31 章"));
        }
    }

    @Test
    void currentCountDoesNotReuseAnEarlierRepeatableReadView() throws Exception {
        try (var f = open()) {
            f.tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            fillNodes(f, 300);
            var s = services(f);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                assertThrows(IllegalStateException.class, () -> f.tx.executeWithoutResult(tx -> {
                    assertEquals(300, f.db.queryForObject("select count(*) from cms_topic_node where topic_id=100", Integer.class));
                    // Controlled legacy-writer interleaving: commit an over-budget physical row after the RR view exists.
                    await(worker.submit(() -> new JdbcTemplate(f.manager.getDataSource()).update(
                            "insert into cms_topic_node(id,topic_id,chapter_id,name,status) values(9000,100,10,'late legacy row',0)")));
                    s.guard.assertTopicSizeWithinLimits(TOPIC);
                }));
                assertEquals(301, nodeCount(f));
            } finally { stop(worker); }
        }
    }

    @Test
    void graphOverflowRollsBackEveryInsertedChapterAndNode() throws Exception {
        try (var f = open()) {
            fillNodes(f, 300);
            var s = services(f);
            f.mark();
            assertThrows(IllegalStateException.class, () -> append(s));
            f.unchanged();
        }
    }

    @Test
    void twoGraphWritersAtTwoHundredNinetyNineCannotCommitThreeHundredAndOne() throws Exception {
        try (var f = open()) {
            fillNodes(f, 299);
            var s = services(f);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
            Callable<Boolean> write = () -> {
                ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS));
                try { append(s); return true; }
                catch (IllegalStateException expected) {
                    assertTrue(expected.getMessage().contains("当前 301 个"), expected.getMessage());
                    return false;
                }
            };
            try {
                Future<Boolean> one = workers.submit(write), two = workers.submit(write);
                assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
                assertNotEquals(await(one), await(two));
                assertEquals(300, nodeCount(f));
                assertEquals(3, f.db.queryForObject("select count(*) from cms_topic_chapter where topic_id=100", Integer.class));
            } finally { start.countDown(); stop(workers); }
        }
    }

    @Test
    void publicationBudgetFenceHoldsTheGraphWriterUntilCommit() throws Exception {
        try (var f = open()) {
            fillNodes(f, 299);
            var s = services(f);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            CountDownLatch entered = new CountDownLatch(1);
            Future<?>[] write = new Future<?>[1];
            try {
                f.tx.executeWithoutResult(tx -> {
                    s.guard.assertTopicSizeWithinLimits(TOPIC);
                    write[0] = worker.submit(() -> { entered.countDown(); append(s); });
                    assertTrue(awaitLatch(entered));
                    assertThrows(TimeoutException.class, () -> write[0].get(250, TimeUnit.MILLISECONDS));
                    assertEquals(299, nodeCount(f));
                });
                await(write[0]);
                assertEquals(300, nodeCount(f));
            } finally { stop(worker); }
        }
    }

    @Test
    void failedGraphTransactionReleasesTheParentFenceWithoutLeakingItsRows() throws Exception {
        try (var f = open()) {
            fillNodes(f, 300);
            var s = services(f);
            assertThrows(IllegalStateException.class, () -> append(s));
            f.tx.executeWithoutResult(tx -> s.guard.assertTopicSizeWithinLimits(TOPIC));
            assertEquals(300, nodeCount(f));
            assertEquals(2, f.db.queryForObject("select count(*) from cms_topic_chapter where topic_id=100", Integer.class));
        }
    }

    @Test
    void chapterCrudNeverHoldsAChildWhileWaitingForThePublicationParent() throws Exception {
        try (var f = open()) {
            CountDownLatch attemptedParent = new CountDownLatch(1);
            var s = services(f, attemptedParent);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            Future<?>[] write = new Future<?>[1];
            try {
                f.tx.executeWithoutResult(tx -> {
                    s.guard.lockTopicContent(TOPIC); // Parent only: an old chapter-first writer could hold a child here.
                    write[0] = worker.submit(() -> {
                        CmsTopicChapter patch = new CmsTopicChapter(); patch.setId(10L); patch.setName("edited");
                        assertEquals(1, s.chapters.updateCmsTopicChapter(patch));
                    });
                    assertTrue(awaitLatch(attemptedParent));
                    assertThrows(TimeoutException.class, () -> write[0].get(250, TimeUnit.MILLISECONDS));
                    // NOWAIT count must succeed: the blocked writer cannot have acquired any child row.
                    s.guard.assertTopicSizeWithinLimits(TOPIC);
                });
                await(write[0]);
                assertEquals("edited", f.db.queryForObject("select name from cms_topic_chapter where id=10",String.class));
            } finally { stop(worker); }
        }
    }

    @Test
    void nodeCrudParticipatesInTheSamePublicationParentFence() throws Exception {
        try (var f = open()) {
            fillNodes(f,299);
            CountDownLatch attemptedParent = new CountDownLatch(1);
            var s = services(f,attemptedParent);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            Future<?>[] write = new Future<?>[1];
            try {
                f.tx.executeWithoutResult(tx -> {
                    s.guard.lockTopicContent(TOPIC);
                    write[0] = worker.submit(() -> assertEquals(1,s.nodes.insertCmsTopicNode(newNode())));
                    assertTrue(awaitLatch(attemptedParent));
                    assertThrows(TimeoutException.class, () -> write[0].get(250, TimeUnit.MILLISECONDS));
                    s.guard.assertTopicSizeWithinLimits(TOPIC);
                    assertEquals(299,nodeCount(f));
                });
                await(write[0]); assertEquals(300,nodeCount(f));
            } finally { stop(worker); }
        }
    }

    @Test
    void twoNodeCrudInsertsCannotRacePastThePhysicalBudget() throws Exception {
        try (var f = open()) {
            fillNodes(f,299); var s=services(f);
            ExecutorService workers=Executors.newFixedThreadPool(2);
            CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
            Callable<Boolean> write=() -> {
                ready.countDown(); assertTrue(start.await(10,TimeUnit.SECONDS));
                try { assertEquals(1,s.nodes.insertCmsTopicNode(newNode())); return true; }
                catch (IllegalStateException expected) {
                    assertTrue(expected.getMessage().contains("当前 301 个"),expected.getMessage()); return false;
                }
            };
            try {
                Future<Boolean> one=workers.submit(write),two=workers.submit(write);
                assertTrue(ready.await(10,TimeUnit.SECONDS)); start.countDown();
                assertNotEquals(await(one),await(two)); assertEquals(300,nodeCount(f));
            } finally { start.countDown(); stop(workers); }
        }
    }

    @Test
    void nodeOwnershipRecheckUsesCurrentReadInsteadOfTheOldRrLocator() throws Exception {
        try (var f=open()) {
            f.tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            var s=services(f); ExecutorService worker=Executors.newSingleThreadExecutor();
            try {
                IllegalStateException error=assertThrows(IllegalStateException.class,() -> f.tx.executeWithoutResult(tx -> {
                    assertEquals(100L,f.db.queryForObject("select topic_id from cms_topic_node where id=11",Long.class));
                    await(worker.submit(() -> {
                        CmsTopicNode move=new CmsTopicNode();move.setId(11L);move.setTopicId(101L);
                        return s.nodes.updateCmsTopicNode(move);
                    }));
                    CmsTopicNode stale=new CmsTopicNode();stale.setId(11L);stale.setName("must not persist");
                    s.nodes.updateCmsTopicNode(stale);
                }));
                assertTrue(error.getMessage().contains("归属已变化"));
                assertEquals(101L,f.db.queryForObject("select topic_id from cms_topic_node where id=11",Long.class));
                assertEquals("old-11",f.db.queryForObject("select name from cms_topic_node where id=11",String.class));
            } finally { stop(worker); }
        }
    }

    @Test
    void physicalNodeCrudDeleteCanRepairAnOverBudgetLegacyTopic() throws Exception {
        try(var f=open()) {
            fillNodes(f,301);var s=services(f);
            assertEquals(1,s.nodes.deleteCmsTopicNodeById(2000L));
            f.tx.executeWithoutResult(tx -> s.guard.assertTopicSizeWithinLimits(TOPIC));
            assertEquals(300,nodeCount(f));
        }
    }

    @Test
    void aLegacyChildFirstLockFailsFastInsteadOfCreatingAnOppositeWaitCycle() throws Exception {
        try(var f=open()) {
            var s=services(f);ExecutorService worker=Executors.newSingleThreadExecutor();
            CountDownLatch childHeld=new CountDownLatch(1),tryParent=new CountDownLatch(1);
            f.mark();
            try {
                Future<?> legacy=worker.submit(() -> f.tx.executeWithoutResult(tx -> {
                    f.db.queryForList("select id from cms_topic_chapter where id=10 for update");
                    childHeld.countDown();assertTrue(awaitLatch(tryParent));
                    s.guard.lockTopicContent(TOPIC);
                }));
                assertTrue(awaitLatch(childHeld));
                RuntimeException failure=assertThrows(RuntimeException.class,() -> f.tx.executeWithoutResult(tx -> {
                    s.guard.lockTopicContent(TOPIC);tryParent.countDown();
                    s.guard.assertTopicSizeWithinLimits(TOPIC);
                }));
                boolean nowait=false;
                for(Throwable cause=failure;cause!=null;cause=cause.getCause())
                    if(cause instanceof java.sql.SQLException sql && sql.getErrorCode()==3572) nowait=true;
                assertTrue(nowait,"must fail closed on NOWAIT contention, never skip rows or wait in a cycle");
                await(legacy); f.unchanged();
            } finally { tryParent.countDown();stop(worker); }
        }
    }

    private static TopicContentGraphMySqlFixture open() throws Exception {
        var f = new TopicContentGraphMySqlFixture();
        try { f.open(); return f; }
        catch (Exception | Error failure) {
            try { f.close(); } catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static void fillNodes(TopicContentGraphMySqlFixture f, int total) {
        for (int i=0;i<total-3;i++) f.db.update(
                "insert into cms_topic_node(id,topic_id,chapter_id,name,sort_id,status) values(?,100,10,'retained',?,1)",2000+i,i+10);
    }

    private static int nodeCount(TopicContentGraphMySqlFixture f) {
        return f.db.queryForObject("select count(*) from cms_topic_node where topic_id=100", Integer.class);
    }

    private static void append(Services s) {
        NodeDTO node = new NodeDTO(); node.setName("new node"); node.setClientNodeKey("new-node");
        ChapterDTO chapter = new ChapterDTO(); chapter.setName("new chapter"); chapter.setNodes(List.of(node));
        s.graph.saveTopicChapters(List.of(chapter), TOPIC, "author", 42L, ProductType.FREE_EXPLORE, false, false);
    }

    private static Services services(TopicContentGraphMySqlFixture f) throws Exception {
        return services(f,null);
    }

    private static Services services(TopicContentGraphMySqlFixture f,CountDownLatch attemptedParent) throws Exception {
        // Separate production mapper assembly keeps the old equivalence fixture's capture lists single-threaded.
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(f.manager.getDataSource());
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/CmsTopicMapper.xml"),
                new ClassPathResource("mapper/business/CmsTopicChapterMapper.xml"),
                new ClassPathResource("mapper/business/CmsTopicNodeMapper.xml"));
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        CmsTopicMapper actualTopics = sql.getMapper(CmsTopicMapper.class);
        Thread fixtureThread=Thread.currentThread();
        CmsTopicMapper topics=attemptedParent==null?actualTopics:(CmsTopicMapper)java.lang.reflect.Proxy.newProxyInstance(
                CmsTopicMapper.class.getClassLoader(),new Class<?>[]{CmsTopicMapper.class},(proxyObject,method,args) -> {
                    if(method.getName().equals("lockTopicContentBudget") && Thread.currentThread()!=fixtureThread)
                        attemptedParent.countDown();
                    try { return method.invoke(actualTopics,args); }
                    catch(java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
        CmsTopicChapterMapper chapters = sql.getMapper(CmsTopicChapterMapper.class);
        CmsTopicNodeMapper nodes = sql.getMapper(CmsTopicNodeMapper.class);
        TopicContentWriteGuard guard = new TopicContentWriteGuard(topics, chapters, null, null, null);
        ReflectionTestUtils.setField(guard, "cmsTopicNodeMapper", nodes);
        TopicContentGraphAuthoringServiceImpl target = new TopicContentGraphAuthoringServiceImpl();
        ReflectionTestUtils.setField(target, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(target, "cmsTopicChapterMapper", chapters);
        ReflectionTestUtils.setField(target, "cmsTopicNodeMapper", nodes);
        ReflectionTestUtils.setField(target, "contentWriteGuard", guard);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(ITopicContentGraphAuthoringService.class);
        proxy.addAdvice(new TransactionInterceptor(f.manager, new AnnotationTransactionAttributeSource()));
        CmsTopicChapterServiceImpl chapterTarget=new CmsTopicChapterServiceImpl();
        ReflectionTestUtils.setField(chapterTarget,"cmsTopicMapper",topics);
        ReflectionTestUtils.setField(chapterTarget,"cmsTopicChapterMapper",chapters);
        ReflectionTestUtils.setField(chapterTarget,"contentWriteGuard",guard);
        ITopicOperationsScopeAuthorizer scope=mock(ITopicOperationsScopeAuthorizer.class);
        when(scope.canViewTopic(anyLong())).thenReturn(true);
        ReflectionTestUtils.setField(chapterTarget,"topicOperationsScopeAuthorizer",scope);
        ReflectionTestUtils.setField(chapterTarget,"commercialTopicPolicy",mock(CommercialTopicPolicy.class));
        ReflectionTestUtils.setField(chapterTarget,"productRoleService",mock(IProductRoleService.class));
        ProxyFactory chapterProxy=new ProxyFactory(chapterTarget);chapterProxy.setInterfaces(ICmsTopicChapterService.class);
        chapterProxy.addAdvice(new TransactionInterceptor(f.manager,new AnnotationTransactionAttributeSource()));
        CmsTopicNodeServiceImpl nodeTarget=new CmsTopicNodeServiceImpl();
        ReflectionTestUtils.setField(nodeTarget,"cmsTopicNodeMapper",nodes);
        ReflectionTestUtils.setField(nodeTarget,"contentWriteGuard",guard);
        ProxyFactory nodeProxy=new ProxyFactory(nodeTarget);nodeProxy.setInterfaces(ICmsTopicNodeService.class);
        nodeProxy.addAdvice(new TransactionInterceptor(f.manager,new AnnotationTransactionAttributeSource()));
        return new Services(guard, (ITopicContentGraphAuthoringService) proxy.getProxy(),
                (ICmsTopicChapterService)chapterProxy.getProxy(),(ICmsTopicNodeService)nodeProxy.getProxy());
    }

    private static CmsTopicNode newNode() {
        CmsTopicNode node=new CmsTopicNode();node.setTopicId(TOPIC);node.setChapterId(10L);node.setName("new CRUD node");
        return node;
    }

    private static boolean awaitLatch(CountDownLatch latch) {
        try { return latch.await(10, TimeUnit.SECONDS); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private static <T> T await(Future<T> future) {
        try { return future.get(15, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static void stop(ExecutorService workers) throws InterruptedException {
        workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
    }

    private record Services(TopicContentWriteGuard guard, ITopicContentGraphAuthoringService graph,
                            ICmsTopicChapterService chapters,ICmsTopicNodeService nodes) { }
}
