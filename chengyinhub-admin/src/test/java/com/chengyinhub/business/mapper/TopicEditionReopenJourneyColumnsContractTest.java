package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.domain.TopicEditionReopenRequest;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 期次重开链路的主题级旅程双列合同:{@code journey_rules_json}(R5-COPY)与
 * {@code journey_story_json}(R8-STORY)必须同时经过 {@code selectSourceTopicForUpdate}
 * 与 {@code insertReopenedTopic} —— 两次改动各补一边,集成时最容易掉的就是"只留一列"。
 *
 * <p>H2 内存库按 mapper XML 真跑两条语句:少一列时 insert 报错或读出 null,当场变红;
 * 只看 Java / Mockito 测不出来,因为两边都不经过 SQL。
 */
class TopicEditionReopenJourneyColumnsContractTest {

    private static final String RULES = "{\"schemaVersion\":1,\"checkEnabled\":true}";
    private static final String STORY = "{\"schemaVersion\":1,\"stages\":[]}";

    /** 只保留本链路读写的列;类型按生产快照口径放宽。 */
    private static final String DDL = "create table cms_topic ("
            + " id bigint auto_increment primary key,"
            + " member_id bigint, club_id bigint, topic_type bigint, product_type int,"
            + " journey_rules_json longtext, journey_story_json longtext,"
            + " name varchar(255), subtitle varchar(255), description text, address_name varchar(255),"
            + " total_time bigint, total_mileage decimal(10,2), location_count bigint, template_count bigint,"
            + " img_url varchar(500), img_arr text, category_ids varchar(255), publish_mode varchar(32),"
            + " audio_url varchar(500), audio_duration int, self_play tinyint, xp_budget int,"
            + " finish_medal_name varchar(255), finish_medal_img varchar(500),"
            + " status tinyint, user_status tinyint, merchant_status tinyint, open_club_pool tinyint,"
            + " del_flag tinyint, is_sync tinyint, is_recommend tinyint, check_status tinyint,"
            + " is_settlement tinyint, lifecycle int, source_topic_id bigint,"
            + " create_by varchar(64), create_time datetime, update_by varchar(64), update_time datetime)";

    @Test
    void insertAndSelectCarryBothJourneyJsonColumns() throws Exception {
        SqlSessionFactory factory = factory();
        try (SqlSession session = factory.openSession(true);
             Statement st = session.getConnection().createStatement()) {
            st.execute(DDL);
            TopicEditionReopenMapper mapper = session.getMapper(TopicEditionReopenMapper.class);

            CmsTopic reopened = new CmsTopic();
            reopened.setMemberId(900L);
            reopened.setName("新一期");
            reopened.setJourneyRulesJson(RULES);
            reopened.setJourneyStoryJson(STORY);

            assertEquals(1, mapper.insertReopenedTopic(reopened));
            assertNotNull(reopened.getId(), "insertReopenedTopic 必须回填新期 id");

            CmsTopic read = mapper.selectSourceTopicForUpdate(reopened.getId());

            assertNotNull(read, "selectSourceTopicForUpdate 必须读得出刚写入的新期");
            assertEquals(RULES, read.getJourneyRulesJson(),
                    "journey_rules_json 必须随 insert 落库、随 select 读出");
            assertEquals(STORY, read.getJourneyStoryJson(),
                    "journey_story_json 必须随 insert 落库、随 select 读出");
        }
    }

    private static SqlSessionFactory factory() throws Exception {
        Configuration configuration = new Configuration();
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:topic_edition_reopen_journey;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAlias("CmsTopic", CmsTopic.class);
        configuration.getTypeAliasRegistry().registerAlias("CmsTopicChapter", CmsTopicChapter.class);
        configuration.getTypeAliasRegistry().registerAlias("CmsTopicNode", CmsTopicNode.class);
        configuration.getTypeAliasRegistry().registerAlias("ProductRole", ProductRole.class);
        configuration.getTypeAliasRegistry().registerAlias("TopicEditionReopenRequest", TopicEditionReopenRequest.class);
        String resource = "mapper/business/TopicEditionReopenMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
