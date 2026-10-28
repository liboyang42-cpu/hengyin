package com.chengyinhub.framework.aspectj;

import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.business.service.impl.TopicCompletionAwardServiceImpl;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.common.annotation.DeadlockRetry;
import com.chengyinhub.web.controller.api.ApiPlayJourneyActionController;
import com.chengyinhub.web.controller.api.ApiPlayProgressController;
import com.chengyinhub.web.controller.api.ApiRegistrationController;
import com.chengyinhub.web.service.PreferencePlayService;
import com.chengyinhub.web.service.play.PlayBasicCommandApplication;
import com.chengyinhub.web.service.play.PlayPhotoCommandApplication;
import com.chengyinhub.web.service.play.PlayPuzzleCommandApplication;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #294 死锁重试的落点契约（lane H3 补测）：拦截器在进入时已有活动事务就不重试，
 * 所以注解只有落在<b>最外层</b>事务边界上才生效。DeadlockRetryInterceptorTest 证明了拦截器本身
 * （次数上限 2、只认 1213/1205、内层参与者不重试），PlayerGrowthConcurrencyMySqlIT 证明了真库落库结果；
 * 这里钉住生产入口：游玩 HTTP 的 @DeadlockRetry 包裹应用代理的唯一事务；其他既有服务
 * 仍由同一服务方法持有重试+事务。Controller 不开启事务，失败应用事务先完成回滚再重入。
 */
class DeadlockRetryEntryPointContractTest {

    private static final String PLAY = "src/main/java/com/chengyinhub/web/controller/api/ApiPlayProgressController.java";
    private static final String JOURNEY = "src/main/java/com/chengyinhub/web/controller/api/ApiPlayJourneyActionController.java";
    private static final String REGISTRATION = "src/main/java/com/chengyinhub/web/controller/api/ApiRegistrationController.java";
    private static final String ROUTE_RUNTIME =
            "../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicRouteRuntimeServiceImpl.java";

