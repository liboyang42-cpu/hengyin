package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.AuditAppeal;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.sql.Timestamp;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 真 XML 跑 H2(MySQL 模式)：申诉表唯一键/状态 CAS，以及活动、主题、玩法、模板广场的审核决定回待审 CAS。
 * 建表与 migration_audit_appeal_20261020.sql 同形(JSON 列在 H2 用 varchar 代替)。
 */
class AuditAppealMapperH2Test {
    private DriverManagerDataSource ds;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:audit_appeal_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table audit_appeal(id bigint auto_increment primary key, biz_type varchar(40) not null,"
                + " biz_id bigint not null, task_id bigint not null, member_id bigint not null, reason text not null,"
                + " evidence_json varchar(4000), state varchar(16) not null default 'SUBMITTED', due_at datetime not null,"
                + " handled_by bigint, handled_at datetime, created_at datetime not null,"
                + " constraint uk_audit_appeal_biz_member unique(biz_type, biz_id, member_id))");
        jdbc.execute("create table cms_activity(id bigint primary key, status bigint, check_status int,"
                + " publish_status bigint, del_flag int, update_time datetime)");
        jdbc.execute("create table cms_topic(id bigint primary key, status int, check_status int, user_status int,"
                + " config_version bigint, del_flag int)");
        jdbc.execute("create table cms_member_template(id bigint primary key, member_id bigint, status bigint,"
                + " check_status int, publish_status bigint, draft_status int, del_flag int, update_time datetime)");
        jdbc.execute("create table cms_template_library(id bigint primary key, member_id bigint, member_template_id bigint,"
                + " status bigint, publish_status bigint, del_flag int, update_time datetime)");
    }

    private SqlSessionFactory factory() throws Exception {
        Configuration configuration = new Configuration(new Environment("appeal", new JdbcTransactionFactory(), ds));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        for (String resource : new String[] {"mapper/business/AuditAppealMapper.xml",
                "mapper/business/CmsActivityMapper.xml", "mapper/business/CmsTopicMapper.xml",
                "mapper/business/CmsMemberTemplateMapper.xml", "mapper/business/CmsTemplateLibraryMapper.xml"}) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
                new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static AuditAppeal appeal(long member) {
        AuditAppeal appeal = new AuditAppeal();
        appeal.setBizType("ACTIVITY");
        appeal.setBizId(501L);
        appeal.setTaskId(71L);
        appeal.setMemberId(member);
        appeal.setReason("活动信息真实");
        appeal.setEvidenceJson("[\"a.png\"]");
        appeal.setState(AuditAppeal.STATE_SUBMITTED);
        appeal.setDueAt(new Date());
        appeal.setCreatedAt(new Date());
        return appeal;
    }

    @Test
    void oneAppealPerContentPerMemberAndStateMachine() throws Exception {
        try (SqlSession session = factory().openSession(true)) {
            AuditAppealMapper mapper = session.getMapper(AuditAppealMapper.class);
            AuditAppeal first = appeal(10L);
            assertEquals(1, mapper.insertAuditAppeal(first));
            assertNotNull(first.getId());
            assertThrows(org.apache.ibatis.exceptions.PersistenceException.class,
                    () -> mapper.insertAuditAppeal(appeal(10L)), "同一作者同一内容第二次申诉被唯一键拦下");

            assertEquals(1, mapper.markReviewing(71L));
            assertEquals(0, mapper.markReviewing(71L), "只有 SUBMITTED 能进入 REVIEWING");
            assertEquals(AuditAppeal.STATE_REVIEWING, mapper.selectByTaskIdForUpdate(71L).getState());

            assertEquals(1, mapper.finishAppeal(first.getId(), AuditAppeal.STATE_ACCEPTED, 900L, new Date()));
            assertEquals(0, mapper.finishAppeal(first.getId(), AuditAppeal.STATE_REJECTED, 901L, new Date()),
                    "终态不可再裁决");
            AuditAppeal done = mapper.selectByBizMember("ACTIVITY", 501L, 10L);
            assertEquals(AuditAppeal.STATE_ACCEPTED, done.getState());
            assertEquals(Long.valueOf(900L), done.getHandledBy());
            assertEquals("[\"a.png\"]", done.getEvidenceJson());
        }
    }

    @Test
    void activityReopenRequiresDecidedStateAndNoLaterEdit() throws Exception {
        Timestamp decided = Timestamp.valueOf("2026-10-20 08:00:00");
        jdbc.update("insert into cms_activity values (1, 2, 2, 0, 0, ?)", Timestamp.valueOf("2026-10-20 07:00:00"));
        jdbc.update("insert into cms_activity values (2, 2, 2, 0, 0, ?)", Timestamp.valueOf("2026-10-20 08:05:00"));
        jdbc.update("insert into cms_activity values (3, 1, 1, 1, 0, null)");
        try (SqlSession session = factory().openSession(true)) {
            CmsActivityMapper mapper = session.getMapper(CmsActivityMapper.class);
            assertEquals(1, mapper.reopenAuditDecision(1L, 2L, 2, 0L, decided));
            assertEquals(0, mapper.reopenAuditDecision(2L, 2L, 2, 0L, decided), "决定后编辑过 = 内容已变化");
            assertEquals(1, mapper.reopenAuditDecision(2L, 2L, 2, 0L, null), "申诉复核不看编辑时间");
            assertEquals(0, mapper.reopenAuditDecision(3L, 2L, 2, 0L, null), "状态不是该决定写下的 = 0 行");
            assertEquals(1, mapper.reopenAuditDecision(3L, 1L, 1, 1L, decided));
            Map<String, Object> row = jdbc.queryForMap("select status, check_status, publish_status from cms_activity where id=3");
            assertEquals(0L, ((Number) row.get("status")).longValue());
            assertEquals(0L, ((Number) row.get("publish_status")).longValue());
        }
    }

    @Test
    void topicReopenHidesAndChecksBundleConfigVersion() throws Exception {
        jdbc.update("insert into cms_topic values (1, 1, 1, 1, 7, 0)");
        jdbc.update("insert into cms_topic values (2, 2, 2, 0, 8, 0)");
        jdbc.update("insert into cms_topic values (3, 2, 2, 0, 1, 2)");
        try (SqlSession session = factory().openSession(true)) {
            CmsTopicMapper mapper = session.getMapper(CmsTopicMapper.class);
            assertEquals(0, mapper.reopenAuditDecision(1L, 1, 1, 6L), "提交后改过配置");
            assertEquals(1, mapper.reopenAuditDecision(1L, 1, 1, 7L));
            assertEquals(0, ((Number) jdbc.queryForObject("select user_status from cms_topic where id=1", Integer.class)).intValue());
            assertEquals(1, mapper.reopenAuditDecision(2L, 2, 2, null));
            assertEquals(0, mapper.reopenAuditDecision(3L, 2, 2, null), "已删除主题不回待审");
        }
    }

    @Test
    void templateAndLibraryReopenAreOwnerScopedStateCas() throws Exception {
        jdbc.update("insert into cms_member_template values (1, 10, 2, 2, 0, 1, 0, null)");
        jdbc.update("insert into cms_template_library values (5, 10, 1, 1, 1, 0, null)");
        try (SqlSession session = factory().openSession(true)) {
            CmsMemberTemplateMapper templates = session.getMapper(CmsMemberTemplateMapper.class);
            assertEquals(0, templates.reopenAuditDecision(1L, 11L, 2L, 2, 0L), "非作者");
            assertEquals(0, templates.reopenAuditDecision(1L, 10L, 1L, 1, 1L), "方向不符");
            assertEquals(1, templates.reopenAuditDecision(1L, 10L, 2L, 2, 0L));
            CmsTemplateLibraryMapper libraries = session.getMapper(CmsTemplateLibraryMapper.class);
            assertEquals(0, libraries.reopenAuditDecision(5L, 10L, 2L, 1L, 1L), "投影换了个人玩法");
            assertEquals(1, libraries.reopenAuditDecision(5L, 10L, 1L, 1L, 1L));
            assertEquals(0L, jdbc.queryForObject("select status from cms_template_library where id=5", Long.class));
        }
    }
}
