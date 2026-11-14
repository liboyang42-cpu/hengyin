package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.appgaps.mapper.AppOriginalRequestMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "APPGAPS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppOriginalRequestLedgerMySqlIT {
    AppGapsMySql my; AppOriginalRequestLedger ledger;
    static final String SHA = "a".repeat(64), OTHER = "b".repeat(64);

    @BeforeAll void setup() throws Exception {
        my = new AppGapsMySql("migration_20261113_appgaps_original_request.sql");
        my.db.execute("create table if not exists it_appgaps_side_effect(id bigint auto_increment primary key, v varchar(32))");
        ledger = new AppOriginalRequestLedger(my.mapper(AppOriginalRequestMapper.class, "mapper/business/appgaps/AppOriginalRequestMapper.xml"), my.tx);
    }
    @BeforeEach void clean() {
        my.db.update("delete from app_original_request where protocol like 'it-%'");
        my.db.update("delete from it_appgaps_side_effect");
    }
    @AfterAll void drop() { my.db.execute("drop table if exists it_appgaps_side_effect"); }

    String id() { return UUID.randomUUID().toString(); }

    @Test void commitIsAtomicWithBusinessWriteAndReplaysWithoutReexecution() {
        String rid = id(); AtomicInteger calls = new AtomicInteger();
        var writer = (java.util.function.Supplier<Map<String, Object>>) () -> {
            calls.incrementAndGet(); my.db.update("insert into it_appgaps_side_effect(v) values('x')");
            return Map.of("value", 7);
        };
        var first = ledger.run("it-p", 9L, rid, "c1", SHA, writer);
        var again = ledger.run("it-p", 9L, rid, "c1", SHA, writer);
        assertEquals("COMMITTED", first.state()); assertFalse(first.replayed());
        assertEquals("COMMITTED", again.state()); assertTrue(again.replayed());
        assertEquals(7, again.result().get("value"));
        assertEquals(1, calls.get());
        assertEquals(1, my.db.queryForObject("select count(*) from it_appgaps_side_effect", Integer.class));
        assertEquals("COMMITTED", ledger.read("it-p", 9L, rid, "c1", SHA).state());
        assertEquals("NOT_FOUND", ledger.read("it-p", 10L, rid, "c1", SHA).state(), "他人的请求编号不可见");
        assertThrows(ServiceException.class, () -> ledger.run("it-p", 9L, rid, "c1", OTHER, writer));
        assertThrows(ServiceException.class, () -> ledger.run("it-p", 9L, rid, "c2", SHA, writer));
    }

    @Test void businessRejectionRollsBackWriteAndIsRecordedOnce() {
        String rid = id();
        var fact = ledger.run("it-p", 9L, rid, "", SHA, () -> {
            my.db.update("insert into it_appgaps_side_effect(v) values('lost')");
            throw new ServiceException("名额已满", 422);
        });
        assertEquals("REJECTED", fact.state()); assertEquals(422, fact.errorCode()); assertEquals("名额已满", fact.errorMessage());
        assertEquals(0, my.db.queryForObject("select count(*) from it_appgaps_side_effect", Integer.class));
        var replay = ledger.run("it-p", 9L, rid, "", SHA, () -> { throw new AssertionError("must not rerun"); });
        assertEquals("REJECTED", replay.state()); assertTrue(replay.replayed());
    }

    @Test void unexpectedFailureLeavesNoRowSoSameIdCanRetry() {
        String rid = id();
        assertThrows(IllegalStateException.class, () -> ledger.run("it-p", 9L, rid, "", SHA, () -> {
            my.db.update("insert into it_appgaps_side_effect(v) values('lost')");
            throw new IllegalStateException("db blip");
        }));
        assertEquals("NOT_FOUND", ledger.read("it-p", 9L, rid, "", SHA).state());
        assertEquals("COMMITTED", ledger.run("it-p", 9L, rid, "", SHA, () -> Map.of()).state());
    }

    @Test void concurrentDuplicatesExecuteOnce() throws Exception {
        String rid = id(); AtomicInteger calls = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(6); CountDownLatch go = new CountDownLatch(1);
        List<Future<AppOriginalRequestLedger.Fact>> all = new ArrayList<>();
        for (int i = 0; i < 6; i++) all.add(pool.submit(() -> { go.await(); return ledger.run("it-p", 9L, rid, "", SHA, () -> {
            calls.incrementAndGet(); my.db.update("insert into it_appgaps_side_effect(v) values('c')");
            try { Thread.sleep(150); } catch (InterruptedException e) { throw new IllegalStateException(e); }
            return Map.of("n", 1); }); }));
        go.countDown();
        for (var f : all) assertEquals("COMMITTED", f.get(30, TimeUnit.SECONDS).state());
        pool.shutdown();
        assertEquals(1, calls.get());
        assertEquals(1, my.db.queryForObject("select count(*) from it_appgaps_side_effect", Integer.class));
        assertEquals(1, my.db.queryForObject("select count(*) from app_original_request where request_id=?", Integer.class, rid));
    }
}
