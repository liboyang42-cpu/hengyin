package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.ICashWithdrawService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * f-money / 审计 A-5（P0-11 俱乐部快提）：{@code withdrawal.fast-withdraw.enabled} 必须默认关闭，
 * 直到业主定下提现口径（实付，还是应结 + 调整）。
 *
 * <p>三层锁：①两个读开关的字段，其 {@code @Value} 在没有任何外部配置时解析为 false；
 * ②仓库里随 jar 打包的配置（所有模块 {@code src/main/resources} 下的 application*.yml / *.properties）
 * 都没有把它打开，且用打包的 application.yml 实际解析仍是 false；③默认值下钱包页报告关闭、提现请求在进 service 前被拒。</p>
 */
class FastWithdrawDefaultOffTest {

    private static final String KEY = "withdrawal.fast-withdraw.enabled";
    private static final List<Class<?>> READERS = List.of(ApiWalletController.class, ApiClubSettlementController.class);

    private static String valueExpression(Class<?> type) throws NoSuchFieldException {
        Field f = type.getDeclaredField("fastWithdrawEnabled");
        Value v = f.getAnnotation(Value.class);
        assertTrue(v != null, type.getSimpleName() + ".fastWithdrawEnabled 必须由 @Value 注入");
        assertEquals(boolean.class, f.getType());
        return v.value();
    }

    @Test
    void everyReaderDefaultsToFalseWithNoExternalConfig() throws Exception {
        for (Class<?> type : READERS) {
            String expr = valueExpression(type);
            assertEquals("${" + KEY + ":false}", expr, type.getSimpleName() + " 的默认值必须是 false");
            assertEquals("false", new StandardEnvironment().resolvePlaceholders(expr));
        }
    }

    @Test
    void packagedApplicationYamlLeavesItOff() throws Exception {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        Resource packaged = new ClassPathResource("application.yml");
        assertTrue(packaged.exists(), "classpath 上找不到 application.yml");
        yaml.setResources(packaged);
        Properties props = yaml.getObject();
        assertFalse(props.containsKey(KEY), "打包的 application.yml 不得配置 " + KEY);
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> map = new HashMap<>();
        props.forEach((k, v) -> map.put(String.valueOf(k), v));
        env.getPropertySources().addFirst(new MapPropertySource("packaged", map));
        for (Class<?> type : READERS) {
            assertEquals("false", env.resolvePlaceholders(valueExpression(type)));
        }
    }

    @Test
    void noPackagedConfigFileTurnsItOn() throws IOException {
        Path root = Files.exists(Paths.get("chengyinhub-system")) ? Paths.get("") : Paths.get("..");
        Pattern on = Pattern.compile("(?im)(fast-withdraw\\s*[:.].*enabled|fast-withdraw\\.enabled)\\s*[:=]\\s*\\$?\\{?[^\\n]*true");
        Pattern mention = Pattern.compile("(?i)fast-withdraw");
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : (Iterable<Path>) modules.filter(p -> p.getFileName().toString().startsWith("chengyinhub-"))::iterator) {
                Path res = module.resolve("src/main/resources");
                if (!Files.isDirectory(res)) continue;
                try (Stream<Path> files = Files.walk(res)) {
                    for (Path f : (Iterable<Path>) files.filter(p -> p.toString().matches(".*\\.(ya?ml|properties)$"))::iterator) {
                        scanned++;
                        String text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                        if (on.matcher(text).find() || mention.matcher(text).find()) offenders.add(root.relativize(f).toString());
                    }
                }
            }
        }
        assertTrue(scanned > 0);
        assertEquals(List.of(), offenders, "随 jar 打包的配置不得出现 fast-withdraw 开关（只能由部署方显式打开）");
    }

    @Test
    void negativeControlRecognisesAnEnablingLine() {
        Pattern on = Pattern.compile("(?im)(fast-withdraw\\s*[:.].*enabled|fast-withdraw\\.enabled)\\s*[:=]\\s*\\$?\\{?[^\\n]*true");
        assertTrue(on.matcher("withdrawal.fast-withdraw.enabled=true").find());
        assertTrue(on.matcher("withdrawal.fast-withdraw.enabled: ${FAST:true}").find());
    }

    @Test
    void walletReportsOffAndWithdrawIsRejectedBeforeAnyMoneyMovesAtTheDefault() throws Exception {
        ApiWalletController wallet = new ApiWalletController();
        ICashWithdrawService svc = mock(ICashWithdrawService.class);
        ReflectionTestUtils.setField(wallet, "cashWithdrawService", svc);
        ReflectionTestUtils.setField(wallet, "fastWithdrawEnabled",
                Boolean.parseBoolean(new StandardEnvironment().resolvePlaceholders(valueExpression(ApiWalletController.class))));
        Map<String, Object> body = new HashMap<>();
        body.put("amount", "10");
        AjaxResult r = wallet.withdraw(body);
        assertNotEquals(200, r.get("code"));
        verify(svc, never()).withdraw(any(), any());
    }
}
