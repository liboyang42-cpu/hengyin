package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.NodeDTO;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.ITopicContentGraphAuthoringService;
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
        // Separate production mapper assembly keeps the old equivalence fixture's capture lists single-threaded.
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(f.manager.getDataSource());
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/CmsTopicMapper.xml"),
                new ClassPathResource("mapper/business/CmsTopicChapterMapper.xml"),
                new ClassPathResource("mapper/business/CmsTopicNodeMapper.xml"));
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        CmsTopicMapper topics = sql.getMapper(CmsTopicMapper.class);
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
        return new Services(guard, (ITopicContentGraphAuthoringService) proxy.getProxy());
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

    private record Services(TopicContentWriteGuard guard, ITopicContentGraphAuthoringService graph) { }
}
