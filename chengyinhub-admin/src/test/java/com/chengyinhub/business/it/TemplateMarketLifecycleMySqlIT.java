package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.domain.vo.MemberTemplateVO;
import com.chengyinhub.business.domain.vo.TemplateLibraryVO;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.sql.Connection;
import java.util.Collections;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 MySQL + Mapper + Spring 事务：作者修改、审核、撤回不能相互覆盖。 */
class TemplateMarketLifecycleMySqlIT {
    private static PooledDataSource source;
    private static SqlSessionTemplate sql;
    private static DataSourceTransactionManager transactions;
    private JdbcTemplate jdbc;
    private CmsMemberTemplateMapper members;
    private CmsTemplateLibraryMapper libraries;
    private AuditTaskMapper tasks;
    private AuditTaskServiceImpl auditTarget;
    private IAuditTaskService audit;
    private ITemplatePublishService publish;

    @BeforeAll
    static void database() throws Exception {
        source = new PooledDataSource("com.mysql.cj.jdbc.Driver", MybatisItDatabase.url("template_market"),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        Configuration config = new Configuration();
        for (Class<?> type : new Class<?>[]{CmsMemberTemplate.class, CmsTemplateLibrary.class,
                MemberTemplateVO.class, TemplateLibraryVO.class, AuditTask.class, AuditRecord.class,
                BusinessAuditEvent.class}) config.getTypeAliasRegistry().registerAlias(type.getSimpleName(), type);
        config.setEnvironment(new Environment("it", new SpringManagedTransactionFactory(), source));
        for (String name : new String[]{"CmsMemberTemplate", "CmsTemplateLibrary", "AuditTask", "AuditRecord", "BusinessAuditEvent"}) {
            String resource = "mapper/business/" + name + "Mapper.xml";
            try (InputStream input = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
            }
        }
        sql = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        transactions = new DataSourceTransactionManager(source);
        try (Connection connection = source.getConnection()) {
            assertTrue(connection.getMetaData().getDatabaseProductVersion().startsWith("8.0"));
            ItSchema.apply(connection, "it-schema/journey-full-flow-it-schema.sql");
            connection.createStatement().execute("CREATE TABLE cms_template_library LIKE cms_member_template");
            connection.createStatement().execute("ALTER TABLE cms_template_library ADD member_template_id BIGINT, ADD pack_type INT, ADD INDEX idx_member_template(member_template_id)");
            String base = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("../sql/ry_20250522.sql")), java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher menu = java.util.regex.Pattern.compile("(?is)create table sys_menu\\s*\\(.*?;" ).matcher(base);
            assertTrue(menu.find(), "基础 SQL 必须包含 sys_menu 的真实 DDL");
            connection.createStatement().execute(menu.group());
            ItSchema.applyMigrations(connection, "sql/migration_p3_platform_foundation.sql",
                    "sql/migration_audit_open_task_guard_20260721.sql", "sql/migration_business_audit_event_20260823.sql");
            String governance = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("sql/migration_approval_governance_20260828.sql")), java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher alter = java.util.regex.Pattern.compile("(?is)ALTER TABLE (?:audit_record|business_audit_event)\\s+.*?;").matcher(governance);
            int applied = 0;
            while (alter.find()) { connection.createStatement().execute(alter.group()); applied++; }
            assertEquals(2, applied, "审核治理的两条真实 ALTER 必须加载");
        }
    }

    @AfterAll
    static void close() { if (source != null) source.forceCloseAll(); }

    @BeforeEach
    void services() {
        jdbc = new JdbcTemplate(source);
        for (String table : new String[]{"business_audit_event", "audit_record", "audit_task", "cms_template_library", "cms_member_template"})
            jdbc.update("DELETE FROM " + table);
        members = sql.getMapper(CmsMemberTemplateMapper.class);
        libraries = sql.getMapper(CmsTemplateLibraryMapper.class);
        tasks = sql.getMapper(AuditTaskMapper.class);
        auditTarget = new AuditTaskServiceImpl();
        field(auditTarget, "templateMapper", members); field(auditTarget, "libraryMapper", libraries);
        field(auditTarget, "auditTaskMapper", tasks);
        field(auditTarget, "auditRecordMapper", sql.getMapper(AuditRecordMapper.class));
        field(auditTarget, "businessAuditEventMapper", sql.getMapper(BusinessAuditEventMapper.class));
        ObjectProvider<IImService> im = mock(ObjectProvider.class);
        when(im.getObject()).thenReturn(mock(IImService.class)); field(auditTarget, "imServiceProvider", im);
        audit = proxy(auditTarget);
        CmsMemberTemplateServiceImpl memberService = new CmsMemberTemplateServiceImpl();
        field(memberService, "cmsMemberTemplateMapper", members);
        CmsTemplateLibraryServiceImpl libraryService = new CmsTemplateLibraryServiceImpl();
        field(libraryService, "cmsTemplateLibraryMapper", libraries);
        TemplatePublishServiceImpl target = new TemplatePublishServiceImpl();
        field(target, "memberTemplateService", memberService); field(target, "memberTemplateMapper", members);
        field(target, "templateLibraryService", libraryService); field(target, "libraryMapper", libraries);
        field(target, "auditTaskMapper", tasks); field(target, "auditTaskService", audit);
        publish = proxy(target);
    }

    @Test
    void databaseDefaultsAreReviewedAndPersonalApprovalDoesNotCreateMarketEntry() {
        Long id = personal();
        assertEquals(0, members.selectCmsMemberTemplateById(id).getPhotoReview());
        assertEquals(1L, members.selectCmsMemberTemplateById(id).getStatus());
        assertEquals(0, count("cms_template_library"));
        Long task = publish.submitTemplateToMarket(id, 7L, "author");
        assertEquals(0L, projection(id).getPublishStatus());
        audit.review(task, 8L, approval(0));
        assertEquals(1L, projection(id).getPublishStatus());
        assertEquals(1, audit.review(task, 8L, approval(0)), "重试复用不可变审核事件");
        assertEquals(2, count("business_audit_event"));
    }

    @Test
    void immutableEventFailureRollsBackTaskTimelineAndMarketVisibility() {
        Long id = personal();
        Long task = publish.submitTemplateToMarket(id, 7L, "author");
        BusinessAuditEventMapper failing = mock(BusinessAuditEventMapper.class);
        when(failing.insertBusinessAuditEvent(any())).thenThrow(new IllegalStateException("event unavailable"));
        field(auditTarget, "businessAuditEventMapper", failing);
        int records = count("audit_record");
        assertThrows(IllegalStateException.class, () -> audit.review(task, 8L, approval(0)));
        assertEquals(0, tasks.selectAuditTaskById(task).getStatus());
        assertEquals(0, tasks.selectAuditTaskById(task).getVersion());
        assertEquals(0L, projection(id).getPublishStatus());
        assertEquals(records, count("audit_record"));
    }

    @Test
    void authorWithdrawalWinsAgainstWaitingApprovalAndResubmissionNeedsFreshReview() throws Exception {
        Long id = personal();
        Long oldTask = publish.submitTemplateToMarket(id, 7L, "author");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        Future<?>[] pending = new Future<?>[1];
        try {
            new TransactionTemplate(transactions).execute(status -> {
                members.selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(id));
                pending[0] = worker.submit(() -> { started.countDown(); return audit.review(oldTask, 8L, approval(0)); });
                try { assertTrue(started.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new IllegalStateException(e); }
                assertThrows(TimeoutException.class, () -> pending[0].get(150, TimeUnit.MILLISECONDS), "审批必须等待作者持有的真源锁");
                assertTrue(publish.updateTemplateLibraryStatus(id, 0, 7L));
                return null;
            });
            assertThrows(ExecutionException.class, () -> pending[0].get(10, TimeUnit.SECONDS));
            assertEquals(4, tasks.selectAuditTaskById(oldTask).getStatus());
            assertEquals(0L, projection(id).getPublishStatus());
            Long fresh = publish.submitTemplateToMarket(id, 7L, "author");
            assertNotEquals(oldTask, fresh);
            audit.review(fresh, 8L, approval(0));
            assertEquals(1L, projection(id).getPublishStatus());
            CmsMemberTemplate edit = new CmsMemberTemplate(); edit.setId(id); edit.setQuestionAnswer("changed");
            publish.updateAdminTemplate(edit, "author");
            assertEquals(0L, projection(id).getPublishStatus());
            assertEquals(0L, members.selectCmsMemberTemplateById(id).getStatus());
        } finally { worker.shutdownNow(); }
    }

    @Test
    void personalListKeepsOneRowAndSeparatesOwnerMarketState() {
        Long id = personal();
        jdbc.update("UPDATE cms_member_template SET original_template_id=0 WHERE id=?", id);
        Long task = publish.submitTemplateToMarket(id, 7L, "author");
        CmsTemplateLibrary first = projection(id);
        CmsTemplateLibrary duplicate = new CmsTemplateLibrary();
        duplicate.setMemberTemplateId(id); duplicate.setMemberId(7L); duplicate.setDelFlag(0);
        duplicate.setStatus(2L); duplicate.setPublishStatus(0L);
        libraries.insertCmsTemplateLibrary(duplicate);
        MemberTemplateVO query = new MemberTemplateVO(); query.setMemberId(7L);
        java.util.List<MemberTemplateVO> rows = members.selectMemberTemplateList(query);
        assertEquals(1, rows.size(), "重复历史投影不能放大我的玩法列表分页");
        assertEquals(1, rows.get(0).getCheckStatus());
        assertEquals(first.getId(), rows.get(0).getMarketTemplateId());
        assertEquals(0L, rows.get(0).getMarketPublishStatus());
        query.setMemberId(99L);
        assertTrue(members.selectMemberTemplateList(query).isEmpty());
        assertEquals(0, tasks.selectAuditTaskById(task).getStatus());
    }

    private Long personal() {
        CmsMemberTemplate row = new CmsMemberTemplate(); row.setMemberId(7L); row.setTitle("审核测试");
        row.setValidationMethod(0); row.setQuestionAnswer("original");
        assertEquals(1, publish.createAdminTemplate(row, "author"));
        AuditTask query = new AuditTask(); query.setBizType(AuditTask.BIZ_TEMPLATE); query.setBizId(row.getId());
        audit.review(tasks.selectAuditTaskList(query).get(0).getId(), 8L, approval(0));
        return row.getId();
    }
    private CmsTemplateLibrary projection(Long id) {
        return libraries.selectByMemberTemplateForUpdate(id).get(0);
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private static AuditReviewDTO approval(int version) {
        AuditReviewDTO value = new AuditReviewDTO(); value.setStatus(AuditTask.STATUS_APPROVED);
        value.setExpectedVersion(version); return value;
    }
    private static void field(Object target, String name, Object value) { ReflectionTestUtils.setField(target, name, value); }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Object target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
}
