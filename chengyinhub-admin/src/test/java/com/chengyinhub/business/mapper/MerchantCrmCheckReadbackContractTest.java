package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantCrmCheckReadbackContractTest {

    private String migrationSql() throws Exception {
        return new String(Files.readAllBytes(Paths.get(
                "sql/migration_merchant_operator_crm_phase1a_delivery_20260823.sql")),
                StandardCharsets.UTF_8).toLowerCase().replaceAll("\\s+", " ");
    }

    @Test
    void simpleCheckReadbackPinsTableEnforcementAndCompleteClause() throws Exception {
        String sql = migrationSql();
        int totalMarker = sql.indexOf("-- 四张表只能有下方逐条列出的 15 个 check");
        int marker = sql.indexOf("-- 12 个简单 check");
        int compoundMarker = sql.indexOf("-- lease / receipt 复合 check");
        String readback = marker < 0 || compoundMarker < marker
                ? "" : sql.substring(marker, compoundMarker);
        String totalReadback = totalMarker < 0 || marker < totalMarker
                ? "" : sql.substring(totalMarker, marker);

        assertTrue(totalReadback.contains("select count(*) = 15")
                        && totalReadback.contains("'merchant_crm_marketing_consent'")
                        && totalReadback.contains("'merchant_crm_campaign'")
                        && totalReadback.contains("'merchant_crm_campaign_recipient'")
                        && totalReadback.contains("'merchant_crm_delivery_guard'"),
                "四张表的 CHECK 总集合必须精确为 15 条，额外约束也要 fail closed");
        assertTrue(readback.contains("from information_schema.table_constraints tc")
                        && readback.contains("join information_schema.check_constraints cc"),
                "简单 CHECK 回读必须把约束绑定到实际表");
        assertTrue(readback.contains("tc.constraint_type = 'check'")
                        && readback.contains("tc.enforced = 'yes'"),
                "简单 CHECK 回读必须只接受 ENFORCED=YES 的 CHECK");
        for (String tableConstraintPair : new String[]{
                "tc.table_name = 'merchant_crm_marketing_consent' and tc.constraint_name = 'chk_merchant_crm_consent_event'",
                "tc.table_name = 'merchant_crm_marketing_consent' and tc.constraint_name = 'chk_merchant_crm_consent_channel'",
                "tc.table_name = 'merchant_crm_campaign' and tc.constraint_name = 'chk_merchant_crm_campaign_channel'",
                "tc.table_name = 'merchant_crm_campaign' and tc.constraint_name = 'chk_merchant_crm_campaign_status'",
                "tc.table_name = 'merchant_crm_campaign' and tc.constraint_name = 'chk_merchant_crm_campaign_counts'",
                "tc.table_name = 'merchant_crm_campaign' and tc.constraint_name = 'chk_merchant_crm_campaign_version'",
                "tc.table_name = 'merchant_crm_campaign_recipient' and tc.constraint_name = 'chk_merchant_crm_recipient_channel'",
                "tc.table_name = 'merchant_crm_campaign_recipient' and tc.constraint_name = 'chk_merchant_crm_recipient_status'",
                "tc.table_name = 'merchant_crm_campaign_recipient' and tc.constraint_name = 'chk_merchant_crm_recipient_attempts'",
                "tc.table_name = 'merchant_crm_delivery_guard' and tc.constraint_name = 'chk_merchant_crm_delivery_status'",
                "tc.table_name = 'merchant_crm_delivery_guard' and tc.constraint_name = 'chk_merchant_crm_delivery_channel'",
                "tc.table_name = 'merchant_crm_delivery_guard' and tc.constraint_name = 'chk_merchant_crm_delivery_version'"
        }) {
            assertTrue(readback.contains(tableConstraintPair),
                    "简单 CHECK 回读必须钉住目标表与约束名: " + tableConstraintPair);
        }
        for (String hash : new String[]{
                "75938ee1f39c3801eff910a3f4e68ed816f6ddf4a720c38bb7850542ba0b5a65",
                "d0a50e1594e3992a4f7c29d4a288814f62e50e2f1b055a84245f32e6a5ee818b",
                "d7828e80bf354d77befc4ffdb5ad6526e215687bf85b2c529193a7d218e70f62",
                "b3fc86ae21d08c5999ac2a8d300b568f9893450d6ddf2fbf248182a10c51362b",
                "ed78cf3fd4c0f383a408b73d34cba988c4fd704986f49efe37d83f0b8e932379",
                "29b45aca49e7cf14e3f4a440ba6a69c661c98406d237adeb24961d4ca60182f2",
                "96a885b632ed50eaa794aefc08852dc4f954aa558f19d27ac6c87ebb680c9d57",
                "e17e83edec2fcd96aae69568b9b89d77ee0176cd4107644980feb5d7495cde92",
        }) {
            assertTrue(readback.contains(hash), "简单 CHECK 必须钉完整归一表达式 hash: " + hash);
        }
    }

    @Test
    void compoundCheckReadbackPreservesBooleanGrouping() throws Exception {
        String sql = migrationSql();
        int marker = sql.indexOf("-- lease / receipt 复合 check");
        String readback = marker < 0 ? "" : sql.substring(marker);

        assertTrue(readback.contains("from information_schema.table_constraints tc")
                        && readback.contains("join information_schema.check_constraints cc"),
                "复合 CHECK 回读必须把约束绑定到实际表");
        assertTrue(readback.contains("tc.constraint_type = 'check'")
                        && readback.contains("tc.enforced = 'yes'"),
                "复合 CHECK 回读必须只接受 ENFORCED=YES 的 CHECK");
        for (String tableConstraintPair : new String[]{
                "tc.table_name = 'merchant_crm_campaign' and tc.constraint_name = 'chk_merchant_crm_campaign_lease'",
                "tc.table_name = 'merchant_crm_delivery_guard' and tc.constraint_name = 'chk_merchant_crm_delivery_lease'",
                "tc.table_name = 'merchant_crm_delivery_guard' and tc.constraint_name = 'chk_merchant_crm_delivery_receipt'"
        }) {
            assertTrue(readback.contains(tableConstraintPair),
                    "复合 CHECK 回读必须钉住目标表与约束名: " + tableConstraintPair);
        }
        assertFalse(readback.contains("[[:space:]`\\\\()]"),
                "复合 CHECK 回读不得移除决定 AND/OR 优先级的括号");
        assertTrue(readback.contains("sha2(regexp_replace(lower(cc.check_clause)"),
                "复合 CHECK 必须钉住目标 MySQL 归一化后的完整表达式");
        assertTrue(readback.contains("7be8970f09a57e608c4c8f30c87b127ad7a6ab68f34ccdd61ec775c0164f7552"),
                "campaign lease hash 必须来自真实 MySQL 8.0.45 CHECK_CLAUSE");
        assertTrue(readback.contains("857486327e61eb81d15aefb2e67731d8ea49573a66c11207f15cc344cc2fd89d"),
                "delivery lease hash 必须来自真实 MySQL 8.0.45 CHECK_CLAUSE");
        assertTrue(readback.contains("c73e8e49e70877a156e330d8d17a3b0a3c6f915f264e621d61b909c23c4f566f"),
                "delivery receipt hash 必须来自真实 MySQL 8.0.45 CHECK_CLAUSE");
    }
}
