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

class MerchantAftercareMapperContractTest {
    private static final String XML = "mapper/business/MerchantAftercareMapper.xml";
    private static final String NS = "com.chengyinhub.business.mapper.MerchantAftercareMapper.";

    @Test
    void refundFactsStayOwnerScopedWhileResponsesAndIdempotencyUseMerchantRow() throws Exception {
        Configuration configuration = parse();
        Map<String, Object> params = new HashMap<>();
        params.put("ownerMemberId", 900L);
        params.put("merchantId", 77L);
        params.put("refundId", 41L);

        String detail = sql(configuration, "selectOwnedRefundById", params);
        String locked = sql(configuration, "selectOwnedRefundByIdForUpdate", params);
        String policy = sql(configuration, "selectOwnedRefundPolicySnapshot", params);

        for (String statement : new String[] { detail, locked }) {
            assertTrue(statement.contains("r.merchant_id = ?"),
                    "refund_application 的历史商家事实仍是 owner memberId:" + statement);
            assertTrue(statement.contains("scoped.merchant_id = ?"),
                    "一旦存在 response，退款必须绑定具体 merchant row:" + statement);
            assertTrue(statement.contains("sibling.member_id = ?")
                            && statement.contains("sibling.id <> ?")
                            && statement.contains("sibling.status = 1 and sibling.account_status = 1 and sibling.del_flag = 0"),
                    "未绑定退款只有在 owner 无其他活跃门店时才可见:" + statement);
            assertTrue(statement.contains("del_flag = 0"), statement);
            assertFalse(statement.contains("r.member_id = ?"),
                    "退款所属店铺不能误用退款申请人 member_id:" + statement);
        }
        assertTrue(locked.endsWith("for update"), "追加意见必须与平台终态变更串行:" + locked);
        assertTrue(policy.contains("r.merchant_id = ?"), "政策快照查询也必须绑定服务端 owner:" + policy);
        assertTrue(policy.contains("r.source_type = 'registration'"), policy);
        assertTrue(policy.contains("reg.refund_policy_code"), policy);
        assertTrue(policy.contains("reg.refund_policy_version"), policy);
        assertTrue(policy.contains("reg.refund_deadline"), policy);

        params.put("requestId", "req-1");
        String idempotencyRead = sql(configuration, "selectByRequestIdForUpdate", params);
        assertTrue(idempotencyRead.contains("merchant_id = ?"),
                "同 owner 两个门店不得复用 aftercare requestId:" + idempotencyRead);
        assertFalse(idempotencyRead.contains("merchant_owner_member_id = ?"), idempotencyRead);
        assertTrue(idempotencyRead.contains("request_id = ?"), idempotencyRead);
        assertTrue(idempotencyRead.endsWith("for update"), "幂等回读必须是 RR 可见的当前读:" + idempotencyRead);

        String merchantLock = sql(configuration, "lockActiveMerchantOwner", params);
        assertTrue(merchantLock.contains("from mms_merchant"), merchantLock);
        assertTrue(merchantLock.contains("id = ? and member_id = ?"), merchantLock);
        assertTrue(merchantLock.contains("status = 1 and account_status = 1 and del_flag = 0"), merchantLock);
        assertTrue(merchantLock.endsWith("for update"), "同店 requestId 必须先按服务端主体串行:" + merchantLock);
    }

