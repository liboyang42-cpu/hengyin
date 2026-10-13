package com.chengyinhub.web.runner;

import com.chengyinhub.business.service.IUmsMemberWithdrawalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 仅在显式设置批次大小时迁移历史提现明文，防止重启时无意扫描全表。
 */
@Component
public class WithdrawalPiiBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalPiiBackfillRunner.class);

    @Autowired
    private IUmsMemberWithdrawalService withdrawalService;

    @Value("${withdrawal.pii.backfill-limit:0}")
    private int backfillLimit;

    @Override
    public void run(ApplicationArguments args) {
        if (backfillLimit <= 0) {
            return;
        }
        int migrated = withdrawalService.migrateLegacySensitiveData(backfillLimit);
        log.info("[WithdrawalPII] migrated {} legacy withdrawal records", migrated);
    }
}
