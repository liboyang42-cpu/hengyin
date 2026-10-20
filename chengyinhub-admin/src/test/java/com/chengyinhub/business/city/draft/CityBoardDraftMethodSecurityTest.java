package com.chengyinhub.business.city.draft;

import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.business.CityBoardDraftController;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;

/** Real Spring method-security proxy with an explicit isolated permission fixture, not production auth wiring. */
public class CityBoardDraftMethodSecurityTest {
    private static final String PERMISSION = "business:cityBoardDraft:edit";
    @TempDir Path directory;
    private AnnotationConfigApplicationContext context;
    private CountingAuthority authority;
    private CityBoardDraftService service;

    @Configuration
    @EnableGlobalMethodSecurity(prePostEnabled = true, proxyTargetClass = true)
    static class SecurityConfig {
        @Bean(name = "ss") public TestPermissions permissions() { return new TestPermissions(); }
    }
    /** Only this test context supplies the ss fixture; application PermissionService is unchanged. */
    public static final class TestPermissions {
        public boolean hasPermi(String permission) {
            assertEquals(PERMISSION, permission);
            Authentication actor = SecurityContextHolder.getContext().getAuthentication();
            return actor != null && actor.isAuthenticated()
                    && actor.getAuthorities().contains(new SimpleGrantedAuthority(permission));
        }
    }
    static final class CountingAuthority extends CityBoardDraftPersistenceTest.TestAuthority {
        int calls;
        @Override public CityBoardDraftAuthority.Context requireCurrentPlatformEditor() {
            calls++;
            return super.requireCurrentPlatformEditor();
        }
    }
    @BeforeEach void setup() throws Exception {
        org.h2.jdbcx.JdbcDataSource source = CityBoardDraftPersistenceTest.database(directory.resolve("security").toString());
        CityBoardDraftPersistenceTest.initialize(source);
        authority = new CountingAuthority();
        service = CityBoardDraftPersistenceTest.service(source, authority);
    }
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
    }
    private CityBoardDraftController open(String enabled, boolean provideService, boolean disabledService) {
        context = new AnnotationConfigApplicationContext();
        if (enabled != null) context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "isolated-draft-test", Collections.singletonMap("questify.city-board-drafts.enabled", enabled)));
        context.register(SecurityConfig.class, CityBoardDraftController.class);
        if (provideService) context.registerBean(CityBoardDraftService.class,
                () -> disabledService ? new CityBoardDraftService() : service);
        context.refresh();
        if (!context.containsBeanDefinition("cityBoardDraftController")) return null;
        CityBoardDraftController controller = context.getBean(CityBoardDraftController.class);
        assertTrue(AopUtils.isCglibProxy(controller), "Endpoint methods must execute through a real class proxy");
        return controller;
    }
    private void actor(String permission) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "isolated-platform-test", null, Collections.singleton(new SimpleGrantedAuthority(permission))));
    }
    private List<Supplier<AjaxResult>> allEndpoints(CityBoardDraftController controller) {
        Map<String, Object> command = CityBoardDraftPersistenceTest.command("security-create", 0, null);
        return Arrays.asList(controller::catalog, () -> controller.reopen("test-board"),
                () -> controller.validate(command), () -> controller.save(command),
                () -> controller.preview(Collections.emptyMap()));
    }
    @Test void absentFlagDoesNotRegisterController() {
        assertNull(open(null, true, false));
        assertTrue(context.getBeansOfType(CityBoardDraftController.class).isEmpty());
        assertEquals(0, authority.calls);
    }
    @Test void explicitFalseDoesNotRegisterController() {
        assertNull(open("false", true, false));
        assertTrue(context.getBeansOfType(CityBoardDraftController.class).isEmpty());
    }
    @Test void enablingFlagAloneCannotSupplyServiceOrAuthority() {
        assertThrows(BeansException.class, () -> open("true", false, false));
        assertEquals(0, authority.calls);
    }
    @Test void unrelatedPermissionCannotReachAnyServiceEntry() {
        CityBoardDraftController controller = open("true", true, false);
        actor("business:audit:list");
        for (Supplier<AjaxResult> endpoint : allEndpoints(controller))
            assertThrows(AccessDeniedException.class, endpoint::get);
        assertEquals(0, authority.calls, "Denied requests must not enter the real service");
    }
    @Test void missingAuthenticationCannotReachAnyServiceEntry() {
        CityBoardDraftController controller = open("true", true, false);
        SecurityContextHolder.clearContext();
        for (Supplier<AjaxResult> endpoint : allEndpoints(controller))
            assertThrows(AuthenticationCredentialsNotFoundException.class, endpoint::get);
        assertEquals(0, authority.calls);
    }
    @Test void cachedPermissionDoesNotOverrideFreshAuthorityDenial() {
        CityBoardDraftController controller = open("true", true, false);
        actor(PERMISSION);
        authority.context = new CityBoardDraftAuthority.Context("revoked", Collections.singleton("test-region"), false);
        for (Supplier<AjaxResult> endpoint : allEndpoints(controller)) {
            AjaxResult result = endpoint.get();
            assertEquals(403, result.get("code"));
            assertEquals("PLATFORM_EDITOR_REQUIRED", result.get("errorCode"));
        }
        assertEquals(5, authority.calls);
    }
    @Test void allowedPermissionDelegatesAllFiveMethodsToTheRealService() {
        CityBoardDraftController controller = open("true", true, false);
        actor(PERMISSION);
        assertEquals(200, controller.catalog().get("code"));
        Map<String, Object> command = CityBoardDraftPersistenceTest.command("security-create", 0, null);
        assertEquals(200, controller.validate(command).get("code"));
        AjaxResult saved = controller.save(command);
        assertEquals(200, saved.get("code"));
        assertEquals(200, controller.reopen("test-board").get("code"));
        @SuppressWarnings("unchecked") Map<String, Object> snapshot = (Map<String, Object>) saved.get("data");
        Map<String, Object> preview = CityBoardDraftService.object("draftId", "test-board",
                "expectedVersion", snapshot.get("version"), "expectedHash", snapshot.get("payloadHash"));
        assertEquals(200, controller.preview(preview).get("code"));
        assertEquals(5, authority.calls);
    }
    @Test void permissionRevocationStopsSubsequentCallsBeforeService() {
        CityBoardDraftController controller = open("true", true, false);
        actor(PERMISSION);
        assertEquals(200, controller.catalog().get("code"));
        assertEquals(1, authority.calls);
        actor("business:audit:list");
        for (Supplier<AjaxResult> endpoint : allEndpoints(controller))
            assertThrows(AccessDeniedException.class, endpoint::get);
        assertEquals(1, authority.calls);
    }
    @Test void controllerPermissionCannotEnableDisabledService() {
        CityBoardDraftController controller = open("true", true, true);
        actor(PERMISSION);
        for (Supplier<AjaxResult> endpoint : allEndpoints(controller)) {
            AjaxResult result = endpoint.get();
            assertEquals(503, result.get("code"));
            assertEquals("CITY_DRAFT_DISABLED", result.get("errorCode"));
        }
        assertEquals(0, authority.calls);
    }
}
