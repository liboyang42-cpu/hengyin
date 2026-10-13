package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClubPostAnnouncementContractTest {
    @Test
    void mapperPinsOnlyInClubListAndAllMutationsUseVersionCas() throws Exception {
        String xml = read("../chengyinhub-system/src/main/resources/mapper/business/ClubPostMapper.xml");
        assertTrue(xml.contains("order by cp.is_pinned desc, cp.pinned_at desc, cp.id desc"));
        assertTrue(xml.contains("and version = #{expectedVersion}"));
        assertTrue(xml.contains("for update"));
        String feed = xml.substring(xml.indexOf("<select id=\"selectFeedByClubIds\""),
                xml.indexOf("</select>", xml.indexOf("<select id=\"selectFeedByClubIds\"")));
        assertFalse(feed.contains("order by cp.is_pinned"), "聚合流不能被各俱乐部的长期置顶淹没");
    }

    @Test
    void revisionMapperIsAppendOnlyAndMigrationConstrainsPinnedType() throws Exception {
        String revision = read("../chengyinhub-system/src/main/resources/mapper/business/ClubPostRevisionMapper.xml");
        assertTrue(revision.contains("insertClubPostRevision"));
        assertTrue(revision.contains("selectByPostId"));
        assertFalse(revision.contains("<update"));
        assertFalse(revision.contains("<delete"));
        String migration = read("sql/migration_club_post_announcement_20260823.sql");
        String normalizedMigration = migration.replace("`", "");
        assertTrue(normalizedMigration.contains("is_pinned = 0 OR type <=> 2"),
                "置顶 CHECK 必须用 NULL-safe equality，不能放过 type=NULL");
        assertTrue(normalizedMigration.contains("is_pinned = 1 AND (type IS NULL OR type <> 2)"),
                "迁移回读必须覆盖 type=NULL 的非法置顶");
        assertTrue(migration.contains("UNIQUE KEY uk_club_post_revision_version"));
        assertTrue(migration.contains("-- runner: assert=scalar-true"));
        assertTrue(migration.contains("information_schema.statistics"));
        assertTrue(migration.contains("cols = 'post_id,snapshot_version'"),
                "migration 必须精确读回唯一键列序，不能只数索引名");
        assertTrue(migration.contains("@club_post_announcement_shape_exact")
                        && migration.contains("PREPARE club_post_announcement_alter_stmt")
                        && migration.contains("CREATE TABLE IF NOT EXISTS club_post_revision"),
                "ALTER 成功而 revision CREATE 中断后，整份迁移必须能安全重放");
        assertTrue(count(migration,
                "column_name IN ('is_pinned','pinned_at','version','update_time')") >= 3,
                "旧态与终态精确守卫都必须覆盖 pinned_at/update_time，不能只看两列");
        assertTrue(count(migration,
                "1:BTREE:YES:0:5:5:club_id,del_flag,is_pinned,pinned_at,id") >= 2,
                "ALTER 前和终态都必须精确回读置顶索引形状");
        assertTrue(migration.contains("77b527b91cf0223e5448ff56b03b262540ca583f77407c1a62fbcfece0d87da4")
                        && migration.contains("64fc12be38727a8fa9f88c730a3700b7eae5b503ac4a96ae5742b974ead0f289"),
                "revision 候选表必须精确回读全列与 CHECK 正文/ENFORCED");
    }

    @Test
    void nullSafetyRepairReplacesTheAlreadyAppliedProductionConstraint() throws Exception {
        String repair = read("sql/migration_club_role_z_null_safety_repair_20260826.sql")
                .replaceAll("\\s+", " ");
        assertTrue(repair.contains("DROP CHECK chk_club_post_pinned"));
        assertTrue(repair.contains("is_pinned = 0 OR type <=> 2"));
        assertTrue(repair.contains("is_pinned = 1 AND (type IS NULL OR type <> 2)"));
        assertTrue(repair.contains("information_schema.check_constraints"));
        assertTrue(repair.contains("constraint_name = 'chk_club_post_pinned'"));
        assertTrue(repair.contains("tc.enforced = 'YES'")
                        && repair.contains("96edec2bf807cbf809d410e84d835c9f6bd6a0be56d42ea6a285ac54a1c645e3")
                        && repair.contains("f9ba85873b52394c09bfb30afb76c372d4240b7ac7b9b0857952dfb9b0e1d684"),
                "执行前只接受生产旧式或目标式，不能静默覆盖同名第三形态 CHECK");
    }

    @Test
    void updateReplayLocksAnExactAuditReceiptBackedByTheRequestUniqueKey() throws Exception {
        String mapper = read("../chengyinhub-system/src/main/resources/mapper/business/BusinessAuditEventMapper.xml")
                .replaceAll("\\s+", " ").toLowerCase();
        String lookup = mapper.substring(mapper.indexOf("<select id=\"selectbydomainactionrequestforupdate\""),
                mapper.indexOf("</select>", mapper.indexOf("<select id=\"selectbydomainactionrequestforupdate\"")));
        assertTrue(lookup.contains("domain_type = #{domaintype}"));
        assertTrue(lookup.contains("domain_id = #{domainid}"));
        assertTrue(lookup.contains("action = #{action}"));
        assertTrue(lookup.contains("request_id = #{requestid}"));
        assertTrue(lookup.contains("for update"));

        String migration = read("sql/migration_business_audit_event_20260823.sql").toLowerCase();
        assertTrue(migration.contains(
                "unique key uk_business_audit_request (domain_type, domain_id, action, request_id)"));
        assertTrue(migration.contains("cols = 'domain_type,domain_id,action,request_id'"));
    }

    private String read(String relative) throws Exception {
        Path path = Paths.get(relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private int count(String value, String fragment) {
        int occurrences = 0;
        int offset = 0;
        while ((offset = value.indexOf(fragment, offset)) >= 0) {
            occurrences++;
            offset += fragment.length();
        }
        return occurrences;
    }
}
