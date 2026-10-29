package com.chengyinhub.business.it;

import com.chengyinhub.RuoYiApplication;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.framework.web.service.AppLoginService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Test-classpath child only. No fixture controller, synthetic command response, or production hook.
 * Host owns physical SIGKILL/restart; these observers only hold a real MVC command at a boundary. */
public final class GameplayProcessRecoveryChild {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Path PRIVATE = Path.of("/recovery-private");
    static final String COMMAND = "/api/club/lead/unlock-chapter";
    static final String PROFILE = "gameplay-process-recovery-only";
    static final String BOOT = UUID.randomUUID().toString();
    static JsonNode config;

    public static void main(String[] args) throws Exception {
        require(args.length == 0, "child argv is fixed");
        require("true".equals(System.getenv("CHENGYIN_CI_GAMEPLAY_PROCESS_RECOVERY")), "explicit test admission missing");
        require(Runtime.version().feature() == 21, "JDK21 required");
        require(Files.isDirectory(PRIVATE) && !Files.isSymbolicLink(PRIVATE), "private mount missing");
        config = JSON.readTree(Files.readAllBytes(PRIVATE.resolve("config.json")));
        require(config.path("sourceSha").asText().matches("[0-9a-f]{40}"), "full source required");
        require(config.path("schema").asText().matches("cy_e2e_auth_[0-9a-f]{32}"), "dedicated existing harness schema only");
        require(config.path("sourceSha").asText().equals(System.getenv("GITHUB_SHA")), "source mismatch");
        String origin = GameplayProcessRecoveryChild.class.getProtectionDomain().getCodeSource().getLocation().toString();
        require(origin.startsWith("file:/recovery-testclasses"), "child must be outside normal application JAR");
        require(Files.isRegularFile(Path.of("/application.jar")), "original immutable JAR missing");
        require(sha(Files.readAllBytes(Path.of("/application.jar"))).equals(config.path("jarSha256").asText()), "JAR changed");
        try (java.io.InputStream bytes = GameplayProcessRecoveryChild.class.getResourceAsStream("GameplayProcessRecoveryChild.class")) {
            require(bytes != null && sha(bytes.readAllBytes()).equals(config.path("childClassSha256").asText()), "test child changed");
        }
        require(!config.has("packagedSeedOnly") || config.path("packagedSeedOnly").isBoolean(),
                "typed packaged seed mode required");
        if (config.path("packagedSeedOnly").asBoolean(false)) {
            require("true".equals(System.getenv("CHENGYIN_CI_PACKAGED_GAMEPLAY_CAPACITY")),
                    "packaged seed admission missing");
            require(config.path("capacityItems").asInt() == 72, "fixed packaged seed required");
            require(!Files.exists(PRIVATE.resolve("identities.json")), "packaged seed must be exclusive");
        }
        SpringApplication app = new SpringApplication(RuoYiApplication.class, RecoveryConfiguration.class, LostReplyObserver.class);
        app.setAdditionalProfiles(PROFILE);
        ConfigurableApplicationContext context = app.run(
                "--spring.config.location=file:/fixture/application.properties",
                "--server.port=8080", "--server.address=127.0.0.1", "--spring.main.banner-mode=off",
                "--spring.messages.basename=i18n/messages",
                "--spring.datasource.druid.master.url=" + config.path("jdbcUrl").asText(),
                "--spring.datasource.druid.master.username=startup_probe",
                "--spring.datasource.druid.master.password=synthetic-local-only",
                "--spring.data.redis.host=" + config.path("redisHost").asText(),
                "--token.secret=" + config.path("tokenSecret").asText(),
                "--coupon.qr-secret=" + config.path("tokenSecret").asText());
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        require(context.getBeansOfType(LostReplyObserver.class).size() == 1, "one test-only reply observer required");
        require(config.path("serverUuid").asText().equals(jdbc.queryForObject("SELECT @@server_uuid", String.class)), "wrong MySQL server");
        require(config.path("schema").asText().equals(jdbc.queryForObject("SELECT DATABASE()", String.class)), "wrong schema");
        Path identities = PRIVATE.resolve("identities.json");
        if (!Files.exists(identities)) seed(context, jdbc, identities);
        ObjectNode ready = JSON.createObjectNode().put("bootId", BOOT).put("javaPid", ProcessHandle.current().pid())
                .put("sourceSha", config.path("sourceSha").asText()).put("jarSha256", config.path("jarSha256").asText())
                .put("childClassSha256", config.path("childClassSha256").asText())
                .put("configSha256", sha(Files.readAllBytes(PRIVATE.resolve("config.json"))))
                .put("port", ((ServletWebServerApplicationContext) context).getWebServer().getPort());
        write(PRIVATE.resolve("ready.json"), ready);
        if (config.path("packagedSeedOnly").asBoolean(false)) {
            require("true".equals(System.getenv("CHENGYIN_CI_PACKAGED_GAMEPLAY_CAPACITY")),
                    "packaged seed admission missing");
            require(config.path("capacityItems").asInt() == 72, "fixed packaged seed required");
            // This is only fixture preparation. It must exit before the host launches the real
            // immutable JAR main with no child/test classpath and the same original DB/actors.
            context.close();
            return;
        }
        // Keep the actual web application alive. The host must prove physical death and a new boot.
        context.registerShutdownHook();
    }

