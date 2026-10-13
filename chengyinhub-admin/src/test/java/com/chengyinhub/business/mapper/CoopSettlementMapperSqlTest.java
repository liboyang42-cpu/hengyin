package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CoopSettlement;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 分项结算类型是幂等与保证金抵扣对账的依据,写入后必须能从 Mapper 读回。 */
class CoopSettlementMapperSqlTest {

    @Test
    void selectMappings_includeLineAndPayeeTypes() throws Exception {
        String resource = "mapper/business/CoopSettlementMapper.xml";
        String namespace = "com.chengyinhub.business.mapper.CoopSettlementMapper.";
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("CoopSettlement", CoopSettlement.class);
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
        }

        String sql = cfg.getMappedStatement(namespace + "selectByTopicId")
                .getBoundSql(1L).getSql().replaceAll("\\s+", " ").toLowerCase();
        Set<String> mapped = new HashSet<String>();
        cfg.getResultMap(namespace + "CoopSettlementResult").getResultMappings()
                .forEach(m -> mapped.add(m.getProperty()));

        assertTrue(sql.contains("line_type") && sql.contains("payee_type"), "实际: " + sql);
        assertTrue(mapped.contains("lineType") && mapped.contains("payeeType"), "实际: " + mapped);
    }

    @Test
    void payoutClaimAndCompletion_areBothStateGuarded() throws Exception {
        String resource = "mapper/business/CoopSettlementMapper.xml";
        String namespace = "com.chengyinhub.business.mapper.CoopSettlementMapper.";
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("CoopSettlement", CoopSettlement.class);
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
        }
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("id", 1L);
        params.put("now", new Date());
        params.put("payoutTime", new Date());

        String claimSql = normalizedSql(cfg, namespace + "selectDuePayoutForUpdate", params);
        String dueSql = normalizedSql(cfg, namespace + "selectDuePayouts", params);
        String completionSql = normalizedSql(cfg, namespace + "markPaidIfPending", params);
        CoopSettlement update = new CoopSettlement();
        update.setId(1L);
        update.setStatus(2);
        String genericUpdateSql = normalizedSql(cfg, namespace + "updateCoopSettlement", update);
        String voidSql = normalizedSql(cfg, namespace + "voidUnpaidByTopic",
                java.util.Collections.<String, Object>singletonMap("topicId", 9L));

        assertTrue(claimSql.contains("status = 0") && claimSql.contains("del_flag = 0")
                        && claimSql.contains("payable_time <= ?") && claimSql.endsWith("for update"),
                "领取必须锁定并重验待打款态，实际: " + claimSql);
        assertTrue(completionSql.contains("set status = 1") && completionSql.contains("status = 0")
                        && completionSql.contains("del_flag = 0"),
                "终态写入必须使用 status CAS，实际: " + completionSql);
        assertHistoricalType2Frozen(dueSql, "到期扫描");
        assertHistoricalType2Frozen(claimSql, "事务领取");
        assertHistoricalType2Frozen(completionSql, "打款终态写入");
        assertHistoricalType2Frozen(genericUpdateSql, "通用结算更新");
        assertHistoricalType2Frozen(voidSql, "作废待打款结算");
    }

    private void assertHistoricalType2Frozen(String sql, String operation) {
        assertTrue(sql.contains("not exists") && sql.contains("from coop_order")
                        && sql.contains("join coop_invite") && sql.contains("invite_type = 2"),
                operation + "不得操作历史 type2 结算，实际: " + sql);
    }

    private String normalizedSql(Configuration cfg, String statement, Object params) {
        return cfg.getMappedStatement(statement).getBoundSql(params).getSql()
                .replaceAll("\\s+", " ").trim().toLowerCase();
    }
}
