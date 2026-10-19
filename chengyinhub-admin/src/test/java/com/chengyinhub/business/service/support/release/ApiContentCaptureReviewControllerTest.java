package com.chengyinhub.business.service.support.release;

import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.framework.web.service.PermissionService;
import com.chengyinhub.web.controller.api.ApiContentCaptureReviewController;
import com.chengyinhub.web.controller.business.AuditWorkbenchController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.authorization.method.PreAuthorizeAuthorizationManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP binding/controller + transaction proxy + real mapper SQL. H2 is not MySQL certification. */
class ApiContentCaptureReviewControllerTest extends ContentCaptureReviewServicePathTest {
    private MockMvc mvc;
    private MockMvc workbench;
    private StaticApplicationContext permissions;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void wireHttp() {
        ProxyFactory factory = new ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        mvc = MockMvcBuilders.standaloneSetup(new ApiContentCaptureReviewController((ContentCaptureReviewService) factory.getProxy())).build();
        // Exercise the actual existing review annotation and PermissionService; no reviewer bypass.
        permissions = new StaticApplicationContext();
        permissions.getBeanFactory().registerSingleton("ss", new PermissionService());
        DefaultMethodSecurityExpressionHandler expressions = new DefaultMethodSecurityExpressionHandler();
        expressions.setApplicationContext(permissions);
        PreAuthorizeAuthorizationManager authorization = new PreAuthorizeAuthorizationManager();
        authorization.setExpressionHandler(expressions);
        AuditWorkbenchController controller = new AuditWorkbenchController();
        ProxyFactory auditTx = new ProxyFactory(audit);
        auditTx.setProxyTargetClass(true);
        auditTx.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        ReflectionTestUtils.setField(controller, "auditTaskService", auditTx.getProxy());
        ProxyFactory protectedController = new ProxyFactory(controller);
        protectedController.setProxyTargetClass(true);
        protectedController.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize(authorization));
        workbench = MockMvcBuilders.standaloneSetup(protectedController.getProxy()).setControllerAdvice(new DeniedAdvice()).build();
        login(7);
    }

    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); if (permissions != null) permissions.close(); }

    private void login(long member) {
        AppUser user = new AppUser(); user.setUserId(member);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginAppUser(member, user), null, Collections.emptyList()));
    }
    private void reviewer(String permission) {
        SysUser user = new SysUser(); user.setUserId(90L); user.setRoles(Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(90L, 1L, user, Collections.singleton(permission)), null, Collections.emptyList()));
    }
    private ObjectNode body(String request) {
        ObjectNode b = json.createObjectNode();
        b.put("topicId", 10); b.put("snapshotId", snapshot.getId()); b.put("expectedVersion", 4);
        b.put("expectedHash", snapshot.getContentHash()); b.put("requestId", request); return b;
    }
    private JsonNode call(String action, ObjectNode body, int expected) throws Exception {
        String response = mvc.perform(post("/api/content-capture-review/" + action)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().is(expected)).andExpect(jsonPath("$.code").value(expected))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("server-only-answer"));
        assertFalse(response.contains("snapshotJson"));
        assertFalse(response.contains("currentAssignee"));
        return json.readTree(response);
    }
    private void counts(int tasks, int records) {
        assertEquals(tasks, jdbc.queryForObject("SELECT COUNT(*) FROM audit_task", Integer.class));
        assertEquals(tasks, jdbc.queryForObject("SELECT COUNT(*) FROM content_capture_review_binding", Integer.class));
        assertEquals(records, jdbc.queryForObject("SELECT COUNT(*) FROM audit_record", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_task WHERE biz_type='TOPIC'", Integer.class));
        verifyNoInteractions(publisher);
    }

    @Test void httpSubmitReplayStatusShareDurableExactIdentity() throws Exception {
        JsonNode first = call("submit", body("http-submit"), 200).get("data");
        assertEquals(first, call("submit", body("http-submit"), 200).get("data"));
        assertEquals(first, call("status", body("http-submit"), 200).get("data"));
        assertEquals("PENDING", first.get("status").asText());
        assertEquals(4, first.get("sourceConfigVersion").asInt());
        assertFalse(first.get("candidateReviewed").asBoolean());
        assertFalse(first.get("publicationEligible").asBoolean());
        assertFalse(first.get("completeRelease").asBoolean());
        counts(1, 1);
    }

    @Test void unauthenticatedWrongNamespaceAndInconsistentPrincipalCannotSubmitOrRead() throws Exception {
        SecurityContextHolder.clearContext(); call("submit", body("auth"), 401); call("status", body("auth"), 401);
        reviewer("*:*:*"); call("submit", body("auth"), 401); call("status", body("auth"), 401);
        login(7); ((LoginAppUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).setUserId(8L);
        call("submit", body("auth"), 401); counts(0, 0);
    }

    @Test void crossOwnerAndRevokedContentRoleCannotSubmitReplayOrRead() throws Exception {
        call("submit", body("owner"), 200); login(8);
        call("submit", body("owner"), 409); call("status", body("owner"), 409);
        login(7); jdbc.update("UPDATE product_role SET slot='OPERATE'");
        call("submit", body("owner"), 409); call("status", body("owner"), 409); counts(1, 1);
    }

    @Test void clubOrAmbiguousRoleIsNotMemberContentAuthority() throws Exception {
        jdbc.update("UPDATE product_role SET entity_type='CLUB'"); call("submit", body("club"), 409);
        jdbc.update("UPDATE product_role SET entity_type='MEMBER'");
        jdbc.update("INSERT INTO product_role VALUES(2,10,'CONTENT','MEMBER',7,1)");
        call("submit", body("ambiguous"), 409); counts(0, 0);
    }

    @Test void malformedAndClientPrivilegeFieldsAreRejectedBeforeWriting() throws Exception {
        for (String field : new String[]{"memberId", "submitterId", "reviewerId", "actorRoleCode", "status", "approved", "scope", "publicationEligible", "valid"}) {
            call("submit", body("forged").put(field, "ADMIN"), 400);
        }
        ObjectNode b = body("missing"); b.remove("expectedVersion"); call("submit", b, 400);
        call("submit", body("bad").put("expectedHash", "short"), 400);
        call("submit", body("bad").put("expectedVersion", -1), 400);
        call("submit", body("bad").put("snapshotId", 0), 400);
        call("submit", body("bad").put("requestId", "bad key"), 400); counts(0, 0);
    }

    @Test void numericCoercionAndOverflowAreNotExactCandidateIdentity() throws Exception {
        call("submit", body("coercion").put("expectedVersion", 4.9), 400);
        call("submit", body("coercion").put("expectedVersion", "4"), 400);
        call("submit", body("coercion").put("snapshotId", snapshot.getId() + 0.9), 400);
        call("submit", body("coercion").put("topicId", "10"), 400);
        call("submit", body("coercion").put("requestId", 123), 400);
        call("submit", body("coercion").put("topicId", new java.math.BigInteger("9223372036854775808")), 400);
        counts(0, 0);
    }

    @Test void openTaskCollisionReturnsConflictWithAtomicRollback() throws Exception {
        call("submit", body("open"), 200);
        snapshot.setId(null); snapshot.setRequestId("next-capture");
        run(() -> session.getMapper(com.chengyinhub.business.mapper.ContentCaptureSnapshotMapper.class).insertFrozen(snapshot));
        assertEquals("CONTENT_REVIEW_CONFLICT", call("submit", body("second"), 409).path("errorCode").asText());
        counts(1, 1);
    }

    @Test void timelineWriteFailureReturnsConflictAndRollsBackAllSubmissionRows() throws Exception {
        jdbc.execute("ALTER TABLE audit_record ADD CONSTRAINT no_submit CHECK (action <> 'SUBMIT')");
        call("submit", body("rollback"), 409); counts(0, 0);
    }

    @Test void staleVersionHashSnapshotAndNewKeyConflictsAreStable() throws Exception {
        call("submit", body("exact"), 200);
        for (String action : new String[]{"submit", "status"}) {
            call(action, body("exact").put("expectedVersion", 5), 409);
            call(action, body("exact").put("expectedHash", String.format("%064d", 0)), 409);
            call(action, body("exact").put("snapshotId", snapshot.getId() + 1), 409);
            call(action, body("exact").put("topicId", 11), 409);
            call(action, body("other-key"), 409);
        }
        counts(1, 1);
    }

    @Test void readBeforeSubmissionDoesNotCreateTaskAndDisabledIsUnavailable() throws Exception {
        call("status", body("absent"), 409); counts(0, 0);
        ReflectionTestUtils.setField(service, "enabled", false);
        assertEquals("CONTENT_REVIEW_UNAVAILABLE", call("submit", body("off"), 503).get("errorCode").asText());
        call("status", body("off"), 503); counts(0, 0);
    }

    @Test void schemaFailureIsSanitizedAndDoesNotWrite() throws Exception {
        ContentCaptureReviewSchemaPreflight guard = mock(ContentCaptureReviewSchemaPreflight.class);
        doThrow(new IllegalStateException("private table details")).when(guard).verifyCurrentTransaction();
        ReflectionTestUtils.setField(service, "schemaPreflight", guard);
        assertFalse(call("submit", body("schema"), 503).toString().contains("private table")); counts(0, 0);
    }

    @Test void sameKeyDifferentCandidateCannotReuseReceipt() throws Exception {
        call("submit", body("key"), 200);
        snapshot.setId(null); snapshot.setRequestId("next-capture");
        run(() -> session.getMapper(com.chengyinhub.business.mapper.ContentCaptureSnapshotMapper.class).insertFrozen(snapshot));
        call("submit", body("key"), 409); counts(1, 1);
    }

    @Test void terminalReplayReturnsCurrentDecisionWithoutReopeningOrPublishing() throws Exception {
        long task = call("submit", body("terminal"), 200).path("data").path("auditTaskId").asLong();
        reviewer("business:audit:review");
        workbench.perform(post("/business/audit/review").contentType(MediaType.APPLICATION_JSON)
                .content("{\"taskId\":" + task + ",\"status\":1,\"expectedVersion\":0,\"requestId\":\"decision\",\"actorRoleCode\":\"forged\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        login(7);
        JsonNode result = call("submit", body("terminal"), 200).path("data");
        assertEquals("APPROVED", result.path("status").asText()); assertTrue(result.path("candidateReviewed").asBoolean());
        assertEquals(1, result.path("auditTaskVersion").asInt()); assertFalse(result.path("publicationEligible").asBoolean());
        assertEquals(result, call("status", body("terminal"), 200).path("data")); counts(1, 2);
    }

    @Test void deniedReviewerCannotDecideSubmittedCandidate() throws Exception {
        long task = call("submit", body("denied"), 200).path("data").path("auditTaskId").asLong();
        reviewer("business:audit:list");
        workbench.perform(post("/business/audit/review").contentType(MediaType.APPLICATION_JSON)
                .content("{\"taskId\":" + task + ",\"status\":1,\"expectedVersion\":0,\"actorRoleCode\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
        login(7); assertEquals("PENDING", call("status", body("denied"), 200).path("data").path("status").asText()); counts(1, 1);
    }

    @Test void rejectedCancelledAndRevertedStatesDoNotClaimApprovalOrCreateAnotherTask() throws Exception {
        long task = call("submit", body("states"), 200).path("data").path("auditTaskId").asLong();
        for (int state : new int[]{2, 4, 3, 0}) {
            jdbc.update("UPDATE audit_task SET status=?,version=version+1 WHERE id=?", state, task);
            JsonNode response = call("submit", body("states"), 200).path("data");
            assertFalse(response.path("candidateReviewed").asBoolean()); assertFalse(response.path("publicationEligible").asBoolean());
        }
        counts(1, 1);
    }

    @Test void tamperedApprovalCannotBePromotedBySubmitRetryOrStatus() throws Exception {
        long task = call("submit", body("tampered"), 200).path("data").path("auditTaskId").asLong();
        jdbc.update("UPDATE audit_task SET status=1,version=1,current_assignee=90 WHERE id=?", task);
        call("submit", body("tampered"), 409); call("status", body("tampered"), 409); counts(1, 1);
    }

    @RestControllerAdvice
    static class DeniedAdvice {
        @ExceptionHandler(AccessDeniedException.class)
        ResponseEntity<Void> denied() { return ResponseEntity.status(403).build(); }
    }
}
