package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsTopicMapper;
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
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R18-DRAFT:服务端草稿只对作者本人可见 —— 真 MyBatis + 真 MySQL,跑仓库里那份 CmsTopicMapper.xml。
 *
 * <p><b>为什么必须是真库</b>:这条契约的判据写在 SQL 里(status/user_status 闸)。改 service
 * 单测只能证明「调用了某方法」,证明不了那条 SQL 真的把草稿滤掉 —— 而「草稿悄悄出现在公开面」
 * 恰恰就是 filter 少一个条件这一类缺陷。挑的四条都是公开读/他人读的真实入口:
 * <ul>
 *   <li>{@code selectPublicCmsTopicList} —— 推荐 / 主题页签 / 收藏 / 俱乐部团共用的公开 feed;</li>
 *   <li>{@code selectCmsTopicDetailById} —— /api/topic/info-to-user 的详情读(带 viewer 归属);</li>
 *   <li>{@code selectMerchantVisibleCmsTopicById} —— 商家侧可见主题;</li>
 *   <li>{@code selectPublicCmsTopicById} —— 无 viewer 的单条公开读。</li>
 * </ul>
 *
 * <p>负控:把 fixture 里草稿行的 status 改成 1 / user_status 改成 1,对应断言立刻红
 * (这正是「草稿发布后应当可见」的正向边界)。
 */
class TopicDraftVisibilityMyBatisTest {

    private static final long OWNER = 7L;
    private static final long STRANGER = 999L;
    private static final long PUBLISHED_ID = 70L;
    private static final long DRAFT_ID = 71L;

    @Test
    void draftTopicStaysOutOfPublicReads_andReadableByOwner() throws Exception {
        SqlSessionFactory f = factory();
        try (SqlSession probe = f.openSession()) {
            assertTrue(probe.getConnection().isValid(2), "真实 MySQL fixture 必须可达");
        }
        try (SqlSession s = f.openSession(true)) {
            Statement st = s.getConnection().createStatement();
            recreate(st);
            seed(st);
            CmsTopicMapper mapper = s.getMapper(CmsTopicMapper.class);

            List<Long> feedIds = mapper.selectPublicCmsTopicList(new CmsTopic())
                    .stream().map(CmsTopic::getId).sorted().collect(Collectors.toList());
            assertTrue(feedIds.contains(PUBLISHED_ID), "已发布主题照旧在公开 feed 里");
            assertFalse(feedIds.contains(DRAFT_ID), "草稿不许出现在公开 feed(推荐/页签/收藏/俱乐部团)");

            assertNull(mapper.selectCmsTopicDetailById(DRAFT_ID, STRANGER), "他人读草稿必须为空");
            assertNotNull(mapper.selectCmsTopicDetailById(DRAFT_ID, OWNER), "作者本人要能读自己的草稿做回填");

            assertNull(mapper.selectMerchantVisibleCmsTopicById(DRAFT_ID), "草稿不许出现在商家侧可见主题");
            assertNull(mapper.selectPublicCmsTopicById(DRAFT_ID), "草稿不许从单条公开读漏出");
        }
    }

