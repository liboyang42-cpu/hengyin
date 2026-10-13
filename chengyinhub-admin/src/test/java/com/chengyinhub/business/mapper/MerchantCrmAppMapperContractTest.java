package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantCrmAppMapperContractTest {
    @Test
    void legacyFactsKeepOwnerScopeWhileCrmResourcesUseExactMerchantRow() throws Exception {
        String xml = normalized(read("../chengyinhub-system/src/main/resources/mapper/business/MerchantCrmAppMapper.xml"));
        assertTrue(xml.contains("selectownedcustomersummary"));
        assertTrue(xml.contains("r.member_id = #{customermemberid}"));
        assertTrue(xml.contains("t.member_id = #{ownermemberid}"));
        assertTrue(xml.contains("rm.member_id = #{ownermemberid}"));
        assertTrue(xml.contains("a.member_id = #{ownermemberid}"));
        assertTrue(xml.contains("where merchant_id = #{merchantid} and merchant_scope_version = 2 "
                        + "and member_id = #{customermemberid}"),
                "note/assignment 读取必须排除隔离的 owner namespace 历史行");
        assertTrue(xml.contains("a.merchant_id = #{query.merchantid}"),
                "标签分群条件必须由服务端门店主键注入");
        assertTrue(xml.contains("join merchant_customer_tag t"), "标签定义必须复用既有真源");
        assertTrue(xml.contains("selecttagassignmentbyrequestid"));
        assertTrue(xml.contains("selectcustomercampaigntimeline"));
        assertTrue(xml.contains("campaign.merchant_id = recipient.merchant_id"),
                "单客触达时间线必须按 exact merchant-row 双边收口");
        assertTrue(xml.contains("recipient.member_id = #{customermemberid}"));
        assertFalse(xml.contains("failure_message as timeline_description"),
                "客户详情不得回显投递异常或堆栈文本");
        assertTrue(xml.contains("inserttagassignment"));
        assertFalse(xml.contains("delete from merchant_customer_tag_assignment"), "标签关系必须追加留痕");
        assertFalse(xml.contains("update merchant_customer_tag_assignment"), "标签关系必须追加留痕");
        assertFalse(xml.contains("delete from merchant_customer_note"), "跟进备注必须追加留痕");
        assertFalse(xml.contains("set content ="), "隐藏只能更新删除元数据，不能原地改备注原文");
        assertTrue(xml.contains("set hidden = 1"));
        assertTrue(xml.contains("and id = #{noteid} and hidden = 0 and version = #{expectedversion}"),
                "隐藏必须 owner/customer scoped 且使用 CAS");
        assertFalse(xml.contains("${"), "CRM mapper 禁止字符串插值");
    }

    @Test
    void customerListUsesRealSqlPaginationAndWhitelistedSourceAndTagFilters() throws Exception {
        String xml = normalized(read("../chengyinhub-system/src/main/resources/mapper/business/MerchantCrmAppMapper.xml"));
        assertTrue(xml.contains("selectownedcustomerpage"));
        assertTrue(xml.contains("limit #{limit} offset #{offset}"), "分页必须在数据库完成");
        assertTrue(xml.contains("r.owner_type = #{query.sourcetype}"), "来源筛选只能映射到报名真源枚举");
        assertTrue(xml.contains("a.tag_id = #{query.tagid}"));
        assertTrue(xml.contains("selectownedcustomercount"));
        assertTrue(xml.contains("selectownedsegmentcounts"));
        assertFalse(xml.contains("order by ${"), "排序禁止客户端字符串插值");
    }

    @Test
    void profileRebuildIsIncrementalAndCustomerTagLimitHasAPerCustomerLock() throws Exception {
        String xml = normalized(read("../chengyinhub-system/src/main/resources/mapper/business/MerchantCrmAppMapper.xml"));
        assertTrue(xml.contains("upsertownedcustomerprofiles"));
        assertTrue(xml.contains("on duplicate key update"));
        assertTrue(xml.contains("selectcustomerprofileforupdate"));
        assertTrue(xml.contains("for update"), "计数与追加之间必须锁住同一 owner/customer 画像行");
        assertTrue(xml.contains("merchant_scope_version = 2"), "仅 exact merchant-row 画像可进入 app CRM");
        assertFalse(xml.contains("delete from merchant_customer_profile"), "重建只能增量 upsert/软标陈旧");
        assertFalse(xml.contains("tags_json"), "tags_json 只是派生缓存，CRM mapper 不得直接写");
    }

    @Test
    void legacyAdminAggregateReusesOwnerScopedIncrementalRegistrationModel() throws Exception {
        String service = normalized(read("../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/MerchantCrmServiceImpl.java"));
        String legacyMapper = normalized(read("../chengyinhub-system/src/main/resources/mapper/business/MerchantCustomerProfileMapper.xml"));
        String mapperInterface = normalized(read("../chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/MerchantCustomerProfileMapper.java"));
        String controller = normalized(read("src/main/java/com/chengyinhub/web/controller/business/MerchantCrmController.java"));
        String adminApi = normalized(read("../chengyinhub-ui/src/api/business/crm.js"));
        String adminView = normalized(read("../chengyinhub-ui/src/views/business/crm/index.vue"));

        assertTrue(service.contains("upsertownedcustomerprofiles(merchantid, ownermemberid)"));
        assertTrue(service.contains("markstalecustomerprofiles(merchantid, ownermemberid)"));
        assertTrue(service.contains("countownedregistrationcustomers(ownermemberid)"));
        assertTrue(service.contains("uniquemerchantrowid(ownermemberid)"));
        assertFalse(service.contains("deletebymerchantid"), "legacy service 禁止硬删画像");
        assertFalse(legacyMapper.contains("delete from merchant_customer_profile"));
        assertTrue(legacyMapper.contains("<where> merchant_scope_version = 2 and del_flag = 0"),
                "画像列表必须排除 owner namespace 的 version=0 历史行");
        assertTrue(legacyMapper.contains("where merchant_id = #{merchantid} "
                        + "and merchant_scope_version = 2 and del_flag = 0"),
                "画像汇总必须只统计 exact merchant-row namespace");
        assertFalse(legacyMapper.contains("ums_member_cart"), "购物车不得再成为客户真源");
        assertFalse(mapperInterface.contains("deletebymerchantid"));
        assertFalse(mapperInterface.contains("insertaggregatedbymerchantid"));
        assertTrue(controller.contains("aggregate(@requestparam(\"ownermemberid\") long ownermemberid)"));
        assertTrue(adminApi.contains("aggregatecrm(ownermemberid)"));
        assertTrue(adminApi.contains("params: { ownermemberid }"));
        assertTrue(adminView.contains("mms_merchant.member_id"), "后台必须提示新 ownerMemberId 口径");
    }

    @Test
    void migrationConvertsOnlyUnambiguousLegacyRowsAndUsesMerchantRowIdempotency() throws Exception {
        String sql = normalized(read("sql/migration_merchant_operator_crm_followup_20260823.sql"));
        assertFalse(sql.contains("create table if not exists `merchant_crm_tag`"), "禁止创建第二套标签真源");
        assertTrue(sql.contains("create table if not exists `merchant_customer_tag_assignment`"));
        assertTrue(sql.contains("unique key `uk_merchant_customer_tag_request` "
                + "(`merchant_id`, `merchant_scope_version`, `request_id`)"));
        assertTrue(sql.contains("unique key `uk_merchant_customer_note_request` "
                + "(`merchant_id`, `merchant_scope_version`, `request_id`)"));
        assertTrue(sql.contains("`actor_member_id` bigint not null"));
        assertTrue(sql.contains("`merchant_id` bigint not null comment '门店主键 mms_merchant.id'"));
        assertTrue(sql.contains("add column `merchant_scope_version`"));
        assertTrue(sql.contains("having count(*) = 1"), "同 owner 多门店存量必须保留隔离，禁止猜门店");
        String scopeSql = sql.replace("`", "");
        assertFalse(scopeSql.contains("from mms_merchant where del_flag = 0"),
                "停用或冻结门店不得计入 owner 唯一门店判定");
        assertEquals(count(scopeSql,
                        "from mms_merchant where status = 1 and account_status = 1 and del_flag = 0"),
                count(scopeSql, ") exact_merchant"),
                "所有 exact_merchant 派生表必须统一只认当前有效门店");
        assertTrue(sql.contains("where profile.merchant_scope_version = 0 "
                        + "and profile.merchant_id = exact_merchant.owner_member_id"),
                "画像回填必须用外层 WHERE 限定未归属旧行，不能仅依赖 JOIN 扩大更新范围");
        assertTrue(sql.contains("where tag.merchant_scope_version = 0 "
                        + "and tag.merchant_id = exact_merchant.owner_member_id"),
                "标签回填必须用外层 WHERE 限定未归属旧行，不能仅依赖 JOIN 扩大更新范围");
        assertTrue(sql.contains("merchant_scope_version = 2"));
        assertTrue(sql.contains("check (`action` in ('assign', 'remove'))"));
        assertTrue(sql.contains("`corrects_note_id` bigint default null"));
        assertTrue(sql.contains("`hidden_request_id` varchar(64) default null"));
        assertTrue(sql.contains("-- runner: assert=scalar-true"), "migration 必须有对端读回断言");
        assertTrue(sql.contains("information_schema.statistics"));
        assertTrue(sql.contains("cols = 'merchant_id,merchant_scope_version,request_id'"),
                "migration 必须精确读回 namespace-aware 幂等唯一键列序");
        String legacy = normalized(read("sql/migration_p3_phase_c_crm_finance.sql"));
        assertTrue(legacy.contains("unique key `uk_merchant_tag_code` (`merchant_id`, `tag_code`)"),
                "稳定 tag_code 必须继续由既有唯一键收口并发");
    }

    /**
     * F15 日限的原子性就在这一个词上:countActorActionToday 必须是 for update 的 current read。
     * 去掉它,并发两请求双双读到 49、双双放行,把 50 次上限捅穿(真库并发用例
     * MerchantCrmContactRevealMySqlIT#concurrentRevealsCannotBreakTheDailyCap 已实测抓到;
     * 这里再用结构断言把它拉进快速单测,不让它只能靠连库才发现)。
     */
    @Test
    void contactRevealDailyCapCountMustBeALockingRead() throws Exception {
        String xml = normalized(read(
                "../chengyinhub-system/src/main/resources/mapper/business/BusinessAuditEventMapper.xml"));
        int from = xml.indexOf("countactoractiontoday");
        assertTrue(from >= 0, "countActorActionToday 不见了:日限计数的落点变了,先核新落点");
        int end = xml.indexOf("</select>", from);
        assertTrue(end > from, "countActorActionToday 的 select 结构变了");
        String countQuery = xml.substring(from, end);
        assertTrue(countQuery.contains("for update"),
                "F15 日限计数必须是 for update:普通快照读会让并发两请求双双放行捅穿上限");
    }

    private String read(String relative) throws Exception {
        Path base = Paths.get(System.getProperty("user.dir"));
        return new String(Files.readAllBytes(base.resolve(relative).normalize()), StandardCharsets.UTF_8);
    }

    private String normalized(String value) {
        return value.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    private int count(String value, String needle) {
        int total = 0;
        for (int index = value.indexOf(needle); index >= 0;
             index = value.indexOf(needle, index + needle.length())) {
            total++;
        }
        return total;
    }
}