    /** 生产上会写成长/通关事实的最外层事务入口（#294 审计口径，master 063a6c3c 复核）。 */
    @Test
    void outermostTransactionalEntryPointsCarryTheRetry() {
        Map<Class<?>, List<String>> entries = new LinkedHashMap<>();
        entries.put(ApiPlayProgressController.class,
                Arrays.asList("checkin", "answer", "revealPuzzle", "sensorResult", "arrive", "photo"));
        entries.put(PreferencePlayService.class, Arrays.asList("submit"));
        entries.put(TopicRouteRuntimeServiceImpl.class, Arrays.asList("completeOpening"));
        entries.put(CmsRegistrationServiceImpl.class, Arrays.asList("redeemExploreChapterAndRecord"));
        entries.put(TopicCompletionAwardServiceImpl.class, Arrays.asList("grantOnce"));

        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<Class<?>, List<String>> entry : entries.entrySet()) {
            for (String name : entry.getValue()) {
                // controller 里未映射的便捷重载（如 checkin(a, c, t)）不是 HTTP 入口，主代码无调用方，只计映射的那一个
                boolean controller = entry.getKey().isAnnotationPresent(RestController.class);
                List<Method> overloads = Arrays.stream(entry.getKey().getDeclaredMethods())
                        .filter(m -> m.getName().equals(name) && !m.isSynthetic() && !m.isBridge())
                        .filter(m -> !controller || AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class))
                        .collect(Collectors.toList());
                assertFalse(overloads.isEmpty(), entry.getKey().getSimpleName() + "." + name + " 不存在");
                for (Method method : overloads) {
                    checked++;
                    if (!method.isAnnotationPresent(DeadlockRetry.class)
                            || (!controller && !method.isAnnotationPresent(Transactional.class))) {
                        missing.add(entry.getKey().getSimpleName() + "." + name + Arrays.toString(method.getParameterTypes()));
                    }
                    if (controller) {
                        assertFalse(method.isAnnotationPresent(Transactional.class), "HTTP retry must be outside the application transaction");
                        Class<?> owner = applicationOwner(name);
                        List<Method> applicationEntries = Arrays.stream(owner.getDeclaredMethods())
                                .filter(candidate -> candidate.getName().equals(name)
                                        && candidate.getParameterCount() == method.getParameterCount() + 1)
                                .collect(Collectors.toList());
                        assertEquals(1, applicationEntries.size(), method + " must delegate to exactly one actor-first application entry");
                        assertPlayTransaction(applicationEntries.get(0));
                        assertFalse(applicationEntries.get(0).isAnnotationPresent(DeadlockRetry.class), "only the HTTP outer boundary retries");
                    }
                }
            }
        }
        assertEquals(List.of(), missing, "这些原重试入口缺 @DeadlockRetry 或所属应用/服务事务");
        // W8 审计：PreferencePlayService.submit 新增带 originalPlayInstance 的重载（生产唯一调用方
        // PlayPreferenceApplication 走它），旧重载以 "default" 自调用委托，副作用集合不变。
        assertEquals(13, checked, "入口数与 #294 审计口径不符：新增/删除重载需重新做副作用审计");
    }

    /** Progress HTTP entries retain outer retry and delegate transaction ownership to the actual application. */
    @Test
    void everyPlayEndpointThatAdvancesProgressOwnsTheRetryBoundary() throws Exception {
        TreeSet<String> callers = new TreeSet<>();
        List<String> violations = new ArrayList<>();
        Map<String, String[]> playMethods = methods(source(PLAY));
        Map<String, String[]> graph = new LinkedHashMap<>(playMethods);
        for (String owner : List.of("PlayBasicCommandApplication", "PlayPhotoCommandApplication",
                "PlayPuzzleCommandApplication", "PlayPreferenceApplication", "PlayNodeCompletionApplication")) {
            String bean = Character.toLowerCase(owner.charAt(0)) + owner.substring(1);
            methods(source("src/main/java/com/chengyinhub/web/service/play/" + owner + ".java"))
                    .forEach((key, value) -> graph.put(bean + "." + key, value));
        }
        for (Map.Entry<String, String[]> method : playMethods.entrySet()) {
            String name = method.getKey();
            String annotations = method.getValue()[0];
            String body = method.getValue()[1];
            if (!annotations.contains("Mapping(") || !reachesCall(graph, name, "advanceProgress(", new java.util.HashSet<>())) {
                continue;
            }
            callers.add(name);
            if (reachesCall(graph, name, "preferencePlayService.submit(", new java.util.HashSet<>())) {
                // 偏好题组的事务与重试在 PreferencePlayService.submit 上；controller 再开事务会把它降成内层
                if (annotated(annotations, "Transactional")) violations.add(name + " 不应自带事务");
            } else if (!annotated(annotations, "DeadlockRetry") || annotated(annotations, "Transactional")) {
                violations.add(name + " 必须只持有外层 @DeadlockRetry，由应用持有事务");
            }
        }
        assertEquals(List.of(), violations);
        assertTrue(callers.containsAll(List.of("answer", "arrive", "checkin", "photo", "revealPuzzle",
                "sensorResult", "submitPreference")), "advanceProgress 调用方识别失败: " + callers);
    }

    /** Real Spring transaction advice with mocked JDBC only; this is not storage/MySQL evidence. */
    @Test
    void playHttpRetryReentersTheActualPhotoApplicationAfterRollbackAndConnectionRelease() throws Exception {
        PlayBoundary boundary = playBoundary(true);
        var result = boundary.controller().photo("500", "401", "media-asset:71", null, "photo-original-1", 0,
                88L, null, 0);
        assertEquals(500, result.get("code"));
        assertEquals("节点不存在", result.get("msg"));
        List<Object> originalCommand = Arrays.asList(7L, "500", "401", "media-asset:71", null, "photo-original-1", 0,
                88L, null, 0);
        assertEquals(List.of(originalCommand, originalCommand), boundary.commands(),
                "IR-T07: retry must preserve original actor, scope, media, command identity and expected version");
        assertEquals(List.of("identity", "begin1", "node1", "rollback1", "close1",
                "identity", "begin2", "node2", "commit2", "close2"), boundary.events());
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void photoAndRevealCompatibilityOverloadsEnterTheSameWritableApplicationTransaction() throws Exception {
        PlayBoundary reveal = playBoundary(false);
        assertEquals("节点不存在", reveal.controller().revealPuzzle("500", "401", null, "reveal-original-1", 0,
                88L, null, 0).get("msg"));
        assertEquals(List.of("identity", "begin1", "node1", "commit1", "close1"), reveal.events());
        assertEquals(List.of(Arrays.asList(7L, "500", "401", null, "reveal-original-1", 0, 88L, null, 0)), reveal.commands());
        // W8 起不带原运行时身份的旧重载一律拒绝（与 PlayBasicCommandApplication 的旧重载同口径）：
        // 仍进同一写事务，但在读节点之前就拒，不会推进进度。
        PlayBoundary photo = playBoundary(false);
        assertEquals("必须提供原运行时身份", photo.controller().photo("500", "401", "media-asset:71", null).get("msg"));
        assertEquals(List.of("identity", "begin1", "commit1", "close1"), photo.events());
        assertEquals(List.of(Arrays.asList(7L, "500", "401", "media-asset:71", null)), photo.commands());
        PlayBoundary legacyReveal = playBoundary(false);
        assertEquals("必须提供原运行时身份", legacyReveal.controller().revealPuzzle("500", "401", null).get("msg"));
        assertEquals(List.of("identity", "begin1", "commit1", "close1"), legacyReveal.events());
        assertEquals(List.of(Arrays.asList(7L, "500", "401", null)), legacyReveal.commands());
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
    }

    private record PlayBoundary(ApiPlayProgressController controller, List<String> events,
            List<List<Object>> commands) { }

    private static PlayBoundary playBoundary(boolean deadlockFirst) throws Exception {
        List<String> events = new ArrayList<>();
        List<List<Object>> commands = new ArrayList<>();
        javax.sql.DataSource dataSource = org.mockito.Mockito.mock(javax.sql.DataSource.class);
        java.sql.Connection first = boundaryConnection(events, 1);
        java.sql.Connection second = boundaryConnection(events, 2);
        org.mockito.Mockito.when(dataSource.getConnection()).thenReturn(first, second);
        var transactions = new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        var nodes = org.mockito.Mockito.mock(com.chengyinhub.business.service.ICmsTopicNodeService.class);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.when(nodes.selectCmsTopicNodeById(401L)).thenAnswer(call -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertEquals(Integer.valueOf(java.sql.Connection.TRANSACTION_READ_COMMITTED),
                    org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            int attempt = attempts.incrementAndGet(); events.add("node" + attempt);
            if (deadlockFirst && attempt == 1)
                throw new org.springframework.dao.DeadlockLoserDataAccessException("1213", null);
            return null;
        });
        ApiPlayProgressController controller = new ApiPlayProgressController() {
            @Override public Long getAppUserId() {
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                events.add("identity"); return 7L;
            }
        };
        Object[][] applications = {
                {new PlayPhotoCommandApplication(), "playPhotoCommandApplication"},
                {new PlayPuzzleCommandApplication(), "playPuzzleCommandApplication"}
        };
        // 原局意图闸（回执/版本/身份）不在本边界测试范围内：直接执行命令体，事务与重试仍是真实 advice。
        var intents = org.mockito.Mockito.mock(com.chengyinhub.web.service.play.PlayBasicIntentApplication.class);
        org.mockito.Mockito.when(intents.executeBasicIntent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> call.<java.util.function.Function<Object, Object>>getArgument(12).apply(null));
        for (Object[] row : applications) {
            org.springframework.test.util.ReflectionTestUtils.setField(row[0], "cmsTopicNodeService", nodes);
            org.springframework.test.util.ReflectionTestUtils.setField(row[0], "playBasicIntentApplication", intents);
            var application = new org.springframework.aop.framework.ProxyFactory(row[0]);
            application.setProxyTargetClass(true);
            application.addAdvice((org.springframework.aop.MethodBeforeAdvice) (method, arguments, target) -> {
                if (method.getName().equals("photo") || method.getName().equals("revealPuzzle")) {
                    assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                    commands.add(java.util.Collections.unmodifiableList(Arrays.asList(arguments.clone())));
                }
            });
            application.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(transactions,
                    new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
            org.springframework.test.util.ReflectionTestUtils.setField(controller, (String) row[1], application.getProxy());
        }
        var outer = new org.springframework.aop.framework.ProxyFactory(controller);
        outer.setProxyTargetClass(true);
        outer.addAdvisor(com.chengyinhub.framework.config.DeadlockRetryConfig.deadlockRetryAdvisor());
        return new PlayBoundary((ApiPlayProgressController) outer.getProxy(), events, commands);
    }

    private static java.sql.Connection boundaryConnection(List<String> events, int id) throws Exception {
        java.sql.Connection connection = org.mockito.Mockito.mock(java.sql.Connection.class);
        org.mockito.Mockito.when(connection.getAutoCommit()).thenReturn(true);
        org.mockito.Mockito.when(connection.getTransactionIsolation()).thenReturn(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        org.mockito.Mockito.doAnswer(call -> { if (Boolean.FALSE.equals(call.getArgument(0))) events.add("begin" + id); return null; })
                .when(connection).setAutoCommit(org.mockito.ArgumentMatchers.anyBoolean());
        org.mockito.Mockito.doAnswer(call -> { events.add("rollback" + id); return null; }).when(connection).rollback();
        org.mockito.Mockito.doAnswer(call -> { events.add("commit" + id); return null; }).when(connection).commit();
        org.mockito.Mockito.doAnswer(call -> { events.add("close" + id); return null; }).when(connection).close();
        return connection;
    }

    private static Class<?> applicationOwner(String command) {
        return switch (command) {
            case "checkin", "answer", "sensorResult", "arrive" -> PlayBasicCommandApplication.class;
            case "photo" -> PlayPhotoCommandApplication.class;
            case "revealPuzzle" -> PlayPuzzleCommandApplication.class;
            default -> throw new AssertionError("Unreviewed play transaction owner: " + command);
        };
    }

    private static void assertPlayTransaction(Method method) {
        Transactional tx = method.getAnnotation(Transactional.class);
        assertNotNull(tx, method + " must own the application transaction");
        assertEquals(Propagation.REQUIRED, tx.propagation(), method.toString());
        assertEquals(Isolation.READ_COMMITTED, tx.isolation(), method.toString());
        assertFalse(tx.readOnly(), method.toString());
        assertEquals(List.of(Exception.class), Arrays.asList(tx.rollbackFor()), method.toString());
    }

    /** Follow the actual application owner and same-owner helpers; HTTP retains the outer retry. */
    private static boolean reachesCall(Map<String, String[]> methods, String name, String call, java.util.Set<String> seen) {
        if (!seen.add(name)) return false;
        String body = methods.get(name)[1];
        if (body.contains(call)) return true;
        String owner = name.contains(".") ? name.substring(0, name.lastIndexOf('.') + 1) : "";
        for (Map.Entry<String, String[]> helper : methods.entrySet()) {
            String key = helper.getKey().replaceFirst("#\\d+$", "");
            String targetOwner = key.contains(".") ? key.substring(0, key.lastIndexOf('.') + 1) : "";
            String target = targetOwner.equals(owner) ? key.substring(targetOwner.length()) : key;
            if (Pattern.compile("(?<![\\w.])" + Pattern.quote(target) + "\\s*\\(").matcher(body).find()
                    && reachesCall(methods, helper.getKey(), call, seen)) return true;
        }
        return false;
    }

    /** 服务层重试边界的调用方必须不开事务，否则进入服务时已有活动事务、重试不生效。 */
    @Test
    void callersOfServiceLevelRetryBoundariesStayNonTransactional() throws Exception {
        assertFalse(ApiPlayJourneyActionController.class.isAnnotationPresent(Transactional.class));
        assertFalse(ApiRegistrationController.class.isAnnotationPresent(Transactional.class));
        for (Method method : ApiRegistrationController.class.getDeclaredMethods())
            assertFalse(method.isAnnotationPresent(Transactional.class), method + " must not start a Controller transaction");
        assertFalse(ApiPlayProgressController.class.isAnnotationPresent(Transactional.class));
        for (Method method : ApiPlayProgressController.class.getDeclaredMethods())
            assertFalse(method.isAnnotationPresent(Transactional.class), method + " must not start a Controller transaction");
        // Short photo/reveal application overloads self-delegate: their proxy entries need the same transaction.
        for (Class<?> owner : List.of(PlayPhotoCommandApplication.class, PlayPuzzleCommandApplication.class)) {
            for (Method method : owner.getDeclaredMethods()) {
                if (method.getName().equals("photo") || method.getName().equals("revealPuzzle")
                        || method.getName().equals("puzzleHint")) assertPlayTransaction(method);
            }
        }
        assertFalse(PreferencePlayService.class.isAnnotationPresent(Transactional.class));

        assertNonTransactionalCallers(JOURNEY, "topicRouteRuntimeService.completeOpening(", 1);
        assertNonTransactionalCallers(REGISTRATION, "registrationService.redeemExploreChapterAndRecord(", 1);
        assertNonTransactionalCallers(REGISTRATION, "doRedeemExploreChapter(", 2);

        // completeOpening 内部经私有 grantOpeningAward 发通关奖励；它只能在 completeOpening 里被调
        Map<String, String[]> runtime = methods(source(ROUTE_RUNTIME));
        List<String> openingAwardCallers = runtime.entrySet().stream()
                .filter(e -> !e.getKey().equals("grantOpeningAward") && e.getValue()[1].contains("grantOpeningAward("))
                .map(Map.Entry::getKey).collect(Collectors.toList());
        assertEquals(List.of("completeOpening"), openingAwardCallers);
    }

    /** 通关奖励只有这三处主代码调用方；新增调用方要先按 #294 口径确认外层边界再改这里。 */
    @Test
    void topicCompletionAwardHasNoUnreviewedCaller() throws Exception {
        TreeSet<String> callers = new TreeSet<>();
        for (String module : List.of("../chengyinhub-admin", "../chengyinhub-system", "../chengyinhub-framework")) {
            Path root = Paths.get(module, "src/main/java").normalize();
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    if (new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                            .contains("topicCompletionAwardService.grantOnce(")) {
                        callers.add(path.getFileName().toString());
                    }
                }
            }
        }
        assertEquals(new TreeSet<>(List.of("PlayNodeCompletionApplication.java", "CmsRegistrationServiceImpl.java",
                "TopicRouteRuntimeServiceImpl.java")), callers);
    }

    private static void assertNonTransactionalCallers(String file, String call, int expected) throws Exception {
        List<String> callers = new ArrayList<>();
        for (Map.Entry<String, String[]> method : methods(source(file)).entrySet()) {
            String body = method.getValue()[1];
            if (body.indexOf(call, body.indexOf('(') + 1) < 0) continue;
            callers.add(method.getKey());
            assertFalse(annotated(method.getValue()[0], "Transactional"),
                    file + "#" + method.getKey() + " 包了事务，" + call + " 的重试将失效");
        }
        assertEquals(expected, callers.size(), call + " 调用方: " + callers);
    }

    /**
     * 去掉注释后按 4 空格缩进的方法声明切块：[0] 为声明前紧邻的注解区（含多行注解的续行），[1] 为声明到下一方法前的正文。
     * 同名重载以 #n 区分。
     */
    private static Map<String, String[]> methods(String source) {
        String code = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
        Pattern declaration = Pattern.compile("(?m)^    (?:public|private|protected)[^=;\\n]*?\\s(\\w+)\\(");
        Matcher matcher = declaration.matcher(code);
        List<int[]> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            int start = matcher.start();
            int annotations = start;
            while (true) {
                int previous = code.lastIndexOf('\n', annotations - 2);
                if (previous < 0) break;
                String line = code.substring(previous + 1, annotations - 1);
                if (!line.startsWith("    @") && !line.startsWith("        ")) break;
                annotations = previous + 1;
            }
            starts.add(new int[] {annotations, start});
            names.add(matcher.group(1));
        }
        Map<String, String[]> chunks = new LinkedHashMap<>();
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1)[0] : code.length();
            String key = names.get(i);
            for (int n = 2; chunks.containsKey(key); n++) key = names.get(i) + "#" + n;
            chunks.put(key, new String[] {code.substring(starts.get(i)[0], starts.get(i)[1]),
                    code.substring(starts.get(i)[1], end)});
        }
        return chunks;
    }

    /** 注解区里有没有这个注解（简单名或全限定名都算）。 */
    private static boolean annotated(String annotations, String simpleName) {
        return Pattern.compile("@(?:[\\w.]+\\.)?" + simpleName + "\\b").matcher(annotations).find();
    }

    private static String source(String relative) throws Exception {
        Path path = Paths.get(relative).normalize();
        if (!Files.exists(path)) path = Paths.get("chengyinhub-admin").resolve(relative).normalize();
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
