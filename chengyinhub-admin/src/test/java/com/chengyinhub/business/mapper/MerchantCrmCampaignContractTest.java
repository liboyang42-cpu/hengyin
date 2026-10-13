package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantCrmCampaignContractTest {

    @Test
    void migrationFreezesConsentCampaignRecipientsAndDeliveryIdempotency() throws Exception {
        String sql = normalized(read("sql/migration_merchant_operator_crm_phase1a_delivery_20260823.sql"));

        assertTrue(sql.contains("create table if not exists `merchant_crm_marketing_consent`"));
        assertTrue(sql.contains("create table if not exists `merchant_crm_campaign`"));
        assertTrue(sql.contains("create table if not exists `merchant_crm_campaign_recipient`"));
        assertTrue(sql.contains("create table if not exists `merchant_crm_delivery_guard`"),
                "跨 campaign 日频控必须由共享数据库 guard 承担");
        assertTrue(sql.contains("unique key `uk_merchant_crm_consent_request` (`member_id`, `merchant_row_id`, `request_id`)"));
        assertTrue(sql.contains("unique key `uk_merchant_crm_campaign_request` (`merchant_id`, `request_id`)"));
        assertTrue(sql.contains("unique key `uk_merchant_crm_campaign_recipient` (`campaign_id`, `member_id`, `channel`)"));
        assertTrue(sql.contains("unique key `uk_merchant_crm_delivery_daily` (`merchant_id`, `member_id`, `channel`, `delivery_date`)"),
                "同一商家、客户、渠道、自然日只能有一个共享投递 guard");
        assertTrue(sql.contains("`processing_token` varchar(64)"));
        assertTrue(sql.contains("`processing_lease_until` datetime(3)"));
        assertTrue(sql.contains("`version` bigint not null default 0"));
        assertTrue(sql.contains("`lease_token` varchar(64)"));
        assertTrue(sql.contains("`lease_until` datetime(3)"));
        assertTrue(sql.contains("check (`event_type` in ('opt_in', 'opt_out'))"));
        assertTrue(sql.contains("check (`channel` in ('in_app', 'coupon'))"));
        assertTrue(sql.contains("'skipped_frequency', 'retryable_busy', 'failed'"),
                "recipient CHECK 必须允许不消耗 attempt 的 BUSY 重试状态");
        assertTrue(sql.contains("constraint_name = 'chk_merchant_crm_recipient_status'")
                        && sql.contains("29b45aca49e7cf14e3f4a440ba6a69c661c98406d237adeb24961d4ca60182f2"),
                "migration 必须按表、约束名和 MySQL 8.0.45 归一表达式 hash 精确回读 BUSY 状态约束");
        assertFalse(sql.contains("default 1"), "营销同意绝不能默认开启");
        assertTrue(sql.contains("select count(*) = 4"), "migration 必须有四表引擎读回断言");
        assertTrue(sql.contains("information_schema.statistics"), "必须回读唯一键精确结构");
        assertTrue(sql.contains("information_schema.check_constraints"), "必须回读关键 CHECK");
    }

    @Test
    void legacyCampaignTableIsUpgradedToExactMerchantRowSchemaAndReadBack() throws Exception {
        String sql = normalized(read("sql/migration_merchant_operator_crm_phase1a_delivery_20260823.sql"));

        assertTrue(sql.contains("column_name = 'merchant_row_id'"),
                "兼容升级必须识别旧 merchant_row_id，不能让 create if not exists 假绿");
        assertTrue(sql.contains("change column `merchant_id` `merchant_owner_member_id` bigint not null"),
                "旧 merchant_id 的 owner 事实必须原样改名保留");
        assertTrue(sql.contains("change column `merchant_row_id` `merchant_id` bigint not null"),
                "旧 merchant_row_id 必须成为资源归属 merchant_id");
        assertTrue(sql.contains("drop index `uk_merchant_crm_campaign_request`"));
        assertTrue(sql.contains("add unique key `uk_merchant_crm_campaign_request` (`merchant_id`, `request_id`)"));
        assertTrue(sql.contains("column_name = 'merchant_owner_member_id' and lower(data_type) = 'bigint' "
                + "and is_nullable = 'no'"), "新 owner 快照列必须精确回读类型与 NULL 约束");
        assertTrue(sql.contains("select count(*) = 0 from information_schema.columns "
                        + "where table_schema = database() and table_name = 'merchant_crm_campaign' "
                        + "and column_name = 'merchant_row_id'"),
                "升级后旧列必须由目标库回读证明已经处置");
    }

    @Test
    void campaignAndConsentSqlAreStoreScopedAppendOnlyAndDoNotReadPhones() throws Exception {
        String campaign = normalized(read("../chengyinhub-system/src/main/resources/mapper/business/MerchantCrmCampaignMapper.xml"));
        String consent = normalized(read("../chengyinhub-system/src/main/resources/mapper/business/MerchantCrmMarketingConsentMapper.xml"));
        String app = normalized(read("../chengyinhub-system/src/main/resources/mapper/business/MerchantCrmAppMapper.xml"));

        String replay = section(campaign, "<select id=\"selectbyrequestidforshare\"", "</select>");
        assertTrue(replay.contains("lock in share mode")
                        && replay.contains("c.merchant_id = #{merchantid}")
                        && replay.contains("c.request_id = #{requestid}"),
                "并发创建后的回执须按门店和请求标识当前读，不能再次读取旧快照");
        assertTrue(campaign.contains("merchant_id = #{merchantid}"),
                "campaign 资源必须按具体 merchant row 隔离，不能按 owner member 复用");
        assertTrue(campaign.contains("for update"), "逐人投递必须锁定 recipient 以保证幂等");
        assertTrue(campaign.contains("current_date"), "日频控必须由数据库时间判定");
        assertTrue(campaign.contains("insert ignore into merchant_crm_delivery_guard"),
                "外部副作用前必须先竞争共享日 guard");
        assertTrue(campaign.contains("lease_token = #{leasetoken}"));
        assertTrue(campaign.contains("version = #{expectedversion}"), "guard 状态切换必须用版本 CAS");
        String release = section(campaign, "<update id=\"releasedeliveryguardforretry\">", "</update>");
        assertTrue(release.contains("set status = 'reserved'")
                        && release.contains("status = 'dispatching'")
                        && release.contains("lease_token = #{leasetoken}")
                        && release.contains("version = #{expectedversion}")
                        && release.contains("lease_until = date_sub(now(3), interval 1 second)"),
                "明确未发的 guard 只能按原投递租约 CAS 退回可重领 RESERVED");
        assertTrue(campaign.contains("processing_token = #{processingtoken}"),
                "campaign PROCESSING 必须用 token fence");
        assertTrue(campaign.contains("processing_lease_until &lt; now(3)"),
                "卡滞 campaign 只能在租约到期后恢复");
        String reclaim = section(campaign, "<update id=\"reclaimstalecampaign\">", "</update>");
        assertTrue(reclaim.contains("m.id = c.merchant_id")
                        && reclaim.contains("m.member_id = c.merchant_owner_member_id")
                        && reclaim.contains("m.status = 1")
                        && reclaim.contains("m.account_status = 1")
                        && reclaim.contains("m.del_flag = 0"),
                "stale campaign 接管 CAS 必须在数据库内复核门店存在、归属与启用状态");
        String renew = section(campaign, "<update id=\"renewcampaignlease\">", "</update>");
        assertTrue(renew.contains("m.id = c.merchant_id")
                        && renew.contains("m.member_id = c.merchant_owner_member_id")
                        && renew.contains("m.status = 1")
                        && renew.contains("m.account_status = 1")
                        && renew.contains("m.del_flag = 0"),
                "每位 recipient 外呼前的原子续租必须再次复核 exact merchant、owner lineage 与启用状态");
        String invalidate = section(campaign, "<update id=\"invalidatestalecampaign\">", "</update>");
        assertTrue(invalidate.contains("status = 'no_eligible'")
                        && invalidate.contains("processing_token = null")
                        && invalidate.contains("processing_lease_until = null")
                        && invalidate.contains("version = #{expectedversion}")
                        && invalidate.contains("not exists"),
                "已失效门店的 stale campaign 必须用版本 CAS 进入不可恢复终态");
        String invalidateClaimed = section(campaign,
                "<update id=\"invalidateclaimedcampaign\">", "</update>");
        assertTrue(invalidateClaimed.contains("processing_token = #{processingtoken}")
                        && invalidateClaimed.contains("status = 'no_eligible'")
                        && invalidateClaimed.contains("not exists"),
                "reclaim 后发现门店失效时必须用 processing token fence 终结任务，不能留下可恢复外呼");
        assertTrue(campaign.contains("coalesce(failure_code, '') &lt;&gt; 'delivery_outcome_unknown'"),
                "外部结果未知的 recipient 不得被 retry 重置为 PENDING");
        String projection = section(campaign, "<sql id=\"campaigncolumns\">", "</sql>");
        assertTrue(projection.contains("as retryable_count"));
        assertTrue(projection.contains("coalesce(retry.failure_code, '') &lt;&gt; 'delivery_outcome_unknown'"));
        assertTrue(projection.contains("retry.attempt_count &lt; 20"));
        assertTrue(projection.contains("'retryable_busy'"),
                "BUSY 收件人必须被统计为可重试，不能成为永久失败");
        String reset = section(campaign, "<update id=\"resetretryablerecipients\">", "</update>");
        assertTrue(reset.contains("'retryable_busy'"), "人工重试必须重新领取 BUSY 收件人");
        assertTrue(reset.contains("attempt_count &lt; 20"), "真实投递失败仍必须受 20 次上限保护");
        // 2026-09-23 裁决(CU-M-19):未同意/已退订不算可重试,计数与重置两处同口径
        assertFalse(projection.contains("'skipped_no_consent'"), "可重试人数不得计入未同意/已退订的客户");
        assertFalse(reset.contains("'skipped_no_consent'"), "重试不得重新领取未同意/已退订的客户");
        String busy = section(campaign, "<update id=\"markrecipientretryablebusy\">", "</update>");
        assertTrue(busy.contains("status = 'retryable_busy'"));
        assertTrue(busy.contains("failure_code = 'delivery_busy'"));
        assertFalse(busy.contains("attempt_count"), "锁竞争不是渠道投递，不得消耗 attempt_count");
        String finalize = section(campaign, "<update id=\"finalizecampaign\">", "</update>");
        assertTrue(finalize.contains("status &lt;&gt; 'delivered'"),
                "BUSY 必须让 campaign 保持 PARTIAL_FAILED，而不是误判完成");
        assertTrue(finalize.contains("r.status = 'failed'"),
                "failed_count 只能统计真实 FAILED，不能把 BUSY 伪装为失败");
        assertFalse(campaign.contains("${"));
        assertFalse(consent.contains("update merchant_crm_marketing_consent"), "同意和退订必须追加留痕");
        assertFalse(consent.contains("delete from merchant_crm_marketing_consent"));
        assertTrue(consent.contains("event_type = 'opt_in'"), "无最新 OPT_IN 时必须默认拒绝");
        assertTrue(consent.contains("sibling.member_id = m.member_id")
                        && consent.contains("sibling.id &lt;&gt; m.id"),
                "缺少报名到 merchant row 的权威关联时，多门店主体必须 fail-closed，不能伪造门店级同意");
        assertTrue(app.contains("selectownedcampaignrecipientcandidates"));
        assertFalse(section(app, "selectownedcampaignrecipientcandidates", "</select>").contains("phone"),
                "投递名单冻结不得读取手机号");
    }

    @Test
    void notificationUsesDedicatedCrmCardAndNeverOfficialOrGroupSemantics() throws Exception {
        String adapter = normalized(read("../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/MerchantCrmNotificationAdapter.java"));

        assertTrue(adapter.contains("merchantcrmcampaign"));
        assertTrue(adapter.contains("sendsystem"));
        assertFalse(adapter.contains("notifyofficialbroadcast"));
        assertFalse(adapter.contains("getorcreateclubgroup"));
        assertFalse(adapter.contains("getorcreateteamgroup"));
    }

    @Test
    void staleProcessingRecoveryIsRegisteredAsEnabledNonConcurrentJob() throws Exception {
        String schema = normalized(read("sql/migration_merchant_operator_crm_phase1a_delivery_20260823.sql"));
        String job = normalized(read("sql/migration_merchant_operator_crm_phase1a_delivery_job_20260825.sql"));
        String task = normalized(read("../chengyinhub-system/src/main/java/com/chengyinhub/business/task/MerchantCrmDeliveryRecoveryTask.java"));

        assertFalse(schema.contains("sys_job"), "pre-deploy schema 不得提前注册依赖新 jar 的 Quartz bean");
        assertTrue(job.contains("post-deploy"));
        assertTrue(job.contains("merchantcrmdeliveryrecoverytask.run()"));
        assertTrue(job.contains("'3', '1', '0'"), "恢复任务必须忽略错过批次、禁止并发并启用");
        assertTrue(job.contains("select count(*) = 1")
                && job.contains("job_name = '商家crm触达恢复'")
                && job.contains("job_group = 'default'")
                && job.contains("cron_expression = '0 * * * * ?'")
                && job.contains("misfire_policy = '3'")
                && job.contains("concurrent = '1'")
                && job.contains("status = '0'"), "post-deploy job 必须 exact-one 回读全部调度配置");
        assertTrue(task.contains("campaignservice.recoverstaleprocessing()"));
    }

    private String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = from < 0 ? -1 : source.indexOf(end, from);
        return from < 0 || to < 0 ? "" : source.substring(from, to);
    }

    private String read(String relative) throws Exception {
        Path base = Paths.get(System.getProperty("user.dir"));
        return new String(Files.readAllBytes(base.resolve(relative).normalize()), StandardCharsets.UTF_8);
    }

    private String normalized(String value) {
        return value.toLowerCase().replaceAll("\\s+", " ").trim();
    }
}
