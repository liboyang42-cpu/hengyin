package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.Club;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ClubOwnerQuotaMapperSqlTest {
    private static final String XML = "mapper/business/ClubMapper.xml";
    private static final String NS = "com.chengyinhub.business.mapper.ClubMapper.";

    @Test
    void ownerQuotaAndFormerOwnerReconciliationAreOrderedLockingCurrentReads() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("Club", Club.class);
        try (InputStream input = Resources.getResourceAsStream(XML)) {
            new XMLMapperBuilder(input, configuration, XML, configuration.getSqlFragments()).parse();
        }

        String quota = sql(configuration, "selectOwnedClubIdsForUpdate");
        assertTrue(quota.contains("member_id = ? and del_flag = 0"), quota);
        assertTrue(quota.endsWith("order by id for update"), quota);

        String active = sql(configuration, "selectActiveOwnedClubIdsForUpdate");
        assertTrue(active.contains("member_id = ? and status = 1 and del_flag = 0"), active);
        assertTrue(active.endsWith("order by id for update"), active);
    }

    private String sql(Configuration configuration, String statement) {
        return configuration.getMappedStatement(NS + statement).getBoundSql(902L)
                .getSql().replaceAll("\\s+", " ").trim().toLowerCase();
    }
}
