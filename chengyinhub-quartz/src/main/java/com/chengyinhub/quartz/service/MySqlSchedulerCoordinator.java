package com.chengyinhub.quartz.service;

import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.quartz.util.CanonicalJobTarget;
import com.chengyinhub.quartz.util.MySqlSessionLock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

/** One session owns scheduling; task sessions also fence work that survives a leader handoff. */
@Component
public class MySqlSchedulerCoordinator implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(MySqlSchedulerCoordinator.class);
    private final DataSource source;
    private final ConcurrentTaskScheduler tasks;
    private final Map<String, Plan> plans = new LinkedHashMap<>();
    private final List<ScheduledFuture<?>> futures = new ArrayList<>();
    private final Map<String, Long> lastFire = new ConcurrentHashMap<>();
    @Value("${chengyin.jobs.enabled:true}") private boolean jobsEnabled = true;
    private volatile boolean running;
    private volatile boolean leader;
    private long generation;
    private long activatedAt;
    private MySqlSessionLock.Lease leadership;
    private ScheduledExecutorService control;
    private Runnable refreshQuartz = () -> { };
    private Runnable stopQuartz = () -> { };

    public MySqlSchedulerCoordinator(@Qualifier("masterDataSource") DataSource source,
            @Qualifier("scheduledExecutorService") ScheduledExecutorService executor) {
        this.source = source;
        this.tasks = new ConcurrentTaskScheduler(executor);
    }

    public synchronized void registerQuartz(Runnable refresh, Runnable stop) {
        refreshQuartz = refresh;
        stopQuartz = stop;
    }

    public synchronized void registerCron(String target, String cron, Runnable action) {
        register(new Plan(target, new CronTrigger(cron), null, null, action));
    }

    public synchronized void registerFixedDelay(String target, Duration delay, Duration initial, Runnable action) {
        if (delay.isZero() || delay.isNegative() || initial.isNegative()) throw new ServiceException("任务时间间隔不合法");
        register(new Plan(target, null, delay, initial, action));
    }

    private void register(Plan plan) {
        if (running) throw new IllegalStateException("Register plans before scheduler startup");
        if (plans.putIfAbsent(plan.canonical, plan) != null) throw new ServiceException("存在重复的内置任务定义");
    }

    public synchronized boolean isBuiltin(String canonical) { return plans.containsKey(canonical); }
    public boolean isLeader() { return jobsEnabled && running && leader; }

    public MySqlSessionLock.Lease definitionLock() {
        MySqlSessionLock.Lease lease = MySqlSessionLock.acquire(source, "jobdef", "definitions", 3);
        if (lease == null) throw new ServiceException("任务配置正在更新，请稍后重试");
        return lease.untilTransactionCompletion();
    }

    @Override public synchronized void start() {
        if (running || !jobsEnabled) return;
        running = true;
        control = Executors.newSingleThreadScheduledExecutor(work -> {
            Thread thread = new Thread(work, "chengyin-scheduler-leadership");
            thread.setDaemon(true);
            return thread;
        });
        control.scheduleWithFixedDelay(this::maintainLeadership, 0, 2, TimeUnit.SECONDS);
    }

    /** Production calls use the private control thread; public entry permits deterministic lifecycle tests. */
    public synchronized void maintainLeadership() {
        if (!running || !jobsEnabled) return;
        try {
            if (leadership != null) {
                if (!leadership.isOwned()) {
                    loseLeadership();
                    return;
                }
                refreshQuartz.run();
                return;
            }
            leadership = MySqlSessionLock.acquire(source, "leader", "all", 0);
            if (leadership == null) return;
            generation++;
            activatedAt = System.currentTimeMillis();
            lastFire.clear();
            // Read and validate actual persisted plans before admitting any business work.
            refreshQuartz.run();
            leader = true;
            long epoch = generation;
            for (Plan plan : plans.values()) {
                Runnable action = () -> execute(plan.target, null, plan.action, epoch);
                ScheduledFuture<?> future = plan.cron != null ? tasks.schedule(action, plan.cron)
                        : tasks.scheduleWithFixedDelay(action, Instant.now().plus(plan.initial), plan.delay);
                if (future == null) throw new IllegalStateException("Scheduled task registration failed");
                futures.add(future);
            }
        } catch (RuntimeException failure) {
            loseLeadership();
            log.error("调度主会话不可用，已停止新调度: {}", failure.getClass().getSimpleName());
        }
    }

    public boolean execute(String target, Date fireTime, Runnable action) {
        long epoch;
        synchronized (this) { epoch = generation; }
        return execute(target, fireTime, action, epoch);
    }

    private boolean execute(String target, Date fireTime, Runnable action, long epoch) {
        synchronized (this) {
            if (!isLeader() || epoch != generation) return false;
            if (!leadership.isOwned()) { loseLeadership(); return false; }
            // A successor may not catch up the predecessor's same scheduled fire time.
            if (fireTime != null && fireTime.getTime() <= activatedAt) return false;
        }
        String canonical = CanonicalJobTarget.canonical(target);
        try (MySqlSessionLock.Lease work = MySqlSessionLock.acquire(source, "task", canonical, 0)) {
            if (work == null) return false;
            synchronized (this) {
                if (!isLeader() || epoch != generation || !leadership.isOwned()) return false;
                // GET_LOCK returning success does not prove that its session survived until admission.
                // Do not consume this fire time or enter business work after the task session was lost.
                if (!work.isOwned()) throw new IllegalStateException("任务互斥会话在执行前失效，任务未调用");
                if (fireTime != null) {
                    Long previous = lastFire.get(canonical);
                    if (previous != null && previous >= fireTime.getTime()) return false;
                    lastFire.put(canonical, fireTime.getTime());
                }
            }
            action.run(); // Original transaction and exceptions remain in the invocation.
            if (!work.isOwned()) throw new IllegalStateException("任务互斥会话失效，执行结果需核实");
            return true;
        }
    }

    private void loseLeadership() {
        leader = false;
        generation++;
        for (ScheduledFuture<?> future : futures) future.cancel(false);
        futures.clear();
        try { stopQuartz.run(); }
        catch (RuntimeException failure) { log.error("Quartz暂停失败，入口仍已关闭: {}", failure.getClass().getSimpleName()); }
        if (leadership != null) { leadership.close(); leadership = null; }
    }

    @Override public synchronized void stop() {
        running = false;
        if (control != null) control.shutdownNow();
        loseLeadership();
    }
    @Override public void stop(Runnable callback) { stop(); callback.run(); }
    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }

    private static final class Plan {
        final String target;
        final String canonical;
        final CronTrigger cron;
        final Duration delay;
        final Duration initial;
        final Runnable action;
        Plan(String target, CronTrigger cron, Duration delay, Duration initial, Runnable action) {
            this.target = target; this.canonical = CanonicalJobTarget.canonical(target);
            this.cron = cron; this.delay = delay; this.initial = initial; this.action = action;
        }
    }
}
