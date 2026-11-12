package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.vo.TemplateLibraryVO;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CL-39：模板广场(cms_template_library 公共投影)待审 / 驳回不对他人可见。
 *
 * <p>状态来源：TemplatePublishServiceImpl.submitTemplateToMarket → 投影 status=0 并开审核单；
 * 审核驳回 → status=2；通过 → status=1；作者下架 → status=3，再上架 → status=1。
 * 他人读路径：/api/template/list 与 /homeData(selectCmsTemplateLibraryList + del_flag=0,status=1)、
 * /api/template/info(Java 侧同两条件)、/groupByCategory(selectTemplatesByCategoryIds)。
 */
class TemplateLibraryReviewVisibilityMyBatisTest {

    @Test
    void publicGalleryQueryShowsOnlyApprovedAndListedTemplates() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        seed(jdbc);
        try (SqlSession session = factory(ds).openSession(true)) {
            CmsTemplateLibraryMapper mapper = session.getMapper(CmsTemplateLibraryMapper.class);
            CmsTemplateLibrary gallery = new CmsTemplateLibrary();
            gallery.setDelFlag(0);
            gallery.setStatus(1L);
            assertEquals(List.of("approved"), titles(mapper.selectCmsTemplateLibraryList(gallery)));
            gallery.setTitle("pending");
            assertEquals(List.of(), titles(mapper.selectCmsTemplateLibraryList(gallery)), "keyword search");
        }
    }

    @Test
    void categoryGroupsHideTemplatesThatTheReviewFlowLeftUnlisted() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        seed(jdbc);
        try (SqlSession session = factory(ds).openSession(true)) {
            List<String> titles = session.getMapper(CmsTemplateLibraryMapper.class)
                    .selectTemplatesByCategoryIds(List.of(1L)).stream()
                    .map(TemplateLibraryVO::getTitle).sorted().collect(Collectors.toList());
            assertEquals(List.of("approved"), titles);
        }
    }

    /** 分类分组与公开列表共用 status=1 可见条件。 */
    @Test
    void categoryGroupsAndGalleryBothHidePendingTemplates() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        seed(jdbc);
        jdbc.update("insert into cms_template_library(id,title,category_id,status,del_flag,create_time) values (9,'inconsistent-pending',1,0,0,now())");
        try (SqlSession session = factory(ds).openSession(true)) {
            CmsTemplateLibraryMapper mapper = session.getMapper(CmsTemplateLibraryMapper.class);
            List<String> grouped = mapper.selectTemplatesByCategoryIds(List.of(1L)).stream()
                    .map(TemplateLibraryVO::getTitle).sorted().collect(Collectors.toList());
            assertEquals(List.of("approved"), grouped);
            CmsTemplateLibrary gallery = new CmsTemplateLibrary();
            gallery.setDelFlag(0);
            gallery.setStatus(1L);
            assertEquals(List.of("approved"), titles(mapper.selectCmsTemplateLibraryList(gallery)));
        }
    }

    /** owner 2026-10-23 D5(U-036):同名判重比对池 = 同作者、未删除、不含本模板自己的投影。 */
    @Test
    void authorLiveTitlesExcludeOwnProjectionDeletedRowsAndOtherAuthors() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        seed(jdbc);
        String sql = "insert into cms_template_library(id,member_id,member_template_id,title,category_id,status,del_flag,create_time) values (?,?,?,?,1,0,?,now())";
        jdbc.update(sql, 20, 30L, 300L, "我的夜游", 0);       // 本模板自己的投影:不算「另一个」
        jdbc.update(sql, 21, 30L, 301L, " 我的夜游 ", 0);     // 同作者另一份:进比对池(Java 侧 trim)
        jdbc.update(sql, 22, 30L, 302L, "已删的夜游", 2);     // 已删除:不进
        jdbc.update(sql, 23, 31L, 303L, "我的夜游", 0);       // 别人的:不进
        jdbc.update(sql, 24, 30L, null, "平台迁入", 0);       // member_template_id 为空也算同作者条目
        try (SqlSession session = factory(ds).openSession(true)) {
            List<String> titles = session.getMapper(CmsTemplateLibraryMapper.class)
                    .selectAuthorLiveTitles(30L, 20L).stream().sorted().collect(Collectors.toList());
            assertEquals(List.of(" 我的夜游 ", "平台迁入"), titles);
        }
    }

    @Test
    void interactionProjectionUsesCanonicalOwnerAndParticipatesInAuditIdentity() throws Exception {
        DriverManagerDataSource ds = dataSource(); JdbcTemplate jdbc = new JdbcTemplate(ds);
        seed(jdbc);
        jdbc.update("insert into cms_member_template(id,member_id,interaction_type) values (701,11,'hidden_menu')");
        jdbc.update("update cms_template_library set member_template_id=701 where id=5");
        try (SqlSession session = factory(ds).openSession(true)) {
            CmsTemplateLibraryMapper mapper = session.getMapper(CmsTemplateLibraryMapper.class);
            CmsTemplateLibrary row = mapper.selectCmsTemplateLibraryById(5L);
            assertEquals("hidden_menu", row.getInteractionType());
            String reviewed = com.chengyinhub.business.service.support.TemplateAuditSnapshot.market(row);
            assertEquals("hidden_menu", mapper.selectByMemberTemplateForUpdate(701L).get(0).getInteractionType());
            assertEquals("hidden_menu", mapper.selectTemplatesByCategoryIds(List.of(1L)).get(0).getInteractionType());
            jdbc.update("update cms_member_template set interaction_type='city_story_card' where id=701");
            session.clearCache();
            String changed = com.chengyinhub.business.service.support.TemplateAuditSnapshot.market(mapper.selectCmsTemplateLibraryById(5L));
            assertEquals(false, com.chengyinhub.business.service.support.TemplateAuditSnapshot.matches(reviewed, changed));
            jdbc.update("update cms_member_template set member_id=12 where id=701");
            session.clearCache();
            org.junit.jupiter.api.Assertions.assertNull(mapper.selectCmsTemplateLibraryById(5L).getInteractionType());
        }
    }

    private void seed(JdbcTemplate jdbc) {
        jdbc.execute("create table cms_member_template(id bigint primary key, member_id bigint, interaction_type varchar(32))");
        jdbc.execute("create table cms_template_library("
                + "id bigint auto_increment primary key,"
                + "member_id bigint, member_template_id bigint, category_id bigint,"
                + "title varchar(255), description varchar(1000), img_url varchar(1000),"
                + "players varchar(64), usage_location varchar(255), required_materials varchar(1000),"
                + "duration bigint, difficulty varchar(64), is_sync int,"
                + "rule_instructions text, merchant_guide text, reward_rules_json text,"
                + "validation_method int, sensor_type varchar(64), sensor_config text,"
                + "question_name varchar(255), question_answer text, feedback_text text, coupon_id bigint,"
                + "story_text text, story_img varchar(1000), story_json text, hint1 text, hint2 text,"
                + "answer_reveal text, photo_require_desc text, photo_review int,"
                + "audio_url varchar(1000), audio_duration bigint,"
                + "question_a text, question_b text, question_c text, question_d text, correct_answer varchar(255),"
                + "question_img varchar(1000), question_audio varchar(1000),"
                + "question_option_media_json text, preference_json text, advanced_config_json text,"
                + "activity_categoryids varchar(255), status bigint, reason varchar(255),"
                + "del_flag int, rating decimal(10,2), use_num int, pack_type tinyint,"
                + "create_by varchar(64), create_time timestamp, update_by varchar(64), update_time timestamp)");
        jdbc.execute("create table sys_category(id bigint primary key,category_name varchar(64),icon varchar(255),del_flag int,status int)");
        jdbc.update("insert into sys_category values (1,'cat','',0,1)");
        // 送审中、驳回、作者撤回(status=2)、通过未上架、通过已上架。
        String sql = "insert into cms_template_library(id,member_id,title,category_id,img_url,status,del_flag,use_num,create_time) values (?,11,?,1,?,?,0,0,now())";
        jdbc.update(sql, 1, "pending", "https://cdn.example/pending.jpg", 0);
        jdbc.update(sql, 2, "rejected", "https://cdn.example/rejected.jpg", 2);
        jdbc.update(sql, 3, "withdrawn", "https://cdn.example/withdrawn.jpg", 2);
        jdbc.update(sql, 4, "approved-unlisted", "https://cdn.example/unlisted.jpg", 3);
        jdbc.update(sql, 5, "approved", "https://cdn.example/approved.jpg", 1);
    }

    private static List<String> titles(List<CmsTemplateLibrary> rows) {
        return rows.stream().map(CmsTemplateLibrary::getTitle).collect(Collectors.toList());
    }

    private DriverManagerDataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:template_review_visibility_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        return ds;
    }

    private SqlSessionFactory factory(DriverManagerDataSource dataSource) throws Exception {
        Configuration configuration = new Configuration(new Environment(
                "template-review-visibility", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain.vo");
        configuration.addMapper(CmsTemplateLibraryMapper.class);
        String resource = "mapper/business/CmsTemplateLibraryMapper.xml";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing " + resource);
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
