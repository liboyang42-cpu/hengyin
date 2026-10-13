package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantChapterApplication;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.mapping.ResultMapping;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 撤回、重申与点位写入的 SQL 状态机门禁。 */
class MerchantChapterApplicationMapperSqlTest {

    @Test
    void publisherInboxAlwaysFiltersByTopicOwner() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("publisherMemberId", 1001L);
        String owner = sql(configuration(), MerchantChapterApplicationMapper.class.getName()
                + ".selectByPublisherId", params);
        assertTrue(owner.contains("where t.member_id = ? and t.del_flag = 0"), owner);
    }

    @Test
    void withdrawAndNodeWritesShareApplicationRowLock() throws Exception {
        Configuration configuration = configuration();
        String namespace = MerchantChapterApplicationMapper.class.getName() + ".";

        String byId = sql(configuration, namespace + "selectByIdIncludingDeletedForUpdate", 91L);
        Map<String, Object> pair = new HashMap<>();
        pair.put("chapterId", 73L);
        pair.put("merchantId", 2001L);
        String byPair = sql(configuration, namespace + "selectByChapterAndMerchantForUpdate", pair);

        assertTrue(byId.endsWith("for update"), byId);
        assertTrue(byPair.endsWith("for update"), byPair);
    }

    @Test
    void withdrawAndRestoreUseOwnerAndStatePredicates() throws Exception {
        Configuration configuration = configuration();
        String namespace = MerchantChapterApplicationMapper.class.getName() + ".";
        Map<String, Object> params = new HashMap<>();
        params.put("id", 91L);
        params.put("merchantId", 2001L);
        params.put("message", "重新提交");
        params.put("createBy", "2001");

        String withdraw = sql(configuration, namespace + "withdrawPending", params);
        String restore = sql(configuration, namespace + "restoreWithdrawn", params);

        assertTrue(withdraw.contains("merchant_id = ?")
                && withdraw.contains("status = 0")
                && withdraw.contains("del_flag = 0"), withdraw);
        assertTrue(restore.contains("status = 0") && restore.contains("del_flag = 2"), restore);
    }

    /**
     * 我的申请必须回读唯一键占住的任意状态 offer；不能只投影“当前生效”，否则停用/过期行会被误报成 NONE。
     */
    @Test
    void merchantMineClassifiesInactiveAndExpiredOffers() throws Exception {
        Configuration configuration = configuration();
        String namespace = MerchantChapterApplicationMapper.class.getName() + ".";

        String mine = sql(configuration, namespace + "selectByMerchantId", 2001L);

        assertTrue(mine.contains("left join merchant_chapter_offer o"), mine);
        assertTrue(mine.contains("when o.id is null then 'none'"), mine);
        assertTrue(mine.contains("when o.status = 1 and (o.valid_end is null or o.valid_end > now()) then 'active'"), mine);
        assertTrue(mine.contains("when o.status = 0 then 'inactive'"), mine);
        assertTrue(mine.contains("else 'expired'"), mine);
        assertTrue(mine.contains("as offer_state"), mine);
    }

    @Test
    void merchantMineProjectsTopicCoverAndScheduleWithinExistingOwnerScope() throws Exception {
        Configuration configuration = configuration();
        String namespace = MerchantChapterApplicationMapper.class.getName() + ".";
        String mine = sql(configuration, namespace + "selectByMerchantId", 2001L);

        assertTrue(mine.contains("t.img_url as topic_img_url") && mine.contains("t.start_date")
                && mine.contains("t.end_date"), mine);
        assertTrue(mine.contains("left join cms_topic t on t.id = a.topic_id"), mine);
        assertTrue(mine.contains("where a.merchant_id = ?")
                && mine.contains("a.del_flag = 0 or (a.del_flag = 2 and o.status = 0")
                && mine.contains("nullif(trim(o.withdraw_reason), '') is not null"), mine);
        Map<String, ResultMapping> mappings = new HashMap<>();
        for (ResultMapping mapping : configuration.getResultMap(namespace
                + "MerchantChapterApplicationListResult").getResultMappings()) {
            mappings.put(mapping.getProperty(), mapping);
        }
        String[][] fields = {{"topicImgUrl", "topic_img_url"}, {"startDate", "start_date"},
                {"endDate", "end_date"}};
        for (String[] field : fields) {
            assertNotNull(mappings.get(field[0]), field[0]);
            assertEquals(field[1], mappings.get(field[0]).getColumn(), field[0]);
        }
        assertEquals(String.class, mappings.get("topicImgUrl").getJavaType());
        assertEquals(java.util.Date.class, mappings.get("startDate").getJavaType());
        assertEquals(java.util.Date.class, mappings.get("endDate").getJavaType());
        for (String field : new String[]{"startDate", "endDate"}) {
            assertEquals("yyyy-MM-dd", MerchantChapterApplication.class.getDeclaredField(field)
                    .getAnnotation(com.fasterxml.jackson.annotation.JsonFormat.class).pattern());
        }
    }

    private Configuration configuration() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias(
                "MerchantChapterApplication", MerchantChapterApplication.class);
        String resource = "mapper/business/MerchantChapterApplicationMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private String sql(Configuration configuration, String statement, Object parameter) {
        return configuration.getMappedStatement(statement).getBoundSql(parameter).getSql()
                .replaceAll("\\s+", " ").trim().toLowerCase();
    }
}
