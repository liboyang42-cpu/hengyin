package com.chengyinhub.business.service.support.release;

import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.framework.web.service.PermissionService;
import com.chengyinhub.web.controller.business.ContentCapturePreviewController;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Collections;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real method-security proxy; existing engine tests independently prove fresh DB authority. */
class ContentCapturePreviewMethodSecurityTest {
    @Configuration
    @EnableGlobalMethodSecurity(prePostEnabled=true,proxyTargetClass=true)
    static class SecurityConfig {
        @Bean(name="ss") PermissionService permissions(){return new PermissionService();}
    }
    private AnnotationConfigApplicationContext context;
    private ContentCapturePreviewService service;
    private MockMvc mvc;
    private static final String HASH=String.join("",Collections.nCopies(64,"a"));
    @BeforeEach void setup(){
        context=new AnnotationConfigApplicationContext();context.register(SecurityConfig.class);
        service=mock(ContentCapturePreviewService.class);
        context.registerBean(ContentCapturePreviewService.class,()->service);
        context.registerBean(ContentCapturePreviewController.class);context.refresh();
        mvc=MockMvcBuilders.standaloneSetup(context.getBean(ContentCapturePreviewController.class)).build();
    }
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();if(context!=null)context.close();}
    private void actor(String permission){
        SysUser user=new SysUser();user.setUserId(90L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(90L,1L,user,Collections.singleton(permission)),null,Collections.emptyList()));
    }
    private org.springframework.test.web.servlet.ResultActions request() throws Exception {
        return mvc.perform(get("/business/audit/1/candidate-preview").param("expectedTaskVersion","1")
                .param("snapshotId","2").param("sourceConfigVersion","3").param("contentHash",HASH));
    }
    @Test void cachedReadOnlyPermissionCannotEnterPreviewService() throws Exception {
        actor("business:audit:list");request().andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.errorCode").value("CONTENT_PREVIEW_FORBIDDEN"));verifyNoInteractions(service);
    }
    @Test void reviewPermissionStillDelegatesToFreshAuthorityService() throws Exception {
        actor("business:audit:review");when(service.preview(1,1,2,3,HASH)).thenThrow(new ContentCaptureReviewService.UnavailableException());
        request().andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store"));
        verify(service).preview(1,1,2,3,HASH);
    }
    @Test void cachedPermissionNeverOverridesFreshAuthorityDenial() throws Exception {
        actor("business:audit:review");when(service.preview(1,1,2,3,HASH)).thenThrow(new AccessDeniedException("revoked"));
        request().andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("CONTENT_PREVIEW_FORBIDDEN"));
        verify(service).preview(1,1,2,3,HASH);
    }
}
