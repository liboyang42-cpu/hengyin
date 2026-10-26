package com.chengyinhub.business.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantReviewMapperContractTest {
    private static final String XML = "mapper/business/MerchantReviewMapper.xml";
    private static final String NS = "com.chengyinhub.business.mapper.MerchantReviewMapper.";

    @Test
    void exactOrderEligibilityIsReadOnlyAndRetainsAllExistingGuards() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("authorMemberId", 101L); params.put("merchantRowId", 7L); params.put("registrationId", 303L);
        String exact = sql(parse(), "selectEligibilityForRegistration", params);
        assertTrue(exact.contains("r.id = ?") && exact.contains("r.member_id = ?"), exact);
        assertTrue(exact.contains("rd.status = 'active'") && exact.contains("mm.member_id = rd.merchant_id"), exact);
        assertTrue(exact.contains("sibling.id <> mm.id") && exact.contains("used.registration_id = r.id"), exact);
        assertFalse(exact.contains("for update") || exact.contains("lock in share mode"), exact);
    }

    @Test
    void eligibilityRequiresCurrentAuthorAndActiveSameMerchantRedemption() throws Exception {
        Configuration configuration = parse();
        Map<String, Object> params = new HashMap<>();
        params.put("authorMemberId", 101L);
        params.put("merchantRowId", 7L);
        params.put("merchantOwnerMemberId", 202L);
        params.put("registrationId", 303L);

        String sql = sql(configuration, "selectEligibility", params);

        assertTrue(sql.contains("from redemption rd"), sql);
        assertTrue(sql.contains("straight_join cms_registration r"),
                "锁序必须由 redemption 驱动，再读报名与商家:" + sql);
        assertTrue(sql.contains("r.member_id = ?"), "资格必须绑定当前登录会员:" + sql);
        assertTrue(sql.contains("mm.id = ?"), "资格必须绑定客户端所见的目标门店行:" + sql);
        assertTrue(sql.contains("mm.member_id = rd.merchant_id"), "旧核销 owner 口径只能由目标门店反查:" + sql);
        assertTrue(sql.contains("not exists") && sql.contains("sibling.id <> mm.id"),
                "同 owner 存在多个活跃门店时旧核销无法唯一归店，必须 fail-closed:" + sql);
        assertTrue(sql.contains("rd.status = 'active'"), "已撤销核销不得评价:" + sql);
        assertFalse(sql.contains("'reversed'"), "REVERSED 不能进入正向资格分支:" + sql);
        assertTrue(sql.endsWith("limit 1 for update"),
                "创建资格必须锁住 ACTIVE redemption，消除读取后并发撤销窗口:" + sql);

        String publicEligibility = sql(configuration, "selectLatestEligibility", params);
        assertFalse(publicEligibility.contains("for update"), "公开资格提示不得持有核销锁:" + publicEligibility);
    }

    @Test
    void publicStoreAAndManageStoreAReadsCannotIncludeSameOwnerStoreB() throws Exception {
        Configuration configuration = parse();
        Map<String, Object> params = new HashMap<>();
        params.put("merchantRowId", 7L);
        params.put("merchantOwnerMemberId", 202L);
        params.put("offset", 0);
        params.put("limit", 20);

        String publicRows = sql(configuration, "selectPublicReviews", params);
        String publicCount = sql(configuration, "countPublicReviews", params);
        String manageRows = sql(configuration, "selectManageReviews", params);

        assertTrue(publicRows.contains("mr.status = 'visible'"), publicRows);
        assertTrue(publicCount.contains("mr.status = 'visible'"), publicCount);
        assertTrue(publicRows.contains("mr.merchant_row_id = ?"),
                "公开 A 店评价必须按 merchant_row_id 隔离，不能混入同 owner 的 B 店:" + publicRows);
        assertTrue(publicRows.endsWith("limit ?, ?"), "公开列表必须服务端分页:" + publicRows);
        assertTrue(manageRows.contains("mr.merchant_row_id = ?"),
                "商家管理列表必须 SQL 层绑定 access.merchantId:" + manageRows);
        assertTrue(manageRows.endsWith("limit ?, ?"), "管理列表必须服务端分页:" + manageRows);
        assertFalse(publicRows.contains("u.phone"), "公开评价不得投影手机号:" + publicRows);
        assertFalse(publicRows.contains("u.name"), "公开评价不得投影实名:" + publicRows);
    }

    @Test
    void replyModerationAndReportsUseCasAndDatabaseUniqueness() throws Exception {
        Configuration configuration = parse();
        Map<String, Object> params = new HashMap<>();
        params.put("id", 1L);
        params.put("merchantRowId", 7L);
        params.put("merchantOwnerMemberId", 202L);
        params.put("expectedVersion", 0);

        String reply = sql(configuration, "replyCas", params);
        String moderation = sql(configuration, "moderateCas", params);
        assertTrue(reply.contains("merchant_row_id = ?"),
                "回复 CAS 必须同时绑定门店行，不能只绑定共用 owner:" + reply);
        assertTrue(reply.contains("merchant_owner_member_id = ?"), reply);
        assertTrue(reply.contains("version = ?"), reply);
        assertTrue(reply.contains("reply_content is null"), "只允许一次公开回复:" + reply);
        assertTrue(moderation.contains("where id = ? and status = ? and version = ?"),
                "平台下架/恢复 CAS 的 WHERE 必须同时绑定原状态与 version:" + moderation);
        assertTrue(moderation.contains("version = ?"), "平台下架/恢复也必须 CAS:" + moderation);

        String migration = normalized(new String(Files.readAllBytes(Paths.get(
                "sql/migration_merchant_review_20260823.sql")), StandardCharsets.UTF_8));
        assertTrue(migration.contains("unique key uk_merchant_review_registration_merchant (registration_id, merchant_owner_member_id)"),
                "每 registration+商家最多一条必须由 DB 兜并发");
        assertTrue(migration.contains("unique key uk_merchant_review_author_request (author_member_id, create_request_id)"),
                "创建 requestId 必须幂等");
        assertTrue(migration.contains("unique key uk_merchant_review_reply_request (merchant_row_id, reply_request_id)"),
                "同 owner 多门店的回复幂等键必须按 merchant_row_id 隔离");
        assertTrue(migration.contains("key idx_merchant_review_public (merchant_row_id, status, create_time, id)"),
                "公开门店评价查询必须有 merchant_row_id 前导索引");
        assertTrue(migration.contains("unique key uk_merchant_review_report_request (reporter_member_id, request_id)"),
                "举报 requestId 必须幂等");
        assertTrue(migration.contains("check (rating between 1 and 5)"), "评分边界必须落到 DB");
        assertTrue(migration.contains("default 'pending_review'"), "新评价必须先进入平台复核，不得直接公开");
        assertTrue(migration.contains("check (status in ('pending_review', 'visible', 'hidden'))"),
                "待审/公开/下架状态必须枚举收口");
    }

    private Configuration parse() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream in = Resources.getResourceAsStream(XML)) {
            new XMLMapperBuilder(in, configuration, XML, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private String sql(Configuration configuration, String id, Object parameter) {
        return normalized(configuration.getMappedStatement(NS + id).getBoundSql(parameter).getSql());
    }

    private String normalized(String value) {
        return value.replaceAll("\\s+", " ").trim().toLowerCase();
    }
}
