package com.chengyinhub.upgrade;

import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerMessagePunctuationTest {
    private static final Pattern ASCII = Pattern.compile("(?<=\\p{IsHan})[,;:?!]|[,;:?!](?=\\p{IsHan})");
    private static final Set<String> MESSAGES = Set.of("error", "warn", "fail", "success", "setMsg", "setMessage");

    private static boolean displayed(TreePath path) {
        for (TreePath p = path.getParentPath(); p != null; p = p.getParentPath()) {
            if (p.getLeaf() instanceof NewClassTree call
                    && call.getIdentifier().toString().endsWith("ServiceException")) return true;
            if (p.getLeaf() instanceof MethodInvocationTree call) {
                String method = call.getMethodSelect().toString();
                if (method.matches("(?i)(?:.*\\.)?(?:log|logger)\\.[^.]+")) return false;
                String name = method.substring(method.lastIndexOf('.') + 1);
                if (MESSAGES.contains(name)) return true;
                if (name.equals("put") && call.getArguments().size() == 2
                        && Set.of("\"msg\"", "\"message\"", "\"hint\"", "\"reason\"")
                        .contains(call.getArguments().get(0).toString())) return true;
            }
        }
        return false;
    }

    private static List<String> findings(List<Path> paths) throws Exception {
        List<String> failures = new ArrayList<>();
        try (var manager = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null)) {
            JavacTask task = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(null, manager, null,
                    List.of("-proc:none"), null, manager.getJavaFileObjectsFromPaths(paths));
            var trees = com.sun.source.util.Trees.instance(task);
            for (var unit : task.parse()) new TreePathScanner<Void, Void>() {
                @Override public Void visitLiteral(LiteralTree literal, Void unused) {
                    if (literal.getValue() instanceof String value && ASCII.matcher(value).find()
                            && displayed(getCurrentPath())) {
                        long offset = trees.getSourcePositions().getStartPosition(unit, literal);
                        failures.add(unit.getSourceFile().getName() + ":" + unit.getLineMap().getLineNumber(offset) + " " + value);
                    }
                    return super.visitLiteral(literal, unused);
                }
            }.scan(unit, null);
        }
        return failures;
    }

    @Test void playerMessagesUseChinesePunctuation() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("chengyinhub-admin"))) root = root.getParent();
        try (var files = Files.walk(root)) {
            List<Path> sources = files.filter(p -> p.toString().contains("/src/main/java/")
                    && p.toString().endsWith(".java")).toList();
            assertFalse(sources.isEmpty());
            assertEquals(List.of(), findings(sources));
        }
    }

    @Test void ignoresCommentsLogsAndInternalExceptionsButChecksNestedMessages() throws Exception {
        Path source = Files.createTempFile("player-punctuation", ".java");
        try {
            Files.writeString(source, """
                    class Fixture { void run() {
                      // error("注释,忽略");
                      log.warn("日志,忽略");
                      throw new IllegalStateException("内部,忽略");
                    } void messages() {
                      AjaxResult.error(String.format("失败,重试 %s", "id"));
                      data.put("hint", "完成!请继续");
                      throw new ServiceException("等待;稍后");
                    } }
                    """);
            assertEquals(3, findings(List.of(source)).size());
            assertFalse(ASCII.matcher("完成，请继续！https://example.com").find());
        } finally { Files.deleteIfExists(source); }
    }
}
