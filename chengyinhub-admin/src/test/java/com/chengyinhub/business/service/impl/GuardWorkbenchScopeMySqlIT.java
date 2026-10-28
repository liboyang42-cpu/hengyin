package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.it.MybatisItDatabase;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.github.pagehelper.PageInterceptor;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL + real Mapper XML + PageHelper COUNT/offset. No skipped database fallback. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GuardWorkbenchScopeMySqlIT {
    private JdbcTemplate jdbc;
    private String database;
    private AuditTaskMapper tasks;
    @BeforeAll void setup() throws Exception {
        assertTrue("127.0.0.1".equals(MybatisItDatabase.host()) || "localhost".equals(MybatisItDatabase.host()));
        String url = MybatisItDatabase.url("guard_workbench_scope"); database = MybatisItDatabase.databaseName("guard_workbench_scope");
        DriverManagerDataSource source = new DriverManagerDataSource(url, MybatisItDatabase.user(), MybatisItDatabase.password());
        jdbc = new JdbcTemplate(source);
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(jdbc.queryForObject("SELECT VERSION()", String.class)));
        jdbc.execute("CREATE TABLE audit_task(id BIGINT PRIMARY KEY,biz_type VARCHAR(32),biz_id BIGINT,submitter_id BIGINT,source_type VARCHAR(32),risk_level VARCHAR(32),status INT,current_assignee BIGINT,submitted_at DATETIME,deadline_at DATETIME,snapshot_json LONGTEXT,version INT,create_by VARCHAR(64),create_time DATETIME,update_by VARCHAR(64),update_time DATETIME,remark VARCHAR(500)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE audit_appeal(id BIGINT PRIMARY KEY,biz_type VARCHAR(32),biz_id BIGINT,task_id BIGINT,member_id BIGINT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE play_arrive_guard_log(id BIGINT PRIMARY KEY,member_id BIGINT,topic_id BIGINT) ENGINE=InnoDB");
        for (long id = 1; id <= 6; id++) {
            String type = id == 3 ? "TOPIC" : "play_arrive_guard";
            jdbc.update("INSERT INTO audit_task(id,biz_type,biz_id,submitter_id,source_type,status,version,submitted_at,deadline_at,snapshot_json) VALUES(?,?,?,10,'appeal',0,0,'2026-10-01','2026-10-02','{\"guardOrigin\":{\"topicId\":\"77\"}}')", id, type, 100 + id);
            if (id != 3 && id != 6) {
                jdbc.update("INSERT INTO audit_appeal VALUES(?,?,?,?,?)", id, type, 100 + id, id, 10);
                // 5 is another actor's log; 6 has no persistent appeal/log association.
                jdbc.update("INSERT INTO play_arrive_guard_log VALUES(?,?,?)", 100 + id, id == 5 ? 11 : 10, id == 1 ? 88 : 77);
            }
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/AuditTaskMapper.xml"));
        PageInterceptor interceptor = new PageInterceptor(); Properties properties = new Properties();
        properties.setProperty("helperDialect", "mysql"); interceptor.setProperties(properties); factory.setPlugins(interceptor);
        tasks = new SqlSessionTemplate(factory.getObject()).getMapper(AuditTaskMapper.class);
    }
    private PageInfo<AuditTask> page(int number, List<Long> scope, AuditTask filter) {
        try {
            PageHelper.startPage(number, 1);
            return new PageInfo<>(tasks.selectWorkbenchQueueWithGuardScope(filter, scope));
        } finally { PageHelper.clearPage(); }
    }
    @Test void authorizedRowsAndCountUseSamePersistentScopeOnEveryPage() {
        List<Long> ids = new ArrayList<>();
        for (int n = 1; n <= 3; n++) {
            PageInfo<AuditTask> page = page(n, List.of(77L), new AuditTask());
            assertEquals(3, page.getTotal()); assertEquals(1, page.getList().size()); ids.add(page.getList().get(0).getId());
        }
        assertEquals(List.of(2L, 3L, 4L), ids);
        assertTrue(page(4, List.of(77L), new AuditTask()).getList().isEmpty());
        assertEquals(List.of(1L, 3L), tasks.selectWorkbenchQueueWithGuardScope(new AuditTask(), List.of(88L)).stream().map(AuditTask::getId).toList());
    }
    @Test void emptyScopeAndClientParamsCannotGrantGuardRows() {
        AuditTask forged = new AuditTask(); forged.getParams().put("guardTopicIds", List.of(77L, 88L));
        for (List<Long> scope : Arrays.asList(null, List.<Long>of())) {
            PageInfo<AuditTask> page = page(1, scope, forged);
            assertEquals(1, page.getTotal()); assertEquals(3L, page.getList().get(0).getId());
        }
        forged.setBizType("play_arrive_guard"); assertEquals(0, page(1, List.of(), forged).getTotal());
    }
    @Test void nonGuardFilterAndInternalQueueRemainUnchanged() {
        AuditTask filter = new AuditTask(); filter.setBizType("TOPIC");
        assertEquals(1, page(1, List.of(), filter).getTotal());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), tasks.selectAuditTaskQueue(new AuditTask()).stream().map(AuditTask::getId).toList());
    }
    @AfterAll void cleanup() { PageHelper.clearPage(); if (jdbc != null && database != null) jdbc.execute("DROP DATABASE " + database); }
}
