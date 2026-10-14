package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.BizEventLog;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parses the actual mapper and parameter bindings; execution/concurrency remains a MySQL gate. */
class BizEventLogIdempotencyMapperContractTest {
    @Test
    void financialObservationUsesOriginalOwnedRegistrationAndHistoricalPaymentTime() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("BizEventLog", BizEventLog.class);
        String resource = "mapper/business/BizEventLogMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("eventName", "payment_success"); params.put("registrationId", 9L); params.put("memberId", 1001L);
        BoundSql bound = configuration.getMappedStatement(
                "com.chengyinhub.business.mapper.BizEventLogMapper.selectRegistrationEventFact").getBoundSql(params);
        String sql = bound.getSql().replaceAll("\\s+", " ").trim().toLowerCase();
        assertTrue(sql.contains("where id = ? and member_id = ?"), sql);
        assertTrue(sql.contains("owner_type in (1, 2)"), sql);
        assertTrue(sql.contains("payment_time is not null"), sql);
        org.junit.jupiter.api.Assertions.assertFalse(sql.contains("payment_status = 2"),
                "后续退款不得抹掉原支付事实: " + sql);
        assertTrue(sql.contains("then payment_time else create_time"), sql);
        assertTrue(sql.contains("from cms_registration"), sql);
    }

    @Test
    void ignoredInsertIsConfirmedThroughCompleteUniqueIdentityAndCurrentRead() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("BizEventLog", BizEventLog.class);
        String resource = "mapper/business/BizEventLogMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        BizEventLog event = new BizEventLog();
        event.setEventName("content_view");
        event.setMemberId(1001L);
        event.setActorScope("MEMBER:1001");
        event.setIdempotencyKey("original-event");
        BoundSql sql = configuration.getMappedStatement(
                "com.chengyinhub.business.mapper.BizEventLogMapper.selectIdempotentEventForUpdate")
                .getBoundSql(event);
        String normalized = sql.getSql().replaceAll("\\s+", " ").trim().toLowerCase();
        assertTrue(normalized.contains("where event_name = ? and actor_scope = ? and idempotency_key = ?"), normalized);
        assertTrue(normalized.endsWith("for update"), "重复 INSERT 后不能用旧一致性快照冒充回读: " + normalized);
        assertEquals(Arrays.asList("eventName", "actorScope", "idempotencyKey"),
                sql.getParameterMappings().stream().map(mapping -> mapping.getProperty()).collect(Collectors.toList()));
    }
}
