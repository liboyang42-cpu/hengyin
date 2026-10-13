package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.ClubCorporatePayeeProfile;
import com.chengyinhub.business.service.IClubCorporatePayeeService;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.framework.web.service.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** A4 §5.4-11：写资料 / 审核 / 受控解密三条路必须各自鉴权，且默认视图只回掩码。 */
class ClubCorporatePayeeControllerTest {
    private static final long CLUB = 8L;

    private AnnotationConfigApplicationContext context;
    private IClubCorporatePayeeService payeeService;
    private ClubCorporatePayeeController controller;

    @BeforeEach
    void setUp() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        context = new AnnotationConfigApplicationContext(MethodSecurityTestConfig.class);
        payeeService = context.getBean(IClubCorporatePayeeService.class);
        controller = context.getBean(ClubCorporatePayeeController.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        context.close();
    }

    @Test
    void plaintextPayoutViewIsRefusedToEveryoneWithoutThePayoutPermission() {
        loginWith("business:club:query");
        assertThrows(AccessDeniedException.class, () -> controller.payoutDetail(CLUB));

        loginWith("business:club:edit");
        assertThrows(AccessDeniedException.class, () -> controller.payoutDetail(CLUB));

        loginWith("business:clubpayee:verify");
        assertThrows(AccessDeniedException.class, () -> controller.payoutDetail(CLUB));

        verify(payeeService, never()).payoutView(anyLong());
    }

    @Test
    void payoutPermissionIsTheOnlyPathToPlaintext() {
        when(payeeService.payoutView(CLUB)).thenReturn(plaintext());
        loginWith("business:clubpayee:payout");

        assertEquals(200, controller.payoutDetail(CLUB).get("code"));

        verify(payeeService).payoutView(CLUB);
    }

    @Test
    void verifyRequiresItsOwnPermissionAndNeitherReadNorWriteScopeGrantsIt() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("pass", Boolean.TRUE);
        body.put("licenseHash", "LICENSE_HASH");

        loginWith("business:club:query");
        assertThrows(AccessDeniedException.class, () -> controller.verify(CLUB, 9L, body));

        loginWith("business:club:edit");
        assertThrows(AccessDeniedException.class, () -> controller.verify(CLUB, 9L, body));

        loginWith("business:clubpayee:payout");
        assertThrows(AccessDeniedException.class, () -> controller.verify(CLUB, 9L, body));

        verify(payeeService, never()).verify(anyLong(), anyLong(), anyLong(), anyBoolean(), anyString());
    }

    @Test
    void verifyPassesTheCurrentlyLoggedInFinanceUserAsTheVerifier() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("pass", Boolean.TRUE);
        body.put("licenseHash", "LICENSE_HASH");
        loginWith("business:clubpayee:verify");

        controller.verify(CLUB, 9L, body);

        verify(payeeService).verify(CLUB, 9L, 99L, true, "LICENSE_HASH");
    }

    @Test
    void writingProfileRequiresClubEditAndAnswersWithMaskedViewOnly() {
        when(payeeService.maskedView(CLUB)).thenReturn(masked());

        loginWith("business:club:query");
        assertThrows(AccessDeniedException.class,
                () -> controller.saveProfile(CLUB, new ClubCorporatePayeeProfile()));
        verify(payeeService, never()).createVersion(anyLong(), any(), anyLong());

        loginWith("business:club:edit");
        Object data = controller.saveProfile(CLUB, new ClubCorporatePayeeProfile()).get("data");

        verify(payeeService).createVersion(eq(CLUB), any(), eq(99L));
        assertEquals("62****22", ((Map<?, ?>) data).get("corporateBankAccountNo"));
    }

    @Test
    void defaultViewRequiresOnlyReadPermissionAndNeverReturnsPlaintext() {
        when(payeeService.maskedView(CLUB)).thenReturn(masked());
        loginWith("business:club:query");

        Object data = controller.view(CLUB).get("data");

        assertEquals("138****1234", ((Map<?, ?>) data).get("financeContactPhone"));
        verify(payeeService, never()).payoutView(anyLong());
    }

    private static Map<String, Object> masked() {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("corporateBankAccountNo", "62****22");
        view.put("financeContactPhone", "138****1234");
        return view;
    }

    private static Map<String, Object> plaintext() {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("corporateBankAccountNo", "6222020200011112222");
        return view;
    }

    private static void loginWith(String permission) {
        SysUser user = new SysUser();
        user.setUserName("club-payee-security-test");
        user.setPassword("ignored");
        LoginUser loginUser = new LoginUser(99L, 1L, user,
                new HashSet<>(Collections.singleton(permission)));
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
        SecurityContextHolder.setContext(securityContext);
    }

    @Configuration
    @EnableMethodSecurity(prePostEnabled = true)
    static class MethodSecurityTestConfig {
        @Bean("ss")
        PermissionService permissionService() {
            return new PermissionService();
        }

        @Bean
        IClubCorporatePayeeService payeeService() {
            return mock(IClubCorporatePayeeService.class);
        }

        @Bean
        ClubCorporatePayeeController payeeController(IClubCorporatePayeeService payeeService) {
            return new ClubCorporatePayeeController(payeeService);
        }
    }
}