    private static void seed(ConfigurableApplicationContext context, JdbcTemplate jdbc, Path file) throws Exception {
        write(file, seedRows(context, jdbc, config.path("capacityItems").asInt()));
    }

    static JsonNode seedCombination(ConfigurableApplicationContext context, JdbcTemplate jdbc, Path file) throws Exception {
        Path parent = file.toAbsolutePath().getParent().toRealPath();
        require(parent.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath())
                && parent.equals(file.toAbsolutePath().getParent()) && !Files.isSymbolicLink(file)
                && file.getFileName().toString().equals("identities.json") && !Files.exists(file), "owned new temp identity file required");
        Path pending = parent.resolve("identities.json.new");
        require(!Files.exists(pending) && !Files.isSymbolicLink(pending), "owned pending identity must be absent");
        ObjectNode identities = seedRows(context, jdbc, 72);
        try {
            // Freeze JSON bytes: a parsed small integer need not retain its original Jackson node width.
            byte[] frozenIdentityBytes = JSON.writeValueAsBytes(identities);
            Files.write(pending, frozenIdentityBytes, StandardOpenOption.CREATE_NEW);
            Files.setPosixFilePermissions(pending, PosixFilePermissions.fromString("rw-------"));
            Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE);
            byte[] storedIdentityBytes = Files.readAllBytes(file);
            require(MessageDigest.isEqual(frozenIdentityBytes, storedIdentityBytes), "controlled identity readback mismatch");
            return JSON.readTree(storedIdentityBytes);
        } finally { Files.deleteIfExists(pending); }
    }

    private static ObjectNode seedRows(ConfigurableApplicationContext context, JdbcTemplate jdbc, int capacityItems) throws Exception {
        require(jdbc.queryForObject("SELECT COUNT(*) FROM club_lead_state", Integer.class) == 0, "fixture is not fresh");
        require(jdbc.queryForObject("SELECT COUNT(*) FROM game_command_receipt", Integer.class) == 0, "receipt store is not fresh");
        ObjectNode identities = JSON.createObjectNode();
        long[] actors = new long[2];
        for (int i = 0; i < 2; i++) {
            UmsMember member = new UmsMember();
            member.setPhone("13800000" + (i == 0 ? "901" : "902"));
            member.setNickname("进程恢复夹具" + i); member.setStatus(1L); member.setDelFlag(0);
            member.setWxOpenId("recovery-" + UUID.randomUUID());
            require(context.getBean(UmsMemberMapper.class).insertUmsMember(member) == 1, "member fixture insert failed");
            actors[i] = member.getId();
            // Only the production login service creates JWT/Redis sessions; never set a principal.
            HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                    GameplayProcessRecoveryChild.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class}, (proxy, method, values) -> {
                        if (method.getName().equals("getRemoteAddr")) return "127.0.0.1";
                        if (method.getName().equals("getHeader")) return values[0].equals("User-Agent") ? "Isolated gameplay child" : null;
                        if (method.getName().equals("getAttribute")) return null;
                        if (method.getReturnType().equals(boolean.class)) return false;
                        if (method.getReturnType().equals(int.class)) return 0;
                        if (method.getReturnType().equals(long.class)) return 0L;
                        return null;
                    });
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            String token;
            try { token = context.getBean(AppLoginService.class).loginByMember(member, "phone", member.getPhone(), null); }
            finally { RequestContextHolder.resetRequestAttributes(); }
            identities.putObject(i == 0 ? "a" : "b").put("memberId", member.getId()).put("token", token);
        }
        long club = insert(jdbc, "INSERT INTO club(member_id,name,del_flag) VALUES(?, 'process-recovery-fixture',0)", actors[0]);
        java.util.List<String> cases = new java.util.ArrayList<>(java.util.List.of("BEFORE_TX", "COMMITTED_NO_REPLY", "ACKED"));
        require(capacityItems == 0 || capacityItems == 72, "fixed workload budget required");
        for (int i = 0; i < capacityItems; i++) cases.add(String.format(java.util.Locale.ROOT, "CAPACITY_%02d", i));
        for (String phase : cases) {
            long topic = insert(jdbc, "INSERT INTO cms_topic(member_id,name,del_flag) VALUES(?,'process-recovery-fixture',0)", actors[0]);
            long first = insert(jdbc, "INSERT INTO cms_topic_chapter(topic_id,name,sort_id) VALUES(?,'first',1)", topic);
            long second = insert(jdbc, "INSERT INTO cms_topic_chapter(topic_id,name,sort_id) VALUES(?,'second',2)", topic);
            long third = insert(jdbc, "INSERT INTO cms_topic_chapter(topic_id,name,sort_id) VALUES(?,'third',3)", topic);
            long activity = insert(jdbc, "INSERT INTO cms_activity(topic_id,member_id,club_id,del_flag) VALUES(?,?,?,0)", topic, actors[0], club);
            jdbc.update("INSERT INTO club_lead_state(activity_id,leader_member_id,current_chapter_id,status,create_time) VALUES(?,?,?,2,NOW())",
                    activity, actors[0], first);
            identities.putObject(phase).put("activityId", activity).put("commandId", "process-" + UUID.randomUUID())
                    .put("expectedChapterId", first).put("targetChapterId", second).put("thirdChapterId", third);
        }
        return identities;
    }

    private static long insert(JdbcTemplate jdbc, String sql, Object... values) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        require(jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, key) == 1 && key.getKey() != null, "fixture insert failed");
        return key.getKey().longValue();
    }

    static void boundary(HttpServletRequest request, String stage) throws Exception {
        Path arm = PRIVATE.resolve("arm.json");
        if (!Files.exists(arm)) return;
        JsonNode command = JSON.readTree(Files.readAllBytes(arm));
        if (!stage.equals(command.path("stage").asText()) || !COMMAND.equals(request.getRequestURI())) return;
        if (!command.path("commandId").asText().equals(request.getParameter("commandId"))) return;
        require(command.path("activityId").asText().equals(request.getParameter("activityId")), "boundary target mismatch");
        require(!TransactionSynchronizationManager.isActualTransactionActive(), "boundary holds an application transaction");
        ObjectNode reached = JSON.createObjectNode().put("stage", stage).put("bootId", BOOT)
                .put("javaPid", ProcessHandle.current().pid()).put("commandId", command.path("commandId").asText())
                .put("activityId", command.path("activityId").asLong());
        write(PRIVATE.resolve("boundary.json"), reached);
        // Timeout is a hard failure, never a synthetic business success or provider conclusion.
        TimeUnit.SECONDS.sleep(90);
        throw new IllegalStateException("Expected physical process termination did not occur");
    }

    @Configuration(proxyBeanMethods = false)
    @Profile(PROFILE)
    public static class RecoveryConfiguration {
        @Bean WebMvcConfigurer gameplayRecoveryBoundary() {
            return new WebMvcConfigurer() {
                @Override public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(new HandlerInterceptor() {
                        @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                            boundary(request, "BEFORE_TX"); return true;
                        }
                    }).addPathPatterns(COMMAND);
                }
            };
        }
    }

    @ControllerAdvice
    @Profile(PROFILE)
    public static class LostReplyObserver implements ResponseBodyAdvice<Object> {
        @Override public boolean supports(MethodParameter parameter, Class<? extends HttpMessageConverter<?>> converter) {
            return parameter.getMethod() != null
                    && parameter.getMethod().getDeclaringClass().getName().equals("com.chengyinhub.web.controller.api.ApiClubLeadController")
                    && parameter.getMethod().getName().equals("unlockChapter");
        }
        @Override public Object beforeBodyWrite(Object body, MethodParameter parameter, MediaType media,
                Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest input, ServerHttpResponse output) {
            if (input instanceof org.springframework.http.server.ServletServerHttpRequest servlet) {
                try { boundary(servlet.getServletRequest(), "COMMITTED_NO_REPLY"); }
                catch (Exception failure) { throw new IllegalStateException("Physical lost-reply boundary failed", failure); }
            }
            return body; // The unmodified production response, only after the real transaction returns.
        }
    }

    static String sha(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    static void write(Path path, JsonNode value) throws Exception {
        Path pending = PRIVATE.resolve(path.getFileName() + ".new");
        Files.write(pending, JSON.writeValueAsBytes(value));
        Files.setPosixFilePermissions(pending, PosixFilePermissions.fromString("rw-------"));
        Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
