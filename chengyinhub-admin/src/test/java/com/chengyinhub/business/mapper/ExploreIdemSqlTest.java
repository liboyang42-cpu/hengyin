package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.Redemption;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等查的索引可用性守卫。解析真 XML,**不连库**。
 *
 * <p>为什么必须在 SQL 层守:它的 mapper 在服务测里全是 mock,SQL 少条件那边照样全绿 ——
 * 而「少条件」的后果是**幂等查全表扫**。
 *
 * <p>⚠️ 这类断言的天花板要说清楚:它证明的是**这句 SQL 长这样**,不是**MySQL 认这句 SQL**、
 * 更不是**它跑起来是对的**。语法由生产 EXPLAIN 验,行为由 {@code ExploreRedeemIdempotentTest} 验。
 * 三层缺一层都能骗过自己。
 *
 * <p>⚠️ 负控已实测:{@code selectActiveByIdempotencyKey} 改查 {@code idempotency_key}
 * ⇒ idem_queriesGeneratedColumnForIndex + idem_doesNotFilterStatusInWhere 两条红。
 *
 * <p>注:本类原名 {@code ExploreRecallAndIdemSqlTest},另一半守的是 ③ 召回口径的
 * {@code selectRecallCandidates}。随召回功能于 2026-07-17 整体拆除(模板从未申请、
 * 用户授权从未做 ⇒ 该 job 自注册起就是暂停态、从未通电),那半边连同被守的 SQL 一并下线,
 * 本类改名只留幂等这半。
 */
class ExploreIdemSqlTest {

    private static final String RED = "mapper/business/RedemptionMapper.xml";

    private static final String NS_RED = "com.chengyinhub.business.mapper.RedemptionMapper.";

    private Configuration parse(String resource, Class<?> alias) throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias(alias.getSimpleName(), alias);
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
        }
        return cfg;
    }

    private String sql(Configuration cfg, String statementId, Object param) {
        return cfg.getMappedStatement(statementId)
                .getBoundSql(param)
                .getSql()
                .replaceAll("\\s+", " ")
                .toLowerCase();
    }

    private String idemSql() throws Exception {
        return sql(parse(RED, Redemption.class), NS_RED + "selectActiveByIdempotencyKey", "k");
    }

    /**
     * ★ 幂等查必须走生成列 active_idem_key —— 它上面有唯一索引 uk_redemption_idem_active。
     *
     * <p>查裸 idempotency_key 没有任何索引 ⇒ 全表扫。redemption 是**只增不删的流水表**,
     * 每次核销都要查一次 ⇒ 表越大越慢,且慢在核销这条「商家举着手机等」的路径上。
     */
    @Test
    void idem_queriesGeneratedColumnForIndex() throws Exception {
        String s = idemSql();
        assertTrue(s.contains("active_idem_key = "),
                "幂等查必须走带唯一索引的生成列 active_idem_key。实际: " + s);
    }

    /**
     * ★ where 里不该再写 status —— 生成列的 CASE 已经含了它。
     *
     * <p>写成 `idempotency_key = ? and status = 'ACTIVE'` 语义等价但**没索引可用**,
     * 而且会让人以为撤销后的失忆是靠这个 and 实现的(其实是靠生成列变 NULL)。
     */
    @Test
    void idem_doesNotFilterStatusInWhere() throws Exception {
        String s = idemSql();
        String where = s.substring(s.indexOf("where"));
        assertFalse(where.contains("status"),
                "status 已在生成列的 CASE 里,where 再写一遍 = 放弃索引。实际: " + where);
    }
}
