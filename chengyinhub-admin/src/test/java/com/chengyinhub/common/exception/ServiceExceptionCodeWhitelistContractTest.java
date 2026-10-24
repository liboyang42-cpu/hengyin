package com.chengyinhub.common.exception;

import com.chengyinhub.common.constant.HttpStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C:U-062:{@code new ServiceException(msg, code)} 的码值只能取 {@link ServiceException#ALLOWED_CODES}。
 * 扫描各模块 src/main/java;字面量与 HttpStatus 常量逐一校验,变量/表达式(运行期决定)不在本闸范围。
 */
class ServiceExceptionCodeWhitelistContractTest {

    private static final Pattern CTOR = Pattern.compile("new ServiceException\\(");
    private static final Pattern INT_LITERAL = Pattern.compile("\\d+");
    private static final Pattern HTTP_STATUS = Pattern.compile("(?:[\\w.]+\\.)?HttpStatus\\.(\\w+)");

    @Test
    void literalCodesStayInsideWhitelist() throws Exception {
        Path repo = Paths.get("").toAbsolutePath().getParent();
        List<String> violations = new ArrayList<>();
        int checked = 0;
        for (String module : new String[] {"chengyinhub-admin", "chengyinhub-system", "chengyinhub-framework",
                "chengyinhub-common", "chengyinhub-quartz", "chengyinhub-generator"}) {
            Path src = repo.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(src)) continue;
            List<Path> files;
            try (Stream<Path> walk = Files.walk(src)) {
                files = walk.filter(p -> p.toString().endsWith(".java")).toList();
            }
            for (Path file : files) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                Matcher m = CTOR.matcher(text);
                while (m.find()) {
                    String last = lastTopLevelArg(text, m.end());
                    Integer code = resolve(last);
                    if (code == null) continue;
                    checked++;
                    if (!ServiceException.ALLOWED_CODES.contains(code)) {
                        violations.add(repo.relativize(file) + " -> " + last + " (" + code + ")");
                    }
                }
            }
        }
        assertTrue(checked > 100, "扫描范围异常,只校验到 " + checked + " 处");
        assertTrue(violations.isEmpty(), "ServiceException 业务码不在白名单: " + violations);
    }

    @Test
    void whitelistIsHttpErrorRange() {
        for (Integer code : ServiceException.ALLOWED_CODES) {
            assertTrue(code >= 400 && code <= 599, "白名单只收 4xx/5xx: " + code);
        }
    }

    /** 返回构造参数里最后一个顶层实参(跳过字符串与嵌套括号);只有一个实参时返回 null。 */
    private static String lastTopLevelArg(String s, int start) {
        int depth = 1;
        boolean inString = false;
        int args = 1;
        StringBuilder cur = new StringBuilder();
        for (int i = start; i < s.length() && depth > 0; i++) {
            char ch = s.charAt(i);
            if (inString) {
                if (ch == '\\') { i++; continue; }
                if (ch == '"') inString = false;
                continue;
            }
            if (ch == '"') { inString = true; continue; }
            if (ch == '(' || ch == '[' || ch == '{') depth++;
            else if (ch == ')' || ch == ']' || ch == '}') { if (--depth == 0) break; }
            else if (ch == ',' && depth == 1) { args++; cur.setLength(0); continue; }
            if (depth >= 1) cur.append(ch);
        }
        return args < 2 ? null : cur.toString().trim();
    }

    private static Integer resolve(String arg) throws IOException {
        if (arg == null) return null;
        if (INT_LITERAL.matcher(arg).matches()) return Integer.valueOf(arg);
        Matcher hs = HTTP_STATUS.matcher(arg);
        if (hs.matches()) {
            try {
                Field f = HttpStatus.class.getField(hs.group(1));
                return f.getInt(null);
            } catch (ReflectiveOperationException e) {
                throw new IOException("未知 HttpStatus 常量: " + arg, e);
            }
        }
        return null;
    }
}
