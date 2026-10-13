package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CoopInvite;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** H1：历史 type2 只读，所有通用状态 DML 与写资格查询都必须显式排除。 */
class CoopInviteMapperSqlTest {

    private static final String RESOURCE = "mapper/business/CoopInviteMapper.xml";
    private static final String NS = "com.chengyinhub.business.mapper.CoopInviteMapper.";

    private Configuration parse() throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("CoopInvite", CoopInvite.class);
        try (InputStream in = Resources.getResourceAsStream(RESOURCE)) {
            new XMLMapperBuilder(in, cfg, RESOURCE, cfg.getSqlFragments()).parse();
        }
        return cfg;
    }

    private String sql(Configuration cfg, String statement, Object parameter) {
        return cfg.getMappedStatement(NS + statement).getBoundSql(parameter).getSql()
                .replaceAll("\\s+", " ").toLowerCase();
    }

    private void assertLegacyType2Excluded(String sql) {
        assertTrue(sql.contains("invite_type != 2"),
                "写路径必须显式排除历史 type2，实际 SQL: " + sql);
    }

    @Test
    void commonInviteUpdate_excludesLegacyType2() throws Exception {
        CoopInvite update = new CoopInvite();
        update.setId(1L);
        update.setStatus(1);
        assertLegacyType2Excluded(sql(parse(), "updateCoopInvite", update));
    }

    @Test
    void pendingExpiryUpdate_excludesLegacyType2() throws Exception {
        Configuration cfg = parse();
        assertLegacyType2Excluded(sql(cfg, "expirePendingInvites", new Date()));
    }

    @Test
    void depositUpdate_excludesLegacyType2() throws Exception {
        Map<String, Object> p = new HashMap<String, Object>();
        p.put("id", 1L);
        p.put("status", 2);
        assertLegacyType2Excluded(sql(parse(), "updateDeposit", p));
    }

}