    private SqlSessionFactory factory() throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("CmsTopic", CmsTopic.class);
        PooledDataSource ds = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                MybatisItDatabase.url("topic_draft_visibility"),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        cfg.setEnvironment(new Environment("it", new JdbcTransactionFactory(), ds));
        try (InputStream in = Resources.getResourceAsStream("mapper/business/CmsTopicMapper.xml")) {
            new XMLMapperBuilder(in, cfg, "mapper/business/CmsTopicMapper.xml", cfg.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(cfg);
    }

    private void recreate(Statement st) throws Exception {
        st.execute("DROP TABLE IF EXISTS cms_topic");
        st.execute("CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, member_id BIGINT, club_id BIGINT,"
                + " topic_type INT, product_type INT, circle_theme_code VARCHAR(24),"
                + " source_template_topic_id BIGINT, complete_rule_json JSON, route_mode VARCHAR(20),"
                + " route_graph_json JSON, config_version BIGINT, published_route_graph_json JSON,"
                + " published_config_version BIGINT, game_module_config_json JSON,"
                + " journey_rules_json JSON,"
                + " journey_story_json JSON, theme_runtime_config_json MEDIUMTEXT, published_theme_runtime_config_json MEDIUMTEXT,"
                + " fee_platform_rate DECIMAL(10,4), fee_guarantee_base_rate DECIMAL(10,4),"
                + " fee_guarantee_step_rate DECIMAL(10,4), fee_guarantee_max_merchant TINYINT,"
                + " topic_id BIGINT, name VARCHAR(120), subtitle VARCHAR(120), description TEXT,"
                + " address_name VARCHAR(120), total_time INT, total_mileage DECIMAL(10,2),"
                + " location_count INT, template_count INT, start_date DATETIME, end_date DATETIME,"
                + " img_url VARCHAR(255), img_arr TEXT, category_ids VARCHAR(100), status TINYINT,"
                + " reason VARCHAR(255), user_status TINYINT, merchant_status TINYINT,"
                + " open_club_pool TINYINT, merchant_sign_up_start_date DATETIME,"
                + " merchant_sign_up_end_date DATETIME, del_flag TINYINT, is_sync TINYINT,"
                + " is_recommend TINYINT, check_status TINYINT, is_settlement TINYINT,"
                + " publish_mode VARCHAR(16), beta_flag TINYINT, audio_url VARCHAR(500),"
                + " audio_duration INT, self_play TINYINT, self_play_price DECIMAL(10,2),"
                + " self_play_quota INT, team_mode TINYINT, team_max_members INT, xp_budget INT,"
                + " finish_medal_name VARCHAR(40), finish_medal_img VARCHAR(255),"
                + " complete_reward_coupon_id BIGINT, recruit_deadline DATETIME,"
                + " club_coop_open TINYINT NOT NULL DEFAULT 1, club_pinned TINYINT NOT NULL DEFAULT 0,"
                + " club_member_only TINYINT NOT NULL DEFAULT 0,"
                + " recruit_extend_count TINYINT, lifecycle TINYINT, sale_end_at DATETIME,"
                + " selling_started_at DATETIME, source_topic_id BIGINT, circle_reviewed_at DATETIME,"
                + " is_template TINYINT, is_draft TINYINT NOT NULL DEFAULT 0,"
                + " create_by VARCHAR(64), create_time DATETIME,"
                + " update_by VARCHAR(64), update_time DATETIME)");
        st.execute("DROP TABLE IF EXISTS club");
        st.execute("CREATE TABLE club (id BIGINT PRIMARY KEY, member_id BIGINT, del_flag INT)");
        // 「只对成员可见」那道闸引用成员表与报名表。本 fixture 的主题 club_member_only 全是 0、
        // 短路后取不到数,但 MySQL 解析阶段同样要求表存在 —— 缺了会假红。
        st.execute("DROP TABLE IF EXISTS club_member");
        st.execute("CREATE TABLE club_member (id BIGINT PRIMARY KEY, club_id BIGINT, member_id BIGINT,"
                + " status INT, del_flag INT)");
        // 「已付款的人放行」那条按 owner_type 分两档,场次票要经 cms_activity 回溯源主题。
        st.execute("DROP TABLE IF EXISTS cms_activity");
        st.execute("CREATE TABLE cms_activity (id BIGINT PRIMARY KEY, topic_id BIGINT, club_id BIGINT,"
                + " del_flag TINYINT NOT NULL DEFAULT 0)");
        st.execute("DROP TABLE IF EXISTS cms_registration");
        st.execute("CREATE TABLE cms_registration (id BIGINT PRIMARY KEY, owner_type INT, owner_id BIGINT,"
                + " member_id BIGINT, topic_id BIGINT, del_flag INT NOT NULL DEFAULT 0,"
                + " registration_status INT, payment_status INT)");
        // 圈层闸的子查询要引用这三张表 —— 本 fixture 的主题 circle_theme_code 全是 NULL、
        // 短路后根本不会取数,但 MySQL 解析阶段仍要求表存在,缺了会假红。
        st.execute("DROP TABLE IF EXISTS merchant_chapter_offer");
        st.execute("CREATE TABLE merchant_chapter_offer (id BIGINT PRIMARY KEY, topic_id BIGINT,"
                + " chapter_id BIGINT, merchant_id BIGINT, status INT, valid_end DATETIME)");
        st.execute("DROP TABLE IF EXISTS merchant_circle_supply_profile");
        st.execute("CREATE TABLE merchant_circle_supply_profile (id BIGINT PRIMARY KEY,"
                + " offer_id BIGINT, is_current TINYINT, checked_at DATETIME)");
        st.execute("DROP TABLE IF EXISTS cms_topic_chapter");
        st.execute("CREATE TABLE cms_topic_chapter (id BIGINT PRIMARY KEY, circle_enabled TINYINT)");
    }

    private void seed(Statement st) throws Exception {
        // 两条:70=已发布(公开面可见),71=服务端草稿(status=0 + is_draft=1,除作者外全不可见)。
        st.execute("INSERT INTO cms_topic (id,member_id,name,status,user_status,del_flag,"
                + "is_template,is_draft,merchant_status,route_mode,config_version,published_config_version,"
                + "open_club_pool,recruit_extend_count,lifecycle,beta_flag,team_mode,team_max_members)"
                + " VALUES "
                + "(70,7,'已发布主题',1,1,0,0,0,0,'LINEAR',1,1,0,0,0,0,0,4),"
                + "(71,7,'服务端草稿',0,0,0,0,1,0,'LINEAR',1,0,0,0,0,0,0,4)");
    }
}
