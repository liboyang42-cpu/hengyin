package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.service.IClubEditionCompensationService;
import com.chengyinhub.business.service.ITopicEditionFinalizationService;
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

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** §5.5-5：FINAL、批次确认、付款登记三种权限互不代替，且 FINAL 要对象级 scope。 */
class TopicEditionFinalizationControllerTest {
    private static final long TOPIC = 23L;

    private AnnotationConfigApplicationContext context;
    private ITopicEditionFinalizationService finalizationService;
    private IClubEditionCompensationService compensationService;
    private ITopicOperationsScopeAuthorizer scopeAuthorizer;
    private TopicEditionFinalizationController controller;

    @BeforeEach
    void setUp() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        context = new AnnotationConfigApplicationContext(MethodSecurityTestConfig.class);
        finalizationService = context.getBean(ITopicEditionFinalizationService.class);
        compensationService = context.getBean(IClubEditionCompensationService.class);
        scopeAuthorizer = context.getBean(ITopicOperationsScopeAuthorizer.class);
        controller = context.getBean(TopicEditionFinalizationController.class);
        when(scopeAuthorizer.canEditTopic(TOPIC)).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        context.close();
    }

    @Test
    void batchPermissionsCannotExecuteFinal() {
        loginWith("business:clubpayable:confirm");
        assertThrows(AccessDeniedException.class, () -> controller.finalizeEdition(TOPIC));

        loginWith("business:clubpayable:paid");
        assertThrows(AccessDeniedException.class, () -> controller.finalizeEdition(TOPIC));

        verify(finalizationService, never()).finalizeEdition(anyLong(), any(), anyLong());
    }

    @Test
    void confirmingABatchAndRecordingPaymentAreTwoDifferentPermissions() {
        loginWith("business:clubpayable:confirm");
        assertThrows(AccessDeniedException.class, () -> controller.markBatchPaid(TOPIC, 3L, paidBody()));

        loginWith("business:clubpayable:paid");
        assertThrows(AccessDeniedException.class, () -> controller.confirmBatch(TOPIC, 3L));

        verify(finalizationService, never()).markBatchPaid(anyLong(), anyLong(), anyLong(), anyString());
        verify(finalizationService, never()).confirmBatch(anyLong(), anyLong(), anyLong());
    }

    @Test
    void finalRequiresObjectScopeOnTopOfThePermission() {
        when(scopeAuthorizer.canEditTopic(TOPIC)).thenReturn(false);
        loginWith("business:editionfinal:execute");

        assertEquals(500, controller.finalizeEdition(TOPIC).get(AjaxResult.CODE_TAG));

        verify(finalizationService, never()).finalizeEdition(anyLong(), any(), anyLong());
    }

    @Test
    void eachPermissionDrivesItsOwnActionWithTheLoggedInOperator() {
        loginWith("business:editionfinal:execute");
        assertEquals(200, controller.finalizeEdition(TOPIC).get(AjaxResult.CODE_TAG));
        verify(finalizationService).finalizeEdition(eq(TOPIC), any(), eq(99L));

        loginWith("business:clubpayable:confirm");
        assertEquals(200, controller.confirmBatch(TOPIC, 3L).get(AjaxResult.CODE_TAG));
        verify(finalizationService).confirmBatch(TOPIC, 3L, 99L);

        loginWith("business:clubpayable:paid");
        assertEquals(200, controller.markBatchPaid(TOPIC, 3L, paidBody()).get(AjaxResult.CODE_TAG));
        verify(finalizationService).markBatchPaid(TOPIC, 3L, 99L, "TX-1");
    }

    /**
     * ★只读操作面对三种资金域权限都可达，且拿的是路径上那一期。
     *
     * <p>不可达的话，真正要动钱的 cy_finance / cy_operator 依旧只能连生产库猜 payableId / batchId。
     * 但它仍然是资金域权限专属：拿一个 business:topic:query 是读不到应付明细的。
     */
    @Test
    void theLedgerViewIsReadableByEveryFundsPermissionButNotByPlainTopicQuery() {
        when(scopeAuthorizer.canEditTopic(TOPIC)).thenReturn(false);

        for (String permission : new String[]{"business:editionfinal:execute",
                "business:clubpayable:confirm", "business:clubpayable:paid"}) {
            loginWith(permission);
            assertEquals(200, controller.editionLedger(TOPIC).get(AjaxResult.CODE_TAG), permission);
        }
        verify(finalizationService, times(3)).editionLedgerView(TOPIC);
        verify(compensationService, times(3)).compensationView(TOPIC);

        loginWith("business:topic:query");
        assertThrows(AccessDeniedException.class, () -> controller.editionLedger(TOPIC));
    }

    /**
     * ★建批次也必须对<b>非超管</b>可达，理由与下面四个批次动作完全相同。
     *
     * <p>{@code business:clubpayable:confirm} 只授给了 cy_finance（同一份迁移还把它从 cy_operator
     * 上窄范围删掉）。后台那个「建付款批次(本期)」按钮挂的正是这个权限 ⇒ 它对 cy_finance 可见，
     * 而端点上的 {@code canEditTopic} 恒等于 {@code SecurityUtils.isAdmin} ⇒ 恒 500 ——
     * 一颗看得见点不动的死按钮，而且是同一次收口摘了四个、漏了第五个留下的不一致。
     *
     * <p>归属由 service 侧双重承重、且比 scope 更严：{@code selectUnbatchedPayables} 的
     * {@code where topic_id / club_id} 谓词 + {@code createBatch} 对每一行的
     * {@code CLUB_PAYABLE_EDITION_LEAK} / {@code CLUB_PAYABLE_SCOPE_LEAK} 逐行回读。
     */
    @Test
    void creatingABatchIsReachableByTheFinanceRoleThatOwnsTheConfirmPermission() {
        when(scopeAuthorizer.canEditTopic(TOPIC)).thenReturn(false);
        loginWith("business:clubpayable:confirm");

        assertEquals(200, controller.createBatch(TOPIC, clubBody()).get(AjaxResult.CODE_TAG));

        verify(finalizationService).createBatch(TOPIC, 7L, 99L);
    }

    /** 负控：摘掉 scope 闸不等于谁都能建批次，专用权限仍然是唯一的授权。 */
    @Test
    void creatingABatchStillNeedsItsOwnPermission() {
        loginWith("business:clubpayable:paid");
        assertThrows(AccessDeniedException.class, () -> controller.createBatch(TOPIC, clubBody()));

        loginWith("business:topic:edit");
        assertThrows(AccessDeniedException.class, () -> controller.createBatch(TOPIC, clubBody()));

        verify(finalizationService, never()).createBatch(anyLong(), anyLong(), anyLong());
    }

    /**
     * ★四个批次动作必须对<b>非超管</b>可达，否则「确认人 ≠ 付款登记人」在代码上恒不可满足。
     *
     * <p>{@code TopicOperationsScopeAuthorizer.canEditTopic} 恒等于 {@code SecurityUtils.isAdmin}
     * （userId == 1）。迁移把 {@code clubpayable:confirm} 授给 cy_finance、{@code :paid} 授给
     * cy_operator，可这两个角色的 {@code sys_user.user_id} 必然 ≠ 1 ⇒ 两人都进不了端点 ⇒
     * {@code confirmed_by} 与 {@code paid_by} 恒等于 1 ⇒ Java 分离闸、{@code markBatchPaidCas}
     * 的 {@code confirmed_by <> #{paidBy}}、DB {@code ck_club_payable_batch_separation} 三层
     * 同时恒不可满足 ⇒ <b>没有任何一行 club_payable_batch 能进入 PAID</b>。
     *
     * <p>所以这四个动作的授权改由各自的专用权限承重，对象归属由 service 侧
     * {@code batch.topic_id == 路径 topicId} 的回读承重（见
     * {@code TopicEditionFinalizationServiceTest#aBatchBelongingToAnotherEditionIsRefusedByAllFourBatchActions}）。
     * ⚠️ 这不放宽 {@code TopicOperationsScopeAuthorizer} 本身 —— FINAL / 红冲 / 重计提 / 建批次
     * 五个端点的 scope 闸一个都没动。
     */
    @Test
    void theFourBatchActionsAreReachableWithoutGlobalTopicScopeSoTheTwoRolesCanActuallySign() {
        when(scopeAuthorizer.canEditTopic(TOPIC)).thenReturn(false);

        loginWith("business:clubpayable:confirm");
        assertEquals(200, controller.confirmBatch(TOPIC, 3L).get(AjaxResult.CODE_TAG));
        assertEquals(200, controller.markInvoiceIssued(TOPIC, 3L, invoiceBody()).get(AjaxResult.CODE_TAG));
        assertEquals(200, controller.markInvoiceRed(TOPIC, 3L, redInvoiceBody()).get(AjaxResult.CODE_TAG));

        loginWith("business:clubpayable:paid");
        assertEquals(200, controller.markBatchPaid(TOPIC, 3L, paidBody()).get(AjaxResult.CODE_TAG));

        verify(finalizationService).confirmBatch(TOPIC, 3L, 99L);
        verify(finalizationService).markBatchPaid(TOPIC, 3L, 99L, "TX-1");
        verify(finalizationService).markBatchInvoiceIssued(TOPIC, 3L, "INV-001", 99L);
        verify(finalizationService).markBatchInvoiceRed(TOPIC, 3L, "RED-001", 99L);
    }

    /** 建批次带上 topicId 传给 service：一期一批次。 */
    @Test
    void creatingABatchPassesTheEditionThroughToTheService() {
        loginWith("business:clubpayable:confirm");

        assertEquals(200, controller.createBatch(TOPIC, clubBody()).get(AjaxResult.CODE_TAG));

        verify(finalizationService).createBatch(TOPIC, 7L, 99L);
    }

    /** 重计提走 FINAL 权限，不是批次权限：它写的是资金行。 */
    @Test
    void reaccrualNeedsTheFinalPermissionAndTheObjectScope() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("reason", "定责晚到");

        loginWith("business:clubpayable:confirm");
        assertThrows(AccessDeniedException.class, () -> controller.reaccrue(TOPIC, body));

        loginWith("business:editionfinal:execute");
        when(scopeAuthorizer.canEditTopic(TOPIC)).thenReturn(false);
        assertEquals(500, controller.reaccrue(TOPIC, body).get(AjaxResult.CODE_TAG));

        verify(finalizationService, never()).reaccrueAfterReversal(anyLong(), any(), anyLong(), anyString());
    }

    private static Map<String, Object> clubBody() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("clubId", 7L);
        return body;
    }

    private static Map<String, Object> invoiceBody() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("invoiceNo", "INV-001");
        return body;
    }

    private static Map<String, Object> redInvoiceBody() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("redInvoiceNo", "RED-001");
        return body;
    }

    @Test
    void reversingAPayableNeedsTheFinalPermissionNotAnyBatchPermission() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("reason", "复核后撤销");

        loginWith("business:clubpayable:confirm");
        assertThrows(AccessDeniedException.class, () -> controller.reversePayable(TOPIC, 5L, body));
        loginWith("business:clubpayable:paid");
        assertThrows(AccessDeniedException.class, () -> controller.reversePayable(TOPIC, 5L, body));

        verify(finalizationService, never()).reversePayable(anyLong(), anyLong(), anyLong(), anyString());
    }

    /**
     * ★撤销写的是一条负向资金行，必须和 FINAL 一样先过对象级 scope。
     * 此前它只有权限位、没有 scope，拿一个裸 payableId 就能动钱。
     */
    @Test
    void reversingAPayableIsScopeCheckedBeforeItReachesTheService() {
        when(scopeAuthorizer.canEditTopic(TOPIC)).thenReturn(false);
        loginWith("business:editionfinal:execute");
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("reason", "复核后撤销");

        assertEquals(500, controller.reversePayable(TOPIC, 5L, body).get(AjaxResult.CODE_TAG));

        verify(finalizationService, never()).reversePayable(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void reversingAPayableInScopePassesTheTopicDownToTheService() {
        loginWith("business:editionfinal:execute");
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("reason", "复核后撤销");

        assertEquals(200, controller.reversePayable(TOPIC, 5L, body).get(AjaxResult.CODE_TAG));

        verify(finalizationService).reversePayable(TOPIC, 5L, 99L, "复核后撤销");
    }

    private static Map<String, Object> paidBody() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("paymentReference", "TX-1");
        return body;
    }

    private static void loginWith(String permission) {
        SysUser user = new SysUser();
        user.setUserName("edition-final-security-test");
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
        ITopicEditionFinalizationService finalizationService() {
            return mock(ITopicEditionFinalizationService.class);
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
        TopicEditionFinalizationController finalizationController(ITopicEditionFinalizationService service,
                                                                  IClubEditionCompensationService compensation,
                                                                  ITopicOperationsScopeAuthorizer authorizer) {
            return new TopicEditionFinalizationController(service, compensation, authorizer);
        }
    }
}
