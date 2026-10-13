package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.service.IClubEditionCompensationService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.common.core.domain.AjaxResult;
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

import java.math.BigDecimal;
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

/** §5.2-7 / §5.1-5：确认工时、批额外工时、定质量奖励各要专用权限，宽泛的 topic:edit 一个都换不来。 */
class ClubEditionCompensationControllerTest {
    private static final long TOPIC = 23L;
    private static final long CLUB = 7L;

    private AnnotationConfigApplicationContext context;
    private IClubEditionCompensationService compensationService;
    private ITopicOperationsScopeAuthorizer scopeAuthorizer;
    private ClubEditionCompensationController controller;

    @BeforeEach
    void setUp() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        context = new AnnotationConfigApplicationContext(MethodSecurityTestConfig.class);
        compensationService = context.getBean(IClubEditionCompensationService.class);
        scopeAuthorizer = context.getBean(ITopicOperationsScopeAuthorizer.class);
        controller = context.getBean(ClubEditionCompensationController.class);
        when(scopeAuthorizer.canEditTopic(TOPIC)).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        context.close();
    }

    @Test
    void topicEditAloneCannotConfirmActualHours() {
        loginWith("business:topic:edit");

        assertThrows(AccessDeniedException.class, () -> controller.confirmHours(TOPIC, hoursBody()));

        verify(compensationService, never()).confirmActualHours(anyLong(), anyLong(), anyString(), any(), anyLong());
    }

    @Test
    void topicEditAloneCannotApproveExtraHours() {
        loginWith("business:topic:edit");

        assertThrows(AccessDeniedException.class, () -> controller.preapproveExtra(TOPIC, extraBody()));
        assertThrows(AccessDeniedException.class, () -> controller.correctExtra(TOPIC, correctionBody()));

        verify(compensationService, never()).preapproveExtraHours(anyLong(), anyLong(), anyString(), any(),
                any(), anyString(), anyLong());
        verify(compensationService, never()).correctExtraHours(anyLong(), anyLong(), anyLong(), any(),
                anyString(), anyLong());
    }

    @Test
    void topicEditAloneCannotConfirmQuality() {
        loginWith("business:topic:edit");

        assertThrows(AccessDeniedException.class, () -> controller.confirmQuality(TOPIC, qualityBody()));

        verify(compensationService, never()).confirmQualityOnce(anyLong(), anyLong(), anyString(),
                anyBoolean(), anyString(), anyLong());
    }

    @Test
    void theThreeDedicatedPermissionsAreNotInterchangeable() {
        loginWith("business:clubhours:confirm");
        assertThrows(AccessDeniedException.class, () -> controller.preapproveExtra(TOPIC, extraBody()));
        assertThrows(AccessDeniedException.class, () -> controller.confirmQuality(TOPIC, qualityBody()));

        loginWith("business:clubextra:approve");
        assertThrows(AccessDeniedException.class, () -> controller.confirmHours(TOPIC, hoursBody()));
        assertThrows(AccessDeniedException.class, () -> controller.confirmQuality(TOPIC, qualityBody()));

        loginWith("business:clubquality:confirm");
        assertThrows(AccessDeniedException.class, () -> controller.confirmHours(TOPIC, hoursBody()));
        assertThrows(AccessDeniedException.class, () -> controller.preapproveExtra(TOPIC, extraBody()));
    }

    // ⚠️ 2026-08-25：原来这条还拿 confirmHours / preapproveExtra 当样本，断言 200 且
    //   verify(service).confirmActualHours(...) —— 那两条路径已整条退役，
    //   而 service 是 mock，所以断言恒绿，却钉住了**生产上永不可能发生**的行为。
    //   只保留仍然活着的动作做样本;退役那两个由下面的专门契约盯着。
    @Test
    void eachDedicatedPermissionDrivesItsOwnAction() {
        loginWith("business:clubquality:confirm");
        assertEquals(200, controller.confirmQuality(TOPIC, qualityBody()).get(AjaxResult.CODE_TAG));
        verify(compensationService).confirmQualityOnce(TOPIC, CLUB, "DELIVERY_SAFETY", true,
                "现场三项交付逐条核对通过", 99L);
    }

    // ★退役路径即使权限齐全也必须被 service 拒 —— 权限对 ≠ 这条路还活着。
    @Test
    void retiredHoursAndExtraEndpointsAreRefusedEvenWithTheRightPermission() {
        doThrow(new IllegalStateException("EDITION_ACTUAL_HOURS_RETIRED"))
                .when(compensationService).confirmActualHours(anyLong(), anyLong(), anyString(), any(), anyLong());
        doThrow(new IllegalStateException("EDITION_ACTUAL_HOURS_RETIRED"))
                .when(compensationService).preapproveExtraHours(anyLong(), anyLong(), anyString(), any(),
                        any(), anyString(), anyLong());

        loginWith("business:clubhours:confirm");
        assertEquals("EDITION_ACTUAL_HOURS_RETIRED", assertThrows(IllegalStateException.class,
                () -> controller.confirmHours(TOPIC, hoursBody())).getMessage());

        loginWith("business:clubextra:approve");
        assertEquals("EDITION_ACTUAL_HOURS_RETIRED", assertThrows(IllegalStateException.class,
                () -> controller.preapproveExtra(TOPIC, extraBody())).getMessage());
    }

    @Test
    void planningHoursNeedsObjectScopeNotJustThePermission() {
        when(scopeAuthorizer.canEditTopic(TOPIC)).thenReturn(false);
        loginWith("business:topic:edit");

        assertEquals(500, controller.planHours(TOPIC, hoursBody()).get(AjaxResult.CODE_TAG));

        verify(compensationService, never()).planHours(anyLong(), anyLong(), anyString(), any(), anyLong());
    }

    /**
     * 其余四个写端点仍然过对象级 scope：它们的 service 侧只有 {@code requireExecutingClub}
     * 一层（「这个 clubId 是不是本期的执行俱乐部」），没有把<b>操作人</b>钉到某个具体身份上，
     * 所以 scope 这一层还得留着。★与 EXTRA 两个端点的差别就在这里，见下一条。
     */
    @Test
    void theOtherWriteEndpointsAreStillScopeCheckedBeforeTheyReachTheService() {
        when(scopeAuthorizer.canEditTopic(TOPIC)).thenReturn(false);

        loginWith("business:topic:edit");
        assertEquals(500, controller.planHours(TOPIC, hoursBody()).get(AjaxResult.CODE_TAG));
        loginWith("business:clubhours:confirm");
        assertEquals(500, controller.confirmHours(TOPIC, hoursBody()).get(AjaxResult.CODE_TAG));
        loginWith("business:clubquality:confirm");
        assertEquals(500, controller.confirmQuality(TOPIC, qualityBody()).get(AjaxResult.CODE_TAG));
        loginWith("business:clubquality:exclude");
        assertEquals(500, controller.excludeQuality(TOPIC, exclusionBody()).get(AjaxResult.CODE_TAG));

        verifyNoInteractions(compensationService);
    }

    /**
     * ★EXTRA 预批与更正必须对<b>非超管的项目负责人</b>可达。
     *
     * <p>{@code canEditTopic} 恒等于 {@code SecurityUtils.isAdmin}（userId == 1）。开售时
     * {@code ExtraApproverReachability} 只要求冻进去的人持 {@code business:clubextra:approve} ——
     * 一个非超管账号完全过得了那道闸、被冻进不可变的 terms 与 configHash，然后：他自己来点
     * ⇒ 被 {@code canEditTopic} 拒；超管来点 ⇒ 被 {@code requireFrozenApprover} 的
     * {@code EDITION_EXTRA_APPROVER_NOT_FROZEN} 拒。{@code freezeTermsForSale} 幂等读回原件、
     * 全仓没有 terms 更正方法 ⇒ <b>本期 EXTRA 预批与更正永久双向死锁，只能改库</b>。
     *
     * <p>这两个端点的对象归属比 scope 更严，不靠它：service 侧
     * {@code requireExecutingClub}（clubId 必须等于冻结条款里的执行俱乐部）
     * + {@code requireFrozenApprover}（动手人必须<b>就是</b>开售冻的那一个 userId）。
     */
    // ⚠️ 2026-08-25：本条原是「EXTRA 两个端点对非超管的项目负责人可达」,断言 200 +
    //   verify(service).preapproveExtraHours(...)。EXTRA 已整条退役,那两个 200
    //   在生产上永不可能出现;service 是 mock 才让它一直绿着。
    //   反转成:权限与 scope 都放行的情况下,**仍然**被 service 的退役闸拒掉。
    @Test
    void theExtraApprovalEndpointsAreRetiredEvenForTheFrozenProjectLead() {
        when(scopeAuthorizer.canEditTopic(TOPIC)).thenReturn(false);
        loginWith("business:clubextra:approve");
        doThrow(new IllegalStateException("EDITION_ACTUAL_HOURS_RETIRED"))
                .when(compensationService).preapproveExtraHours(anyLong(), anyLong(), anyString(), any(),
                        any(), anyString(), anyLong());
        doThrow(new IllegalStateException("EDITION_ACTUAL_HOURS_RETIRED"))
                .when(compensationService).correctExtraHours(anyLong(), anyLong(), anyLong(), any(),
                        anyString(), anyLong());

        assertEquals("EDITION_ACTUAL_HOURS_RETIRED", assertThrows(IllegalStateException.class,
                () -> controller.preapproveExtra(TOPIC, extraBody())).getMessage());
        assertEquals("EDITION_ACTUAL_HOURS_RETIRED", assertThrows(IllegalStateException.class,
                () -> controller.correctExtra(TOPIC, correctionBody())).getMessage());
    }

    /** 过错排除能推翻一个已确认的 200 ⇒ 必须是独立的专用权限，确认权换不来。 */
    @Test
    void faultExclusionNeedsItsOwnPermissionAndIsNotCoveredByTheConfirmPermission() {
        loginWith("business:clubquality:confirm");
        assertThrows(AccessDeniedException.class, () -> controller.excludeQuality(TOPIC, exclusionBody()));

        loginWith("business:clubquality:exclude");
        assertThrows(AccessDeniedException.class, () -> controller.confirmQuality(TOPIC, qualityBody()));
        assertEquals(200, controller.excludeQuality(TOPIC, exclusionBody()).get(AjaxResult.CODE_TAG));
        verify(compensationService).excludeQualityByFault(TOPIC, CLUB, "DELIVERY_SAFETY", "MERCHANT",
                "商家临时闭店导致该维无法达成", 99L);
    }

    @Test
    void faultExclusionIsAlsoScopeCheckedBeforeItReachesTheService() {
        when(scopeAuthorizer.canEditTopic(TOPIC)).thenReturn(false);
        loginWith("business:clubquality:exclude");

        assertEquals(500, controller.excludeQuality(TOPIC, exclusionBody()).get(AjaxResult.CODE_TAG));

        verifyNoInteractions(compensationService);
    }

    /**
     * JSON 里的字符串 "true" 曾经被 {@code Boolean.TRUE.equals} 静默读成 false。
     * 「一次确认」不可逆 —— 静默判不过 = 该维永久锁死 0 元，必须显式报错而不是默默取 false。
     */
    @Test
    void aNonBooleanPassFlagIsRefusedInsteadOfSilentlyBecomingFalse() {
        loginWith("business:clubquality:confirm");
        Map<String, Object> body = qualityBody();
        body.put("pass", "true");

        assertThrows(IllegalArgumentException.class, () -> controller.confirmQuality(TOPIC, body));

        verify(compensationService, never()).confirmQualityOnce(anyLong(), anyLong(), anyString(),
                anyBoolean(), anyString(), anyLong());
    }

    private static Map<String, Object> exclusionBody() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("clubId", CLUB);
        body.put("dimension", "DELIVERY_SAFETY");
        body.put("faultParty", "MERCHANT");
        body.put("reason", "商家临时闭店导致该维无法达成");
        return body;
    }

    private static Map<String, Object> hoursBody() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("clubId", CLUB);
        body.put("hourKind", "PREP");
        body.put("plannedHours", "4");
        body.put("confirmedActualHours", "5.5");
        return body;
    }

    private static Map<String, Object> extraBody() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("clubId", CLUB);
        body.put("category", "PREP");
        body.put("approvedHours", "3");
        body.put("workStartedAt", 1_700_000_000_000L);
        body.put("idempotencyKey", "idem-1");
        return body;
    }

    private static Map<String, Object> correctionBody() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("clubId", CLUB);
        body.put("correctionOf", 1L);
        body.put("approvedHours", "2");
        body.put("idempotencyKey", "corr-1");
        return body;
    }

    private static Map<String, Object> qualityBody() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("clubId", CLUB);
        body.put("dimension", "DELIVERY_SAFETY");
        body.put("pass", Boolean.TRUE);
        // 理由必填并落 club_quality_decision.reason —— @Log 不写请求体，判据不落列就查不到。
        body.put("reason", "现场三项交付逐条核对通过");
        return body;
    }

    private static void loginWith(String permission) {
        SysUser user = new SysUser();
        user.setUserName("club-compensation-security-test");
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
        IClubEditionCompensationService compensationService() {
            return mock(IClubEditionCompensationService.class);
        }

        @Bean
        ITopicOperationsScopeAuthorizer scopeAuthorizer() {
            return mock(ITopicOperationsScopeAuthorizer.class);
        }

        @Bean
        ClubEditionCompensationController compensationController(IClubEditionCompensationService service,
                                                                 ITopicOperationsScopeAuthorizer authorizer) {
            return new ClubEditionCompensationController(service, authorizer);
        }
    }
}
