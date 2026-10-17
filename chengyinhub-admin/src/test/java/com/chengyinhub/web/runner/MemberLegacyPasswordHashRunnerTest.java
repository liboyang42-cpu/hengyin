package com.chengyinhub.web.runner;

import com.chengyinhub.business.service.impl.MemberLegacyPasswordHashService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PR10 复审:存量明文口令批量哈希不阻塞启动 —— 就绪后后台线程分批续扫,仍是显式开启。 */
class MemberLegacyPasswordHashRunnerTest {

    private MemberLegacyPasswordHashRunner runner(MemberLegacyPasswordHashService hashService, int limit, int batch) {
        MemberLegacyPasswordHashRunner runner = new MemberLegacyPasswordHashRunner();
        ReflectionTestUtils.setField(runner, "hashService", hashService);
        ReflectionTestUtils.setField(runner, "hashLimit", limit);
        ReflectionTestUtils.setField(runner, "batchSize", batch);
        ReflectionTestUtils.setField(runner, "pauseMillis", 0L);
        return runner;
    }

    @Test
    void readyEventReturnsImmediatelyAndHashesOnBackgroundThread() throws Exception {
        MemberLegacyPasswordHashService hashService = mock(MemberLegacyPasswordHashService.class);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        Thread caller = Thread.currentThread();
        Thread[] worker = new Thread[1];
        when(hashService.hashLegacyPasswords(anyLong(), anyInt())).thenAnswer(call -> {
            worker[0] = Thread.currentThread();
            release.await(5, TimeUnit.SECONDS);
            done.countDown();
            return new MemberLegacyPasswordHashService.Result(0, 0, 0, 0L);
        });
        MemberLegacyPasswordHashRunner runner = runner(hashService, 500, 100);
        try {
            long started = System.nanoTime();
            runner.onApplicationEvent(null);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000,
                    "就绪回调不能等批量哈希跑完");
            release.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNotEquals(caller, worker[0], "哈希必须跑在后台线程");
        } finally {
            runner.destroy();
        }
    }

    @Test
    void hashesInBoundedBatchesFromTheCursorUntilLimitOrEmpty() {
        MemberLegacyPasswordHashService hashService = mock(MemberLegacyPasswordHashService.class);
        when(hashService.hashLegacyPasswords(0L, 100)).thenReturn(new MemberLegacyPasswordHashService.Result(100, 99, 1, 150L));
        when(hashService.hashLegacyPasswords(150L, 100)).thenReturn(new MemberLegacyPasswordHashService.Result(100, 100, 0, 260L));
        when(hashService.hashLegacyPasswords(260L, 50)).thenReturn(new MemberLegacyPasswordHashService.Result(50, 50, 0, 320L));

        runner(hashService, 250, 100).hashInBatches();

        org.mockito.InOrder order = inOrder(hashService);
        order.verify(hashService).hashLegacyPasswords(0L, 100);
        order.verify(hashService).hashLegacyPasswords(150L, 100);
        order.verify(hashService).hashLegacyPasswords(260L, 50);
        verify(hashService, never()).hashLegacyPasswords(320L, 0);
    }

    @Test
    void stopsWhenNothingLeftAndSwallowsFailures() {
        MemberLegacyPasswordHashService hashService = mock(MemberLegacyPasswordHashService.class);
        when(hashService.hashLegacyPasswords(0L, 100)).thenReturn(new MemberLegacyPasswordHashService.Result(0, 0, 0, 0L));
        runner(hashService, 1000, 100).hashInBatches();
        verify(hashService).hashLegacyPasswords(0L, 100);
        verify(hashService, never()).hashLegacyPasswords(0L, 0);

        MemberLegacyPasswordHashService failing = mock(MemberLegacyPasswordHashService.class);
        when(failing.hashLegacyPasswords(anyLong(), anyInt())).thenThrow(new IllegalStateException("db down"));
        assertDoesNotThrow(() -> runner(failing, 1000, 100).hashInBatches());
    }
}
