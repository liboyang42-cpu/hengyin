package com.chengyinhub.business.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Binding contract only; this does not substitute for execution on MySQL. */
class MerchantReviewReplyReceiptMapperSqlTest {
    @Test void bindsEveryScopeAndOriginalCommandWithoutWrites() throws Exception {
        String resource = "mapper/business/MerchantReviewReplyReceiptMapper.xml";
        var cfg = new Configuration();
        try (var in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
        }
        var statement = cfg.getMappedStatement(MerchantReviewReplyReceiptMapper.class.getName() + ".countApplied");
        assertEquals(SqlCommandType.SELECT, statement.getSqlCommandType());
        var bound = statement.getBoundSql(Map.of("merchantId", 12L, "ownerMemberId", 8L, "actorMemberId", 7L,
                "reviewId", 71L, "action", "MERCHANT_REVIEW_REPLIED", "requestId", "reply-1234"));
        assertEquals(List.of("merchantId", "ownerMemberId", "actorMemberId", "reviewId", "action", "requestId"),
                bound.getParameterMappings().stream().map(p -> p.getProperty()).toList());
        String sql = bound.getSql().replaceAll("\\s+", " ");
        assertTrue(sql.contains("domain_type = 'MERCHANT' and domain_id = ?"));
        assertTrue(sql.contains("owner_member_id = ? and actor_member_id = ?"));
        assertTrue(sql.contains("target_type = 'MERCHANT_REVIEW' and target_id = ?"));
        assertTrue(sql.contains("action = ? and request_id = ?"));
    }
}
