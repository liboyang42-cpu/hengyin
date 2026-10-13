package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.AuditRecord;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.ImConversationMember;
import com.chengyinhub.business.domain.RoamHangout;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.AuditTaskServiceImpl;
import com.chengyinhub.business.service.impl.RoamHangoutServiceImpl;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import com.chengyinhub.business.it.MybatisItDatabase;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;

import java.io.InputStream;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** 原 Mapper XML + MySQL 8 RR 当前读；每次运行唯一 localhost scratch，结束清理。 */
class HangoutAuditLockMyBatisIT {
    private static final String SUITE = "hangout_audit_" + UUID.randomUUID().toString().replace("-", "");
    private static SqlSessionFactory factory;
    private static String database;
    private static String adminUrl;

    @BeforeAll
    static void start() throws Exception {
        assertEquals("127.0.0.1", MybatisItDatabase.host(), "只允许显式本机 scratch");
        adminUrl = "jdbc:mysql://127.0.0.1:" + MybatisItDatabase.port() + "/?useSSL=false&allowPublicKeyRetrieval=true";
        try (Connection c = DriverManager.getConnection(adminUrl, MybatisItDatabase.user(), MybatisItDatabase.password());
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery("select version()")) {
            assertTrue(rs.next());
            String version = rs.getString(1);
            assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(version), "需要 MySQL >= 8,不能把 H2/MySQL 9 当作通过,实际 " + version);
        }
        String url = MybatisItDatabase.url(SUITE);
        database = MybatisItDatabase.databaseName(SUITE);
        Configuration cfg = new Configuration();
        cfg.setEnvironment(new Environment("hangout-local", new JdbcTransactionFactory(),
                new PooledDataSource("com.mysql.cj.jdbc.Driver", url, MybatisItDatabase.user(), MybatisItDatabase.password())));
        cfg.getTypeAliasRegistry().registerAlias("AuditTask", AuditTask.class);
        cfg.getTypeAliasRegistry().registerAlias("AuditRecord", AuditRecord.class);
        cfg.getTypeAliasRegistry().registerAlias("ImConversationMember", ImConversationMember.class);
        cfg.getTypeAliasRegistry().registerAlias("RoamHangout", RoamHangout.class);
        for (String name : Arrays.asList("AuditTaskMapper", "AuditRecordMapper", "ImConversationMemberMapper", "RoamHangoutMapper")) {
            String path = "mapper/business/" + name + ".xml";
            try (InputStream in = HangoutAuditLockMyBatisIT.class.getClassLoader().getResourceAsStream(path)) {
                new XMLMapperBuilder(in, cfg, path, cfg.getSqlFragments()).parse();
            }
        }
        factory = new SqlSessionFactoryBuilder().build(cfg);
        try (SqlSession session = factory.openSession(true); Statement st = session.getConnection().createStatement()) {
            st.execute("create table roam_hangout(id bigint primary key,member_id bigint,title varchar(100),description text,cover_url varchar(255),latitude decimal(10,6),longitude decimal(10,6),address_name varchar(100),start_at datetime,status int,conversation_id bigint,create_time datetime,update_time datetime,del_flag int default 0) engine=InnoDB");
            st.execute("create table im_conversation_member(id bigint auto_increment primary key,conversation_id bigint,member_id bigint,role int,unread_count int,last_read_msg_id bigint,muted int,deleted int,create_time datetime,unique(conversation_id,member_id)) engine=InnoDB");
            st.execute("create table audit_task(id bigint primary key,biz_type varchar(40),biz_id bigint,submitter_id bigint,source_type varchar(30),risk_level varchar(30),status int,current_assignee bigint,submitted_at datetime,deadline_at datetime,snapshot_json text,version int,create_by varchar(64),create_time datetime,update_by varchar(64),update_time datetime,remark varchar(255)) engine=InnoDB");
            st.execute("create table audit_record(id bigint auto_increment primary key,task_id bigint,action varchar(40),operator_id bigint,reason_code varchar(40),reason_text varchar(255),snapshot_json text,reverted_record_id bigint,create_time datetime,index(task_id)) engine=InnoDB");
        }
    }

    @BeforeEach
    void reset() throws Exception {
        try (SqlSession s = factory.openSession(true); Statement st = s.getConnection().createStatement()) {
            st.execute("delete from audit_record"); st.execute("delete from audit_task");
            st.execute("delete from im_conversation_member"); st.execute("delete from roam_hangout");
            st.execute("insert into roam_hangout(id,member_id,status,conversation_id) values(88,4,0,500)");
            st.execute("insert into im_conversation_member(conversation_id,member_id,deleted) values(500,5,1),(600,6,0)");
            st.execute("insert into audit_task(id,biz_type,biz_id,status,version) values(40,'HANGOUT',88,0,0)");
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (database == null) return;
        assertTrue(database.matches("chengyin_mybatis_hangout_audit_[a-z0-9_]+"));
        try (Connection c = DriverManager.getConnection(adminUrl, MybatisItDatabase.user(), MybatisItDatabase.password());
             Statement st = c.createStatement()) {
            st.execute("drop database `" + database + "`");
            try (ResultSet rs = st.executeQuery("select count(*) from information_schema.schemata where schema_name = '" + database + "'")) {
                assertTrue(rs.next()); assertEquals(0, rs.getInt(1), "scratch 清理必须回读确认");
            }
        }
        System.out.println("Cleaned unique MySQL 8 scratch: " + database);
    }

    @Test
    void currentReadIncludesReporterCommittedAfterOldRepeatableReadSnapshot() throws Exception {
        try (SqlSession reader = factory.openSession(false)) {
            reader.getConnection().setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            assertEquals(0, reader.getMapper(AuditTaskMapper.class).selectAuditTaskById(40L).getStatus());
            AuditRecord query = new AuditRecord(); query.setTaskId(40L);
            assertEquals(0, reader.getMapper(AuditRecordMapper.class).selectAuditRecordList(query).size());
            try (SqlSession writer = factory.openSession(true)) {
                AuditRecord report = new AuditRecord(); report.setTaskId(40L); report.setAction("REPORT"); report.setOperatorId(9L);
                report.setSnapshotJson("{\"bizType\":\"HANGOUT\",\"bizId\":88,\"content\":{\"memberId\":4,\"conversationId\":500}}");
                assertEquals(1, writer.getMapper(AuditRecordMapper.class).insertAuditRecord(report));
            }
            reader.clearCache();
            assertEquals(0, reader.getMapper(AuditRecordMapper.class).selectAuditRecordList(query).size(), "负控：旧一致性快照看不到新举报");
            assertEquals(0, reader.getMapper(AuditTaskMapper.class).selectAuditTaskByIdForUpdate(40L).getStatus());
            AuditRecord current = reader.getMapper(AuditRecordMapper.class).selectReportsForUpdate(40L).get(0);
            assertEquals(9L, current.getOperatorId());
            assertEquals("{\"bizType\":\"HANGOUT\",\"bizId\":88,\"content\":{\"memberId\":4,\"conversationId\":500}}",
                    current.getSnapshotJson(), "当前读必须携带后续服务器快照，供删除内容回退");
            reader.rollback();
        }
    }

    @Test
    void reviewNotifiesMemberWhoseJoinCommittedAfterRepeatableReadSnapshot() throws Exception {
        try (SqlSession review = factory.openSession(false)) {
            review.getConnection().setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            assertEquals(0, review.getMapper(AuditTaskMapper.class).selectAuditTaskById(40L).getStatus());
            // 两个真实事务交错：审核已建立 RR 快照，玩家随后持组局锁入群并提交。
            try (SqlSession joining = factory.openSession(false)) {
                IImService joinIm = mock(IImService.class);
                when(joinIm.getOrCreateHangoutGroup(88L, 7L, 4L)).thenAnswer(c -> {
                    ImConversationMember member = new ImConversationMember();
                    member.setConversationId(500L); member.setMemberId(7L); member.setRole(0);
                    joining.getMapper(ImConversationMemberMapper.class).insertImConversationMember(member);
                    return 500L;
                });
                RoamHangoutServiceImpl joinService = hangouts(joining, joinIm);
                assertEquals(500L, joinService.join(7L, 88L));
                joining.commit();
            }
            review.clearCache();
            assertEquals(1, review.getMapper(ImConversationMemberMapper.class).selectByConversation(500L).size(),
                    "负控：RR 旧快照只见历史成员，漏掉已提交入群者");

            IImService im = mock(IImService.class);
            doAnswer(c -> {
                review.getMapper(ImConversationMemberMapper.class).markAllDeleted(500L);
                return null;
            }).when(im).closeHangoutGroup(88L);
            AuditTaskServiceImpl service = new AuditTaskServiceImpl();
            ReflectionTestUtils.setField(service, "auditTaskMapper", review.getMapper(AuditTaskMapper.class));
            ReflectionTestUtils.setField(service, "auditRecordMapper", review.getMapper(AuditRecordMapper.class));
            ReflectionTestUtils.setField(service, "roamHangoutMapper", review.getMapper(RoamHangoutMapper.class));
            ReflectionTestUtils.setField(service, "imConversationMemberMapper", review.getMapper(ImConversationMemberMapper.class));
            BusinessAuditEventMapper events = mock(BusinessAuditEventMapper.class);
            when(events.insertBusinessAuditEvent(any())).thenReturn(1);
            ReflectionTestUtils.setField(service, "businessAuditEventMapper", events);
            ReflectionTestUtils.setField(service, "imServiceProvider", provider(im));
            ReflectionTestUtils.setField(service, "roamHangoutServiceProvider", provider(hangouts(review, im)));
            try (Statement st = review.getConnection().createStatement()) {
                st.execute("update audit_task set source_type='report',submitter_id=9 where id=40");
            }
            review.clearCache();
            AuditReviewDTO request = new AuditReviewDTO();
            request.setStatus(2); request.setReasonText("核实后的公开原因"); request.setRequestId("rr-member-40");
            assertEquals(1, service.review(40L, 100L, request));
            Set<Long> recipients = new LinkedHashSet<>();
            mockingDetails(im).getInvocations().stream().filter(i -> i.getMethod().getName().equals("sendSystem"))
                    .forEach(i -> recipients.add(i.getArgument(0)));
            assertEquals(2, review.getMapper(RoamHangoutMapper.class).selectByIdForUpdate(88L).getStatus());
            assertEquals(1, review.getMapper(ImConversationMemberMapper.class).selectByConvAndMember(500L, 7L).getDeleted(),
                    "关闭确实包含新成员");
            assertEquals(new LinkedHashSet<>(Arrays.asList(9L, 4L, 5L, 7L)), recipients,
                    "已被关群的新成员必须收到通知，其他会话成员不得收到");
            review.rollback();
        }
    }

    private RoamHangoutServiceImpl hangouts(SqlSession session, IImService im) {
        RoamHangoutServiceImpl service = new RoamHangoutServiceImpl();
        ReflectionTestUtils.setField(service, "roamHangoutMapper", session.getMapper(RoamHangoutMapper.class));
        ReflectionTestUtils.setField(service, "imService", im);
        return service;
    }

    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(value);
        return provider;
    }

    @Test
    void reporterTaskLockWaitsAndReadsCommittedDecision() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch reading = new CountDownLatch(1);
        try (SqlSession review = factory.openSession(false)) {
            assertEquals(1, review.getMapper(AuditTaskMapper.class).updateAuditTaskReviewCas(40L, 0, 2, 100L, 0, new java.util.Date()));
            Future<Integer> report = pool.submit(() -> {
                try (SqlSession s = factory.openSession(false)) {
                    reading.countDown();
                    int status = s.getMapper(AuditTaskMapper.class).selectAuditTaskByIdForUpdate(40L).getStatus();
                    s.rollback(); return status;
                }
            });
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> report.get(100, TimeUnit.MILLISECONDS));
            review.commit(); assertEquals(2, report.get(5, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
}
