package com.chengyinhub.web.runner;

import com.chengyinhub.business.service.impl.MemberLegacyPasswordHashService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * F-L6-17：仅在显式设置 member.password.legacy-hash-limit 时把存量明文口令批量改写为 BCrypt。
 * 与 WithdrawalPiiBackfillRunner 同口径：默认 0 = 不扫描，避免重启时无意动生产数据。
 * 失败只记日志，不阻断启动（口令语义不变，未处理的行仍可由登录路径逐个升级）。
 *
 * <p>PR10 复审:BCrypt 每行几十毫秒,原先在 ApplicationRunner 里同步跑完整个 limit 会把启动卡住。
 * 现在应用就绪后才在单独的后台线程里按 legacy-hash-batch 分批续扫(批间小憩),启动线程立刻返回;
 * 与登录路径的并发由 AppLoginService 的「CAS 落空后重读 BCrypt」兜住。
 */
@Component
public class MemberLegacyPasswordHashRunner implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MemberLegacyPasswordHashRunner.class);

    @Autowired
    private MemberLegacyPasswordHashService hashService;

    @Value("${chengyin.jobs.enabled:true}")
    private boolean jobsEnabled = true;

    @Value("${member.password.legacy-hash-limit:0}")
    private int hashLimit;

    @Value("${member.password.legacy-hash-batch:100}")
    private int batchSize = 100;

    @Value("${member.password.legacy-hash-pause-ms:200}")
    private long pauseMillis = 200L;

    private ExecutorService executor;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        start();
    }

    /** 开关关着什么都不做;开着就把分批任务交给后台单线程,调用方(启动线程)立即返回。 */
    void start() {
        if (!jobsEnabled || hashLimit <= 0) {
            return;
        }
        synchronized (this) {
            if (executor == null) {
                executor = Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "legacy-password-hash");
                    thread.setDaemon(true);
                    return thread;
                });
            }
        }
        executor.execute(this::hashInBatches);
    }

    /** 按批续扫直到 limit 用完或没有剩余明文;任何异常只记日志。 */
    void hashInBatches() {
        int remaining = hashLimit;
        int batch = Math.max(1, batchSize);
        long cursor = 0L;
        int hashed = 0;
        int skipped = 0;
        int scanned = 0;
        try {
            while (remaining > 0 && !Thread.currentThread().isInterrupted()) {
                MemberLegacyPasswordHashService.Result result =
                        hashService.hashLegacyPasswords(cursor, Math.min(batch, remaining));
                if (result == null || result.getScanned() <= 0) {
                    break;
                }
                scanned += result.getScanned();
                hashed += result.getHashed();
                skipped += result.getSkipped();
                remaining -= result.getScanned();
                cursor = result.getLastId();
                if (remaining > 0 && pauseMillis > 0) {
                    Thread.sleep(pauseMillis);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error("[LegacyPasswordHash] batch stopped: {}", e.getClass().getSimpleName());
        }
        log.info("[LegacyPasswordHash] scanned={} hashed={} skipped={}", scanned, hashed, skipped);
    }

    @Override
    public void destroy() {
        ExecutorService current;
        synchronized (this) {
            current = executor;
            executor = null;
        }
        if (current != null) {
            current.shutdownNow();
        }
    }
}
