package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.dto.TopicOperationalListSummaryDTO;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 列表摘要是只读批量投影；这里校验它不会丢掉工作台已经固化的合同、生效供给和核销口径。
 */
class TopicOperationalSummaryMapperSqlTest {

    private static final String XML = "mapper/business/TopicOperationalSummaryMapper.xml";
    private static final String STATEMENT =
            "com.chengyinhub.business.mapper.TopicOperationalSummaryMapper.selectByTopicIds";

    @Test
    void batchProjection_usesOperationalTruthSourcesInsteadOfPoolFlags() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream in = Resources.getResourceAsStream(XML)) {
            new XMLMapperBuilder(in, configuration, XML, configuration.getSqlFragments()).parse();
        }
        String sql = configuration.getMappedStatement(STATEMENT)
                .getBoundSql(Collections.singletonMap("topicIds", Arrays.asList(1L, 2L)))
                .getSql()
                .replaceAll("\\s+", " ")
                .toLowerCase();

        assertFalse(sql.contains(", from"), sql);
        assertTrue(sql.contains("product_role"), sql);
        assertTrue(sql.contains("coop_club_apply"), sql);
        assertTrue(sql.contains("coop_invite"), sql);
        assertTrue(sql.contains("coop_order"), sql);
        assertTrue(sql.contains("to_type = 'club'"), sql);
        assertTrue(sql.contains("to_type = 'merchant'"), sql);
        assertTrue(sql.contains("merchant_chapter_offer"), sql);
        assertTrue(sql.contains("offer.status = 1"), sql);
        assertTrue(sql.contains("offer.valid_end is null or offer.valid_end > now()"), sql);
        assertTrue(sql.contains("coalesce(nullif(chapter.max_merchant, 0), 1)"), sql);
        assertTrue(sql.contains("count(*)"), sql);
        assertTrue(sql.contains("self_play_pass_occupied_count"), sql);
        assertTrue(sql.contains("purchase_kind"), sql);
        assertTrue(sql.contains("club_lead_state"), sql);
        assertTrue(sql.contains("cls.status in (1, 2)"), sql);
        assertTrue(sql.contains("owner_type = 1"), sql);
        assertTrue(sql.contains("payment_status = 2"), sql);
        assertTrue(sql.contains("verification_status = 1"), sql);
        assertTrue(sql.contains("coop_settlement"), sql);
        assertTrue(sql.contains("coop_complaint"), sql);
        assertTrue(sql.contains("open_complaint_count"), sql);
    }
}
