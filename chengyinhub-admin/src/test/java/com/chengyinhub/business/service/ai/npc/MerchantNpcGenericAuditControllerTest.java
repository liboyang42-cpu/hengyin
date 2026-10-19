package com.chengyinhub.business.service.ai.npc;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.web.controller.business.AuditWorkbenchController;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
public class MerchantNpcGenericAuditControllerTest {
    private final MerchantNpcGenericAuditBoundaryTest fixture=new MerchantNpcGenericAuditBoundaryTest();
    private static final String BIZ=MerchantNpcKnowledgeDraftService.BIZ_TYPE, SOURCE=MerchantNpcKnowledgeDraftService.SOURCE_TYPE;
    private AuditTask task(String biz,String source,int status) {return fixture.task(biz,source,status);}
    private java.util.List<AuditTask> restricted() {return fixture.restricted();}
    private com.chengyinhub.business.service.impl.AuditTaskServiceImpl service(AuditTaskMapper tasks,AuditRecordMapper records,BusinessAuditEventMapper events) {return fixture.service(tasks,records,events);}

    @org.springframework.context.annotation.Configuration
    @org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity(prePostEnabled=true, proxyTargetClass=true)
    public static class SecurityConfig {}

    @Test void actualPermissionEvaluatorAndMethodSecurityDoNotGrantNpcVisibility() throws Exception {
        AuditTaskMapper tasks=mock(AuditTaskMapper.class);AuditRecordMapper records=mock(AuditRecordMapper.class);BusinessAuditEventMapper events=mock(BusinessAuditEventMapper.class);
        when(tasks.selectAuditTaskById(91L)).thenReturn(task(BIZ,SOURCE,0));
        org.springframework.context.annotation.AnnotationConfigApplicationContext context=new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        context.register(SecurityConfig.class);
        context.getBeanFactory().registerSingleton("ss",new com.chengyinhub.framework.web.service.PermissionService());
        context.getBeanFactory().registerSingleton("auditTaskService",service(tasks,records,events));
        for(java.lang.reflect.Field field:AuditWorkbenchController.class.getDeclaredFields()) {
            if(field.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class) && !"auditTaskService".equals(field.getName()))
                context.getBeanFactory().registerSingleton(field.getName(),mock(field.getType()));
        }
        context.registerBean("auditController",AuditWorkbenchController.class);
        try {
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(new org.springframework.mock.web.MockHttpServletRequest()));
            context.refresh();AuditWorkbenchController c=context.getBean(AuditWorkbenchController.class);
            com.chengyinhub.common.core.domain.model.LoginUser login=new com.chengyinhub.common.core.domain.model.LoginUser();
            com.chengyinhub.common.core.domain.entity.SysUser user=new com.chengyinhub.common.core.domain.entity.SysUser();user.setUserId(700L);login.setUser(user);login.setUserId(700L);
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(login,null,java.util.Collections.emptyList()));
            login.setPermissions(java.util.Collections.emptySet());
            assertThrows(org.springframework.security.access.AccessDeniedException.class,()->c.getInfo(91L));
            verifyNoInteractions(tasks,records,events);
            login.setPermissions(new java.util.HashSet<>(java.util.Arrays.asList("business:audit:list")));
            assertEquals(c.getInfo(404L),c.getInfo(91L));assertEquals(c.detail(404L),c.detail(91L));
            com.chengyinhub.business.domain.dto.AuditReviewDTO review=new com.chengyinhub.business.domain.dto.AuditReviewDTO();review.setTaskId(91L);review.setStatus(1);
            assertThrows(org.springframework.security.access.AccessDeniedException.class,()->c.review(review));
            login.setPermissions(new java.util.HashSet<>(java.util.Arrays.asList("business:audit:list","business:audit:review")));
            assertEquals(403,c.review(review).get("code"));
            com.chengyinhub.business.domain.dto.AuditRevertDTO revert=new com.chengyinhub.business.domain.dto.AuditRevertDTO();revert.setTaskId(91L);revert.setExpectedVersion(2);revert.setReason("reason");revert.setIdempotencyKey("receipt");
            assertEquals(403,c.revert(revert).get("code"));
            verifyNoInteractions(records,events);
        } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();context.close();}
    }

    @Test void controllerWithActualServiceReturnsSameReadResponseAsMissingTask() {
        for(AuditTask t:restricted()) {
            AuditTaskMapper tasks=mock(AuditTaskMapper.class); AuditRecordMapper records=mock(AuditRecordMapper.class);
            BusinessAuditEventMapper events=mock(BusinessAuditEventMapper.class);
            when(tasks.selectAuditTaskById(91L)).thenReturn(t);
            AuditWorkbenchController c=new AuditWorkbenchController();ReflectionTestUtils.setField(c,"auditTaskService",service(tasks,records,events));
            assertEquals(c.getInfo(404L),c.getInfo(91L));assertEquals(c.detail(404L),c.detail(91L));
            verifyNoInteractions(records,events);
        }
    }
}
