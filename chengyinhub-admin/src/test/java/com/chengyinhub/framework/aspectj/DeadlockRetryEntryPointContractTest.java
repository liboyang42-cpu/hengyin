package com.chengyinhub.framework.aspectj;

import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.business.service.impl.TopicCompletionAwardServiceImpl;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.common.annotation.DeadlockRetry;
import com.chengyinhub.web.controller.api.ApiPlayJourneyActionController;
import com.chengyinhub.web.controller.api.ApiPlayProgressController;
import com.chengyinhub.web.controller.api.ApiRegistrationController;
import com.chengyinhub.web.service.PreferencePlayService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #294 死锁重试的落点契约（lane H3 补测）：拦截器在进入时已有活动事务就不重试，
 * 所以注解只有落在<b>最外层</b>事务边界上才生效。DeadlockRetryInterceptorTest 证明了拦截器本身
 * （次数上限 2、只认 1213/1205、内层参与者不重试），PlayerGrowthConcurrencyMySqlIT 证明了真库落库结果；
 * 这里钉住生产入口：写成长/通关事实的每条路径，最外层都带 @DeadlockRetry + @Transactional，
 * 而它们的调用方不再包一层事务（否则重试被静默降级为内层参与者）。
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
                    if (!method.isAnnotationPresent(DeadlockRetry.class) || !method.isAnnotationPresent(Transactional.class)) {
                        missing.add(entry.getKey().getSimpleName() + "." + name + Arrays.toString(method.getParameterTypes()));
                    }
                }
            }
        }
        assertEquals(List.of(), missing, "这些最外层事务入口缺 @DeadlockRetry 或 @Transactional");
        assertEquals(12, checked, "入口数与 #294 审计口径不符：新增/删除重载需重新做副作用审计");
    }

    /** 新加的游玩端点（带请求映射）只要推进节点进度（会写成长与通关事实），就必须自己就是带重试的最外层事务。 */
    @Test
    void everyPlayEndpointThatAdvancesProgressOwnsTheRetryBoundary() throws Exception {
        TreeSet<String> callers = new TreeSet<>();
        List<String> violations = new ArrayList<>();
        Map<String, String[]> playMethods = methods(source(PLAY));
        for (Map.Entry<String, String[]> method : playMethods.entrySet()) {
            String name = method.getKey();
            String annotations = method.getValue()[0];
            String body = method.getValue()[1];
            if (!annotations.contains("Mapping(") || !reachesAdvanceProgress(playMethods, name, new java.util.HashSet<>())) {
                continue;
            }
            callers.add(name);
            if (body.contains("preferencePlayService.submit(")) {
                // 偏好题组的事务与重试在 PreferencePlayService.submit 上；controller 再开事务会把它降成内层
                if (annotated(annotations, "Transactional")) violations.add(name + " 不应自带事务");
            } else if (!annotated(annotations, "DeadlockRetry") || !annotated(annotations, "Transactional")) {
                violations.add(name + " 缺 @DeadlockRetry/@Transactional");
            }
        }
        assertEquals(List.of(), violations);
        assertTrue(callers.containsAll(List.of("answer", "arrive", "checkin", "photo", "revealPuzzle",
                "sensorResult", "submitPreference")), "advanceProgress 调用方识别失败: " + callers);
    }

    /** 命令入口仍拥有最外层事务；沿真实私有 helper 调用查找推进，不能把未映射 helper 当入口。 */
    private static boolean reachesAdvanceProgress(Map<String, String[]> methods, String name, java.util.Set<String> seen) {
        if (!seen.add(name)) return false;
        String body = methods.get(name)[1];
        if (body.contains("advanceProgress(")) return true;
        for (Map.Entry<String, String[]> helper : methods.entrySet()) {
            if (!helper.getValue()[1].stripLeading().startsWith("private ")) continue;
            String helperName = helper.getKey().replaceFirst("#\\d+$", "");
            if (Pattern.compile("\\b" + Pattern.quote(helperName) + "\\s*\\(").matcher(body).find()
                    && reachesAdvanceProgress(methods, helper.getKey(), seen)) return true;
        }
        return false;
    }

    /** 服务层重试边界的调用方必须不开事务，否则进入服务时已有活动事务、重试不生效。 */
    @Test
    void callersOfServiceLevelRetryBoundariesStayNonTransactional() throws Exception {
        assertFalse(ApiPlayJourneyActionController.class.isAnnotationPresent(Transactional.class));
        assertFalse(ApiRegistrationController.class.isAnnotationPresent(Transactional.class));
        assertFalse(ApiPlayProgressController.class.isAnnotationPresent(Transactional.class));
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
        assertEquals(new TreeSet<>(List.of("ApiPlayProgressController.java", "CmsRegistrationServiceImpl.java",
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
