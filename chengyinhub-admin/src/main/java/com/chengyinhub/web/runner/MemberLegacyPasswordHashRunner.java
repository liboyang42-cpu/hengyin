package com.chengyinhub.web.runner;

import com.chengyinhub.business.service.impl.MemberLegacyPasswordHashService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * F-L6-17：仅在显式设置 member.password.legacy-hash-limit 时把存量明文口令批量改写为 BCrypt。
 * 与 WithdrawalPiiBackfillRunner 同口径：默认 0 = 不扫描，避免重启时无意动生产数据。
 * 失败只记日志，不阻断启动（口令语义不变，未处理的行仍可由登录路径逐个升级）。
 */
@Component
public class MemberLegacyPasswordHashRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MemberLegacyPasswordHashRunner.class);

    @Autowired
    private MemberLegacyPasswordHashService hashService;

    @Value("${chengyin.jobs.enabled:true}")
    private boolean jobsEnabled = true;

    @Value("${member.password.legacy-hash-limit:0}")
    private int hashLimit;

    @Override
    public void run(ApplicationArguments args) {
        if (!jobsEnabled || hashLimit <= 0) {
            return;
        }
        try {
            MemberLegacyPasswordHashService.Result result = hashService.hashLegacyPasswords(hashLimit);
            log.info("[LegacyPasswordHash] scanned={} hashed={} skipped={}",
                    result.getScanned(), result.getHashed(), result.getSkipped());
        } catch (RuntimeException e) {
            log.error("[LegacyPasswordHash] batch stopped: {}", e.getClass().getSimpleName());
        }
    }
}
