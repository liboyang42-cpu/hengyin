package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClubMembershipMigrationContractTest {
    @Test
    void migrationKeepsMembershipFundsIndependentFrozenAndFailClosed() throws Exception {
        String sql = read(Paths.get("sql", "migration_club_role_membership_20260823.sql"));

        assertTrue(sql.contains("create table if not exists club_membership_plan")
                && sql.contains("create table if not exists club_membership_invoice")
                && sql.contains("create table if not exists club_membership_term"));
        assertFalse(sql.contains("cms_registration"), "会费不得复用活动报名或票务表");
        assertTrue(sql.contains("exit_policy_snapshot")
                && sql.contains("refund_policy_snapshot")
                && sql.contains("snapshot_hash char(64)"), "退出/退款/金额合同必须冻结到账单");
        assertTrue(sql.contains("out_trade_no regexp '^clm[0-9a-f]{29}$'")
                && sql.contains("unique key uk_club_membership_transaction")
                && sql.contains("unique key uk_club_membership_idempotency")
                && sql.contains("active_paying_member_id bigint generated always as")
                && sql.contains("unique key uk_club_membership_one_paying (club_id, active_paying_member_id)"));
        assertTrue(sql.contains("'pending','active','grace','past_due','expired','cancelled','refunded'"));
        assertTrue(sql.contains("status = 'pending_review'")
                && !sql.contains("approved_pending_payout"), "首版只能记录平台审核中，不能伪造打款终态");
        assertTrue(sql.contains("'feature.flag.clubmembership', 'false'"));
        assertFalse(sql.contains("sys_job"), "手动续费首版不得偷偷注册自动扣款任务");
    }

    @Test
    void declaredDependenciesRequireRoleAndImmutableAuditMigrations() throws Exception {
        String dependencies = read(Paths.get("..", "ci", "migration-dependencies.json"));
        assertTrue(dependencies.contains("\"club_role_membership_20260823\"")
                && dependencies.contains("\"business_audit_event_20260823\"")
                && dependencies.contains("\"club_role_assignment_20260823\""));
    }

    @Test
    void memberForeignKeysMatchUnsignedUmsMemberPrimaryKey() throws Exception {
        String sql = read(Paths.get("sql", "migration_club_role_membership_20260823.sql"));

        assertEquals(4, occurrences(sql, "member_id bigint unsigned not null"),
                "四个引用 ums_member.id 的外键列必须保持 BIGINT UNSIGNED，否则 MySQL 建表报 3780");
    }

    @Test
    void renewalCommitsLocalIntentBeforeWechatAndNeverLocksSettingAcrossNetwork() throws Exception {
        String mapper = read(Paths.get("..", "chengyinhub-system", "src", "main", "resources",
                "mapper", "business", "ClubMembershipMapper.xml"));
        String service = read(Paths.get("..", "chengyinhub-system", "src", "main", "java",
                "com", "chengyinhub", "business", "service", "impl", "ClubMembershipServiceImpl.java"));

        assertTrue(mapper.contains("id=\"lockmemberforrenewal\"")
                && mapper.contains("select id from ums_member")
                && mapper.contains("where id = #{memberid}")
                && mapper.contains("for update"));
        assertFalse(mapper.contains("selectsettingforupdate"),
                "续费不得把俱乐部 setting 单行锁跨越微信预下单网络调用");
        assertTrue(mapper.contains("id=\"selectplanbyidforupdate\"")
                        && mapper.contains("from club_membership_plan where id = #{id} and del_flag = 0 for update"),
                "冻结会费套餐价格前必须锁住 plan，防止并发改价或下架后仍生成旧条款账单");
        assertTrue(service.indexOf("transaction().execute(status -> preparerenewalintent")
                        < service.indexOf("wechatpayservice.creategenericorder"),
                "PAYING intent 必须先用独立短事务提交，再调用微信，不能制造不可见孤儿订单");
    }

    @Test
    void paidCallbackAndPendingCloseUseExplicitLocksAndForwardOnlyCas() throws Exception {
        String membershipMapper = read(Paths.get("..", "chengyinhub-system", "src", "main", "resources",
                "mapper", "business", "ClubMembershipMapper.xml"));
        String memberMapper = read(Paths.get("..", "chengyinhub-system", "src", "main", "resources",
                "mapper", "business", "ClubMemberMapper.xml"));
        String sql = read(Paths.get("sql", "migration_club_role_membership_20260823.sql"));

        assertTrue(memberMapper.contains("id=\"selectbyclubandmemberforupdate\"")
                && memberMapper.contains("cm.status = 1 and cm.del_flag = 0")
                && memberMapper.contains("for update"),
                "支付回调必须在 club 锁之后锁住当前有效成员关系，和退出/移除串行化");
        assertTrue(membershipMapper.contains("id=\"markinvoiceclosedcas\"")
                && membershipMapper.contains("set status = 'closed'")
                && membershipMapper.contains("status = 'paying'"),
                "只有微信终态读回后，PAYING 才能以前向 CAS 释放为 CLOSED");
        assertTrue(sql.contains("status = 'paying' then member_id")
                && sql.contains("unique key uk_club_membership_one_paying (club_id, active_paying_member_id)"),
                "CLOSED 必须释放 one-PAYING 唯一约束，允许新账单");
    }

    @Test
    void accessEndFenceBlocksResumeAndNeverReturnsStoredPayParams() throws Exception {
        String migration = read(Paths.get("sql",
                "migration_club_role_membership_resume_fence_20260826.sql"));
        String mapper = read(Paths.get("..", "chengyinhub-system", "src", "main", "resources",
                "mapper", "business", "ClubMembershipMapper.xml"));
        String service = read(Paths.get("..", "chengyinhub-system", "src", "main", "java",
                "com", "chengyinhub", "business", "service", "impl", "ClubMembershipServiceImpl.java"));

        assertTrue(migration.contains("add column resume_blocked_at datetime default null")
                && migration.contains("runner: assert=scalar-true")
                && migration.contains("column_name = 'status' and column_type = 'varchar(16)'")
                && migration.contains("is_nullable = 'no'")
                && migration.contains("chk_club_membership_invoice_status")
                && migration.contains("not (status <=> 'paying')"),
                "候选账单表 status 漂移或 NULL 行都必须在加恢复栅栏前 fail closed");
        assertTrue(mapper.contains("id=\"selectpayinginvoicesbymemberforupdate\"")
                && mapper.contains("id=\"selectpayinginvoicesbyclubforupdate\"")
                && mapper.contains("id=\"blockinvoiceresumecas\"")
                && mapper.contains("set resume_blocked_at = #{blockedat}, pay_params_json = null")
                && mapper.contains("id=\"attachpayparamscas\"")
                && mapper.contains("and resume_blocked_at is null"));
        assertTrue(service.contains("club_membership_prepay_blocked")
                && service.contains("invoice.getresumeblockedat() != null")
                && service.contains("paid_no_entitlement"));
    }

    private String read(java.nio.file.Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                .toLowerCase().replaceAll("\\s+", " ");
    }

    private int occurrences(String source, String target) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(target, offset)) >= 0) {
            count++;
            offset += target.length();
        }
        return count;
    }
}
