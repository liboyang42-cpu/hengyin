package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantCrmPhase1AContractTest {
    @Test
    void segmentAndExportMappersAreMerchantScopedAndStateChangesUseCas() throws Exception {
        String segment = normalized(read("../chengyinhub-system/src/main/resources/mapper/business/MerchantCrmSegmentMapper.xml"));
        String export = normalized(read("../chengyinhub-system/src/main/resources/mapper/business/MerchantCrmExportTaskMapper.xml"));
        assertTrue(segment.contains("where merchant_id = #{merchantid}"));
        assertFalse(segment.contains("#{ownermemberid}"));
        assertFalse(segment.contains("${"));
        assertTrue(export.contains("t.merchant_id = #{merchantid} and t.merchant_owner_member_id = #{ownermemberid}"));
        assertTrue(export.contains("m.id = t.merchant_id and m.member_id = t.merchant_owner_member_id"));
        assertTrue(export.contains("m.status = 1 and m.account_status = 1 and m.del_flag = 0"));
        assertTrue(export.contains("merchant_owner_member_id = #{ownermemberid}"));
        assertTrue(export.contains("invalidateownermismatch"));
        assertTrue(export.contains("status in ('pending', 'running', 'success')"));
        assertTrue(export.contains("where id = #{taskid} and version = #{expectedversion}"));
        assertTrue(export.contains("status = 'pending'"));
        assertTrue(export.contains("status = 'running' and version = #{expectedversion}"));
        assertTrue(export.contains("status = 'success'"));
        assertFalse(export.contains("${"));
    }

    @Test
    void migrationStoresOnlyTokenHashAndDeclaresOrderedDependencies() throws Exception {
        String sql = normalized(read("sql/migration_merchant_operator_crm_phase1a_20260823.sql"));
        String dependencies = normalized(read("../ci/migration-dependencies.json"));
        assertTrue(sql.contains("create table if not exists `merchant_customer_segment`"));
        assertTrue(sql.contains("create table if not exists `merchant_crm_export_task`"));
        assertTrue(sql.contains("`token_hash` char(64) not null"));
        assertFalse(sql.contains("raw_token"));
        assertFalse(sql.contains("download_token"));
        assertTrue(sql.contains("unique key `uk_merchant_crm_export_request` (`merchant_id`, `request_id`)"));
        assertTrue(sql.contains("-- runner: assert=scalar-true"));
        assertTrue(sql.contains("information_schema.statistics"));
        assertTrue(sql.contains("cols = 'merchant_id,request_id'"));
        assertTrue(dependencies.contains("\"merchant_operator_crm_phase1a_20260823\": [ \"business_audit_event_20260823\", \"merchant_operator_crm_followup_20260823\" ]"));
    }

    @Test
    void productionCandidateRepairRenamesTheExactEmptyLegacyExportScope() throws Exception {
        String repair = normalized(read("sql/migration_merchant_z_candidate_schema_repair_20260826.sql"));
        assertTrue(repair.contains("@merchant_crm_export_legacy_shape_ok"));
        assertTrue(repair.contains("select count(*) from merchant_crm_export_task")
                        && repair.contains("@merchant_crm_export_before_rows = 0"),
                "旧候选导出表只有为空时才允许交换两个 merchant 身份列");
        assertTrue(repair.contains("change column `merchant_id` `merchant_owner_member_id` bigint not null"));
        assertTrue(repair.contains("change column `merchant_row_id` `merchant_id` bigint not null"));
        assertTrue(repair.contains("unique key `uk_merchant_crm_export_request` (`merchant_id`, `request_id`)"));
        assertTrue(repair.contains("@merchant_aftercare_before_rows")
                        && repair.contains("@merchant_crm_export_before_rows"),
                "修复后必须回读两张表行数守恒");
    }

    @Test
    void privateWriterCannotFallBackToPublicProfileDownloadDirectory() throws Exception {
        String source = normalized(read("../chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/MerchantCrmPrivateExcelWriter.java"));
        assertTrue(source.contains("profile + \"-private\""));
        assertFalse(source.contains("getdownloadpath"));
        assertTrue(source.contains("getcanonicalfile"));
    }

    private String read(String relative) throws Exception {
        Path base = Paths.get(System.getProperty("user.dir"));
        return new String(Files.readAllBytes(base.resolve(relative).normalize()), StandardCharsets.UTF_8);
    }

    private String normalized(String value) { return value.toLowerCase().replaceAll("\\s+", " ").trim(); }
}
