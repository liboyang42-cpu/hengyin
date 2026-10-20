package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.service.impl.AuditTaskServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 真 AuditTaskMapper.xml 跑 H2(MySQL 模式):工作台队列排序、认领条件更新、超期计数。
 */
class AuditTaskQueueH2Test {

    private static final long HOUR = 3600_000L;

    private DriverManagerDataSource ds;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:audit_task_queue_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table audit_task(id bigint auto_increment primary key, biz_type varchar(64), biz_id bigint,"
                + " submitter_id bigint, source_type varchar(32), risk_level varchar(32), status int,"
                + " current_assignee bigint, submitted_at timestamp, deadline_at timestamp, snapshot_json varchar(1024),"
                + " version int default 0, create_by varchar(64), create_time timestamp, update_by varchar(64),"
                + " update_time timestamp, remark varchar(255))");
    }

    private void row(long id, int status, Long assignee, String submitted, String deadline) {
        jdbc.update("insert into audit_task(id,biz_type,biz_id,status,current_assignee,submitted_at,deadline_at,version)"
                + " values (?,'COMMENT',?,?,?,?,?,0)", id, id, status, assignee,
                java.sql.Timestamp.valueOf(submitted), deadline == null ? null : java.sql.Timestamp.valueOf(deadline));
    }

    private SqlSessionFactory factory() throws Exception {
        Configuration configuration = new Configuration(new Environment("audit", new JdbcTransactionFactory(), ds));
        configuration.getTypeAliasRegistry().registerAlias("AuditTask", AuditTask.class);
        String resource = "mapper/business/AuditTaskMapper.xml";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static List<Long> ids(List<AuditTask> tasks) {
        return tasks.stream().map(AuditTask::getId).collect(Collectors.toList());
    }

    @Test
    void queueOrdersMostUrgentFirstAndNullDeadlinesLast() throws Exception {
        row(1, 0, null, "2026-10-01 08:00:00", null);                  // 历史单,无 deadline
        row(2, 0, null, "2026-10-01 09:00:00", "2026-10-03 09:00:00"); // 48h
        row(3, 0, null, "2026-10-01 10:00:00", "2026-10-01 14:00:00"); // HIGH 4h,最紧急
        row(4, 0, null, "2026-10-01 07:00:00", "2026-10-02 07:00:00"); // 24h
        row(5, 0, null, "2026-10-01 06:00:00", "2026-10-02 07:00:00"); // 同 deadline,更早提交在前
        row(6, 0, null, "2026-10-01 05:00:00", null);
        try (SqlSession session = factory().openSession(true)) {
            AuditTaskMapper mapper = session.getMapper(AuditTaskMapper.class);
            assertEquals(Arrays.asList(3L, 5L, 4L, 2L, 6L, 1L), ids(mapper.selectAuditTaskQueue(new AuditTask())));

            AuditTask pendingOnly = new AuditTask();
            pendingOnly.setStatus(AuditTask.STATUS_PENDING);
            assertEquals(6, mapper.selectAuditTaskQueue(pendingOnly).size(), "过滤条件与旧列表一致");

            // 内部"取最新一单"语义保持 submitted_at desc,不受工作台排序影响
            assertEquals(Long.valueOf(3L), mapper.selectAuditTaskList(new AuditTask()).get(0).getId());
        }
    }

    @Test
    void claimOnlyOnceAndNeverOnFinishedTask() throws Exception {
        row(10, 0, null, "2026-10-01 08:00:00", "2026-10-02 08:00:00");
        row(11, 1, null, "2026-10-01 08:00:00", "2026-10-02 08:00:00");
        try (SqlSession session = factory().openSession(true)) {
            AuditTaskMapper mapper = session.getMapper(AuditTaskMapper.class);
            AuditTaskServiceImpl svc = new AuditTaskServiceImpl();
            ReflectionTestUtils.setField(svc, "auditTaskMapper", mapper);

            svc.claim(10L, 501L);
            assertEquals(Long.valueOf(501L),
                    jdbc.queryForObject("select current_assignee from audit_task where id=10", Long.class));

            ServiceException again = assertThrows(ServiceException.class, () -> svc.claim(10L, 502L));
            assertEquals("该任务已被认领或已处理", again.getMessage());
            assertEquals(Long.valueOf(501L),
                    jdbc.queryForObject("select current_assignee from audit_task where id=10", Long.class),
                    "第二次认领不得覆盖认领人");

            ServiceException finished = assertThrows(ServiceException.class, () -> svc.claim(11L, 501L));
            assertEquals("该任务已被认领或已处理", finished.getMessage());
            assertThrows(ServiceException.class, () -> svc.claim(999L, 501L));
        }
    }

    @Test
    void overdueCountIncludesNewTaskOnceItsDeadlinePasses() throws Exception {
        try (SqlSession session = factory().openSession(true)) {
            AuditTaskMapper mapper = session.getMapper(AuditTaskMapper.class);
            AuditTaskServiceImpl svc = new AuditTaskServiceImpl();
            ReflectionTestUtils.setField(svc, "auditTaskMapper", mapper);

            Long id = svc.submit(AuditTask.BIZ_COMMENT, 77L, 8L, "auto_gate", "{}");

            Date now = new Date();
            assertEquals(0, mapper.countPendingOverdue(now), "未到时限不算超期");
            Date afterDeadline = new Date(now.getTime() + 25 * HOUR);
            assertEquals(1, mapper.countPendingOverdue(afterDeadline), "超过 deadline_at 的待审新单必须计入超期");
            assertEquals(Arrays.asList(id), mapper.samplePendingOverdueIds(afterDeadline));
        }
    }
}
