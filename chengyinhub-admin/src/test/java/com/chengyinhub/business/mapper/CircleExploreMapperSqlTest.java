package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CircleExploreAnswer;
import com.chengyinhub.business.domain.CircleExploreRecord;
import com.chengyinhub.business.domain.CircleExploreSession;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 玩家记录 SQL 不得写入核销/订单事实，完成进度按不同商家计数。 */
class CircleExploreMapperSqlTest {
    private static final String RESOURCE = "mapper/business/CircleExploreMapper.xml";
    private static final String NS = "com.chengyinhub.business.mapper.CircleExploreMapper.";

    @Test
    void recordWritesOnlySelfReportedFields() throws Exception {
        CircleExploreRecord record = new CircleExploreRecord();
        String sql = sql("insertRecord", record);
        assertTrue(sql.contains("circle_explore_record"));
        assertTrue(sql.contains("session_id, member_id, offer_id, supply_profile_id, note"));
        assertFalse(sql.contains("redemption"));
        assertFalse(sql.contains("registration"));
        assertFalse(sql.contains("order_id"));
    }

    @Test
    void completionCountsDistinctMerchantsNotOffers() throws Exception {
        String sql = sql("countDistinctRecordedMerchants", Collections.singletonMap("sessionId", 1L));
        assertTrue(sql.contains("count(distinct o.merchant_id)"));
    }

    @Test
    void answerUpsertCanReplaceTheSessionWideSharedStageOwnerAndValue() throws Exception {
        String sql = sql("upsertAnswer", new CircleExploreAnswer());
        assertTrue(sql.contains("on duplicate key update member_id = values(member_id), answer_value = values(answer_value)"));
        assertFalse(sql.contains("answer_stage ="));
    }

    private static String sql(String statement, Object parameter) throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("CircleExploreSession", CircleExploreSession.class);
        configuration.getTypeAliasRegistry().registerAlias("CircleExploreRecord", CircleExploreRecord.class);
        configuration.getTypeAliasRegistry().registerAlias("CircleExploreAnswer", CircleExploreAnswer.class);
        try (InputStream input = Resources.getResourceAsStream(RESOURCE)) {
            new XMLMapperBuilder(input, configuration, RESOURCE,
                    configuration.getSqlFragments()).parse();
        }
        return configuration.getMappedStatement(NS + statement).getBoundSql(parameter).getSql()
                .replaceAll("\\s+", " ").toLowerCase();
    }
}
