package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsInfomation;
import com.chengyinhub.business.domain.dto.CmsInfomationWriteDTO;
import com.chengyinhub.business.mapper.CmsInfomationMapper;
import com.chengyinhub.business.service.ICmsInfomationService;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.framework.web.service.PermissionService;
import com.chengyinhub.web.controller.api.ApiCommonController;
import com.chengyinhub.web.controller.business.CmsInfomationController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Stable controller entry -> real method security/transaction proxy -> real MyBatis and H2. */
class CmsInfomationAuditMybatisIntegrationTest {
    private static final String PRIVATE_REASON = "PRIVATE_INFO_AUDIT_7d29";
    private JdbcTemplate db;
    private ICmsInfomationService service;
    private CmsInfomationController controller;
    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:info_audit_" + UUID.randomUUID().toString().replace("-", "")
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        db = new JdbcTemplate(source);
        db.execute("CREATE TABLE cms_infomation(id BIGINT AUTO_INCREMENT PRIMARY KEY, title VARCHAR(100),"
                + " subtitle VARCHAR(160), sort_id BIGINT, contents TEXT, create_by VARCHAR(64),"
                + " create_time DATETIME, update_by VARCHAR(64), update_time DATETIME)");
        db.execute("CREATE TABLE cms_infomation_change_audit(id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + " infomation_id BIGINT NOT NULL, action VARCHAR(6) NOT NULL, operator_user_id BIGINT NOT NULL,"
                + " operator_username VARCHAR(64) NOT NULL, reason VARCHAR(500) NOT NULL, create_time DATETIME NOT NULL)");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliases(CmsInfomation.class);
        factory.setMapperLocations(new ClassPathResource("mapper/business/CmsInfomationMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        CmsInfomationServiceImpl target = new CmsInfomationServiceImpl();
        ReflectionTestUtils.setField(target, "cmsInfomationMapper", session.getMapper(CmsInfomationMapper.class));
        ProxyFactory transactionProxy = new ProxyFactory(target);
        transactionProxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        service = (ICmsInfomationService) transactionProxy.getProxy();
        context = new AnnotationConfigApplicationContext();
        context.register(MethodSecurityConfig.class);
        context.registerBean("ss", PermissionService.class, PermissionService::new);
        context.registerBean(ICmsInfomationService.class, () -> service);
        context.registerBean(CmsInfomationController.class);
        context.refresh();
        controller = context.getBean(CmsInfomationController.class);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        login(42L, Set.of("business:infomation:add", "business:infomation:edit"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        if (context != null) context.close();
        if (db != null) db.execute("SHUTDOWN");
    }

    @Test
    void createPersistsGeneratedTargetAndServerActor() {
        CmsInfomationWriteDTO request = request(null, "  " + PRIVATE_REASON + "  ");
        request.setId(999L);
        controller.add(request);
        Long id = guideId();
        assertNotEquals(999L, id);
        Map<String, Object> audit = onlyAudit();
        assertEquals(id, ((Number) audit.get("infomation_id")).longValue());
        assertEquals("CREATE", audit.get("action"));
        assertEquals(42L, ((Number) audit.get("operator_user_id")).longValue());
        assertEquals("guide-audit-operator", audit.get("operator_username"));
        assertEquals(PRIVATE_REASON, audit.get("reason"));
        assertNotNull(audit.get("create_time"));
        assertEquals("guide-audit-operator", db.queryForObject("SELECT create_by FROM cms_infomation", String.class));
    }

    @Test
    void updateAppendsAuditAndPreservesCreate() {
        controller.add(request(null, "create-reason"));
        Long id = guideId();
        Map<String, Object> original = onlyAudit();
        CmsInfomationWriteDTO edit = request(id, PRIVATE_REASON);
        edit.setSubtitle("after");
        controller.edit(edit);
        List<Map<String, Object>> audits = db.queryForList("SELECT * FROM cms_infomation_change_audit ORDER BY id");
        assertEquals(2, audits.size());
        assertEquals(original, audits.get(0));
        assertEquals("UPDATE", audits.get(1).get("action"));
        assertEquals(PRIVATE_REASON, audits.get(1).get("reason"));
        assertEquals(id, ((Number) audits.get(1).get("infomation_id")).longValue());
        assertEquals(42L, ((Number) audits.get(1).get("operator_user_id")).longValue());
        assertEquals("after", db.queryForObject("SELECT subtitle FROM cms_infomation", String.class));
    }

    @Test
    void auditFailureRollsBackNewGuide() {
        rejectAuditReason();
        assertThrows(RuntimeException.class, () -> controller.add(request(null, "FORCE_AUDIT_FAILURE")));
        assertEquals(0, count("cms_infomation"));
        assertEquals(0, count("cms_infomation_change_audit"));
    }

    @Test
    void auditFailureRollsBackGuideUpdate() {
        controller.add(request(null, "create-reason"));
        Long id = guideId();
        Map<String, Object> originalGuide = db.queryForMap("SELECT * FROM cms_infomation");
        List<Map<String, Object>> originalAudit = db.queryForList("SELECT * FROM cms_infomation_change_audit ORDER BY id");
        rejectAuditReason();
        CmsInfomationWriteDTO edit = request(id, "FORCE_AUDIT_FAILURE");
        edit.setTitle("must rollback");
        assertThrows(RuntimeException.class, () -> controller.edit(edit));
        assertEquals(originalGuide, db.queryForMap("SELECT * FROM cms_infomation"));
        assertEquals(originalAudit, db.queryForList("SELECT * FROM cms_infomation_change_audit ORDER BY id"));
    }

    @Test
    void missingServerActorCannotWrite() {
        login(0L, Set.of("business:infomation:add"));
        assertThrows(RuntimeException.class, () -> controller.add(request(null, PRIVATE_REASON)));
        assertEquals(0, count("cms_infomation"));
        assertEquals(0, count("cms_infomation_change_audit"));
    }

    @Test
    void missingPermissionIsRejectedBeforeWrite() {
        login(42L, Collections.emptySet());
        assertThrows(AccessDeniedException.class, () -> controller.add(request(null, PRIVATE_REASON)));
        CmsInfomationWriteDTO edit = request(1L, PRIVATE_REASON);
        assertThrows(AccessDeniedException.class, () -> controller.edit(edit));
        assertEquals(0, count("cms_infomation"));
        assertEquals(0, count("cms_infomation_change_audit"));
    }

    @Test
    void invalidReasonsRejectedBeforeWrite() {
        for (String reason : new String[] { null, "  ", "x".repeat(501) }) {
            assertThrows(RuntimeException.class, () -> controller.add(request(null, reason)));
            assertThrows(RuntimeException.class, () -> controller.edit(request(1L, reason)));
        }
        assertEquals(0, count("cms_infomation"));
        assertEquals(0, count("cms_infomation_change_audit"));
    }

    @Test
    void missingUpdateTargetDoesNotAppendAudit() {
        assertThrows(RuntimeException.class, () -> controller.edit(request(999L, PRIVATE_REASON)));
        assertEquals(0, count("cms_infomation"));
        assertEquals(0, count("cms_infomation_change_audit"));
    }

    @Test
    void publicListAndDetailNeverExposePrivateReason() throws Exception {
        controller.add(request(null, PRIVATE_REASON));
        ApiCommonController publicController = new ApiCommonController();
        ReflectionTestUtils.setField(publicController, "cmsInfomationService", service);
        ObjectMapper json = new ObjectMapper();
        for (Object result : List.of(publicController.infomationList(), publicController.infomationDetail(guideId().toString()))) {
            String output = json.writeValueAsString(result);
            assertTrue(output.contains("QA guide"));
            assertTrue(output.contains("body"));
            assertFalse(output.contains(PRIVATE_REASON));
            assertFalse(output.contains("auditReason"));
            assertFalse(output.contains("operator_user_id"));
            assertFalse(output.contains("operatorUsername"));
        }
    }

    private void rejectAuditReason() {
        db.execute("ALTER TABLE cms_infomation_change_audit ADD CONSTRAINT reject_test_reason"
                + " CHECK(reason <> 'FORCE_AUDIT_FAILURE')");
    }

    private CmsInfomationWriteDTO request(Long id, String reason) {
        CmsInfomationWriteDTO request = new CmsInfomationWriteDTO();
        request.setId(id);
        request.setTitle("QA guide");
        request.setSubtitle("before");
        request.setSortId(0L);
        request.setContents("<p>body</p>");
        request.setAuditReason(reason);
        return request;
    }

    private Long guideId() {
        return db.queryForObject("SELECT id FROM cms_infomation", Long.class);
    }

    private Map<String, Object> onlyAudit() {
        List<Map<String, Object>> audits = db.queryForList("SELECT * FROM cms_infomation_change_audit ORDER BY id");
        assertEquals(1, audits.size());
        return audits.get(0);
    }

    private int count(String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void login(Long id, Set<String> permissions) {
        SysUser user = new SysUser();
        user.setUserId(id);
        user.setUserName("guide-audit-operator");
        LoginUser login = new LoginUser(id, 1L, user, new HashSet<>(permissions));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(login, null, Collections.emptyList()));
    }

    @Configuration
    @EnableMethodSecurity(prePostEnabled = true)
    static class MethodSecurityConfig { }
}
