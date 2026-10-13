package com.chengyinhub.web.interceptor;

import com.chengyinhub.business.domain.vo.DeregistrationStatusVO;
import com.chengyinhub.business.service.IAccountDeregistrationService;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountDeregistrationInterceptorTest {

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void pendingAccountCanOnlyAccessDeregistrationFlow() throws Exception {
        IAccountDeregistrationService service = mock(IAccountDeregistrationService.class);
        DeregistrationStatusVO status = new DeregistrationStatusVO();
        status.setStatus("PENDING");
        when(service.status(1001L)).thenReturn(status);
        AppUser appUser = new AppUser();
        appUser.setUserId(1001L);
        LoginAppUser loginAppUser = new LoginAppUser(1001L, appUser);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(loginAppUser, null));
        AccountDeregistrationInterceptor interceptor = new AccountDeregistrationInterceptor(service);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getRequestURI()).thenReturn("/api/topic/add");
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        assertFalse(interceptor.preHandle(request, response, new Object()));

        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
    }
}
