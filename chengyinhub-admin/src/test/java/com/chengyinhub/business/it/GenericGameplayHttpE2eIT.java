package com.chengyinhub.business.it;

import com.chengyinhub.RuoYiApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.chengyinhub.business.service.support.GameplayTemplateRules;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.framework.web.service.AppLoginService;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.net.URL;
import java.net.URLClassLoader;
import javax.tools.ToolProvider;
import java.util.Comparator;
import java.security.Permission;
import java.security.AllPermission;
import java.security.CodeSource;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.Policy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@SpringBootTest(classes = RuoYiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = GenericGameplayHttpE2eIT.LocalInitializer.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GenericGameplayHttpE2eIT {
    @Autowired private TestRestTemplate http;
    @Autowired private ConfigurableApplicationContext context;
    @Autowired private UmsMemberMapper members;
    @Autowired private AppLoginService login;
    @Autowired private JdbcTemplate database;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
    private final com.fasterxml.jackson.databind.node.ArrayNode transcript = json.createArrayNode();
    private final ReviewedMediaFixture mediaFixture = new ReviewedMediaFixture("generic");

    // shortcut: local PNG and offline signatures exclude OSS I/O; use dedicated OSS acceptance for upload/download.
    static final class ReviewedMediaFixture implements AutoCloseable {
        private static final String ENDPOINT = "http://127.0.0.1:9";
        private static final String BUCKET = "offline-http-e2e";
        private static final String ACCESS_ID = "offline-http-e2e-not-a-credential";
        private final String label;
        private Path directory;
        private final java.util.Map<Long, String> byOwner = new java.util.LinkedHashMap<>();
        private final java.util.Map<String, String> byKey = new java.util.LinkedHashMap<>();
        private final java.util.List<Path> files = new java.util.ArrayList<>();
        private final ArrayNode evidence = new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();

        ReviewedMediaFixture(String label) { this.label = label; }

        static void configure(ConfigurableApplicationContext application) {
            String[][] properties = {{"CHENGYIN_OSS_ENDPOINT", ENDPOINT}, {"CHENGYIN_OSS_BUCKET_NAME", BUCKET},
                    {"CHENGYIN_OSS_ACCESS_KEY_ID", ACCESS_ID}, {"CHENGYIN_OSS_ACCESS_KEY_SECRET", "offline-http-e2e-not-a-secret"}};
            for (String[] property : properties) {
                String inherited = System.getenv(property[0]);
                assertTrue(inherited == null || inherited.trim().isEmpty() || property[1].equals(inherited),
                        "HTTP fixture refuses inherited non-fixture OSS settings");
                TestPropertySourceUtils.addInlinedPropertiesToEnvironment(application, property[0] + "=" + property[1]);
            }
        }

        String approvedImage(ConfigurableApplicationContext application, JdbcTemplate database, long memberId) {
            if (byOwner.containsKey(memberId)) return byOwner.get(memberId);
            try {
                if (directory == null) directory = Files.createTempDirectory("http-e2e-media-");
                String key = "upload/" + UUID.randomUUID().toString().replace("-", "") + ".png";
                Path file = directory.resolve(key.substring("upload/".length()));
                java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
                assertTrue(javax.imageio.ImageIO.write(image, "png", file.toFile())); files.add(file);
                byte[] bytes = Files.readAllBytes(file);
                String digest = String.format("%064x", new java.math.BigInteger(1, java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
                assertArrayEquals(bytes, Files.readAllBytes(file));
                assertEquals(2, javax.imageio.ImageIO.read(file.toFile()).getWidth());
                assertEquals(digest, String.format("%064x", new java.math.BigInteger(1,
                        java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))));
                com.chengyinhub.business.service.impl.AppMediaAssetService assets = application.getBean(com.chengyinhub.business.service.impl.AppMediaAssetService.class);
                com.chengyinhub.business.service.IAuditTaskService audits = application.getBean(com.chengyinhub.business.service.IAuditTaskService.class);
                com.chengyinhub.business.service.support.AppMediaPublicationGuard guard = application.getBean(com.chengyinhub.business.service.support.AppMediaPublicationGuard.class);
                java.util.Map<String, Object> staged = assets.stage(memberId, key);
                long id = ((Number) staged.get("assetId")).longValue(); String reference = "media-asset:" + id;
                long taskId = database.queryForObject("SELECT audit_task_id FROM app_media_asset WHERE id=?", Long.class, id);
                int version = database.queryForObject("SELECT version FROM audit_task WHERE id=?", Integer.class, taskId);
                assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM app_media_asset WHERE id=? AND member_id=? AND owner_type='APP'"
                        + " AND storage_type='OSS' AND object_key=? AND media_type='IMAGE' AND review_state='HOLD' AND version=0 AND audit_task_id=?",
                        Integer.class, id, memberId, key, taskId));
                assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM audit_task WHERE id=? AND biz_type='MEDIA_ASSET' AND biz_id=?"
                        + " AND submitter_id=? AND status=0 AND version=? AND JSON_EXTRACT(snapshot_json,'$.assetVersion')=0",
                        Integer.class, taskId, id, memberId, version));
                assertEquals("媒体尚未审核通过，暂不能公开", assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                        () -> guard.requireMedia(memberId, reference, true)).getMessage());
                assertEquals("媒体来源未经审核，请重新上传", assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                        () -> guard.requireMedia(memberId, "https://unreviewed.invalid/foreign.png", true)).getMessage());

                com.chengyinhub.common.core.domain.entity.SysUser operator = new com.chengyinhub.common.core.domain.entity.SysUser();
                operator.setUserId(900000000000L + memberId); operator.setUserName("e2e_media_" + UUID.randomUUID().toString().substring(0, 8));
                operator.setNickName("HTTP媒体夹具审核员"); operator.setStatus("0");
                assertEquals(1, application.getBean(com.chengyinhub.system.service.ISysUserService.class).insertUser(operator));
                assertFalse(operator.isAdmin());
                assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM sys_user WHERE user_id=? AND status='0' AND del_flag='0'", Integer.class, operator.getUserId()));
                com.chengyinhub.common.core.domain.model.LoginUser principal = new com.chengyinhub.common.core.domain.model.LoginUser(
                        operator.getUserId(), null, operator, java.util.Collections.emptySet());
                com.chengyinhub.business.domain.dto.AuditReviewDTO request = new com.chengyinhub.business.domain.dto.AuditReviewDTO();
                request.setExpectedVersion(version); request.setStatus(com.chengyinhub.business.domain.AuditTask.STATUS_APPROVED);
                request.setRequestId("http-media-" + UUID.randomUUID()); request.setActorRoleCode("SYS_USER");
                org.springframework.security.core.context.SecurityContext previous = org.springframework.security.core.context.SecurityContextHolder.getContext();
                try {
                    org.springframework.security.core.context.SecurityContext local = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
                    local.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, null, java.util.Collections.emptyList()));
                    org.springframework.security.core.context.SecurityContextHolder.setContext(local);
                    assertEquals("无媒体审核权限", assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                            () -> audits.review(taskId, operator.getUserId(), request)).getMessage());
                    assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM audit_task WHERE id=? AND status=0 AND version=?", Integer.class, taskId, version));
                    assertEquals("HOLD", database.queryForObject("SELECT review_state FROM app_media_asset WHERE id=?", String.class, id));
                    assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM audit_record WHERE task_id=? AND action='APPROVE'", Integer.class, taskId));
                    assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM business_audit_event WHERE domain_type='PLATFORM' AND domain_id=? AND action='AUDIT_DECISION_APPROVE'", Integer.class, taskId));
                    assertEquals(0, notificationCount(database, memberId, taskId));
                    principal.setPermissions(java.util.Collections.singleton("business:mediaReview:approve"));
                    assertEquals(1, audits.review(taskId, operator.getUserId(), request));
                } finally { org.springframework.security.core.context.SecurityContextHolder.setContext(previous); }
                assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM app_media_asset WHERE id=? AND member_id=? AND owner_type='APP'"
                        + " AND object_key=? AND review_state='APPROVED' AND version=0 AND audit_task_id=?", Integer.class, id, memberId, key, taskId));
                assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM audit_task WHERE id=? AND status=1 AND version=?", Integer.class, taskId, version + 1));
                assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM audit_record WHERE task_id=? AND action='APPROVE' AND operator_id=?", Integer.class, taskId, operator.getUserId()));
                assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM business_audit_event WHERE domain_type='PLATFORM' AND domain_id=?"
                        + " AND action='AUDIT_DECISION_APPROVE' AND actor_type='SYS_USER' AND actor_member_id=? AND request_id=?",
                        Integer.class, taskId, operator.getUserId(), request.getRequestId()));
                assertEquals(1, notificationCount(database, memberId, taskId));
                assertEquals(reference, guard.requireMedia(memberId, reference, true));
                byKey.put(key, reference);
                String signed = assets.approvedUrl(id);
                assertEquals(reference, knownReference(signed));
                assertReadViewCanonicalContract(signed, key, reference);
                byOwner.put(memberId, reference);
                evidence.addObject().put("assetId", id).put("auditTaskId", taskId).put("pngBytes", bytes.length).put("localPngSha256", digest)
                        .put("auditState", "APPROVED").put("ossUploadDownload", "NOT_RUN").put("providerReview", "NOT_RUN");
                return reference;
            } catch (java.io.IOException | java.security.NoSuchAlgorithmException failure) {
                throw new IllegalStateException("Local gameplay media fixture failed", failure);
            }
        }

        private int notificationCount(JdbcTemplate database, long memberId, long taskId) {
            return database.queryForObject("SELECT COUNT(*) FROM im_message WHERE receiver_id=? AND sender_id=0"
                    + " AND JSON_UNQUOTE(JSON_EXTRACT(extra_json,'$.result.taskId'))=?", Integer.class, memberId, String.valueOf(taskId));
        }

        private String knownReference(String value) {
            java.net.URI uri = java.net.URI.create(value);
            assertTrue("http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() == 9
                    && uri.getUserInfo() == null && uri.getFragment() == null, "Offline fixture origin required");
            String prefix = "/" + BUCKET + "/";
            assertTrue(uri.getRawPath().startsWith(prefix), "Offline fixture bucket required");
            String key = uri.getRawPath().substring(prefix.length());
            assertTrue(byKey.containsKey(key), "Only this fixture's known object may be restored");
            java.util.Map<String, String> query = new java.util.HashMap<>();
            assertNotNull(uri.getRawQuery());
            for (String entry : uri.getRawQuery().split("&", -1)) {
                String[] pair = entry.split("=", 2); assertEquals(2, pair.length);
                assertNull(query.put(pair[0], pair[1]), "Duplicate offline signature field");
            }
            assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("Expires", "OSSAccessKeyId", "Signature")), query.keySet());
            assertTrue(ACCESS_ID.equals(query.get("OSSAccessKeyId")) && query.get("Expires").matches("[1-9][0-9]{0,11}"),
                    "Offline public signature shape required");
            long expires = Long.parseLong(query.get("Expires")), now = java.time.Instant.now().getEpochSecond();
            assertTrue(expires >= now + 9L * 365 * 24 * 60 * 60 && expires <= now + 11L * 365 * 24 * 60 * 60,
                    "Short owner/reviewer preview must not become a stable public reference");
            String signature = java.net.URLDecoder.decode(query.get("Signature"), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(20, java.util.Base64.getDecoder().decode(signature).length, "OSS V1 signature shape required");
            assertEquals(query.get("Signature"), java.net.URLEncoder.encode(signature, java.nio.charset.StandardCharsets.UTF_8),
                    "Canonical encoded signature required");
            return byKey.get(key);
        }

        JsonNode canonicalReadView(JsonNode view) {
            JsonNode copy = view.deepCopy();
            if (copy.isTextual() && copy.asText().startsWith(ENDPOINT + "/" + BUCKET + "/"))
                return new com.fasterxml.jackson.databind.node.TextNode(knownReference(copy.asText()));
            if (copy.isArray()) for (int i = 0; i < copy.size(); i++)
                ((ArrayNode) copy).set(i, canonicalReadView(copy.get(i)));
            if (copy.isObject()) copy.fields().forEachRemaining(field ->
                    ((ObjectNode) copy).set(field.getKey(), canonicalReadView(field.getValue())));
            return copy;
        }

        private void assertReadViewCanonicalContract(String first, String key, String reference) {
            com.aliyun.oss.OSSClient signer = new com.aliyun.oss.OSSClient(ENDPOINT, ACCESS_ID, "offline-http-e2e-not-a-secret");
            String second, preview;
            try {
                com.aliyun.oss.model.GeneratePresignedUrlRequest request = new com.aliyun.oss.model.GeneratePresignedUrlRequest(BUCKET, key);
                request.setExpiration(new java.util.Date(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000 + 2000));
                second = signer.generatePresignedUrl(request).toString();
                request.setExpiration(new java.util.Date(System.currentTimeMillis() + 300000));
                preview = signer.generatePresignedUrl(request).toString();
            } finally { signer.shutdown(); }
            assertNotEquals(first, second);
            ObjectNode original = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                    .put("text", "fixture-text").put("scope", "TEAM").put("nodeId", 123).put("quantity", 2).put("image", first);
            original.putArray("order").add("first").add("second");
            ObjectNode snapshot = original.deepCopy(), expected = original.deepCopy().put("image", reference);
            assertEquals(expected, canonicalReadView(original));
            assertEquals(expected, canonicalReadView(original.deepCopy().put("image", second)));
            assertEquals(snapshot, original, "Read view canonicalization must not mutate the response");
            assertNotEquals(expected, canonicalReadView(original.deepCopy().put("text", "different")));
            ObjectNode reordered = original.deepCopy(); reordered.putArray("order").add("second").add("first");
            assertNotEquals(expected, canonicalReadView(reordered));
            String foreign = first.replace(ENDPOINT, "http://127.0.0.2:9");
            assertNotEquals(expected, canonicalReadView(original.deepCopy().put("image", foreign)));
            assertEquals(foreign, canonicalReadView(original.deepCopy().put("image", foreign)).path("image").asText());
            for (String refused : new String[]{first.replace(key, "upload/00000000000000000000000000000000.png"),
                    first + "&Expires=1", first + "&", first.replace("OSSAccessKeyId=" + ACCESS_ID, "OSSAccessKeyId=foreign"),
                    first.replaceFirst("Signature=[^&]+", "Signature=AAAA"), preview})
                assertThrows(AssertionError.class, () -> canonicalReadView(original.deepCopy().put("image", refused)));
        }

        void restoreKnownItemImages(ObjectNode config) {
            for (JsonNode item : config.path("items")) {
                String value = item.path("image").asText();
                if (value.startsWith(ENDPOINT + "/" + BUCKET + "/")) ((ObjectNode) item).put("image", knownReference(value));
            }
        }

        @Override public void close() throws java.io.IOException {
            for (Path file : files) { assertEquals(directory, file.getParent()); Files.delete(file); assertFalse(Files.exists(file)); }
            if (directory != null) { Files.delete(directory); assertFalse(Files.exists(directory)); }
            Files.createDirectories(Paths.get("target/http-e2e"));
            ObjectNode receipt = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("scope", "GAMEPLAY_MEDIA_STATE_FIXTURE_ONLY")
                    .put("localFilesRemoved", true).put("ossUploadDownload", "NOT_RUN").put("providerDeviceAcceptance", "NOT_RUN");
            receipt.set("assets", evidence);
            Files.write(Paths.get("target/http-e2e/media-fixture-" + label + ".json"), receipt.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    static JsonNode requireGameCommandReceipt(JsonNode receipt, String outcome) {
        assertTrue(receipt.isObject(), "Game command receipt required");
        for (String id : new String[]{"receiptId", "activityId"})
            assertTrue(receipt.path(id).isIntegralNumber() && receipt.path(id).canConvertToLong() && receipt.path(id).asLong() > 0, "Positive command receipt identity required");
        assertTrue(receipt.path("requestId").isTextual() && !receipt.path("requestId").asText().trim().isEmpty()
                && receipt.path("requestId").asText().length() <= 64, "Command request identity required");
        assertTrue(receipt.path("action").isTextual() && receipt.path("action").asText().matches("[A-Z][A-Z0-9_]{0,63}"), "Command action required");
        assertEquals(outcome, receipt.path("outcome").asText(), receipt.toString());
        assertTrue(receipt.path("revision").isIntegralNumber() && receipt.path("revision").canConvertToInt() && receipt.path("revision").asInt(-1) >= 0, "Command revision required");
        assertTrue(receipt.path("replayed").isBoolean() && receipt.path("result").isObject(), "Complete command receipt required");
        if ("FAILED".equals(outcome))
            assertTrue(receipt.path("reasonCode").isTextual() && receipt.path("reasonCode").asText().matches("GAME_[A-Z0-9_]+"), "Game failure reason required");
        return receipt;
    }

    static JsonNode appliedResponseData(JsonNode response) {
        assertEquals(200, response.path("code").asInt(), response.toString());
        JsonNode data = response.path("data");
        if (data.isObject() && (data.has("receiptId") || (data.has("requestId") && data.has("action") && data.has("outcome"))))
            requireGameCommandReceipt(data, "APPLIED");
        return data;
    }

    static void rejectedGameCommand(JsonNode response) {
        assertTrue(response.path("code").isIntegralNumber() && response.path("code").canConvertToInt(), "Ajax command response required");
        JsonNode data = response.path("data");
        if (response.path("code").asInt() == 200) requireGameCommandReceipt(data, "FAILED");
        else assertTrue(data.path("reasonCode").isTextual() && data.path("reasonCode").asText().matches("GAME_[A-Z0-9_]+"),
                "A technical error without a game reason is not a verified command rejection");
    }

    static void configureRequestSerialization(TestRestTemplate client, com.fasterxml.jackson.databind.ObjectMapper plainJson) throws java.io.IOException {
        java.util.List<org.springframework.http.converter.HttpMessageConverter<?>> original =
                new java.util.ArrayList<>(client.getRestTemplate().getMessageConverters());
        java.util.List<org.springframework.http.converter.HttpMessageConverter<?>> replacements = new java.util.ArrayList<>();
        java.util.Map<org.springframework.http.converter.json.MappingJackson2HttpMessageConverter,
                com.fasterxml.jackson.databind.ObjectMapper> unchanged = new java.util.IdentityHashMap<>();
        for (org.springframework.http.converter.HttpMessageConverter<?> converter : original) {
            if (converter instanceof org.springframework.http.converter.json.MappingJackson2HttpMessageConverter) {
                org.springframework.http.converter.json.MappingJackson2HttpMessageConverter prior =
                        (org.springframework.http.converter.json.MappingJackson2HttpMessageConverter) converter;
                unchanged.put(prior, prior.getObjectMapper());
                assertNotSame(prior.getObjectMapper(), plainJson);
                org.springframework.http.converter.json.MappingJackson2HttpMessageConverter isolated =
                        new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(plainJson);
                assertNotSame(prior, isolated);
                ObjectNode request = plainJson.createObjectNode().put("image", "media-asset:1");
                request.putArray("imageUrls").add("/profile/lc-owned-1.png").add("media-asset:1");
                org.springframework.mock.http.MockHttpOutputMessage wire = new org.springframework.mock.http.MockHttpOutputMessage();
                isolated.write(request, org.springframework.http.MediaType.APPLICATION_JSON, wire);
                assertEquals(request, plainJson.readTree(wire.getBodyAsBytes()), "Client request media values must reach the wire unchanged");
                replacements.add(isolated);
            } else replacements.add(converter);
        }
        assertFalse(unchanged.isEmpty(), "Expected Jackson2 client converter");
        client.getRestTemplate().setMessageConverters(replacements);
        unchanged.forEach((converter, mapper) -> assertSame(mapper, converter.getObjectMapper(), "Server-shared converter must remain untouched"));
        assertEquals(replacements, client.getRestTemplate().getMessageConverters());
        ObjectNode receipt = plainJson.createObjectNode().put("receiptId", 1).put("activityId", 1).put("requestId", "fixture-contract")
                .put("action", "QUEST_RESOLVE").put("outcome", "APPLIED").put("revision", 1).put("replayed", false);
        receipt.putObject("result");
        ObjectNode response = plainJson.createObjectNode().put("code", 200); response.set("data", receipt);
        assertSame(receipt, appliedResponseData(response));
        assertThrows(AssertionError.class, () -> rejectedGameCommand(response));
        ObjectNode failed = response.deepCopy();
        ((ObjectNode) failed.path("data")).put("outcome", "FAILED").put("reasonCode", "GAME_TASK_COST_UNAVAILABLE");
        rejectedGameCommand(failed);
        assertThrows(AssertionError.class, () -> appliedResponseData(failed));
        ObjectNode incomplete = response.deepCopy(); ((ObjectNode) incomplete.path("data")).remove("receiptId");
        assertThrows(AssertionError.class, () -> appliedResponseData(incomplete));
        assertThrows(AssertionError.class, () -> rejectedGameCommand(plainJson.createObjectNode().put("code", 500)));
        ObjectNode noReceipt = plainJson.createObjectNode().put("code", 200); noReceipt.putObject("data");
        assertThrows(AssertionError.class, () -> rejectedGameCommand(noReceipt));
    }

    static void assertFixtureMapperColumns(Statement statement) throws SQLException {
        try(ResultSet columns=statement.executeQuery("SELECT withdraw_reason, withdraw_required, replacement_of_offer_id FROM merchant_chapter_offer WHERE 1=0")) {
            assertEquals(3,columns.getMetaData().getColumnCount(),"商家供给 Mapper 列必须存在于本地夹具");
        }
        try(ResultSet columns=statement.executeQuery("SELECT declared_by FROM product_role WHERE 1=0")) {
            assertEquals(1,columns.getMetaData().getColumnCount(),"角色声明 Mapper 列必须存在于本地夹具");
        }
        try(ResultSet columns=statement.executeQuery("SELECT replacement_of_offer_id FROM merchant_chapter_application WHERE 1=0")) {
            assertEquals(1,columns.getMetaData().getColumnCount(),"商家补位 Mapper 列必须存在于本地夹具");
        }
    }

    public static class LocalInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext application) {
            assertEquals("127.0.0.1", MybatisItDatabase.host());
            Policy.setPolicy(new Policy() {
                @Override public PermissionCollection getPermissions(CodeSource source) {
                    Permissions permissions = new Permissions();
                    permissions.add(new AllPermission());
                    return permissions;
                }
            });
            System.setSecurityManager(new SecurityManager() {
                @Override public void checkPermission(Permission permission) { }
                @Override public void checkPermission(Permission permission, Object accessContext) { }
                @Override public void checkConnect(String host, int port) {
                    if (!"127.0.0.1".equals(host) && !"localhost".equals(host)
                            && !"::1".equals(host) && !"0:0:0:0:0:0:0:1".equals(host)) {
                        throw new SecurityException("HTTP E2E forbids every non-loopback connection");
                    }
                }
                @Override public void checkConnect(String host, int port, Object accessContext) {
                    checkConnect(host, port);
                }
            });
            String database=System.getenv("CHENGYIN_HTTP_E2E_DATABASE");
            assertNotNull(database,"Run the isolated HTTP script first");
            assertTrue(database.matches("cy_e2e_w1_http_[0-9_]+"),"Private HTTP database required");
            String reportPath=System.getenv("CHENGYIN_HTTP_E2E_SCHEMA_REPORT");
            assertNotNull(reportPath,"Full fresh-schema report required");
            try {
                JsonNode report=new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readAllBytes(Paths.get(reportPath)));
                assertEquals("SUCCEEDED",report.path("mode").asText());assertEquals(database,report.path("database").asText());
                assertTrue(report.path("files").size()>100);assertEquals(report.path("files").size(),report.path("completed").size());
            } catch(java.io.IOException failure) {throw new IllegalStateException("Complete fresh-schema report unavailable",failure);}
            String url="jdbc:mysql://127.0.0.1:"+MybatisItDatabase.port()+"/"+database+"?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true";
            try (Connection connection = DriverManager.getConnection(url, MybatisItDatabase.user(), MybatisItDatabase.password());
                 Statement statement = connection.createStatement()) {
                assertFixtureMapperColumns(statement);
            } catch (Exception failure) {
                throw new IllegalStateException("Local HTTP schema bootstrap failed", failure);
            }
            byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
            String secret = UUID.randomUUID().toString() + UUID.randomUUID().toString();
            String configuredPort = System.getenv("CHENGYIN_HTTP_E2E_REDIS_PORT");
            assertNotNull(configuredPort, "Run test/tools/generic-gameplay-http-e2e.sh with its private Redis");
            int redisPort = Integer.parseInt(configuredPort);
            assertThrows(SecurityException.class, () -> System.getSecurityManager().checkConnect("outside.invalid", 443));
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(application,
                    "server.address=127.0.0.1", "server.port=0", "server.servlet.context-path=",
                    "spring.datasource.druid.master.url=" + url,
                    "spring.datasource.druid.master.username=" + MybatisItDatabase.user(),
                    "spring.datasource.druid.master.password=" + MybatisItDatabase.password(),
                    "spring.datasource.druid.slave.enabled=false",
                    "spring.redis.host=127.0.0.1", "spring.redis.port=" + redisPort, "spring.redis.database=15", "spring.redis.password=",
                    "token.secret=" + secret, "coupon.qr-secret=" + secret,
                    "chengyin.pii.key=" + Base64.getEncoder().encodeToString(key),
                    "withdrawal.pii.key=" + Base64.getEncoder().encodeToString(key),
                    "wx.pay.enabled=false", "wx.pay.api-v3-key=", "wx.sec-check.enabled=true", "wx.mini.secret=",
                    "alarm.webhook=", "alarm.failure-webhook=",
                    "spring.quartz.auto-startup=false", "spring.devtools.restart.enabled=false",
                    "chengyin.status.feed-file=target/http-e2e/status.json", "logging.level.com.chengyinhub=WARN");
            registerContentSecurityFixture(application);
            ReviewedMediaFixture.configure(application);
        }
    }

    private static void registerContentSecurityFixture(ConfigurableApplicationContext application) {
        Path root = Paths.get("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("scripts/e2e/E2eWxContentSecurityStub.java.stub"))) root = root.getParent();
        assertNotNull(root, "Reviewed content-security fixture must exist");
        assertNotNull(ToolProvider.getSystemJavaCompiler(), "Content-security fixture requires a JDK");
        try {
            Path scratch = Files.createTempDirectory("cy-http-content-security-");
            try {
                Path source = scratch.resolve("E2eWxContentSecurityStub.java");
                Files.copy(root.resolve("scripts/e2e/E2eWxContentSecurityStub.java.stub"), source);
                String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
                assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                        "-proc:none", "-classpath", classpath, "-d", scratch.toString(), source.toString()));
                try (URLClassLoader loader = new URLClassLoader(new URL[]{scratch.toUri().toURL()},
                        GenericGameplayHttpE2eIT.class.getClassLoader())) {
                    Class<?> stub = loader.loadClass("com.chengyinhub.e2e.E2eWxContentSecurityStub");
                    org.springframework.beans.factory.support.RootBeanDefinition definition =
                            new org.springframework.beans.factory.support.RootBeanDefinition(stub);
                    definition.setPrimary(true);
                    ((org.springframework.beans.factory.support.BeanDefinitionRegistry) application.getBeanFactory())
                            .registerBeanDefinition("httpE2eWxContentSecurityStub", definition);
                }
            } finally {
                try (java.util.stream.Stream<Path> files = Files.walk(scratch)) {
                    for (Path file : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) Files.delete(file);
                }
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Reviewed content-security fixture could not be registered", failure);
        }
    }

    static void assertContentSecurityFixture(ConfigurableApplicationContext application) {
        com.chengyinhub.business.service.IWxContentSecurityService security =
                application.getBean(com.chengyinhub.business.service.IWxContentSecurityService.class);
        assertSame(application.getBean("httpE2eWxContentSecurityStub"), security);
        Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(security);
        assertEquals("com.chengyinhub.e2e.E2eWxContentSecurityStub", target.getClass().getName());
        assertEquals(com.chengyinhub.business.service.impl.WxContentSecurityServiceImpl.class, target.getClass().getSuperclass());
        assertTrue(security.isEnabled());
        assertNull(security.checkText("HTTP fixture", "e2e-http-security-probe"));
        assertEquals("内容包含违规信息，请修改后重试", security.checkText("E2E-SEC-REJECT", "e2e-http-security-probe"));
        assertEquals(com.chengyinhub.business.service.IWxContentSecurityService.MSG_UNAVAILABLE, security.checkText("HTTP fixture", null));
        assertEquals(com.chengyinhub.business.service.IWxContentSecurityService.MSG_UNAVAILABLE, security.checkText("HTTP fixture", ""));
        assertEquals(com.chengyinhub.business.service.IWxContentSecurityService.MSG_UNAVAILABLE, security.checkText("HTTP fixture", " "));
        com.chengyinhub.business.service.IWxContentSecurityService real = application.getBean(
                "wxContentSecurityServiceImpl", com.chengyinhub.business.service.IWxContentSecurityService.class);
        assertEquals(com.chengyinhub.business.service.IWxContentSecurityService.MSG_UNAVAILABLE, real.checkText("HTTP fixture", " "));
    }

    static void registerPublisherIdentity(ConfigurableApplicationContext application, JdbcTemplate database, Long memberId) {
        com.chengyinhub.business.service.impl.MemberIdentityService identities =
                application.getBean(com.chengyinhub.business.service.impl.MemberIdentityService.class);
        assertEquals(com.chengyinhub.business.service.impl.MemberIdentityService.MSG_NOT_REGISTERED,
                identities.checkRegistered(memberId));
        assertFalse(identities.isRegistered(memberId));
        // Public synthetic ID from MemberIdentityServiceTest; this is registration, not real-person verification.
        identities.register(memberId, "测试主理人", "110105199003070017", true,
                com.chengyinhub.business.service.impl.MemberIdentityService.SOURCE_TOPIC_PUBLISH, "127.0.0.1");
        assertNull(identities.checkRegistered(memberId));
        assertTrue(identities.isRegistered(memberId));
        identities.register(memberId, "测试主理人", "110105199003070017", true,
                com.chengyinhub.business.service.impl.MemberIdentityService.SOURCE_TOPIC_PUBLISH, "127.0.0.1");
        assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM ums_member_identity WHERE member_id=?", Integer.class, memberId));
        assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM consent_record WHERE member_id=? AND doc_type='publisher_identity'", Integer.class, memberId));
        assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM consent_record WHERE member_id=? AND doc_type='publisher_identity'"
                + " AND doc_version='v1' AND scene='topic_publish' AND event_type='AGREE'"
                + " AND scope_type IS NULL AND scope_id IS NULL AND request_id REGEXP '^[0-9a-f]{32}$'"
                + " AND occurred_at IS NOT NULL AND create_time IS NOT NULL", Integer.class, memberId));
    }

    @org.junit.jupiter.api.BeforeAll void loadExistingPermissionFixture() throws Exception {
        configureRequestSerialization(http, json);
        assertContentSecurityFixture(context);
        // Fresh-schema skips migration data; reuse its existing role defaults for local author capabilities.
        for(String file:new String[]{"sql/migration_p1_rbac_permission_defaults.sql","sql/migration_m1_permission_full.sql","sql/migration_p1_rbac_permission_defaults.sql"})
            for(String statement:ItSchema.splitStatements(new String(Files.readAllBytes(Paths.get(file)),java.nio.charset.StandardCharsets.UTF_8))) {
                String sql=statement.replaceAll("(?m)^\\s*--.*$","").trim();
                if(sql.matches("(?is)^(INSERT INTO|UPDATE) user_permission\\b.*"))database.execute(sql);
            }
    }

    @AfterAll void closeLocalServer() throws Exception {
        try { mediaFixture.close(); } finally {
            if (context != null) {
                java.util.concurrent.ScheduledExecutorService executor = context.getBean("scheduledExecutorService", java.util.concurrent.ScheduledExecutorService.class);
                executor.shutdown();
                if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) executor.shutdownNow();
            }
            Files.createDirectories(Paths.get("target/http-e2e"));
            json.writerWithDefaultPrettyPrinter().writeValue(Paths.get("target/http-e2e/transcript.json").toFile(),transcript);
        }
    }

    @Test void realHttpUsesTheProductionControllerAndRejectsAnonymousState() throws Exception {
        assertNotNull(context.getBean(com.chengyinhub.web.controller.api.ApiThemeRuntimeController.class));
        ResponseEntity<JsonNode> anonymous = http.getForEntity("/api/play/theme/state?topicId=1", JsonNode.class);
        record("GET","/api/play/theme/state?topicId=1",null,anonymous);
        assertNotEquals(200, anonymous.getBody().path("code").asInt());
        UmsMember author = new UmsMember();author.setPhone("13800000001");author.setNickname("本地测试作者");
        author.setStatus(1L);author.setDelFlag(0);author.setWxOpenId("e2e-http-generic-13800000001");members.insertUmsMember(author);
        registerPublisherIdentity(context, database, author.getId());
        MockHttpServletRequest issuance = new MockHttpServletRequest();issuance.setRemoteAddr("127.0.0.1");
        issuance.addHeader("User-Agent", "Local HTTP E2E");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(issuance));
        String jwt;
        try { jwt = login.loginByMember(author,"phone",author.getPhone(),null); }
        finally { RequestContextHolder.resetRequestAttributes(); }
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();headers.setBearerAuth(jwt);
        ResponseEntity<JsonNode> matrix = http.exchange("/api/play/theme/support-matrix", org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers), JsonNode.class);
        record("GET","/api/play/theme/support-matrix",null,matrix);
        assertEquals(200, matrix.getStatusCode().value());
        assertEquals(200, matrix.getBody().path("code").asInt());
        ObjectNode effectTemplate=json.createObjectNode().put("schemaVersion",1);
        effectTemplate.putArray("effects").addObject().put("var","energy").put("op","FLOOR").put("value",8);
        effectTemplate.putObject("interactionRules").put("limit",2).putArray("resourceSlots").addObject()
                .put("key","energy").put("kind","VALUE").put("label","所需数值").put("required",true);
        ObjectNode template = json.createObjectNode().put("title","本地HTTP资源模板")
                .put("validationMethod",4).put("isSync",0).put("advancedConfigJson",effectTemplate.toString());
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> draft = http.exchange("/api/template/draft", org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(template,headers),JsonNode.class);
        record("POST","/api/template/draft",template,draft);
        assertEquals(200,draft.getBody().path("code").asInt(),draft.getBody().toString());
        long templateId=draft.getBody().path("data").asLong();assertTrue(templateId>0);
        JsonNode storedTemplate=json.readTree(database.queryForObject("SELECT advanced_config_json FROM cms_member_template WHERE id=?",String.class,templateId));
        assertEquals("energy",storedTemplate.path("effects").path(0).path("var").asText());
        ObjectNode templateReadback=json.createObjectNode().put("templateId",templateId).put("memberId",author.getId());templateReadback.set("advancedConfig",storedTemplate);
        transcript.addObject().put("method","DB_READBACK").put("path","cms_member_template:"+templateId).put("httpStatus",0).set("response",templateReadback);
        ObjectNode topic = topicRequest("本地HTTP通用主题");
        ResponseEntity<JsonNode> created = http.exchange("/api/topic/v2/create", org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(topic,headers),JsonNode.class);
        record("POST","/api/topic/v2/create",topic,created);
        assertEquals(200,created.getBody().path("code").asInt(),created.getBody().toString());
        long topicId=created.getBody().path("data").path("topicId").asLong();assertTrue(topicId>0);
        org.springframework.http.HttpHeaders stranger=loginHeaders("13800000002","其他作者");
        rejected(form("/api/topic/edit-detail",topicId,stranger));
        rejected(form("/api/template/myinfo",templateId,stranger));
        ObjectNode attack=topic.deepCopy().put("id",topicId).put("name","越权改名");rejected(post("/api/topic/v2/update",attack,stranger));
        rejected(post("/api/template/draft",template.deepCopy().put("id",templateId).put("title","越权模板"),stranger));
        assertEquals(author.getId(),database.queryForObject("SELECT member_id FROM cms_member_template WHERE id=?",Long.class,templateId));
        assertEquals("本地HTTP资源模板",database.queryForObject("SELECT title FROM cms_member_template WHERE id=?",String.class,templateId));
        assertEquals("本地HTTP通用主题",database.queryForObject("SELECT name FROM cms_topic WHERE id=?",String.class,topicId));
        topic.put("id",topicId);refreshChapterIds(topic,topicId);
        topic.put("journeyRules","{\"schemaVersion\":1,\"stateEnabled\":true,\"checkEnabled\":false,\"hp\":{\"enabled\":true,\"init\":6,\"max\":12},\"attributes\":[{\"key\":\"counter.focus\",\"label\":\"专注\",\"initial\":2,\"min\":0,\"max\":12}]}");
        success(post("/api/topic/v2/update",topic,headers));
        ObjectNode registration=json.createObjectNode().put("topicId",topicId);
        registration.putArray("keys").addObject().put("stateKey","counter.focus").put("keyType","counter").put("scope","run").put("label","专注").put("initialValue",2).put("minValue",0).put("maxValue",12);
        success(post("/api/topic/state-keys",registration,headers));
        refreshChapterIds(topic,topicId);
        String registryVersion=confirmAttributeBinding(topic,success(get("/api/topic/attribute-binding?topicId="+topicId,headers)));
        ((ObjectNode)topic.path("chapters").path(0)).withArray("nodes").addObject()
                .put("clientNodeKey","restore").put("name","补充站").put("templateId",templateId).put("nodeTime",0).put("nodeMoney",0).put("latitude","31.2304").put("longitude","121.4737").put("address","本地测试位置");
        ((ObjectNode)topic.path("chapters").path(0)).withArray("blocks").addObject().put("type","node").put("nodeIndex",0);
        ObjectNode theme=json.createObjectNode().put("schemaVersion",1).put("capacity",6).put("hpName","状态");
        theme.putObject("gameplayRuntime").put("runtimeScope","PLAYER").putObject("survival").put("zeroHpPolicy","BLOCK_HP_COST").put("actionLimitPolicy","BLOCK_OVERFLOW");
        ObjectNode binding=theme.putObject("templateBindings").putObject("client:restore").put("templateId",templateId)
                .put("slotDigest",GameplayTemplateRules.slotDigest(effectTemplate.path("interactionRules").path("resourceSlots")));
        binding.putObject("values").put("energy","counter.focus");binding.putObject("items");
        JsonNode beforeAdoption=persistedTopic(topicId,"before-adoption");
        java.util.List<Long> beforeNodes=database.queryForList("SELECT id FROM cms_topic_node WHERE topic_id=? ORDER BY id",Long.class,topicId);
        ObjectNode foreignTheme=theme.deepCopy();ObjectNode foreignBindings=(ObjectNode)foreignTheme.path("templateBindings");
        foreignBindings.set("client:foreign",foreignBindings.remove("client:restore"));
        JsonNode foreign=post("/api/topic/v2/update",topic.deepCopy().put("themeRuntimeConfig",foreignTheme.toString()),headers);
        rejected(foreign);assertTrue(foreign.path("msg").asText().contains("client:foreign"),foreign.toString());
        assertEquals(beforeAdoption,persistedTopic(topicId,"before-adoption"));
        assertEquals(beforeNodes,database.queryForList("SELECT id FROM cms_topic_node WHERE topic_id=? ORDER BY id",Long.class,topicId));
        topic.put("themeRuntimeConfig",theme.toString());JsonNode adoption=post("/api/topic/v2/update",topic,headers);
        if(adoption.path("code").asInt()!=200) {
            assertEquals(beforeAdoption,persistedTopic(topicId,"before-adoption"));
            assertEquals(beforeNodes,database.queryForList("SELECT id FROM cms_topic_node WHERE topic_id=? ORDER BY id",Long.class,topicId));
        }
        success(adoption);
        long nodeId=database.queryForObject("SELECT id FROM cms_topic_node WHERE topic_id=? ORDER BY id LIMIT 1",Long.class,topicId);
        JsonNode persisted=persistedTopic(topicId,"adopted");
        assertEquals("counter.focus",persisted.path("theme").path("templateBindings").path(String.valueOf(nodeId)).path("values").path("energy").asText());
        assertFalse(persisted.path("theme").toString().contains("client:"));
        ObjectNode bad=topic.deepCopy();refreshChapterIds(bad,topicId);
        ((ObjectNode)theme.path("templateBindings").path("client:restore")).put("slotDigest",String.join("",java.util.Collections.nCopies(64,"0")));
        bad.put("themeRuntimeConfig",theme.toString());String before=database.queryForObject("SELECT theme_runtime_config_json FROM cms_topic WHERE id=?",String.class,topicId);
        java.util.List<Long> chapterIds=database.queryForList("SELECT id FROM cms_topic_chapter WHERE topic_id=? ORDER BY id",Long.class,topicId);
        JsonNode stale=post("/api/topic/v2/update",bad,headers);rejected(stale);
        assertTrue(stale.path("msg").asText().contains("声明已变化"),stale.toString());
        assertEquals(before,database.queryForObject("SELECT theme_runtime_config_json FROM cms_topic WHERE id=?",String.class,topicId));
        assertEquals(chapterIds,database.queryForList("SELECT id FROM cms_topic_chapter WHERE topic_id=? ORDER BY id",Long.class,topicId));
        success(publishBound(topicId,registryVersion,headers));persisted=persistedTopic(topicId,"published");
        assertEquals("counter.focus",persisted.path("snapshot").path("nodeConfigs").path(String.valueOf(nodeId)).path("effects").path(0).path("var").asText());
        assertEquals("counter.focus",persisted.path("snapshot").path("nodeJourney").path(String.valueOf(nodeId)).path("effects").path(0).path("var").asText());
        assertFalse(persisted.path("snapshot").toString().contains("resourceSlots"));assertFalse(persisted.path("snapshot").toString().contains("templateBindings"));
        ResponseEntity<JsonNode> play=http.exchange("/api/play/theme/state?topicId="+topicId,org.springframework.http.HttpMethod.GET,new org.springframework.http.HttpEntity<>(headers),JsonNode.class);
        record("GET","/api/play/theme/state?topicId="+topicId,null,play);
        rejected(play.getBody());assertTrue(play.getBody().path("msg").asText().contains("通行证"),play.getBody().toString());
        assertFreshTemplateCopyFollowsPublishedEligibility(topicId,headers);
    }
    @Test void terminalVoteAdoptionUsesThePersistedFinalChapter() throws Exception {
        org.springframework.http.HttpHeaders headers=loginHeaders("13800000003","结局测试作者");
        ObjectNode advanced=json.createObjectNode().put("schemaVersion",1);
        ObjectNode tools=advanced.putObject("interactionTools").put("votePolicy","MAJORITY").put("decidesEnding",true);
        ArrayNode plans=tools.putArray("plans");plans.addObject().put("planKey","finish").put("title","共同结束");
        long templateId=success(post("/api/template/draft",json.createObjectNode().put("title","本地结局投票模板")
                .put("validationMethod",4).put("isSync",0).put("advancedConfigJson",advanced.toString()),headers)).asLong();
        ObjectNode topic=topicRequest("本地HTTP结局主题");
        ObjectNode ending=((ArrayNode)topic.path("chapters")).addObject().put("name","最终章").put("schemaVersion",1);
        ending.putArray("nodes");ending.putArray("blocks").addObject().put("type","text").put("content","到达共同结局");
        ending.putObject("ending").put("fallback",true);
        long topicId=success(post("/api/topic/v2/create",topic,headers)).path("topicId").asLong();assertTrue(topicId>0);topic.put("id",topicId);refreshChapterIds(topic,topicId);
        JsonNode before=persistedTopic(topicId,"before-terminal-adoption");
        long endingChapterId=topic.path("chapters").path(1).path("id").asLong();
        assertEquals(endingChapterId,before.path("story").path("ending").path("endings").path(0).path("chapterId").asLong());
        ObjectNode first=(ObjectNode)topic.path("chapters").path(0);
        first.withArray("nodes").addObject().put("clientNodeKey","vote").put("name","讨论站").put("templateId",templateId).put("nodeTime",0).put("nodeMoney",0)
                .put("latitude","31.2304").put("longitude","121.4737").put("address","本地测试位置");
        first.withArray("blocks").addObject().put("type","node").put("nodeIndex",0);
        ObjectNode theme=json.createObjectNode().put("schemaVersion",1).put("capacity",6);
        theme.putObject("gameplayRuntime").put("runtimeScope","TEAM").put("worldMode","WORLD_SHARED");
        ObjectNode binding=theme.putObject("templateBindings").putObject("client:vote").put("templateId",templateId)
                .put("slotDigest",GameplayTemplateRules.slotDigest(json.createArrayNode())).put("planDigest",GameplayTemplateRules.planDigest(plans));
        binding.putObject("values");binding.putObject("items");binding.putObject("planEndings").put("finish",String.valueOf(endingChapterId));
        topic.put("themeRuntimeConfig",theme.toString());
        JsonNode response=post("/api/topic/v2/update",topic,headers);
        if(response.path("code").asInt()!=200) {
            assertEquals(before,persistedTopic(topicId,"before-terminal-adoption"),"终局绑定验证失败必须回滚章节与主题");
        }
        success(response);
        success(form("/api/topic/publish-draft",topicId,headers));
        JsonNode frozen=persistedTopic(topicId,"terminal-published");
        JsonNode plan=frozen.path("published").path("gameplayRuntime").path("plans").path(0);
        assertTrue(plan.path("terminal").asBoolean());assertEquals("ending1",plan.path("storyEndingId").asText());
        assertEquals(frozen.path("story").path("ending").path("endings").path(0).path("chapterId"),plan.path("endingChapterId"));
        assertFreshTemplateCopyFollowsPublishedEligibility(topicId,headers);
    }
    /**
     * F2 RPG P0(T01/T02):属性定义 PUT / validate 与角色卡 GET 走真实 HTTP。
     * 越界初值 400 + errorCode、改 label 不改键名、同一 PUT 重放幂等、非作者被拒、匿名被拒;
     * 未建角色时角色卡返回模板(初值),定义改动即时反映到模板。
     */
    @Test void f2HttpAttributeDefinitionAndCharacterCardStayOwnerScoped() throws Exception {
        org.springframework.http.HttpHeaders author=loginHeaders("13600000201","F2属性作者");
        org.springframework.http.HttpHeaders stranger=loginHeaders("13600000202","F2旁观者");
        long topicId=success(post("/api/topic/v2/create",topicRequest("F2属性卡"),author)).path("topicId").asLong();
        ObjectNode reg=json.createObjectNode().put("topicId",topicId);
        reg.putArray("keys").addObject().put("stateKey","counter.str").put("keyType","counter").put("scope","run").put("label","力量").put("initialValue",3);
        success(post("/api/topic/state-keys",reg,author));
        // #625 模板角色卡只展示 journeyRules 启用的注册键：作者按注册版本确认启用 counter.str 后存草稿。
        ObjectNode draft=topicRequest("F2属性卡");draft.put("id",topicId);refreshChapterIds(draft,topicId);
        draft.put("journeyRules","{\"schemaVersion\":1,\"stateEnabled\":true,\"attributes\":[{\"key\":\"counter.str\",\"label\":\"力量\",\"initial\":3,\"min\":0,\"max\":100}]}");
        confirmAttributeBinding(draft,success(get("/api/topic/attribute-binding?topicId="+topicId,author)));
        success(post("/api/topic/v2/update",draft,author));

        ResponseEntity<JsonNode> anonymous=http.getForEntity("/api/play/character?topicId="+topicId,JsonNode.class);
        record("GET","/api/play/character?topicId="+topicId,null,anonymous);
        assertNotEquals(200,anonymous.getBody().path("code").asInt());
        JsonNode template=success(get("/api/play/character?topicId="+topicId,author));
        assertFalse(template.path("created").asBoolean(true),template.toString());
        assertEquals("counter.str",template.path("attributes").path(0).path("stateKey").asText());
        assertEquals(3,template.path("attributes").path(0).path("value").asInt());
        assertEquals(100,template.path("attributes").path(0).path("max").asInt(),"老数据无范围 = 0..100");
        assertEquals("run",template.path("attributes").path(0).path("persistScope").asText());
        JsonNode foreignCard=get("/api/play/character?topicId="+topicId,stranger);
        assertEquals(404,foreignCard.path("code").asInt(),"未发布主题对非作者不可见:"+foreignCard);

        ObjectNode outOfRange=json.createObjectNode().put("topicId",topicId);outOfRange.putObject("key").put("label","力量").put("initialValue",120);
        JsonNode range=put("/api/topic/state-keys/counter.str",outOfRange,author);
        assertEquals(400,range.path("code").asInt(),range.toString());assertEquals("STATE_KEY_RANGE",range.path("errorCode").asText());
        ObjectNode rename=json.createObjectNode().put("topicId",topicId);rename.putObject("key").put("stateKey","counter.power").put("label","力量");
        JsonNode renamed=put("/api/topic/state-keys/counter.str",rename,author);
        assertEquals(400,renamed.path("code").asInt());assertEquals("STATE_KEY_RENAME_FORBIDDEN",renamed.path("errorCode").asText());
        ObjectNode definition=json.createObjectNode().put("topicId",topicId);
        definition.putObject("key").put("label","膂力").put("initialValue",8).put("minValue",0).put("maxValue",20).put("displayType","bar")
                .put("color","#AA3300").put("icon","muscle").put("sortOrder",1).put("visible",true).put("persistScope","topic");
        rejected(put("/api/topic/state-keys/counter.str",definition,stranger));
        assertEquals("力量",database.queryForObject("SELECT label FROM theme_state_key WHERE topic_id=? AND state_key='counter.str'",String.class,topicId));
        success(put("/api/topic/state-keys/counter.str",definition,author));
        success(put("/api/topic/state-keys/counter.str",definition,author));
        assertEquals(1,database.queryForObject("SELECT COUNT(*) FROM theme_state_key WHERE topic_id=?",Integer.class,topicId),"同一 PUT 重放不新增行");
        assertEquals(1,database.queryForObject("SELECT COUNT(*) FROM theme_state_key WHERE topic_id=? AND state_key='counter.str' AND label='膂力'"
                + " AND max_value=20 AND persist_scope='topic' AND display_type='bar'",Integer.class,topicId),"改 label 不改键名");

        ObjectNode check=json.createObjectNode().put("topicId",topicId);
        check.putArray("keys").addObject().put("stateKey","counter.dex").put("keyType","counter").put("scope","run").put("color","red").put("initialValue",101);
        JsonNode problems=post("/api/topic/state-keys/validate",check,author);
        assertEquals(400,problems.path("code").asInt(),problems.toString());assertTrue(problems.path("problems").size()>=1);
        ((ObjectNode)check.path("keys").path(0)).put("color","#00AA00").put("initialValue",50);
        assertTrue(success(post("/api/topic/state-keys/validate",check,author)).path("valid").asBoolean());
        assertEquals(0,database.queryForObject("SELECT COUNT(*) FROM theme_state_key WHERE topic_id=? AND state_key='counter.dex'",Integer.class,topicId),"试校验不写库");

        JsonNode card=success(get("/api/play/character?topicId="+topicId,author)).path("attributes").path(0);
        assertEquals("膂力",card.path("label").asText());assertEquals(8,card.path("value").asInt());assertEquals(20,card.path("max").asInt());
        assertEquals("bar",card.path("displayType").asText());assertEquals("topic",card.path("persistScope").asText());
        assertEquals(0,database.queryForObject("SELECT COUNT(*) FROM theme_character WHERE topic_id=?",Integer.class,topicId),"读角色卡不写台账");
    }
    private JsonNode put(String path,JsonNode body,org.springframework.http.HttpHeaders headers) {
        ResponseEntity<JsonNode> response=http.exchange(path,org.springframework.http.HttpMethod.PUT,new org.springframework.http.HttpEntity<>(body,headers),JsonNode.class);
        record("PUT",path,body,response);return response.getBody();
    }
    private static class Lifecycle {
        org.springframework.http.HttpHeaders headers;
        long memberId, templateId, topicId, nodeId, routeId, advancedId, activityId, teamId;
        org.springframework.http.HttpHeaders partner;
        String valueKey;
        JsonNode publication;
    }
    private ObjectNode r5(String kit) {
        ObjectNode c=json.createObjectNode().put("schemaVersion",1);
        ObjectNode main=c.putObject(kit).put("enabled",true);
        if("qa".equals(kit))main.put("title","答案").put("mode","TYPE").put("answerText","correct");
        if("typeIn".equals(kit))main.put("title","打字").put("target","hello").put("seconds",3).put("tries",1);
        if("check".equals(kit))main.put("checkId","lc").put("tier","hard");
        ObjectNode o=c.putObject("outcomeRules").put("version",1);
        o.putObject("pass").put("reply","完成").putArray("costs").addObject().put("statKey","vital").put("amount",1);
        if(!"album".equals(kit))o.putObject("fail").put("reply","再试一次").putArray("costs").addObject().put("statKey","vital").put("amount",9);
        c.putObject("interactionRules").put("limit",2).putArray("resourceSlots").addObject().put("key","vital").put("kind","VALUE").put("label","生命").put("required",true);
        return c;
    }
    private Lifecycle lifecycle(ObjectNode config,String policy) throws Exception {
        return lifecycle(config,policy,"counter.energy");
    }
    private Lifecycle lifecycle(ObjectNode config,String policy,String valueKey) throws Exception {
        return lifecycle(config,policy,valueKey,"PLAYER",false);
    }
    private Lifecycle lifecycle(ObjectNode config,String policy,String valueKey,String scope,boolean publishOnly) throws Exception {
        return lifecycle(config,policy,valueKey,scope,publishOnly,4);
    }
    private Lifecycle lifecycle(ObjectNode config,String policy,String valueKey,String scope,boolean publishOnly,int participation) throws Exception {
        Lifecycle s=new Lifecycle();s.valueKey=valueKey;String phone="138"+String.format("%08d",100+transcript.size());
        s.headers=loginHeaders(phone,"生命周期作者");s.memberId=database.queryForObject("SELECT id FROM ums_member WHERE phone=?",Long.class,phone);
        ObjectNode template=json.createObjectNode().put("title","LC "+phone).put("validationMethod",participation).put("isSync",0).put("advancedConfigJson",config.toString());
        s.templateId=success(post("/api/template/draft",template,s.headers)).asLong();
        template.put("id",s.templateId);success(post("/api/template/draft",template,s.headers));
        JsonNode read=success(form("/api/template/myinfo",s.templateId,s.headers));assertTrue(read.toString().contains("advancedConfigJson"),read.toString());
        ObjectNode topic=topicRequest("LC "+phone);if("TEAM".equals(scope))topic.put("teamMode",1).put("teamMaxMembers",4);s.topicId=success(post("/api/topic/v2/create",topic,s.headers)).path("topicId").asLong();topic.put("id",s.topicId);refreshChapterIds(topic,s.topicId);
        topic.put("journeyRules","{\"schemaVersion\":1,\"stateEnabled\":true,\"checkEnabled\":true,\"hp\":{\"enabled\":true,\"init\":6,\"max\":12},\"luck\":{\"enabled\":true,\"init\":2,\"max\":3},\"recovery\":{\"stageRestHpFloor\":0,\"stageRestLuckFloor\":0,\"stationEndIfHurt\":0}}");
        ObjectNode rules=(ObjectNode)json.readTree(topic.path("journeyRules").asText());
        if(!valueKey.startsWith("sys.")){rules.putArray("attributes").addObject().put("key",valueKey).put("label","能量").put("initial",6).put("min",0).put("max",12);ObjectNode registration=json.createObjectNode().put("topicId",s.topicId);registration.putArray("keys").addObject().put("stateKey",valueKey).put("keyType","counter").put("scope","run").put("label","能量").put("initialValue",6).put("minValue",0).put("maxValue",12);success(post("/api/topic/state-keys",registration,s.headers));}

        topic.put("journeyRules",rules.toString());
        String registryVersion=valueKey.startsWith("sys.")?null:confirmAttributeBinding(topic,success(get("/api/topic/attribute-binding?topicId="+s.topicId,s.headers)));
        ObjectNode chapter=(ObjectNode)topic.path("chapters").path(0);
        for(int i=0;i<2;i++){chapter.withArray("nodes").addObject().put("clientNodeKey","lc"+i).put("name","站点"+i).put("templateId",s.templateId).put("nodeTime",0).put("nodeMoney",0).put("latitude","31.2304").put("longitude","121.4737").put("address","本地位置");chapter.withArray("blocks").addObject().put("type","node").put("nodeIndex",i);}
        ObjectNode theme=json.createObjectNode().put("schemaVersion",1).put("capacity",6);
        ObjectNode survival=theme.putObject("gameplayRuntime").put("runtimeScope",scope).putObject("survival").put("zeroHpPolicy",policy).put("actionLimitPolicy","BLOCK_OVERFLOW");
        if("STEPS".equals(policy))survival.putObject("zeroSteps").put("version",1).put("steps",100).put("restoreTo",4).put("limit",2).put("thenPolicy","THEME_FAIL");
        if("WAIT".equals(policy))survival.putObject("zeroWait").put("version",1).put("minutes",1).put("restoreTo",4).put("limit",2).put("thenPolicy","THEME_FAIL");
        if("TEAM".equals(scope))theme.with("gameplayRuntime").put("worldMode","WORLD_SHARED");
        boolean stock=config.path("interactionTools").path("plans").path(0).path("materialCost").size()>0;
        if(stock){theme.putArray("items").addObject().put("id","paper").put("kind","ITEM").put("name","纸张").put("image",mediaFixture.approvedImage(context, database, s.memberId)).put("slotCost",1);theme.putArray("events").addObject().put("id","initial_stock").put("content","初始物资").putArray("grants").addObject().put("itemId","paper").put("quantity",2);}
        for(int i=0;i<2;i++){ObjectNode b=theme.with("templateBindings").putObject("client:lc"+i).put("templateId",s.templateId).put("slotDigest",GameplayTemplateRules.slotDigest(config.path("interactionRules").path("resourceSlots")));b.putObject("values").put("vital",valueKey);ObjectNode items=b.putObject("items");if(stock)items.put("stock","paper");}
        topic.put("themeRuntimeConfig",theme.toString());success(post("/api/topic/v2/update",topic,s.headers));success(form("/api/topic/edit-detail",s.topicId,s.headers));
        s.nodeId=database.queryForObject("SELECT id FROM cms_topic_node WHERE topic_id=? ORDER BY id LIMIT 1",Long.class,s.topicId);
        s.publication=registryVersion==null?form("/api/topic/publish-draft",s.topicId,s.headers):publishBound(s.topicId,registryVersion,s.headers);
        if(participation==0){rejected(s.publication);return s;}
        success(s.publication);JsonNode frozen=persistedTopic(s.topicId,"r5-frozen");if(config.has("outcomeRules"))assertEquals(valueKey,frozen.path("snapshot").path("nodeConfigs").path(String.valueOf(s.nodeId)).path("outcomeRules").path("pass").path("costs").path(0).path("statKey").asText());
        if(publishOnly)return s;
        // Local paid entitlement/static scan fixtures; no payment or remote QR API is exercised.
        database.update("INSERT INTO cms_registration(registration_no,member_id,owner_type,owner_id,topic_id,registration_status,payment_status,purchase_kind,original_amount,total_amount,payable_amount,pay_expire_time,create_time) VALUES(?,?,1,?,?,2,2,2,0,0,0,DATE_ADD(NOW(),INTERVAL 1 DAY),NOW())","lc-"+s.topicId,s.memberId,s.topicId,s.topicId);
        database.update("UPDATE cms_topic_node SET checkin_code=? WHERE id=?","lc-"+s.nodeId,s.nodeId);
        JsonNode view=theme(s);s.routeId=view.path("sessionId").asLong();assertTrue(s.routeId>0,view.toString());
        success(formFields("/api/play/checkin",json.createObjectNode().put("topicId",s.topicId).put("code","lc-"+s.nodeId).put("routeActionId","arrive"),s.headers));
        if(config.has("outcomeRules"))assertScanHasNotCompleted(s);
        success(route(s,"QUEST_START","start"));
        if(config.has("outcomeRules")){JsonNode advanced=success(post("/api/play/advanced/start",json.createObjectNode().put("topicId",s.topicId).put("nodeId",s.nodeId),s.headers));s.advancedId=advanced.path("sessionId").asLong();assertTrue(s.advancedId>0,advanced.toString());}
        return s;
    }
    private void assertScanHasNotCompleted(Lifecycle s) throws Exception {
        JsonNode read=theme(s),state=stored(s);
        assertTrue(read.path("lastOutcome").isNull() || read.path("lastOutcome").isMissingNode(),read.toString());
        assertEquals("sys.luck".equals(s.valueKey)?2:6,state.path("variables").path(s.valueKey).asInt());
        assertEquals(0,state.path("themeRuntime").path("verifiedNodes").path(String.valueOf(s.nodeId)).asLong());
        assertEquals(0,state.path("themeRuntime").path("cooperation").path("facilities").path("tpl_node_"+s.nodeId).path("count").asInt());
    }
    @Test void r5HttpDefaultAdoptedQaCanPublishAsZeroHpChallenge() throws Exception {
        org.springframework.http.HttpHeaders headers=loginHeaders("13800000931","归零挑战作者");
        ObjectNode config=r5("qa");config.remove("interactionRules");
        config.with("outcomeRules").with("pass").putArray("costs");
        config.with("outcomeRules").with("fail").putArray("costs");
        ObjectNode template=json.createObjectNode().put("title","R5免费恢复题组")
                .put("validationMethod",5).put("isSync",0).put("advancedConfigJson",config.toString());
        long templateId=success(post("/api/template/draft",template,headers)).asLong();
        template.put("id",templateId);success(post("/api/template/draft",template,headers));
        assertEquals(1,json.readTree(success(form("/api/template/myinfo",templateId,headers))
                .path("advancedConfigJson").asText()).path("outcomeRules").path("version").asInt());
        ObjectNode topic=topicRequest("R5默认采用归零挑战");
        long topicId=success(post("/api/topic/v2/create",topic,headers)).path("topicId").asLong();
        topic.put("id",topicId);
        JsonNode read=success(form("/api/topic/edit-detail",topicId,headers));
        topic.put("configVersion",read.path("topic").path("configVersion").asInt());
        ObjectNode chapter=(ObjectNode)topic.path("chapters").path(0);
        chapter.put("id",read.path("chapters").path(0).path("id").asLong());
        chapter.withArray("nodes").addObject().put("clientNodeKey","recovery").put("name","恢复题组")
                .put("templateId",templateId).put("nodeTime",0).put("nodeMoney",0)
                .put("latitude","31.2304").put("longitude","121.4737").put("address","本地位置");
        chapter.withArray("blocks").addObject().put("type","node").put("nodeIndex",0);
        chapter.withArray("nodes").addObject().put("clientNodeKey","ordinary").put("name","普通题组")
                .put("templateId",templateId).put("nodeTime",0).put("nodeMoney",0)
                .put("latitude","31.2304").put("longitude","121.4737").put("address","本地位置");
        chapter.withArray("blocks").addObject().put("type","node").put("nodeIndex",1);
        topic.put("journeyRules","{\"schemaVersion\":1,\"stateEnabled\":true,\"hp\":{\"enabled\":true,\"init\":6,\"max\":12}}");
        ObjectNode theme=json.createObjectNode().put("schemaVersion",1).put("capacity",6);
        theme.putObject("gameplayRuntime").put("runtimeScope","PLAYER").putObject("survival")
                .put("zeroHpPolicy","BLOCK_HP_COST").put("actionLimitPolicy","BLOCK_OVERFLOW");
        ObjectNode binding=theme.putObject("templateBindings").putObject("client:recovery")
                .put("templateId",templateId).put("slotDigest",GameplayTemplateRules.slotDigest(json.createArrayNode()));
        binding.putObject("values");binding.putObject("items");
        topic.put("themeRuntimeConfig",theme.toString());success(post("/api/topic/v2/update",topic,headers));
        read=success(form("/api/topic/edit-detail",topicId,headers));
        long nodeId=read.path("chapters").path(0).path("cmsTopicNodeList").path(0).path("id").asLong();
        assertTrue(nodeId>0,read.toString());
        topic.put("configVersion",read.path("topic").path("configVersion").asInt());
        chapter.put("id",read.path("chapters").path(0).path("id").asLong());
        ((ObjectNode)chapter.path("nodes").path(0)).put("id",nodeId);
        long ordinaryNodeId=read.path("chapters").path(0).path("cmsTopicNodeList").path(1).path("id").asLong();
        assertTrue(ordinaryNodeId>0,read.toString());
        ((ObjectNode)chapter.path("nodes").path(1)).put("id",ordinaryNodeId);
        theme=(ObjectNode)json.readTree(read.path("themeRuntimeConfig").asText());
        ObjectNode survival=theme.with("gameplayRuntime").with("survival");
        survival.put("zeroHpPolicy","CHALLENGE").putObject("zeroChallenge").put("version",2)
                .put("challengeNodeId",nodeId).put("gameplayKey","qa").put("requiredCompletions",2)
                .put("withinSeconds",3600).put("thenPolicy","THEME_FAIL").put("restoreTo",4).put("limit",2);
        topic.put("themeRuntimeConfig",theme.toString());
        // The author selects the adopted node; ordinary task declarations remain the server defaults.
        success(post("/api/topic/v2/update",topic,headers));
        read=success(form("/api/topic/edit-detail",topicId,headers));
        nodeId=read.path("chapters").path(0).path("cmsTopicNodeList").path(0).path("id").asLong();
        ordinaryNodeId=read.path("chapters").path(0).path("cmsTopicNodeList").path(1).path("id").asLong();
        success(form("/api/topic/publish-draft",topicId,headers));
        JsonNode snapshot=json.readTree(database.queryForObject("SELECT published_route_graph_json FROM cms_topic WHERE id=?",String.class,topicId));
        JsonNode game=snapshot.path("themeConfig").path("gameplayRuntime");
        assertEquals(1,game.path("quests").size());
        assertEquals(ordinaryNodeId,game.path("quests").path(0).path("nodeId").asLong());
        assertEquals(nodeId,game.path("survival").path("zeroChallenge").path("challengeNodeId").asLong());
        assertEquals(1,snapshot.path("nodeConfigs").path(String.valueOf(nodeId)).path("outcomeRules").path("version").asInt());
    }
    @Test void personalR5DirectParticipationWithoutMerchantIsRefused() throws Exception {
        Lifecycle s=lifecycle(r5("qa"),"BLOCK_HP_COST","counter.energy","PLAYER",true,0);
        assertTrue(s.publication.path("msg").asText().contains("个人任务"),s.publication.toString());
        assertNull(database.queryForObject("SELECT published_route_graph_json FROM cms_topic WHERE id=?",String.class,s.topicId));
        success(form("/api/topic/edit-detail",s.topicId,s.headers));success(form("/api/template/myinfo",s.templateId,s.headers));
    }
    @Test void personalR5ScanOnlyDoesNotCompleteTheGameplay() throws Exception {
        Lifecycle s=lifecycle(r5("qa"),"BLOCK_HP_COST");assertScanHasNotCompleted(s);
        assertFalse(advanced(s).path("readyForBase").asBoolean());rejected(route(s,"QUEST_RESOLVE","scan-only"));
        assertScanHasNotCompleted(s);act(s,"SUBMIT_QA","correct",json.createObjectNode().put("input","correct"));
        success(route(s,"QUEST_RESOLVE","settle"));assertOutcome(s,"PASS",5,1);
    }
    private JsonNode get(String path,org.springframework.http.HttpHeaders headers) {
        ResponseEntity<JsonNode> response=http.exchange(path,org.springframework.http.HttpMethod.GET,new org.springframework.http.HttpEntity<>(headers),JsonNode.class);record("GET",path,null,response);return response.getBody();
    }
    private JsonNode formFields(String path,ObjectNode fields,org.springframework.http.HttpHeaders original) {
        org.springframework.http.HttpHeaders headers=new org.springframework.http.HttpHeaders();headers.putAll(original);headers.setContentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);
        org.springframework.util.LinkedMultiValueMap<String,String> body=new org.springframework.util.LinkedMultiValueMap<>();fields.fields().forEachRemaining(e->body.add(e.getKey(),e.getValue().asText()));
        ResponseEntity<JsonNode> response=http.exchange(path,org.springframework.http.HttpMethod.POST,new org.springframework.http.HttpEntity<>(body,headers),JsonNode.class);record("POST",path,fields,response);return response.getBody();
    }
    /**
     * #625 起属性定义唯一来源是注册表：带 attributes 却无 attributeBinding 的旧规则发布即 409。
     * 与小程序 attribute-binding.confirm 同一流程：按读回的注册版本确认 attributes 启用的键，
     * 投影须与注册定义一致（夹具注册时已写同一 label/初值/范围）。返回注册版本供发布绑定。
     */
    static String confirmAttributeBinding(ObjectNode topic,JsonNode registry) throws java.io.IOException {
        ObjectNode rules=(ObjectNode)new com.fasterxml.jackson.databind.ObjectMapper().readTree(topic.path("journeyRules").asText());
        String registryVersion=registry.path("registryVersion").asText();
        ArrayNode enabled=rules.putObject("attributeBinding").put("schemaVersion",1).put("registryVersion",registryVersion).putArray("enabledKeys");
        for(JsonNode attribute:rules.path("attributes")){((ObjectNode)attribute).put("visible",true);enabled.add(attribute.path("key").asText());}
        topic.put("journeyRules",rules.toString()).put("configVersion",registry.path("configVersion").asLong());
        return registryVersion;
    }
    private JsonNode publishBound(long topicId,String registryVersion,org.springframework.http.HttpHeaders headers) {
        long configVersion=database.queryForObject("SELECT config_version FROM cms_topic WHERE id=?",Long.class,topicId);
        return formFields("/api/topic/publish-draft",json.createObjectNode().put("id",topicId).put("configVersion",configVersion).put("registryVersion",registryVersion),headers);
    }
    private JsonNode theme(Lifecycle s){return success(get("/api/play/theme/state?topicId="+s.topicId,s.headers));}
    private JsonNode advanced(Lifecycle s){return success(get("/api/play/advanced/state?sessionId="+s.advancedId,s.headers));}
    private ObjectNode actionBody(Lifecycle s,String action,String key,ObjectNode payload){ObjectNode b=json.createObjectNode().put("sessionId",s.advancedId).put("version",advanced(s).path("version").asInt()).put("idempotencyKey","lc-"+s.advancedId+"-"+key).put("action",action);b.set("payload",payload);return b;}
    private JsonNode act(Lifecycle s,String action,String key,ObjectNode payload){return success(post("/api/play/advanced/action",actionBody(s,action,key,payload),s.headers));}
    private JsonNode route(Lifecycle s,String operation,String key){return post("/api/play/theme/gameplay-action",json.createObjectNode().put("sessionId",s.routeId).put("expectedVersion",theme(s).path("version").asInt()).put("actionId",key).put("operation",operation).put("contentId","node_"+s.nodeId),s.headers);}
    private JsonNode stored(Lifecycle s) throws Exception {return json.readTree(database.queryForObject("SELECT state_json FROM topic_route_session WHERE id=?",String.class,s.routeId));}
    private void assertOutcome(Lifecycle s,String outcome,int hp,int uses) throws Exception {
        JsonNode state=stored(s);assertEquals(hp,state.path("variables").path(s.valueKey).asInt());assertEquals(outcome,state.path("themeRuntime").path("lastOutcome").path("outcome").asText());
        assertEquals(uses,state.path("themeRuntime").path("cooperation").path("facilities").path("tpl_node_"+s.nodeId).path("count").asInt(),state.toString());
        JsonNode read=theme(s);assertEquals(outcome,read.path("lastOutcome").path("outcome").asText(),read.toString());
        assertPersonalCardReadback(s,state);
        JsonNode usage=null;for(JsonNode quest:read.path("quests"))if(quest.path("nodeId").asLong()==s.nodeId)usage=quest.path("facility");assertNotNull(usage,read.toString());assertEquals(uses,usage.path("count").asInt());assertEquals(2-uses,usage.path("remainingCount").asInt());success(form("/api/topic/edit-detail",s.topicId,s.headers));success(form("/api/template/myinfo",s.templateId,s.headers));
    }
    private void assertPersonalCardReadback(Lifecycle s,JsonNode state) {
        JsonNode card=get("/api/play/theme/card?sessionId="+s.routeId,s.headers);
        if(state.path("themeRuntime").path("result").isObject()) {
            JsonNode data=success(card);assertEquals(state.path("themeRuntime").path("result").path("success"),data.path("success"));
            assertEquals(data,success(get("/api/play/theme/card?sessionId="+s.routeId,s.headers)));
        } else {rejected(card);assertTrue(card.path("msg").asText().contains("尚未结束"),card.toString());}
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void r5HttpTypeInUnlimitedAndTimerDeadlineSurviveReentry(boolean timer) throws Exception {
            ObjectNode c=r5("typeIn");if(timer)c.putObject("timer").put("enabled",true).put("durationSeconds",30).put("timeoutResult","FAILED");
            Lifecycle s=lifecycle(c,"BLOCK_HP_COST");act(s,"START_CHALLENGE","begin",json.createObjectNode().put("game","typeIn"));
            JsonNode before=advanced(s);JsonNode reopened=success(post("/api/play/advanced/start",json.createObjectNode().put("topicId",s.topicId).put("nodeId",s.nodeId),s.headers));
            assertEquals(before.path("deadlineAt"),reopened.path("deadlineAt"));if(timer)assertTrue(before.path("deadlineAt").asLong()>0,before.toString());
            else assertTrue(before.path("deadlineAt").isNull(),before.toString());
            ObjectNode body=actionBody(s,"SUBMIT_TYPE_IN","typed",json.createObjectNode().put("text","hello").put("elapsedMs",0));JsonNode judged=success(post("/api/play/advanced/action",body,s.headers));assertEquals(judged,success(post("/api/play/advanced/action",body,s.headers)));
            ObjectNode settle=json.createObjectNode().put("sessionId",s.routeId).put("expectedVersion",theme(s).path("version").asInt()).put("actionId","settle").put("operation","QUEST_RESOLVE").put("contentId","node_"+s.nodeId);
            JsonNode done=success(post("/api/play/theme/gameplay-action",settle,s.headers));assertEquals(done,success(post("/api/play/theme/gameplay-action",settle,s.headers)));assertOutcome(s,"PASS",5,1);
    }
    @Test void r5HttpPassInsufficientCostKeepsProofAndDoesNotConsumeSuccessfulUse() throws Exception {
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(context.getBean(com.chengyinhub.business.service.ITopicRouteRuntimeService.class));Object original=org.springframework.test.util.ReflectionTestUtils.getField(target,"d20");
        // Existing injected dice seam fixes PASS; real HTTP reroll spends luck after affordability preflight.
        org.springframework.test.util.ReflectionTestUtils.setField(target,"d20",(java.util.function.IntSupplier)()->20);
        try {
        ObjectNode c=r5("check");((ObjectNode)c.path("outcomeRules").path("pass").path("costs").path(0)).put("amount",2);
        Lifecycle s=lifecycle(c,"BLOCK_HP_COST","sys.luck");act(s,"ROLL_CHECK","roll",json.createObjectNode());act(s,"REROLL_CHECK","reroll",json.createObjectNode());
        assertEquals(1,stored(s).path("variables").path("sys.luck").asInt());act(s,"SUBMIT_CHECK","submit",json.createObjectNode());
        ObjectNode body=json.createObjectNode().put("sessionId",s.routeId).put("expectedVersion",theme(s).path("version").asInt()).put("actionId","blocked-pass").put("operation","QUEST_RESOLVE").put("contentId","node_"+s.nodeId);
        JsonNode result=success(post("/api/play/theme/gameplay-action",body,s.headers));assertEquals(result,success(post("/api/play/theme/gameplay-action",body,s.headers)));
        assertEquals("BLOCKED_COST",result.path("settlement").path("status").asText(),result.toString());assertOutcome(s,"PASS",1,0);
        assertEquals("node_"+s.nodeId,stored(s).path("themeRuntime").path("gameplay").path("activeQuest").asText());assertTrue(advanced(s).path("readyForBase").asBoolean());
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(target,"d20",original);}
    }
    @Test void r5HttpFailClampsAndFreezesFailureCard() throws Exception {
        Lifecycle s=lifecycle(r5("qa"),"THEME_FAIL","sys.hp");ObjectNode b=actionBody(s,"SUBMIT_QA","wrong",json.createObjectNode().put("input","wrong"));JsonNode result=success(post("/api/play/advanced/action",b,s.headers));assertEquals(result,success(post("/api/play/advanced/action",b,s.headers)));assertOutcome(s,"FAIL",0,0);
        JsonNode last=stored(s).path("themeRuntime").path("lastOutcome");assertEquals(9,last.path("costs").path(0).path("requested").asInt(),last.toString());assertEquals(6,last.path("costs").path(0).path("charged").asInt());assertFalse(last.toString().contains("BLOCKED_COST"));
        JsonNode card=success(get("/api/play/theme/card?sessionId="+s.routeId,s.headers));assertFalse(card.path("success").asBoolean(true));assertEquals(card,success(get("/api/play/theme/card?sessionId="+s.routeId,s.headers)));rejected(route(s,"QUEST_START","closed"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"easy","medium","hard"})
    void r5HttpCheckRollRerollSubmitHasAuthoritativeActions(String tier) throws Exception {
        ObjectNode c=r5("check");c.with("check").put("tier",tier);Lifecycle s=lifecycle(c,"BLOCK_HP_COST");assertTrue(advanced(s).path("check").path("allowedActions").toString().contains("ROLL_CHECK"));
        act(s,"ROLL_CHECK","roll",json.createObjectNode());JsonNode rolled=advanced(s);assertTrue(rolled.path("check").path("allowedActions").toString().contains("SUBMIT_CHECK"));
        act(s,"REROLL_CHECK","reroll",json.createObjectNode());assertFalse(advanced(s).path("check").path("allowedActions").toString().contains("REROLL_CHECK"));
        ObjectNode b=actionBody(s,"SUBMIT_CHECK","submit",json.createObjectNode());JsonNode done=success(post("/api/play/advanced/action",b,s.headers));assertEquals(done,success(post("/api/play/advanced/action",b,s.headers)));
        String outcome=done.path("check").path("success").asBoolean()?"PASS":"FAIL";if("PASS".equals(outcome)){success(route(s,"QUEST_RESOLVE","confirm"));assertOutcome(s,"PASS",5,1);}else assertOutcome(s,"FAIL",0,0);
    }

    @Test void r5HttpAlbumAcceptsOwnedEvidenceAndRefusesForeignEvidence() throws Exception {
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(context.getBean(com.chengyinhub.business.service.IAdvancedGameRuntimeService.class));
        Object original=org.springframework.test.util.ReflectionTestUtils.getField(target,"photoEvidenceVerifier");
        // Existing IT upload-verifier seam: HTTP/transactions are real, OSS metadata is local evidence.
        org.springframework.test.util.ReflectionTestUtils.setField(target,"photoEvidenceVerifier",(java.util.function.BiConsumer<String,Long>)(url,member)->{if(!url.equals("/profile/lc-owned-"+member+".png"))throw new IllegalArgumentException("foreign upload");});
        try {
            Lifecycle s=lifecycle(r5("album"),"BLOCK_HP_COST");
            ObjectNode foreign=json.createObjectNode();
            foreign.putArray("imageUrls").add("/profile/lc-owned-0.png");
            String before=stored(s).toString();
            rejected(post("/api/play/advanced/action",actionBody(s,"SUBMIT_ALBUM","foreign",foreign),s.headers));
            assertEquals(before,stored(s).toString());
            ObjectNode owned=json.createObjectNode();
            owned.putArray("imageUrls").add("/profile/lc-owned-"+s.memberId+".png");
            ObjectNode body=actionBody(s,"SUBMIT_ALBUM","album",owned);
            JsonNode done=success(post("/api/play/advanced/action",body,s.headers));
            assertTrue(done.path("playKit").path("album").path("done").asBoolean(),done.toString());
            assertEquals(done,success(post("/api/play/advanced/action",body,s.headers)));
            success(route(s,"QUEST_RESOLVE","confirm"));
            assertOutcome(s,"PASS",5,1);
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(target,"photoEvidenceVerifier",original);}
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"PASS","FAIL","CLOSED"})
    void r5HttpSettledFeedbackCanReopenThroughTheComponentStartPath(String outcome) throws Exception {
        Lifecycle s=lifecycle(feedbackConfig(),"BLOCK_HP_COST","sys.hp");
        boolean passed=!"FAIL".equals(outcome);
        act(s,"SUBMIT_QA","answer",json.createObjectNode().put("input",passed?"correct":"wrong"));
        if(passed)success(route(s,"QUEST_RESOLVE","confirm"));
        if("CLOSED".equals(outcome))success(post("/api/play/theme/action",json.createObjectNode()
                .put("sessionId",s.routeId).put("expectedVersion",theme(s).path("version").asInt())
                .put("actionId","close-feedback").put("operation","END"),s.headers));
        JsonNode before=advanced(s);assertEquals("feedback",before.path("branch").path("currentStepId").asText());
        assertEquals(!"CLOSED".equals(outcome),before.path("branch").path("writable").asBoolean());
        JsonNode routeBefore=stored(s);
        // advanced-game opens a node through /start; a /state-only readback misses this reentry gate.
        JsonNode reopened=success(post("/api/play/advanced/start",json.createObjectNode()
                .put("topicId",s.topicId).put("nodeId",s.nodeId),s.headers));
        assertEquals(s.advancedId,reopened.path("sessionId").asLong());
        assertEquals(before.path("branch"),reopened.path("branch"));
        assertEquals(before.path("version"),reopened.path("version"));
        assertEquals(routeBefore,stored(s),"Reopen must not charge costs or settle the route again");
    }
    private ObjectNode feedbackConfig() {
        ObjectNode c=r5("qa");for(String result:new String[]{"pass","fail"})c.with("outcomeRules").with(result).put("nextStepId","feedback");
        ArrayNode steps=c.putObject("branch").put("enabled",true).put("startStepId","feedback").putArray("steps");
        steps.addObject().put("id","feedback").put("title","反馈").putArray("options").addObject().put("id","continue").put("label","继续").put("nextStepId","done").putArray("effects").addObject().put("var","vital").put("op","INC").put("value",1);
        steps.addObject().put("id","done").put("terminal",true).putArray("options");return c;
    }
    @Test void r5HttpFeedbackRunsAfterOutcomeOnceAndClosedSessionRefusesChoice() throws Exception {
        for(boolean close:new boolean[]{false,true}){
            Lifecycle s=lifecycle(feedbackConfig(),"BLOCK_HP_COST");rejected(post("/api/play/advanced/action",actionBody(s,"CHOOSE","early",json.createObjectNode().put("optionId","continue")),s.headers));
            act(s,"SUBMIT_QA","wrong",json.createObjectNode().put("input","wrong"));assertOutcome(s,"FAIL",0,0);JsonNode receipt=stored(s).path("themeRuntime").path("lastOutcome").deepCopy();
            if(close){success(post("/api/play/theme/action",json.createObjectNode().put("sessionId",s.routeId).put("expectedVersion",theme(s).path("version").asInt()).put("actionId","end").put("operation","END"),s.headers));rejected(post("/api/play/advanced/action",actionBody(s,"CHOOSE","closed",json.createObjectNode().put("optionId","continue")),s.headers));}
            else {ObjectNode body=actionBody(s,"CHOOSE","choice",json.createObjectNode().put("optionId","continue"));JsonNode done=success(post("/api/play/advanced/action",body,s.headers));assertEquals(done,success(post("/api/play/advanced/action",body,s.headers)));assertEquals(1,stored(s).path("variables").path(s.valueKey).asInt());assertTrue(advanced(s).path("branch").path("ended").asBoolean());}
            assertEquals(receipt,stored(s).path("themeRuntime").path("lastOutcome"));theme(s);
        }
    }
    @Test void r5HttpStepsRecoveryEpisodeUsesVerifiedFixtureAndRestoresExactlyOnce() throws Exception {
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(context.getBean(com.chengyinhub.business.service.ITopicRouteRuntimeService.class));Object original=org.springframework.test.util.ReflectionTestUtils.getField(target,"verifiedWeRunService");
        org.springframework.test.util.ReflectionTestUtils.setField(target,"verifiedWeRunService",R5WeRunFixture.service());
        try {
            Lifecycle s=lifecycle(r5("qa"),"STEPS","sys.hp");act(s,"SUBMIT_QA","wrong",json.createObjectNode().put("input","wrong"));assertOutcome(s,"FAIL",0,0);JsonNode zero=theme(s).path("zeroHp");String episode=zero.path("episodeId").asText();assertFalse(episode.isEmpty(),zero.toString());assertTrue(zero.path("allowedActions").toString().contains("ZERO_HP_STEPS"));assertEquals(episode,theme(s).path("zeroHp").path("episodeId").asText());
            ObjectNode body=json.createObjectNode().put("sessionId",s.routeId).put("expectedVersion",theme(s).path("version").asInt()).put("actionId","baseline").put("operation","ZERO_HP_STEPS");body.set("weRun",R5WeRunFixture.today(System.currentTimeMillis(),s.memberId,episode,1000));JsonNode first=success(post("/api/play/theme/gameplay-action",body,s.headers));assertEquals(first,success(post("/api/play/theme/gameplay-action",body,s.headers)));assertEquals(0,theme(s).path("zeroHp").path("progress").asInt());
            body.put("expectedVersion",theme(s).path("version").asInt()).put("actionId","restore");body.set("weRun",R5WeRunFixture.today(System.currentTimeMillis(),s.memberId,episode,1100));JsonNode restored=success(post("/api/play/theme/gameplay-action",body,s.headers));assertEquals(restored,success(post("/api/play/theme/gameplay-action",body,s.headers)));assertEquals(4,stored(s).path("variables").path("sys.hp").asInt());assertEquals(1,stored(s).path("themeRuntime").path("zeroHp").path("used").asInt());theme(s);assertEquals(1,stored(s).path("themeRuntime").path("zeroHp").path("used").asInt());assertOutcome(s,"FAIL",4,0);
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(target,"verifiedWeRunService",original);}
    }
    @Test void r5HttpWaitReentryKeepsServerDeadlineAndRestoreIsOnce() throws Exception {
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(context.getBean(com.chengyinhub.business.service.ITopicRouteRuntimeService.class));Object original=org.springframework.test.util.ReflectionTestUtils.getField(target,"clock");
        try {
            Lifecycle s=lifecycle(r5("qa"),"WAIT","sys.hp");act(s,"SUBMIT_QA","wrong",json.createObjectNode().put("input","wrong"));assertOutcome(s,"FAIL",0,0);JsonNode zero=theme(s).path("zeroHp");long deadline=zero.path("deadlineAt").asLong();assertTrue(deadline>System.currentTimeMillis());assertEquals(deadline,theme(s).path("zeroHp").path("deadlineAt").asLong());success(route(s,"ZERO_HP_SYNC","early"));assertEquals(0,stored(s).path("variables").path("sys.hp").asInt());
            // Existing service clock seam avoids sleeping; episode/deadline/state remain in real MySQL.
            org.springframework.test.util.ReflectionTestUtils.setField(target,"clock",java.time.Clock.fixed(java.time.Instant.ofEpochMilli(deadline),java.time.ZoneId.of("Asia/Shanghai")));
            ObjectNode body=json.createObjectNode().put("sessionId",s.routeId).put("expectedVersion",theme(s).path("version").asInt()).put("actionId","wait-restore").put("operation","ZERO_HP_SYNC");JsonNode restored=success(post("/api/play/theme/gameplay-action",body,s.headers));assertEquals(restored,success(post("/api/play/theme/gameplay-action",body,s.headers)));assertEquals(4,stored(s).path("variables").path("sys.hp").asInt());assertEquals(1,stored(s).path("themeRuntime").path("zeroHp").path("used").asInt());theme(s);assertEquals(1,stored(s).path("themeRuntime").path("zeroHp").path("used").asInt());assertOutcome(s,"FAIL",4,0);
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(target,"clock",original);}
    }

    @Test void r5HttpIndependentVoteAdoptsTeamWorldAndPlayerScopeRefusesWithReason() throws Exception {
        ObjectNode c=r5("qa");ObjectNode tools=c.putObject("interactionTools").put("votePolicy","MAJORITY");tools.putArray("plans").addObject().put("planKey","continue").put("title","继续前行").putArray("materialCost").addObject().put("itemId","stock").put("quantity",1);c.with("interactionRules").withArray("resourceSlots").addObject().put("key","stock").put("kind","ITEM").put("label","投入物资").put("required",true);
        Lifecycle s=lifecycle(c,"BLOCK_HP_COST","counter.energy","TEAM",true);JsonNode frozen=persistedTopic(s.topicId,"vote-team-world");assertEquals("TEAM",frozen.path("published").path("gameplayRuntime").path("runtimeScope").asText());assertEquals("WORLD_SHARED",frozen.path("published").path("gameplayRuntime").path("worldMode").asText());assertTrue(frozen.path("published").path("gameplayRuntime").path("plans").size()>0,frozen.toString());assertEquals("paper",frozen.path("published").path("gameplayRuntime").path("plans").path(0).path("materialCost").path(0).path("itemId").asText());
        JsonNode read=success(form("/api/topic/edit-detail",s.topicId,s.headers));assertTrue(read.toString().contains("MAJORITY"));
        ObjectNode topic=topicRequest("PLAYER投票拒绝");long topicId=success(post("/api/topic/v2/create",topic,s.headers)).path("topicId").asLong();topic.put("id",topicId);refreshChapterIds(topic,topicId);topic.put("journeyRules","{\"schemaVersion\":1,\"stateEnabled\":true,\"attributes\":[{\"key\":\"counter.energy\",\"label\":\"能量\",\"initial\":6,\"min\":0,\"max\":12}]}");ObjectNode reg=json.createObjectNode().put("topicId",topicId);reg.putArray("keys").addObject().put("stateKey","counter.energy").put("keyType","counter").put("scope","run").put("label","能量").put("initialValue",6);success(post("/api/topic/state-keys",reg,s.headers));ObjectNode ch=(ObjectNode)topic.path("chapters").path(0);ch.withArray("nodes").addObject().put("clientNodeKey","vote-player").put("name","投票站").put("templateId",s.templateId).put("nodeTime",0).put("nodeMoney",0).put("latitude","31.2304").put("longitude","121.4737").put("address","本地位置");ch.withArray("blocks").addObject().put("type","node").put("nodeIndex",0);ObjectNode theme=json.createObjectNode().put("schemaVersion",1).put("capacity",6);theme.putObject("gameplayRuntime").put("runtimeScope","PLAYER");ObjectNode b=theme.putObject("templateBindings").putObject("client:vote-player").put("templateId",s.templateId).put("slotDigest",GameplayTemplateRules.slotDigest(c.path("interactionRules").path("resourceSlots")));b.putObject("values").put("vital","counter.energy");b.putObject("items").put("stock","paper");theme.putArray("items").addObject().put("id","paper").put("kind","ITEM").put("name","纸张").put("image",mediaFixture.approvedImage(context, database, s.memberId)).put("slotCost",1);topic.put("themeRuntimeConfig",theme.toString());JsonNode refusal=post("/api/topic/v2/update",topic,s.headers);rejected(refusal);assertTrue(refusal.path("msg").asText().contains("投票")||refusal.path("msg").asText().contains("公共世界"),refusal.toString());
    }
    @Test void legacyHttpFrozenScanSessionIgnoresLaterR5TemplateEdit() throws Exception {
        ObjectNode legacy=json.createObjectNode().put("schemaVersion",1);legacy.putObject("interactionRules").put("limit",2).putArray("resourceSlots").addObject().put("key","vital").put("kind","VALUE").put("label","能量").put("required",true);legacy.putArray("effects").addObject().put("var","vital").put("op","INC").put("value",1);
        Lifecycle s=lifecycle(legacy,"BLOCK_HP_COST");String frozen=database.queryForObject("SELECT config_snapshot_json FROM topic_route_session WHERE id=?",String.class,s.routeId);assertFalse(frozen.contains("outcomeRules"));
        success(post("/api/template/draft",json.createObjectNode().put("id",s.templateId).put("title","旧模板改为R5").put("validationMethod",4).put("isSync",0).put("advancedConfigJson",r5("qa").toString()),s.headers));assertEquals(frozen,database.queryForObject("SELECT config_snapshot_json FROM topic_route_session WHERE id=?",String.class,s.routeId));
        JsonNode old=theme(s);assertFalse(old.toString().contains("gameplayDecision"));ObjectNode body=json.createObjectNode().put("sessionId",s.routeId).put("expectedVersion",theme(s).path("version").asInt()).put("actionId","old-confirm").put("operation","QUEST_RESOLVE").put("contentId","node_"+s.nodeId);JsonNode done=success(post("/api/play/theme/gameplay-action",body,s.headers));assertEquals(done,success(post("/api/play/theme/gameplay-action",body,s.headers)));assertEquals(7,stored(s).path("variables").path(s.valueKey).asInt());assertFalse(stored(s).path("themeRuntime").has("lastOutcome"));assertEquals(frozen,database.queryForObject("SELECT config_snapshot_json FROM topic_route_session WHERE id=?",String.class,s.routeId));success(form("/api/template/myinfo",s.templateId,s.headers));theme(s);
    }

    private JsonNode gameCommand(long activityId,String action,String key,ObjectNode payload,org.springframework.http.HttpHeaders headers) {
        JsonNode view=success(get("/api/game/session/view?activityId="+activityId+"&perspective=CLUB",headers));
        ObjectNode body=json.createObjectNode().put("activityId",activityId).put("expectedRevision",view.path("revision").asInt()).put("requestId",key).put("action",action);
        if(view.path("authorityEpoch").isInt())body.set("authorityEpoch",view.path("authorityEpoch"));body.set("payload",payload);return success(post("/api/game/session/command",body,headers));
    }
    private Lifecycle teamLifecycle(ObjectNode config) throws Exception {
        Lifecycle s=lifecycle(config,"BLOCK_HP_COST","counter.energy","TEAM",true);
        String clubName="LC真实本地主理团"+s.memberId;
        long club=success(post("/api/club/apply",json.createObjectNode().put("name",clubName).put("leaderName","LC主理人"),s.headers)).path("clubId").asLong();assertTrue(club>0);
        assertEquals(1,database.queryForObject("SELECT COUNT(*) FROM club WHERE id=? AND member_id=? AND name=? AND del_flag=0",Integer.class,club,s.memberId,clubName));
        // Existing local activity/paid/scope fixtures; topic publication and all runtime state/actions use HTTP.
        database.update("INSERT INTO cms_activity(member_id,topic_id,club_id,name,status,publish_status,del_flag,start_date,end_date) VALUES(?,?,?,'LC隔离场次',1,1,0,?,?)",s.memberId,s.topicId,club,new java.sql.Timestamp(System.currentTimeMillis()-3600000),new java.sql.Timestamp(System.currentTimeMillis()+3600000));
        long activity=database.queryForObject("SELECT id FROM cms_activity WHERE topic_id=? AND name='LC隔离场次' ORDER BY id DESC LIMIT 1",Long.class,s.topicId);
        database.update("INSERT INTO coop_order(topic_id,from_id,to_id,to_type,status,del_flag,create_time) VALUES(?,?,?,'club',0,0,NOW())",s.topicId,s.memberId,club);
        String phone="139"+String.format("%08d",transcript.size());org.springframework.http.HttpHeaders partner=loginHeaders(phone,"LC队员");long member=database.queryForObject("SELECT id FROM ums_member WHERE phone=?",Long.class,phone);
        for(long player:new long[]{s.memberId,member})database.update("INSERT INTO cms_registration(registration_no,member_id,owner_type,owner_id,topic_id,registration_status,payment_status,purchase_kind,original_amount,total_amount,payable_amount,pay_expire_time,create_time) VALUES(?,?,2,?,?,2,2,0,0,0,0,DATE_ADD(NOW(),INTERVAL 1 DAY),NOW())","lc-team-"+activity+"-"+player,player,activity,s.topicId);
        JsonNode team=success(post("/api/team/create",json.createObjectNode().put("ownerType",2).put("ownerId",activity).put("maxMembers",4).put("joinMode",1),s.headers));long teamId=team.path("teamId").asLong();assertTrue(teamId>0,team.toString());
        success(post("/api/team/join",json.createObjectNode().put("inviteCode",team.path("team").path("inviteCode").asText()),partner));
        gameCommand(activity,"PREPARE","prepare",json.createObjectNode(),s.headers);gameCommand(activity,"START","start",json.createObjectNode(),s.headers);
        JsonNode arrival=gameCommand(activity,"ARRIVAL_ISSUE","arrival-issue",json.createObjectNode().put("questKey","node_"+s.nodeId),s.headers);
        String nonce=arrival.path("result").path("nonce").asText();assertFalse(nonce.isEmpty(),arrival.toString());
        gameCommand(activity,"ARRIVAL_CONFIRM","arrival-confirm",json.createObjectNode().put("teamId",teamId).put("questKey","node_"+s.nodeId).put("nonce",nonce),s.headers);
        if(config.path("interactionTools").path("plans").isEmpty())gameCommand(activity,"QUEST_START","quest",json.createObjectNode().put("questKey","node_"+s.nodeId),s.headers);
        s.activityId=activity;s.teamId=teamId;s.partner=partner;return s;
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"easy","medium","hard"})
    void r5HttpTeamCheckRerollIsCaptainOnlyAndSharedSettlementIsIdempotent(String tier) throws Exception {
        ObjectNode config=r5("check");
        config.with("check").put("tier",tier);
        Lifecycle s=teamLifecycle(config);
        long activity=s.activityId,teamId=s.teamId;
        org.springframework.http.HttpHeaders partner=s.partner;
        JsonNode opened=success(post("/api/play/advanced/start",json.createObjectNode().put("topicId",s.topicId).put("nodeId",s.nodeId).put("activityId",activity).put("teamId",teamId),s.headers));
        s.advancedId=opened.path("sessionId").asLong();
        assertEquals(2,opened.path("ownerType").asInt(),opened.toString());
        act(s,"ROLL_CHECK","roll",json.createObjectNode());
        JsonNode captain=advanced(s);
        JsonNode other=success(get("/api/play/advanced/state?sessionId="+s.advancedId,partner));
        com.chengyinhub.business.domain.JourneyRules.Tiers tiers=new com.chengyinhub.business.domain.JourneyRules().getTiers();
        assertEquals(("easy".equals(tier)?tiers.getEasy():"medium".equals(tier)?tiers.getMedium():tiers.getHard()).getDc(),captain.path("check").path("dc").asInt());
        assertEquals(tier,captain.path("check").path("tier").asText());
        assertTrue(captain.path("check").path("allowedActions").toString().contains("REROLL_CHECK"));
        assertFalse(other.path("check").path("allowedActions").toString().contains("REROLL_CHECK"));
        rejected(post("/api/play/advanced/action",actionBody(s,"REROLL_CHECK","member-reroll",json.createObjectNode()),partner));
        act(s,"REROLL_CHECK","captain-reroll",json.createObjectNode());
        ObjectNode submit=actionBody(s,"SUBMIT_CHECK","submit",json.createObjectNode());
        JsonNode outcome=success(post("/api/play/advanced/action",submit,s.headers));
        assertEquals(outcome,success(post("/api/play/advanced/action",submit,s.headers)));
        boolean passed=outcome.path("check").path("success").asBoolean();
        if(passed){JsonNode view=success(get("/api/game/session/view?activityId="+activity+"&perspective=CLUB",s.headers));
        ObjectNode settle=json.createObjectNode().put("activityId",activity).put("expectedRevision",view.path("revision").asInt()).put("authorityEpoch",view.path("authorityEpoch").asInt()).put("requestId","settle").put("action","QUEST_RESOLVE");
        settle.putObject("payload").put("questKey","node_"+s.nodeId);
        JsonNode done=success(post("/api/game/session/command",settle,s.headers));
        JsonNode repeat=success(post("/api/game/session/command",settle,s.headers));
        assertEquals(done.path("result"),repeat.path("result"));
        assertTrue(repeat.path("replayed").asBoolean());
        }
        JsonNode player=success(get("/api/game/session/view?activityId="+activity+"&perspective=PLAYER",s.headers)).path("player");
        assertEquals(passed?"PASS":"FAIL",player.path("lastOutcome").path("outcome").asText(),player.toString());
        assertFalse(player.has("resultCard"),player.toString());
        JsonNode usage=null;for(JsonNode tool:player.path("interactionTools"))if(tool.path("nodeId").asLong()==s.nodeId)usage=tool;assertNotNull(usage,player.toString());assertEquals(passed?1:0,usage.path("count").asInt());assertEquals(passed?1:2,usage.path("remainingCount").asInt());
        JsonNode owner=json.readTree(database.queryForObject("SELECT variables_json FROM game_story_state WHERE game_session_id=(SELECT id FROM game_session WHERE activity_id=?) AND owner_type='TEAM' AND owner_id=?",String.class,activity,teamId));
        assertEquals(passed?5:0,owner.path("variables").path(s.valueKey).asInt());
        assertEquals(passed?1:0,owner.path("themeRuntime").path("cooperation").path("facilities").path("tpl_node_"+s.nodeId).path("count").asInt());
        success(form("/api/topic/edit-detail",s.topicId,s.headers));
        success(form("/api/template/myinfo",s.templateId,s.headers));
    }

    @Test void r5HttpTeamVoteStakeExecutesOnceAndReopensPublishedTemplate() throws Exception {
        ObjectNode c=r5("qa");c.putObject("interactionTools").put("votePolicy","MAJORITY").putArray("plans").addObject().put("planKey","continue").put("title","继续前行").putArray("materialCost").addObject().put("itemId","stock").put("quantity",1);
        c.with("interactionRules").withArray("resourceSlots").addObject().put("key","stock").put("kind","ITEM").put("label","投入物资").put("required",true);
        Lifecycle s=teamLifecycle(c);long activity=s.activityId;
        JsonNode frozen=persistedTopic(s.topicId,"vote-runtime-frozen");String plan=frozen.path("published").path("gameplayRuntime").path("plans").path(0).path("endingKey").asText();assertFalse(plan.isEmpty());
        gameCommand(activity,"PLAN_PROPOSE","propose",json.createObjectNode().put("proposalKey","lc_proposal").put("endingKey",plan),s.headers);
        ObjectNode intent=json.createObjectNode().put("proposalKey","lc_proposal").put("version",1);intent.putArray("amounts").addObject().put("itemId","paper").put("quantity",1);gameCommand(activity,"PLAN_INTENT","stake",intent,s.headers);
        gameCommand(activity,"VOTE_OPEN","open-vote",json.createObjectNode(),s.headers);
        JsonNode world=json.readTree(database.queryForObject("SELECT variables_json FROM game_story_state WHERE game_session_id=(SELECT id FROM game_session WHERE activity_id=?) AND owner_type='WORLD'",String.class,activity));int round=world.path("themeRuntime").path("cooperation").path("round").asInt();assertTrue(round>0,world.toString());
        ObjectNode selected=json.createObjectNode().put("round",round).put("proposalKey","lc_proposal");gameCommand(activity,"VOTE_CAST","vote",selected,s.headers);
        gameCommand(activity,"OPERATION_START","vote-op",json.createObjectNode().put("operationKind","PLAN").put("operationKey",plan),s.headers);
        JsonNode opened=success(post("/api/play/advanced/start",json.createObjectNode().put("topicId",s.topicId).put("nodeId",s.nodeId).put("activityId",activity).put("teamId",s.teamId),s.headers));s.advancedId=opened.path("sessionId").asLong();act(s,"SUBMIT_QA","vote-proof",json.createObjectNode().put("input","correct"));
        gameCommand(activity,"PLAN_CONFIRM","confirm-stake",selected,s.headers);
        JsonNode view=success(get("/api/game/session/view?activityId="+activity+"&perspective=CLUB",s.headers));ObjectNode execute=json.createObjectNode().put("activityId",activity).put("expectedRevision",view.path("revision").asInt()).put("authorityEpoch",view.path("authorityEpoch").asInt()).put("requestId","execute").put("action","PLAN_EXECUTE");execute.set("payload",selected);
        JsonNode first=success(post("/api/game/session/command",execute,s.headers)),repeat=success(post("/api/game/session/command",execute,s.headers));assertEquals(first.path("result"),repeat.path("result"));assertTrue(repeat.path("replayed").asBoolean());
        JsonNode owner=json.readTree(database.queryForObject("SELECT variables_json FROM game_story_state WHERE game_session_id=(SELECT id FROM game_session WHERE activity_id=?) AND owner_type='TEAM' AND owner_id=?",String.class,activity,s.teamId));assertEquals(1,owner.path("themeRuntime").path("inventory").path("paper").asInt(),owner.toString());assertEquals(5,owner.path("variables").path(s.valueKey).asInt());assertEquals("PASS",owner.path("themeRuntime").path("lastOutcome").path("outcome").asText());assertEquals(1,owner.path("themeRuntime").path("cooperation").path("facilities").path("plan:"+s.nodeId).path("count").asInt(),owner.toString());
        JsonNode reopened=success(get("/api/game/session/view?activityId="+activity+"&perspective=PLAYER",s.headers));assertEquals("PASS",reopened.path("player").path("lastOutcome").path("outcome").asText(),reopened.toString());assertFalse(reopened.path("player").has("resultCard"),reopened.toString());JsonNode usage=null;for(JsonNode tool:reopened.path("player").path("interactionTools"))if(plan.equals(tool.path("endingKey").asText()))usage=tool;assertNotNull(usage,reopened.toString());assertEquals(1,usage.path("count").asInt());assertEquals(0,usage.path("remainingCount").asInt());success(form("/api/topic/edit-detail",s.topicId,s.headers));success(form("/api/template/myinfo",s.templateId,s.headers));
    }

    /**
     * 2026-10-22 owner 裁决(L07)：/use 与公共货架同口径，只要求已审核、在架且是模板，不再要求 VERIFIED。
     * 负控保留：另存为模板之前直链 /use 必须被拒且不落新行；另存后(无 VERIFIED 元数据)可复制出归属本人的新草稿。
     */
    private void assertFreshTemplateCopyFollowsPublishedEligibility(long topicId,org.springframework.http.HttpHeaders headers) throws Exception {
        int rows=database.queryForObject("SELECT COUNT(*) FROM cms_topic",Integer.class);
        JsonNode notTemplate=form("/api/template/topic-template/use",topicId,headers);
        rejected(notTemplate);assertEquals("该主题不是可用模板",notTemplate.path("msg").asText());
        assertEquals(rows,database.queryForObject("SELECT COUNT(*) FROM cms_topic",Integer.class));
        String before=database.queryForObject("SELECT theme_runtime_config_json FROM cms_topic WHERE id=?",String.class,topicId);
        success(form("/api/template/topic-template/save-as",topicId,headers));JsonNode source=persistedTopic(topicId,"marked-as-template");
        assertEquals(1,database.queryForObject("SELECT is_template FROM cms_topic WHERE id=?",Integer.class,topicId));
        assertEquals(0,database.queryForObject("SELECT COUNT(*) FROM cms_topic_template_meta WHERE topic_id=? AND template_status='VERIFIED' AND del_flag=0",Integer.class,topicId));
        assertEquals(before,database.queryForObject("SELECT theme_runtime_config_json FROM cms_topic WHERE id=?",String.class,topicId));
        JsonNode copied=success(form("/api/template/topic-template/use",topicId,headers));
        long copyId=copied.path("topicId").asLong();
        assertTrue(copyId>0&&copyId!=topicId,copied.toString());
        assertEquals(rows+1,database.queryForObject("SELECT COUNT(*) FROM cms_topic",Integer.class));
        assertEquals(source.path("memberId").asLong(),database.queryForObject("SELECT member_id FROM cms_topic WHERE id=?",Long.class,copyId));
        assertEquals(before,database.queryForObject("SELECT theme_runtime_config_json FROM cms_topic WHERE id=?",String.class,topicId));
    }
    private ObjectNode topicRequest(String name) {
        ObjectNode request=json.createObjectNode().put("name",name).put("saveDraft",1).put("publishMode","simple").put("productType",1).put("routeMode","LINEAR");
        ObjectNode chapter=request.putArray("chapters").addObject().put("name","第一章").put("schemaVersion",1);
        chapter.putArray("nodes");chapter.putArray("blocks").addObject().put("type","text").put("content","本地作者流程验证");return request;
    }
    private void refreshChapterIds(ObjectNode request,long topicId) {
        java.util.List<Long> ids=database.queryForList("SELECT id FROM cms_topic_chapter WHERE topic_id=? ORDER BY sort_id,id",Long.class,topicId);
        for(int index=0;index<ids.size();index++)((ObjectNode)request.path("chapters").path(index)).put("id",ids.get(index));
        request.put("configVersion",database.queryForObject("SELECT config_version FROM cms_topic WHERE id=?",Long.class,topicId));
    }
    private org.springframework.http.HttpHeaders loginHeaders(String phone,String nickname) {
        UmsMember member=new UmsMember();member.setPhone(phone);member.setNickname(nickname);member.setStatus(1L);member.setDelFlag(0);member.setWxOpenId("e2e-http-generic-"+phone);members.insertUmsMember(member);
        registerPublisherIdentity(context, database, member.getId());
        context.getBean(com.chengyinhub.business.service.ICapabilityService.class).grant(member.getId(),"club_leader",member.getId());
        MockHttpServletRequest request=new MockHttpServletRequest();request.setRemoteAddr("127.0.0.1");request.addHeader("User-Agent","Local HTTP E2E");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        org.springframework.http.HttpHeaders headers=new org.springframework.http.HttpHeaders();
        try {headers.setBearerAuth(login.loginByMember(member,"phone",phone,null));}finally {RequestContextHolder.resetRequestAttributes();}
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);return headers;
    }
    private JsonNode post(String path,JsonNode body,org.springframework.http.HttpHeaders headers) {
        ResponseEntity<JsonNode> response=http.exchange(path,org.springframework.http.HttpMethod.POST,new org.springframework.http.HttpEntity<>(body,headers),JsonNode.class);
        record("POST",path,body,response);return response.getBody();
    }
    private JsonNode form(String path,long id,org.springframework.http.HttpHeaders original) {
        org.springframework.http.HttpHeaders headers=new org.springframework.http.HttpHeaders();headers.putAll(original);headers.setContentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);
        org.springframework.util.LinkedMultiValueMap<String,String> body=new org.springframework.util.LinkedMultiValueMap<>();body.add("id",String.valueOf(id));
        ResponseEntity<JsonNode> response=http.exchange(path,org.springframework.http.HttpMethod.POST,new org.springframework.http.HttpEntity<>(body,headers),JsonNode.class);
        record("POST",path,json.createObjectNode().put("id",id),response);return response.getBody();
    }
    private JsonNode success(JsonNode response) {return appliedResponseData(response);}
    private void rejected(JsonNode response) {assertNotEquals(200,response.path("code").asInt(),response.toString());}
    private JsonNode persistedTopic(long id,String phase) throws Exception {
        java.util.Map<String,Object> row=database.queryForMap("SELECT id,member_id,is_template,journey_rules_json,journey_story_json,theme_runtime_config_json,published_theme_runtime_config_json,published_route_graph_json FROM cms_topic WHERE id=?",id);
        ObjectNode values=json.createObjectNode().put("topicId",id).put("phase",phase).put("memberId",((Number)row.get("member_id")).longValue());
        values.set("isTemplate",json.valueToTree(row.get("is_template")));
        for(String[] field:new String[][]{{"journey_rules_json","rules"},{"journey_story_json","story"},{"theme_runtime_config_json","theme"},{"published_theme_runtime_config_json","published"},{"published_route_graph_json","snapshot"}})
            if(row.get(field[0])!=null)values.set(field[1],json.readTree(row.get(field[0]).toString()));
        transcript.addObject().put("method","DB_READBACK").put("path","cms_topic:"+id).put("httpStatus",0).set("response",values);return values;
    }
    static JsonNode redact(JsonNode node) {
        if(node==null)return null;JsonNode copy=node.deepCopy();
        if(copy.isObject()){ObjectNode object=(ObjectNode)copy;object.fields().forEachRemaining(e->{if(java.util.Arrays.asList("invitecode","nonce","token","quotesign","authorization","password").contains(e.getKey().toLowerCase(java.util.Locale.ROOT)))object.put(e.getKey(),"[REDACTED]");else object.set(e.getKey(),redact(e.getValue()));});}
        else if(copy.isArray())for(int i=0;i<copy.size();i++)((ArrayNode)copy).set(i,redact(copy.get(i)));return copy;
    }
    private void record(String method,String path,JsonNode request,ResponseEntity<JsonNode> response) {
        com.fasterxml.jackson.databind.node.ObjectNode row = transcript.addObject().put("method",method).put("path",path)
                .put("authorization","[REDACTED]").put("httpStatus",response.getStatusCode().value());
        if(request!=null){JsonNode safe=request.deepCopy();if(safe.path("weRun").isObject()){ObjectNode weRun=(ObjectNode)safe.path("weRun");weRun.remove(java.util.Arrays.asList("code","encryptedData","iv"));weRun.put("fixture","R5WeRunFixture: local AES and mocked WeChat exchange");}row.set("request",redact(safe));}
        row.set("response",redact(response.getBody()));
    }
}
