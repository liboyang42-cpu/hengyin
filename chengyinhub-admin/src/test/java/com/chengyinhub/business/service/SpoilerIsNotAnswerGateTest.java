package com.chengyinhub.business.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 剧透保护不是答案闸(2026-09-02 安全修复)。
 *
 * <p>本仓自己写死过这条原则(ApiPlayProgressController,FREE_COLLECT 支):
 * 「剧透保护管的是<b>剧情文本</b>该不该提前看见,不是答案该不该提前拿到 —— 两件事。」
 * 但那句无条件 removeAll 只落在一个分支里,经典定向那一支走 {@code project()},
 * 答案由 {@code SpoilerAccessSnapshot} 放行 —— 同一条原则只落实了一半。
 *
 * <p>而 {@code shouldReveal() = revealAll || revealedIds.contains(id)},{@code revealAll} 来自
 * {@code POST /api/spoiler/preference},那个端点<b>只校验登录</b>:零成本、无需报名、
 * 与该团毫无关系也能设。任何登录会员翻一下偏好再拉 {@code /api/play/nodes} 就能拿光整团答案,
 * 不扣分不留痕;而正规路径 {@code /api/spoiler/reveal} 会 recordReveal 并把本题解谜分记 0。
 *
 * <p>判据钉的是<b>结构</b>:列表接口那段代码里,不许把答案正文喂给 {@code project()}。
 * 扫描前先剥注释 —— 解释这次修复的注释里必然出现这些字样。
 */
class SpoilerIsNotAnswerGateTest {

    private static final Path CTRL = Paths.get(
            "src", "main", "java", "com", "chengyinhub", "web", "controller", "api",
            "ApiPlayProgressController.java");
    /** W8 起 /api/play/nodes 的列表构建下沉到 play 应用服务,/puzzle/reveal 的记分出口同理。 */
    private static final Path PLAY = Paths.get(
            "src", "main", "java", "com", "chengyinhub", "web", "service", "play");
    private static final Path[] LIST = {CTRL, PLAY.resolve("PlayEntryApplication.java"),
            PLAY.resolve("PlayNodeQueryApplication.java")};
    private static final Path REVEAL = PLAY.resolve("PlayPuzzleCommandApplication.java");

    private static String withoutComments(Path... files) throws Exception {
        StringBuilder src = new StringBuilder();
        for (Path file : files) src.append(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).append('\n');
        return src.toString().replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
    }

    private static String codeWithoutComments() throws Exception {
        return withoutComments(LIST);
    }

    /**
     * 列表接口里是否把答案正文当第三个参数喂给了 project()。
     * <p>用括号配平解析取实参 —— 正则的 {@code [^,)]+} 吃不下 {@code n.getId()} 里的括号,
     * 会静默匹配不到,那种"检查器"是恒绿的。
     */
    private static boolean feedsAnswerIntoProject(String code) {
        int at = 0;
        while ((at = code.indexOf(".project(", at)) >= 0) {
            int open = code.indexOf('(', at);
            int depth = 0, end = -1;
            for (int i = open; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') { depth--; if (depth == 0) { end = i; break; } }
            }
            if (end < 0) return true;              // 括号不配平,判红而不是放行
            String args = code.substring(open + 1, end);
            java.util.List<String> parts = splitTopLevel(args);
            if (parts.size() >= 3 && !"null".equals(parts.get(2).trim())) return true;
            at = end;
        }
        return false;
    }

    /** 按顶层逗号切实参(嵌套括号内的逗号不算) */
    private static java.util.List<String> splitTopLevel(String args) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) { out.add(args.substring(start, i)); start = i + 1; }
        }
        out.add(args.substring(start));
        return out;
    }

    @Test
    @DisplayName("列表接口不许把答案正文喂给 project() —— 那等于让剧透偏好当答案闸")
    void listMustNotFeedAnswerIntoProject() throws Exception {
        assertFalse(feedsAnswerIntoProject(codeWithoutComments()),
                "ApiPlayProgressController 把答案正文传给了 spoilerProtectionService.project()。"
                        + "shouldReveal() 会因为 revealAll 短路,而 revealAll 由只校验登录的 "
                        + "/api/spoiler/preference 设置 —— 任何登录会员都能零成本拿光整团答案。"
                        + "答案正文只能走 /reveal 端点(记 0 分、留痕)。");
    }

    @Test
    @DisplayName("列表构建里不许直接 put answerReveal(兜底链会漏出标准答案本身)")
    void listMustNotPutAnswerRevealDirectly() throws Exception {
        String code = codeWithoutComments();
        // 列表构建往逐节点的 map 里塞:m.put(...);合法出口用的是 data.put(...)(见 /reveal)
        assertFalse(Pattern.compile("\\bm\\.put\\(\"answerReveal\"").matcher(code).find(),
                "列表构建里直接 put 了 answerReveal。原兜底链是 "
                        + "answerReveal → questionAnswer → correctAnswer,"
                        + "没写揭示文案的节点会直接漏出标准答案本身");
    }

    @Test
    @DisplayName("正规出口必须还在:/reveal 记 0 分之后才下发答案")
    void legitimateRevealPathStillExists() throws Exception {
        String code = withoutComments(REVEAL);
        assertTrue(code.contains("recordReveal"), "付费闸 recordReveal 不能被一起删掉");
        assertTrue(Pattern.compile("recordReveal[\\s\\S]{0,900}?data\\.put\\(\"answerReveal\"")
                        .matcher(code).find(),
                "答案的合法出口(recordReveal 之后 put answerReveal)必须保留,别把功能一起修没了");
    }

    @Test
    @DisplayName("负控:把答案正文喂回 project() 必须判红")
    void negativeControl() throws Exception {
        String clean = codeWithoutComments();
        assertFalse(feedsAnswerIntoProject(clean), "干净输入不该报");

        String mutated = clean.replace(
                "spoilerProtectionService.project(read.spoilerAccess, n.getId(), null)",
                "spoilerProtectionService.project(read.spoilerAccess, n.getId(), tpl.getAnswerReveal())");
        assertFalse(clean.equals(mutated), "负控变异注入失败 —— 锚点没命中,这条负控是假的");
        assertTrue(feedsAnswerIntoProject(mutated), "检查器没抓到被喂回去的答案正文");
    }

    @Test
    @DisplayName("负控:注释里提到这些字样不算违规 —— 判据看代码不看注解")
    void negativeControlCommentsAreStripped() throws Exception {
        String withComment = "// x.project(snapshot, n.getId(), tpl.getAnswerReveal()) 是原来的写法\n"
                + "x.project(a, b, null);";
        String stripped = withComment.replaceAll("(?m)^\\s*//.*$", "");
        assertFalse(feedsAnswerIntoProject(stripped),
                "解释修复的注释里必然出现该调用形状,按原文扫会把注释判成违规");
        assertTrue(feedsAnswerIntoProject(withComment),
                "证明剥注释这一步真的起了作用,而不是恒不报");
    }
}
