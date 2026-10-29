package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsTemplateLibrary;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * pack_type 筛选的**结果集契约**:带上 pack_type 与不带,查出来的行必须真的不一样。
 *
 * <p>为什么要有这一条:前面两条测试只钉到「解析结果落进了查询对象」。查询对象对了、
 * 但 mapper 里没有这条过滤(或过滤写成了别的列),用户仍然会看到全部模板 ——
 * 筛选看起来生效,实际没有任何作用,而且不报错。
 *
 * <p>负控:删掉 CmsTemplateLibraryMapper.xml 里
 * {@code <if test="packType != null "> and pack_type = #{packType}</if>} 这一行,
 * 本测试必须变红(筛选后仍是 4 行)。只改解析/投影不会让本测试变绿或变红。
 */
class TemplateLibraryPackTypeFilterMyBatisTest {

    @Test
    void packTypeFilterShrinksTheResultSetInsteadOfReturningEverything() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:template_pack_type;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop all objects");
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
                + "activity_categoryids varchar(255), status bigint, publish_status bigint, reason varchar(255),"
                + "del_flag int, rating decimal(10,2), use_num int, pack_type tinyint,"
                + "create_by varchar(64), create_time timestamp, update_by varchar(64), update_time timestamp)");
        insert(jdbc, "单节点玩法A", 0);
        insert(jdbc, "单节点玩法B", 0);
        insert(jdbc, "剧情包", 1);
        insert(jdbc, "店铺IP包", 2);

        SqlSessionFactory factory = factory(dataSource);
        try (SqlSession session = factory.openSession(true)) {
            CmsTemplateLibraryMapper mapper = session.getMapper(CmsTemplateLibraryMapper.class);

            assertEquals(4, mapper.selectCmsTemplateLibraryList(visible(null)).size(),
                    "不传 pack_type = 不过滤,四个形态都要在");
            assertEquals(2, mapper.selectCmsTemplateLibraryList(visible(0)).size());
            assertEquals(1, mapper.selectCmsTemplateLibraryList(visible(1)).size());
            assertEquals(1, mapper.selectCmsTemplateLibraryList(visible(2)).size());

            List<String> storyTitles = mapper.selectCmsTemplateLibraryList(visible(1)).stream()
                    .map(CmsTemplateLibrary::getTitle).collect(Collectors.toList());
            assertEquals(java.util.Collections.singletonList("剧情包"), storyTitles,
                    "筛出来的必须是对应形态的行,不是随便一行");
        }
    }

    private static CmsTemplateLibrary visible(Integer packType) {
        CmsTemplateLibrary query = new CmsTemplateLibrary();
        query.setPackType(packType);
        query.setDelFlag(0);
        query.setStatus(1L);
        query.setPublishStatus(1L);
        return query;
    }

    private static void insert(JdbcTemplate jdbc, String title, Integer packType) {
        jdbc.update("insert into cms_template_library(title, status, publish_status, del_flag, use_num, pack_type)"
                + " values (?, 1, 1, 0, 0, ?)", title, packType);
    }

    private static SqlSessionFactory factory(DriverManagerDataSource dataSource) throws Exception {
        Configuration configuration = new Configuration(new Environment(
                "template-pack-type", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        configuration.addMapper(CmsTemplateLibraryMapper.class);
        String resource = "mapper/business/CmsTemplateLibraryMapper.xml";
        try (InputStream in = TemplateLibraryPackTypeFilterMyBatisTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing " + resource);
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