    @Test
    void listKeepsOwnerRefundFactsButScopesOpinionsAndUnboundFallbackToMerchantRow() throws Exception {
        Configuration configuration = parse();
        Map<String, Object> params = new HashMap<>();
        params.put("ownerMemberId", 900L);
        params.put("merchantId", 77L);
        params.put("bucket", "PENDING");
        params.put("offset", 0);
        params.put("limit", 20);

        String rows = sql(configuration, "selectOwnedRefundList", params);
        String count = sql(configuration, "countOwnedRefundList", params);

        for (String statement : new String[] { rows, count }) {
            assertTrue(statement.contains("r.merchant_id = ?"), statement);
            assertTrue(statement.contains("r.del_flag = 0"), statement);
            assertTrue(statement.contains("where merchant_id = ?")
                            && statement.contains("opinion.merchant_id = ?")
                            && statement.contains("scoped.merchant_id = ?"),
                    "商家意见、已绑定退款都必须按具体 merchant row 查询:" + statement);
            assertFalse(statement.contains("merchant_owner_member_id = ?"),
                    "owner 只能作为 refund 事实来源，不能作为 response 资源边界:" + statement);
            assertTrue(statement.contains("sibling.member_id = ?")
                            && statement.contains("sibling.id <> ?")
                            && statement.contains("sibling.status = 1 and sibling.account_status = 1 and sibling.del_flag = 0"),
                    "同 owner 多活跃门店且退款未绑定时必须 fail closed:" + statement);
            assertTrue(statement.contains("r.status = 0"), "PENDING 必须是平台待审:" + statement);
            assertTrue(statement.contains("latest_opinion_id is null"),
                    "商家已给意见后不应继续伪装成待回应:" + statement);
        }
        assertTrue(rows.contains("order by r.create_time desc, r.id desc"), rows);
        assertTrue(rows.endsWith("limit ?, ?"), "列表必须服务端分页:" + rows);
        assertFalse(rows.contains("#{merchantid}"), "不得接受前端 merchantId:" + rows);
    }

    @Test
    void mapperAndMigrationCanOnlyAppendOpinionsNotMutateRefundMoneyState() throws Exception {
        parse();
        String mapperXml = normalized(new String(Files.readAllBytes(Paths.get(
                "../chengyinhub-system/src/main/resources/mapper/business/MerchantAftercareMapper.xml")),
                StandardCharsets.UTF_8));
        String migration = normalized(new String(Files.readAllBytes(Paths.get(
                "sql/migration_merchant_operator_aftercare_20260823.sql")), StandardCharsets.UTF_8));

        assertFalse(mapperXml.contains("<update"), "售后 mapper 不得更新 refund_application");
        assertFalse(mapperXml.contains("<delete"), "售后 mapper 不得删除资金/意见记录");
        assertTrue(mapperXml.contains("insert into merchant_aftercare_response"));
        assertFalse(migration.contains("create table if not exists"));
        assertTrue(migration.contains("@merchant_aftercare_response_table_exists"));
        assertTrue(migration.contains("prepare merchant_aftercare_response_create_stmt"),
                "CREATE 已提交但回读前中断后，重跑必须按目标形状 no-op，不能 ERROR 1050");
        assertTrue(migration.contains("engine = 'innodb'")
                        && migration.contains("collation_name = 'ascii_bin'"),
                "allowed-shape 预断言必须钉住存储引擎与 request_id collation");
        assertTrue(migration.contains("unique key uk_merchant_aftercare_request (merchant_id, request_id)"));
        assertTrue(migration.contains("cols = 'merchant_id,request_id'"),
                "迁移必须从目标库精确回读 merchant-row 幂等唯一键列序");
        assertTrue(migration.contains("key idx_merchant_aftercare_refund_lookup "
                        + "(refund_application_id, merchant_id, id)"),
                "退款详情回读必须有 refund_application_id 前导覆盖索引");
        assertTrue(migration.contains("index_name = 'idx_merchant_aftercare_refund_lookup'")
                        && migration.contains(
                        "'1:btree:yes:0:refund_application_id,merchant_id,id'"),
                "新前导索引必须从 information_schema 精确回读列序");
        assertTrue(migration.contains("check (decision in (''agree'',''reject'',''evidence''))"));
        assertTrue(migration.contains(
                "actor_role_code in (''merchant_owner'',''merchant_manager'',''merchant_finance'')"));
        assertTrue(migration.contains("as assertion_ok"));
        assertFalse(migration.contains("alter table refund_application"));
        assertFalse(migration.contains("update refund_application"));
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
