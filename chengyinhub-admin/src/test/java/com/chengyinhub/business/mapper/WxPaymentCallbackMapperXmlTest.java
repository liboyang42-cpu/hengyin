package com.chengyinhub.business.mapper;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class WxPaymentCallbackMapperXmlTest
{
    @Test
    void inboxMapperKeepsIdempotentInsertAndOwnerFencedTerminalStatements() throws Exception
    {
        Configuration configuration = parse("mapper/business/WxPaymentCallbackInboxMapper.xml");
        String namespace = WxPaymentCallbackInboxMapper.class.getName() + ".";
        assertTrue(configuration.hasStatement(namespace + "insertIgnore"));
        assertTrue(configuration.hasStatement(namespace + "selectOwnedForUpdate"));
        assertTrue(configuration.hasStatement(namespace + "markDone"));
        assertTrue(configuration.hasStatement(namespace + "markIgnoredStale"));
        assertTrue(configuration.hasStatement(namespace + "release"));
        assertTrue(configuration.hasStatement(namespace + "lockBusinessTarget"));

        String insert = sql(configuration, namespace + "insertIgnore");
        assertTrue(insert.contains("insert ignore into wx_payment_callback_inbox"),
                "notify_hash 唯一键去重必须走 insert ignore,重复投递不能 500");
        String claim = sql(configuration, namespace + "claim");
        assertTrue(claim.contains("fence_token = fence_token + 1"),
                "每次领取必须推进 fence_token,旧 owner 的终态写入才写不进来");
        assertTrue(claim.contains("lease_until"), "领取必须写租约,过期才能被接管");
        String owned = sql(configuration, namespace + "selectOwnedForUpdate");
        assertTrue(owned.contains("owner_token = UNHEX(") && owned.contains("fence_token = "),
                "终态事务必须校验 owner+fence");
    }

    @Test
    void outboxMapperNeverAutoDispatchesMoneyAffectingRows() throws Exception
    {
        Configuration configuration = parse("mapper/business/WxPaymentCallbackOutboxMapper.xml");
        String namespace = WxPaymentCallbackOutboxMapper.class.getName() + ".";
        assertTrue(configuration.hasStatement(namespace + "insertIgnore"));
        assertTrue(configuration.hasStatement(namespace + "selectClaimableForUpdate"));
        assertTrue(configuration.hasStatement(namespace + "claimById"));
        assertTrue(configuration.hasStatement(namespace + "complete"));
        assertTrue(configuration.hasStatement(namespace + "retryOrEscalate"));
        assertTrue(configuration.hasStatement(namespace + "escalateToManual"));

        String insert = sql(configuration, namespace + "insertIgnore");
        assertTrue(insert.contains("insert ignore into wx_payment_callback_outbox"),
                "idempotency_key 唯一键去重必须走 insert ignore");
        String claimable = sql(configuration, namespace + "selectClaimableForUpdate");
        assertTrue(claimable.contains("money_affecting = 0"),
                "任务书1.6:money_affecting=1 的资金动作禁止自动派发");
        assertTrue(claimable.contains("state in (0, 3)") && claimable.contains("for update"),
                "只领取 pending/retryable 且行锁防并发");
        String retry = sql(configuration, namespace + "retryOrEscalate");
        assertTrue(retry.contains("max_attempts"),
                "自动重试必须以 max_attempts 为上限,不允许无限重试");
        assertTrue(retry.contains("money_affecting = 0"),
                "资金动作即使被误领取也不得走自动重试通道");
    }

    @Test
    void migrationKeepsTheUniqueKeysTheMappersRelyOn() throws Exception
    {
        Path migration = migrationFile();
        String ddl = new String(Files.readAllBytes(migration), StandardCharsets.UTF_8);
        assertTrue(ddl.contains("uk_wx_payment_callback_notify_hash"),
                "inbox 去重依赖 notify_hash 唯一键");
        assertTrue(ddl.contains("uk_wx_payment_outbox_idempotency"),
                "outbox 幂等依赖 idempotency_key 唯一键");
        assertTrue(ddl.contains("max_attempts") && ddl.contains("DEFAULT 3"),
                "任务书1.6:重试上限默认3");
    }

    private static Configuration parse(String resource) throws Exception
    {
        Configuration configuration = new Configuration();
        try (InputStream input = Resources.getResourceAsStream(resource))
        {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private static String sql(Configuration configuration, String statement)
    {
        return configuration.getMappedStatement(statement).getBoundSql(null).getSql();
    }

    private static Path migrationFile()
    {
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        if (root.getFileName() != null && "chengyinhub-admin".equals(root.getFileName().toString()))
        {
            return root.resolve("sql/migration_wx_payment_callback_inbox_outbox_20260725.sql");
        }
        return root.resolve("chengyinhub-admin/sql/migration_wx_payment_callback_inbox_outbox_20260725.sql");
    }
}
