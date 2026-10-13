package com.chengyinhub.common.utils;

import java.util.regex.Pattern;

/**
 * 「这句话能不能给用户看」的唯一判据(终审 §12.3-3 P0 错误映射层)。
 *
 * <p>业务方以异常抛出的中文短句保留;技术泄漏标记(类名/包路径/堆栈/文件引用/SQL/请求路径/凭据)
 * 一律换成调用方给的稳定兜底文案。
 *
 * <p><b>为什么抽到 common</b>:原来它只长在 {@code GlobalExceptionHandler} 里(private),
 * 于是自己 try/catch 的控制器要么把消息原样吐出去(泄漏),要么一律压成通用句(不可诊断)——
 * {@code ApiTopicController.createTopic} 选了后者,把四种不同原因压成同一句
 * 「主题发布失败,请稍后重试」,其中两条服务端本来就有明确业务文案。两处共用一份判据,
 * 改口径只改这一处。
 */
public final class UserSafeMessage {

    /** 未预期的系统异常返回给用户的稳定文案。 */
    public static final String GENERIC = "系统繁忙，请稍后重试";

    /** 技术泄漏标记:匹配 Java 类名/包路径/堆栈/文件引用/SQL/请求路径等内部细节,命中即掩码。 */
    private static final Pattern TECHNICAL_LEAK_PATTERN = Pattern.compile(
            "(?i)(java\\.lang\\.|java\\.sql\\.|com\\.chengyinhub\\.|class [a-z]\\w*\\.|"
            + "\\b(?:NullPointerException|ClassCastException|SQLException|SQLSyntaxErrorException|IllegalArgumentException|IllegalStateException|RuntimeException|Exception)\\b|"
            + "bad SQL grammar|nested exception|Error querying database|Cannot get a connection|"
            + "cannot be cast to|\\.java:\\d+|\\bat \\w+\\.\\w+\\b|/api/|Request processing failed|"
            + "(?:authorization\\s*:\\s*)?bearer\\s+[a-z0-9._~+/=-]{8,}|"
            + "(?:token|password|passwd|secret|api[_-]?key|access[_-]?key|private[_-]?key)\\s*[:=]\\s*\\S+|"
            + "(?:openid|unionid|cookie|session(?:id)?|sid)\\s*[:=]\\s*\\S+)");

    private UserSafeMessage() {
    }

    /** 掩码后仍可展示则返回原句,否则返回 {@link #GENERIC}。 */
    public static String of(String raw) {
        return of(raw, GENERIC);
    }

    /** 同上,但不可展示时用调用方自己的兜底文案(例如「主题发布失败,请稍后重试」)。 */
    public static String of(String raw, String fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        String msg = raw.trim();
        // 超长消息基本都是堆栈/SQL 片段,直接收敛
        if (msg.length() > 120) {
            return fallback;
        }
        if (TECHNICAL_LEAK_PATTERN.matcher(msg).find()) {
            return fallback;
        }
        return msg;
    }
}
