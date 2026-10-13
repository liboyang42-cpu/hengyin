package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.impl.AuditTaskServiceImpl;
import com.chengyinhub.business.service.support.CommunityPostAuditSnapshot;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.InputStream;
import java.util.Date;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditTaskMapperCasContractTest {
    @Test
    void reviewAndRevertBothNameExpectedStatusAndVersionInWhereClause() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get("..", "chengyinhub-system", "src", "main",
                "resources", "mapper", "business", "AuditTaskMapper.xml")), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");

        String review = update(xml, "updateAuditTaskReviewCas");
        String revert = update(xml, "revertAuditTaskDecisionCas");
        assertTrue(review.contains("where id = #{id}")
                && review.contains("and status = #{expectedStatus}")
                && review.contains("and version = #{version}"));
        assertTrue(revert.contains("where id = #{id}")
                && revert.contains("and status = #{expectedStatus}")
                && revert.contains("and version = #{expectedVersion}"));
    }

    @Test
    void communityRefreshUsesRealOwnerSourceStatusAndVersionCas() throws Exception {
        DriverManagerDataSource ds = database(); JdbcTemplate jdbc = new JdbcTemplate(ds);
        AuditTaskMapper mapper = new SqlSessionTemplate(factory(ds)).getMapper(AuditTaskMapper.class);
        assertEquals(0, mapper.refreshCommunitySafetySnapshotCas(19L, 1, 9L, "EDIT_REVIEW", "new", new Date()));
        assertEquals(0, mapper.refreshCommunitySafetySnapshotCas(19L, 2, 10L, "EDIT_REVIEW", "new", new Date()));
        jdbc.update("update audit_task set source_type='report' where id=19");
        assertEquals(0, mapper.refreshCommunitySafetySnapshotCas(19L, 2, 9L, "EDIT_REVIEW", "new", new Date()));
        jdbc.update("update audit_task set source_type='auto_gate',status=1 where id=19");
        assertEquals(0, mapper.refreshCommunitySafetySnapshotCas(19L, 2, 9L, "EDIT_REVIEW", "new", new Date()));
        jdbc.update("update audit_task set status=0,biz_type='TEMPLATE' where id=19");
        assertEquals(0, mapper.refreshCommunitySafetySnapshotCas(19L, 2, 9L, "EDIT_REVIEW", "new", new Date()));
        jdbc.update("update audit_task set biz_type='COMMUNITY_POST' where id=19");
        assertEquals(1, mapper.refreshCommunitySafetySnapshotCas(19L, 2, 9L, "EDIT_REVIEW", "new", new Date()));
        assertEquals(3, mapper.selectAuditTaskById(19L).getVersion());
        assertEquals("new", mapper.selectAuditTaskById(19L).getSnapshotJson());
        assertEquals(0, mapper.refreshCommunitySafetySnapshotCas(19L, 2, 9L, "NORMAL", "stale", new Date()));
    }

    @Test
    void communitySubmissionThroughTransactionalProxyRollsBackSnapshotAndVersionOnRecordFailure() throws Exception {
        DriverManagerDataSource ds = database(); JdbcTemplate jdbc = new JdbcTemplate(ds);
        AuditTaskMapper mapper = new SqlSessionTemplate(factory(ds)).getMapper(AuditTaskMapper.class);
        CommunityPostMapper posts = mock(CommunityPostMapper.class);
        CommunityPost post = new CommunityPost(); post.setId(7L); post.setAuthorId(9L);
        post.setVersion(4); post.setLifecycle("CHECKING"); post.setBody("current edit");
        when(posts.selectByIdForUpdate(7L)).thenReturn(post);
        AuditRecordMapper records = mock(AuditRecordMapper.class);
        when(records.insertAuditRecord(any())).thenReturn(0);
        AuditTaskServiceImpl target = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(target, "auditTaskMapper", mapper);
        ReflectionTestUtils.setField(target, "communityPostMapper", posts);
        ReflectionTestUtils.setField(target, "auditRecordMapper", records);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds), new AnnotationTransactionAttributeSource()));
        IAuditTaskService service = (IAuditTaskService) proxy.getProxy();
        assertThrows(ServiceException.class, () -> service.submitSafetyHold(AuditTask.BIZ_COMMUNITY_POST,
                7L, 9L, "EDIT_REVIEW", CommunityPostAuditSnapshot.capture(post, 4, posts)));
        assertEquals(2, jdbc.queryForObject("select version from audit_task where id=19", Integer.class));
        assertEquals("legacy", jdbc.queryForObject("select snapshot_json from audit_task where id=19", String.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void communityReviewProxyRollsBackTaskRecordAndDomainOnCallbackFailure() throws Exception {
        DriverManagerDataSource ds = database(); JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table decision_record(id int primary key)");
        jdbc.execute("create table post_state(id bigint primary key,lifecycle varchar(16),version int)");
        jdbc.update("insert into post_state values(7,'CHECKING',4)");
        AuditTaskMapper mapper = new SqlSessionTemplate(factory(ds)).getMapper(AuditTaskMapper.class);
        CommunityPostMapper posts = mock(CommunityPostMapper.class);
        CommunityPost post = new CommunityPost(); post.setId(7L); post.setAuthorId(9L);
        post.setVersion(4); post.setLifecycle("CHECKING"); post.setBody("pending");
        when(posts.selectByIdForUpdate(7L)).thenReturn(post);
        jdbc.update("update audit_task set snapshot_json=? where id=19", CommunityPostAuditSnapshot.capture(post, 4, posts));
        AuditRecordMapper records = mock(AuditRecordMapper.class);
        when(records.insertAuditRecord(any())).thenAnswer(inv -> jdbc.update("insert into decision_record values(1)"));
        com.chengyinhub.business.mapper.BusinessAuditEventMapper events = mock(com.chengyinhub.business.mapper.BusinessAuditEventMapper.class);
        com.chengyinhub.business.service.ICommunityPostService callback = mock(com.chengyinhub.business.service.ICommunityPostService.class);
        doAnswer(inv -> {
            jdbc.update("update post_state set lifecycle='PUBLISHED',version=5 where id=7");
            throw new ServiceException("domain write failed", 409);
        }).when(callback).applyAuditDecision(7L, true, 4);
        org.springframework.beans.factory.ObjectProvider<com.chengyinhub.business.service.ICommunityPostService> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getObject()).thenReturn(callback);
        org.springframework.beans.factory.ObjectProvider<com.chengyinhub.business.service.IImService> im =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(im.getObject()).thenReturn(mock(com.chengyinhub.business.service.IImService.class));
        AuditTaskServiceImpl target = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(target, "auditTaskMapper", mapper);
        ReflectionTestUtils.setField(target, "communityPostMapper", posts);
        ReflectionTestUtils.setField(target, "auditRecordMapper", records);
        ReflectionTestUtils.setField(target, "businessAuditEventMapper", events);
        ReflectionTestUtils.setField(target, "communityPostServiceProvider", provider);
        ReflectionTestUtils.setField(target, "imServiceProvider", im);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds), new AnnotationTransactionAttributeSource()));
        IAuditTaskService service = (IAuditTaskService) proxy.getProxy();
        com.chengyinhub.business.domain.dto.AuditReviewDTO request = new com.chengyinhub.business.domain.dto.AuditReviewDTO();
        request.setExpectedVersion(2); request.setStatus(1);
        assertThrows(ServiceException.class, () -> service.review(19L, 1L, request));
        assertEquals(0, jdbc.queryForObject("select status from audit_task where id=19", Integer.class));
        assertEquals(2, jdbc.queryForObject("select version from audit_task where id=19", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from decision_record", Integer.class));
        assertEquals("CHECKING", jdbc.queryForObject("select lifecycle from post_state where id=7", String.class));
        assertEquals(4, jdbc.queryForObject("select version from post_state where id=7", Integer.class));
    }

    private DriverManagerDataSource database() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:audit_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table audit_task(id bigint primary key,biz_type varchar(40),biz_id bigint,submitter_id bigint,"
                + "source_type varchar(40),risk_level varchar(40),status int,current_assignee bigint,submitted_at timestamp,"
                + "deadline_at timestamp,snapshot_json clob,version int,create_by varchar(64),create_time timestamp,"
                + "update_by varchar(64),update_time timestamp,remark varchar(128))");
        jdbc.update("insert into audit_task(id,biz_type,biz_id,submitter_id,source_type,risk_level,status,snapshot_json,version) "
                + "values(19,'COMMUNITY_POST',7,9,'auto_gate','NORMAL',0,'legacy',2)");
        return ds;
    }

    private SqlSessionFactory factory(DriverManagerDataSource ds) throws Exception {
        Configuration config = new Configuration(new Environment("test", new SpringManagedTransactionFactory(), ds));
        config.getTypeAliasRegistry().registerAlias("AuditTask", AuditTask.class);
        config.addMapper(AuditTaskMapper.class);
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("mapper/business/AuditTaskMapper.xml")) {
            new XMLMapperBuilder(input, config, "mapper/business/AuditTaskMapper.xml", config.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(config);
    }

    private String update(String xml, String id) {
        int start = xml.indexOf("<update id=\"" + id + "\">");
        int end = xml.indexOf("</update>", start);
        return start < 0 || end < 0 ? "" : xml.substring(start, end);
    }
}
