package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.compliance.ComplianceFeatureSwitches;
import com.chengyinhub.framework.web.service.PermissionService;
import com.chengyinhub.web.controller.business.UserInvoiceProcessingController;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserInvoiceProcessingWireTest {
    @Configuration @EnableMethodSecurity static class Security {
        @Bean(name="ss") PermissionService permissions() { return mock(PermissionService.class); }
        @Bean UserInvoiceProcessingService service() { return mock(UserInvoiceProcessingService.class); }
        @Bean ComplianceFeatureSwitches switches() {
            ComplianceFeatureSwitches s=mock(ComplianceFeatureSwitches.class); when(s.userInvoiceOn()).thenReturn(true); return s;
        }
        @Bean UserInvoiceRefundReconciliationJob reconciliation() { return mock(UserInvoiceRefundReconciliationJob.class); }
        @Bean UserInvoiceProcessingController controller(UserInvoiceProcessingService s,ComplianceFeatureSwitches f,UserInvoiceRefundReconciliationJob j) {
            return new UserInvoiceProcessingController(s,f,j) { @Override public Long getUserId() {return 901L;} };
        }
    }
    /** D6:刷新退款事实会暂停申请并写审计,查看权限不够,必须 process。 */
    @Test void refreshRefundFactsRequiresProcessPermissionNotViewPermission() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("operator","unused","ROLE_USER"));
        try(AnnotationConfigApplicationContext c=new AnnotationConfigApplicationContext(Security.class)) {
            PermissionService permissions=c.getBean(PermissionService.class);
            UserInvoiceProcessingController controller=c.getBean(UserInvoiceProcessingController.class);
            UserInvoiceRefundReconciliationJob job=c.getBean(UserInvoiceRefundReconciliationJob.class);
            when(permissions.hasPermi("business:userInvoice:list")).thenReturn(true);
            when(permissions.hasPermi("business:userInvoice:query")).thenReturn(true);
            assertThrows(org.springframework.security.access.AccessDeniedException.class,()->controller.refreshRefundFacts(7L,new MockHttpServletResponse()));
            verifyNoInteractions(job);
            when(permissions.hasPermi("business:userInvoice:process")).thenReturn(true);
            controller.refreshRefundFacts(7L,new MockHttpServletResponse());
            verify(job).refreshApplication(901L,7L);
        } finally {SecurityContextHolder.clearContext();}
    }
    @Test void actualMethodSecurityRequiresDedicatedTaxPermissionNotFinancialListPermission() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("operator","unused","ROLE_USER"));
        try(AnnotationConfigApplicationContext c=new AnnotationConfigApplicationContext(Security.class)) {
            PermissionService permissions=c.getBean(PermissionService.class);
            UserInvoiceProcessingController controller=c.getBean(UserInvoiceProcessingController.class);
            UserInvoiceProcessingService service=c.getBean(UserInvoiceProcessingService.class);
            when(permissions.hasPermi("business:payablebatch:list")).thenReturn(true);
            when(permissions.hasPermi("business:userInvoice:list")).thenReturn(true);
            assertThrows(org.springframework.security.access.AccessDeniedException.class,()->controller.taxNumber(Long.MAX_VALUE,Map.of("requestId","tax"),new MockHttpServletResponse()));
            verifyNoInteractions(service);
            when(permissions.hasPermi("business:userInvoice:taxView")).thenReturn(true);
            controller.taxNumber(Long.MAX_VALUE,Map.of("requestId","tax"),new MockHttpServletResponse());
            verify(service).revealTaxNumber(901L,Long.MAX_VALUE,"tax");
        } finally {SecurityContextHolder.clearContext();}
    }
    @Test void httpBindingPreservesLongVersionTokenActorNoStoreAndSeparateReconciliationRoute() throws Exception {
        UserInvoiceProcessingService service=mock(UserInvoiceProcessingService.class);
        ComplianceFeatureSwitches flags=mock(ComplianceFeatureSwitches.class); when(flags.userInvoiceOn()).thenReturn(true);
        UserInvoiceProcessingController controller=new UserInvoiceProcessingController(service,flags,mock(UserInvoiceRefundReconciliationJob.class)) {
            @Override public Long getUserId() {return 901L;}
        };
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/business/userInvoice/9223372036854775807/commands")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"actor\":903,\"requestId\":\"wire\",\"version\":\"9007199254740993\",\"action\":\"CLAIM\"}"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        verify(service).command(eq(901L),eq(Long.MAX_VALUE),argThat(v->v.version()==9007199254740993L && "wire".equals(v.requestId())));
        clearInvocations(service);
        mvc.perform(post("/business/userInvoice/1/commands").contentType(MediaType.APPLICATION_JSON)
            .content("{\"requestId\":\"wrong-route\",\"version\":0,\"action\":\"RECONCILE\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.errorCode").value("INVOICE_ARGUMENT_INVALID"));
        verifyNoInteractions(service);
        when(flags.userInvoiceOn()).thenReturn(false);
        mvc.perform(post("/business/userInvoice/1/tax-number").contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"tax\"}"))
            .andExpect(jsonPath("$.errorCode").value("INVOICE_FEATURE_OFF"));
        verifyNoInteractions(service);
    }
}
