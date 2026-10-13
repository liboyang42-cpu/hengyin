package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsTopicChapterTemplateMeta;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * F05:开售快照的 merchantWorkPackage 从 cms_topic_chapter_template_meta 读 roleNeed + 五项 effort,
 * 但写链只有服务容量三数的 upsert ⇒ 正常建出来的主题这六项恒空。真 MyBatis + H2(MySQL mode),不冒充真实 MySQL。
 */
class ChapterWorkPackageMyBatisTest
{
    private static final String UPSERT =
            "com.chengyinhub.business.mapper.CmsTopicTemplateMetaMapper.upsertChapterWorkPackage";

    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception
    {
        Configuration configuration = new Configuration();
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:chapter_work_package_" + System.nanoTime()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/business/CmsTopicTemplateMetaMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            statement.execute("create table cms_topic_chapter_template_meta ("
                    + "id bigint auto_increment primary key, chapter_id bigint not null, topic_id bigint not null,"
                    + "role_need text, exclusivity_mode varchar(8), exclusivity_scope varchar(255),"
                    + "exclusivity_period varchar(255), exclusivity_reason text, effort_prep_hours decimal(6,2),"
                    + "effort_staff_minutes int, effort_venue varchar(255), effort_materials text,"
                    + "batch_size int, batch_interval_minutes int, effort_max_concurrent int,"
                    + "effort_available_slots varchar(255), effort_needs_booking tinyint, effort_cancel_cost text,"
                    + "effort_qualifications text, del_flag tinyint not null default 0,"
                    + "create_time timestamp default current_timestamp, update_time timestamp default current_timestamp,"
                    + "constraint uk_chapter_meta_chapter unique(chapter_id))");
        }
    }

    private static CmsTopicChapterTemplateMeta workPackage(String roleNeed)
    {
        CmsTopicChapterTemplateMeta meta = new CmsTopicChapterTemplateMeta();
        meta.setChapterId(18L);
        meta.setTopicId(81L);
        meta.setRoleNeed(roleNeed);
        meta.setEffortStaffMinutes(30);
        meta.setEffortVenue("店内 2 张桌");
        meta.setEffortMaterials("提示卡 20 张");
        meta.setEffortAvailableSlots("周六 14:00-18:00");
        meta.setEffortQualifications("食品经营许可证");
        return meta;
    }

    @Test
    void workPackageUpsertWritesAllSixFactsAndKeepsServiceCapacity()
    {
        try (SqlSession session = factory.openSession(true)) {
            CmsTopicTemplateMetaMapper mapper = session.getMapper(CmsTopicTemplateMetaMapper.class);
            mapper.upsertChapterServiceCapacity(18L, 81L, 4, 15, 2);
            session.insert(UPSERT, workPackage("店员带玩家完成一次试吃"));
            session.insert(UPSERT, workPackage("店员带玩家完成两次试吃"));
            mapper.upsertChapterServiceCapacity(18L, 81L, 6, 15, 2);

            List<CmsTopicChapterTemplateMeta> metas = mapper.selectChapterMetaByTopicId(81L);
            assertEquals(1, metas.size(), "一章一行");
            CmsTopicChapterTemplateMeta row = metas.get(0);
            assertEquals("店员带玩家完成两次试吃", row.getRoleNeed());
            assertEquals(Integer.valueOf(30), row.getEffortStaffMinutes());
            assertEquals("店内 2 张桌", row.getEffortVenue());
            assertEquals("提示卡 20 张", row.getEffortMaterials());
            assertEquals("周六 14:00-18:00", row.getEffortAvailableSlots());
            assertEquals("食品经营许可证", row.getEffortQualifications());
            assertEquals(Integer.valueOf(6), row.getBatchSize(), "两条写口互不覆盖");
            assertEquals(Integer.valueOf(2), row.getEffortMaxConcurrent());
        }
    }
}
