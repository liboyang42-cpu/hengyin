package com.chengyinhub.business.service.support.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Independent exact-wire boundary probes, isolated from the author's frozen source. */
class ContentCaptureReviewIndependentBoundaryTest extends ApiContentCaptureReviewControllerTest {
    private String validBody(String key) {
        ObjectNode body = new ObjectMapper().createObjectNode();
        body.put("topicId", 10); body.put("snapshotId", snapshot.getId());
        body.put("expectedVersion", 4); body.put("expectedHash", snapshot.getContentHash());
        body.put("requestId", key);
        return body.toString();
    }
    private void rejects(String action, String body) throws Exception {
        MockMvc mvc = (MockMvc) ReflectionTestUtils.getField(this, "mvc");
        mvc.perform(post("/api/content-capture-review/" + action).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("CONTENT_REVIEW_INVALID"))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_task", Integer.class));
    }
    @Test void rejectsDuplicateIdentityMembersBeforeWriting() throws Exception {
        rejects("submit", validBody("duplicate").replace("\"expectedVersion\":4", "\"expectedVersion\":-1,\"expectedVersion\":4"));
    }
    @Test void rejectsTrailingPrivilegeObjectBeforeWriting() throws Exception {
        rejects("submit", validBody("trailing-object") + " {\"memberId\":8,\"approved\":true}");
    }
    @Test void rejectsTrailingNonJsonBeforeWriting() throws Exception {
        rejects("submit", validBody("trailing-junk") + " garbage");
    }
    @Test void rejectsDuplicateRequestIdentityBeforeWriting() throws Exception {
        rejects("submit", validBody("duplicate-key").replace("\"requestId\":\"duplicate-key\"", "\"requestId\":\"first-key\",\"requestId\":\"duplicate-key\""));
    }
    @Test void statusAlsoRejectsDuplicateAndTrailingWireContent() throws Exception {
        rejects("status", validBody("status-duplicate").replace("\"topicId\":10", "\"topicId\":11,\"topicId\":10"));
        rejects("status", validBody("status-trailing") + " {}");
        rejects("status", validBody("status-junk") + " garbage");
    }
    @Test void commandBodyLimitRejectsExcessWhitespaceBeforeWriting() throws Exception {
        rejects("submit", validBody("large") + new String(new char[4096]).replace('\0', ' '));
        rejects("status", validBody("large") + new String(new char[4096]).replace('\0', ' '));
    }
    @Test void wrongJsonTypesAndMissingFieldsRemainInvalid() throws Exception {
        for (String action : new String[]{"submit", "status"}) {
            for (String raw : new String[]{"null", "[]", "1", "true", "\"text\"", "{}",
                    validBody("shape").replace("\"topicId\":10", "\"topicId\":true"),
                    validBody("shape").replace("\"expectedVersion\":4", "\"expectedVersion\":4e0"),
                    validBody("shape").replace("\"expectedVersion\":4", "\"expectedVersion\":null")}) {
                rejects(action, raw);
            }
        }
    }
    @Test void deepUnknownValueFailsClosedBeforeWriting() throws Exception {
        String nested = new String(new char[1800]).replace('\0', '[') + "0"
                + new String(new char[1800]).replace('\0', ']');
        rejects("submit", validBody("deep").replaceFirst("\\{", "{\"owner\":" + nested + ","));
    }
    @Test void unauthenticatedPrincipalFlagCannotSubmitOrRead() throws Exception {
        Object principal = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, null));
        MockMvc mvc = (MockMvc) ReflectionTestUtils.getField(this, "mvc");
        for (String action : new String[]{"submit", "status"}) {
            mvc.perform(post("/api/content-capture-review/" + action).contentType(MediaType.APPLICATION_JSON)
                    .content(validBody("unauthenticated"))).andExpect(status().isUnauthorized());
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_task", Integer.class));
    }

    @Test void statusMetadataIsAnExactWhitelist() throws Exception {
        MockMvc mvc = (MockMvc) ReflectionTestUtils.getField(this, "mvc");
        String response = mvc.perform(post("/api/content-capture-review/submit")
                .contentType(MediaType.APPLICATION_JSON).content(validBody("whitelist")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode data = new ObjectMapper().readTree(response).path("data");
        java.util.Set<String> fields = new java.util.HashSet<>(); data.fieldNames().forEachRemaining(fields::add);
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("contract", "topicId", "snapshotId",
                "sourceConfigVersion", "contentHash", "requestId", "auditTaskId", "auditTaskVersion", "status",
                "candidateReviewed", "reviewScope", "completeRelease", "publicationEligible")), fields);
    }
    @Test void ownerRevocationCommittedBeforeStatusLockReadBlocksTheResponse() throws Exception {
        MockMvc mvc = (MockMvc) ReflectionTestUtils.getField(this, "mvc");
        String body = validBody("owner-revoked-race");
        mvc.perform(post("/api/content-capture-review/submit").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk());
        org.springframework.security.core.Authentication authentication =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (java.sql.Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.executeQuery("SELECT id FROM cms_topic WHERE id=10 FOR UPDATE").close();
                statement.executeUpdate("UPDATE product_role SET status=0 WHERE id=1");
                java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.Future<Integer> response = pool.submit(() -> {
                    org.springframework.security.core.context.SecurityContextHolder.getContext()
                            .setAuthentication(authentication);
                    try {
                        started.countDown();
                        return mvc.perform(post("/api/content-capture-review/status")
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                                .andReturn().getResponse().getStatus();
                    } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
                });
                assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS));
                connection.commit();
                assertEquals(409, response.get(20, java.util.concurrent.TimeUnit.SECONDS));
            } finally { connection.rollback(); }
        } finally {
            pool.shutdownNow(); assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_task", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_record", Integer.class));
    }

    @Test void stringBodyBindingDoesNotAcceptNonJsonMediaTypes() throws Exception {
        MockMvc mvc = (MockMvc) ReflectionTestUtils.getField(this, "mvc");
        for (String action : new String[]{"submit", "status"}) {
            for (MediaType type : new MediaType[]{MediaType.TEXT_PLAIN, MediaType.APPLICATION_XML}) {
                mvc.perform(post("/api/content-capture-review/" + action).contentType(type)
                        .content(validBody("media-type"))).andExpect(status().isUnsupportedMediaType());
            }
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_task", Integer.class));
    }

}
