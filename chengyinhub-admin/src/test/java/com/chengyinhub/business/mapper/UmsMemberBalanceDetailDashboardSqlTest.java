package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商家看板收入聚合 SQL 守卫(解析真 XML 生成 BoundSql,不连库)。
 * 锁的口径:按 member_id + change_type=1 在库内 SUM / 按日 GROUP BY;口径与原 Java 求和一致,不额外按 del_flag 过滤。
 */
class UmsMemberBalanceDetailDashboardSqlTest {

    private static final String XML = "mapper/business/UmsMemberBalanceDetailMapper.xml";
    private static final String NS = "com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper.";

    private String sql(String statement) throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("UmsMemberBalanceDetail", UmsMemberBalanceDetail.class);
        try (InputStream in = Resources.getResourceAsStream(XML)) {
            new XMLMapperBuilder(in, cfg, XML, cfg.getSqlFragments()).parse();
        }
        Map<String, Object> param = new HashMap<>();
        param.put("memberId", 1L);
        param.put("since", new Date());
        return cfg.getMappedStatement(NS + statement).getBoundSql(param).getSql()
                .replaceAll("\\s+", " ").toLowerCase();
    }

    @Test
    void totalIsSqlSumScopedToMemberIncome() throws Exception {
        String s = sql("selectDashboardIncomeTotal");
        assertTrue(s.contains("coalesce(sum(change_balance), 0)"), s);
        assertTrue(s.contains("member_id = ?") && s.contains("change_type = 1"), s);
        assertFalse(s.contains("del_flag"), "口径同原看板:不按 del_flag 过滤");
    }

    @Test
    void dailyIsGroupedByNaturalDaySinceWindowStart() throws Exception {
        String s = sql("selectDashboardIncomeDaily");
        assertTrue(s.contains("member_id = ?") && s.contains("change_type = 1"), s);
        assertTrue(s.contains("create_time >= ?"), "必须带窗口起点,不能全量分组");
        assertTrue(s.contains("group by date_format(create_time, '%y-%m-%d')"), s);
    }
}
