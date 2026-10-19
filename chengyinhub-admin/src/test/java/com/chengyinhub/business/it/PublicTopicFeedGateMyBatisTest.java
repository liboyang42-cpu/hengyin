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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 玩家侧公开主题 feed 的两道闸 —— 真 MyBatis + 真 MySQL,跑仓库里那份 CmsTopicMapper.xml。
 *
 * <p><b>这道测试在钉住什么。</b>
 * <ul>
 *   <li><b>模板不进 feed</b>:模板是创作者侧素材,有自己的货架(/api/template/*)。
 *       玩家侧四个消费方(推荐主题 / 底部主题页签 / 收藏 / 俱乐部团)都走
 *       {@code selectPublicCmsTopicList},一个都不该出模板 ⇒ 写死在 SQL,不做成可选开关。</li>
 *   <li><b>常青主题不该被 end_date 滤掉</b>:2026-08-27 实测,全库 end_date 在未来的主题
 *       <b>0 条</b>(12 条 NULL、7 条已过期),而控制器给公开分支设了 isNew=1
 *       ⇒ 首页两块主题区长期静默全空。活动是场次该有起止,主题是内容不该。</li>
 * </ul>
 *
 * <p>负控用法:把 XML 里的 {@code and is_template = 0} 删掉,或把
 * {@code <if test="isNew == 1">} 加回来,下面对应断言必须变红。
 */
class PublicTopicFeedGateMyBatisTest {

    @Test
    void feedExcludesTemplatesAndKeepsEvergreenTopics() throws Exception {
        SqlSessionFactory f = factory();
        try (SqlSession probe = f.openSession()) {
            assertTrue(probe.getConnection().isValid(2), "真实 MySQL fixture 必须可达");
        }
        try (SqlSession s = f.openSession(true)) {
            Statement st = s.getConnection().createStatement();
            recreate(st);
            seed(st);
            CmsTopicMapper mapper = s.getMapper(CmsTopicMapper.class);

            List<Long> ids = mapper.selectPublicCmsTopicList(new CmsTopic())
                    .stream().map(CmsTopic::getId).sorted().collect(Collectors.toList());

            assertFalse(ids.contains(90L), "★ 模板不许进玩家 feed —— 它有自己的货架");
            assertTrue(ids.contains(11L), "end_date 为 NULL 的常青主题必须出得来");
            assertTrue(ids.contains(12L), "end_date 已过去的主题同样出得来 —— 主题不是场次");
            assertFalse(ids.contains(13L), "下架(user_status=0)仍然不可见");
            assertFalse(ids.contains(14L), "未过审(status≠1)仍然不可见");
            assertEquals(java.util.Arrays.asList(11L, 12L), ids, "公开 feed 只留这两条");
        }
    }

    private SqlSessionFactory factory() throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("CmsTopic", CmsTopic.class);
        PooledDataSource ds = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                MybatisItDatabase.url("public_topic_feed"),
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
                + " is_template TINYINT, create_by VARCHAR(64), create_time DATETIME,"
                + " update_by VARCHAR(64), update_time DATETIME)");
        st.execute("DROP TABLE IF EXISTS club");
        st.execute("CREATE TABLE club (id BIGINT PRIMARY KEY, member_id BIGINT, del_flag INT)");
        // 「只对成员可见」那道闸引用成员表与报名表。本 fixture 的主题 club_member_only 全是 0、
        // 短路后取不到数,但 MySQL 解析阶段同样要求表存在 —— 缺了会假红。
        st.execute("DROP TABLE IF EXISTS club_member");
        st.execute("CREATE TABLE club_member (id BIGINT PRIMARY KEY, club_id BIGINT, member_id BIGINT,"
                + " status INT, del_flag INT)");
        // 「已付款的人放行」那条现在按 owner_type 分两档,场次票要经 cms_activity 回溯源主题。
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
        // 五行:常青(NULL)/已过期/下架/未过审/模板 —— 覆盖两道闸的正反面。
        st.execute("INSERT INTO cms_topic (id,member_id,name,status,user_status,del_flag,"
                + "is_template,end_date,route_mode,config_version,published_config_version,"
                + "open_club_pool,recruit_extend_count,lifecycle,beta_flag,team_mode,team_max_members)"
                + " VALUES "
                + "(11,7,'常青·end_date 为空',1,1,0,0,NULL,'LINEAR',1,0,0,0,0,0,0,4),"
                + "(12,7,'已过期但仍是有效内容',1,1,0,0,DATE_SUB(NOW(), INTERVAL 90 DAY),'LINEAR',1,0,0,0,0,0,0,4),"
                + "(13,7,'已下架',1,0,0,0,NULL,'LINEAR',1,0,0,0,0,0,0,4),"
                + "(14,7,'未过审',0,1,0,0,NULL,'LINEAR',1,0,0,0,0,0,0,4),"
                + "(90,7,'玩法包模板',1,1,0,1,NULL,'LINEAR',1,0,0,0,0,0,0,4)");
    }
}
