package com.chengyinhub.business.domain.vo;

/**
 * 一条可定位的发布问题:{code, message, nodeId?, field?}。
 *
 * <p>field 是作者配置里的 JSON 路径(如 {@code nodes[nodeId=12].template.advancedConfigJson}),
 * message 去掉发布闸支持用的 {@code [topicId=.., path=..]} 尾巴,给作者直接看。
 * 这里只是一条数据,不是第二套判据:code 与 message 都直接来自发布闸自己的异常。
 */
public class PublicationProblem {

    private static final String LOCATION_MARK = "[topicId=";
    private static final String PATH_MARK = ", path=";
    private static final java.util.regex.Pattern NODE_PATH =
            java.util.regex.Pattern.compile("nodes\\[nodeId=(\\d+)\\]");
    public static final String CODE_TOPIC_BUNDLE_INVALID = "TOPIC_BUNDLE_INVALID";
    public static final String CODE_STATE_KEY_TYPE_INVALID = "STATE_KEY_TYPE_INVALID";
    public static final String CODE_BLOCK_CONFIG_INVALID = "BLOCK_CONFIG_INVALID";
    public static final String CODE_JOURNEY_RULES_INVALID = "JOURNEY_RULES_INVALID";
    public static final String CODE_CHAPTER_BLOCKS_INVALID = "CHAPTER_BLOCKS_INVALID";


    public static final String LEVEL_ERROR = "error";
    public static final String LEVEL_WARN = "warn";

    public static final String CODE_TOPIC_NAME_REQUIRED = "TOPIC_NAME_REQUIRED";
    public static final String CODE_AI_SIMPLE_LOCATION_INVALID = "AI_SIMPLE_LOCATION_INVALID";
    public static final String CODE_RECRUIT_DEADLINE_REQUIRED = "RECRUIT_DEADLINE_REQUIRED";
    public static final String CODE_TICKET_INVALID = "TICKET_INVALID";
    public static final String CODE_SIZE_LIMIT_EXCEEDED = "SIZE_LIMIT_EXCEEDED";
    public static final String CODE_STATE_KEY_UNREGISTERED = "STATE_KEY_UNREGISTERED";
    public static final String CODE_THOUGHT_REF_UNRESOLVED = "THOUGHT_REF_UNRESOLVED";
    public static final String CODE_JOURNEY_STORY_INVALID = "JOURNEY_STORY_INVALID";
    public static final String CODE_THEME_RUNTIME_REF_INVALID = "THEME_RUNTIME_REF_INVALID";
    public static final String CODE_NARRATIVE_NPC_INVALID = "NARRATIVE_NPC_INVALID";
    public static final String CODE_BLOCK_SYS_VAR_INVALID = "BLOCK_SYS_VAR_INVALID";
    /** 冻结预演失败:路线图结构 / 模板规则编译 / 冻结快照 / 玩法运行期校验任一处不通过。 */
    public static final String CODE_FREEZE_INVALID = "FREEZE_INVALID";
    /** 冻结预演未能跑完(warn,**明确表示未检查**,不是通过)。 */
    public static final String CODE_FREEZE_PREVIEW_INCOMPLETE = "FREEZE_PREVIEW_INCOMPLETE";
    public static final String CODE_ROUTE_MODE_INVALID = "ROUTE_MODE_INVALID";
    public static final String CODE_LOCATION_BINDING_INVALID = "LOCATION_BINDING_INVALID";

    private final String level;
    private final String code;
    private final Long nodeId;
    private final String field;
    private final String message;

    protected PublicationProblem(String level, String code, Long nodeId, String field, String message) {
        this.level = level;
        this.code = code;
        this.nodeId = nodeId;
        this.field = field;
        this.message = message;
    }

    public static PublicationProblem at(String code, Long nodeId, String field, String message) {
        return new PublicationProblem(LEVEL_ERROR, code, nodeId, field, message);
    }

    public static PublicationProblem warn(String code, Long nodeId, String field, String message) {
        return new PublicationProblem(LEVEL_WARN, code, nodeId, field, message);
    }

    /** 把发布闸抛出的异常收敛成一条问题:尾部定位尾巴拆到 field,nodeId 从字段路径里认。 */
    public static PublicationProblem from(String code, String fallbackField, RuntimeException failure) {
        String raw = failure.getMessage() == null || failure.getMessage().trim().isEmpty()
                ? fallbackField + " 未通过发布校验" : failure.getMessage();
        // 路径本身含方括号(nodes[nodeId=301].template[templateId=900].advancedConfigJson),
        // 正则 [^\]]+ 会在第一个 ']' 截断,nodeId 就永远解析不出来。按标记位置切,不按字符类贪心。
        String field = null;
        String cleaned = raw;
        int cursor = cleaned.indexOf(LOCATION_MARK);
        while (cursor >= 0) {
            int pathStart = cleaned.indexOf(PATH_MARK, cursor + LOCATION_MARK.length());
            if (pathStart <= 0) break;
            // 路径自身带方括号(nodes[nodeId=301].template[templateId=401].advancedConfigJson),
            // 而且定位尾巴常被拼进 List.toString(),后面会再跟一个 ']'。
            // 因此「尾巴结束」只能按括号配对判定:深度回到 0 的那个 ']' 才是尾巴的收尾。
            int depth = 0;
            int closing = -1;
            for (int i = pathStart + PATH_MARK.length(); i < cleaned.length(); i++) {
                char c = cleaned.charAt(i);
                if (c == '[') depth++;
                else if (c == ']') {
                    if (depth == 0) { closing = i; break; }
                    depth--;
                }
            }
            if (closing < 0) break;
            if (field == null) field = cleaned.substring(pathStart + PATH_MARK.length(), closing);
            cleaned = cleaned.substring(0, cursor) + cleaned.substring(closing + 1);
            cursor = cleaned.indexOf(LOCATION_MARK);
        }
        if (field == null) field = fallbackField;
        Long nodeId = null;
        java.util.regex.Matcher node = NODE_PATH.matcher(field);
        if (node.find()) {
            try { nodeId = Long.valueOf(node.group(1)); } catch (RuntimeException ignore) { nodeId = null; }
        }
        String message = cleaned.trim().isEmpty() ? fallbackField + " 未通过发布校验" : cleaned.trim();
        return new PublicationProblem(LEVEL_ERROR, code, nodeId, field, message);
    }

    public PublicationProblem userSafe() {
        return new PublicationProblem(level, code, nodeId, field,
                com.chengyinhub.common.utils.UserSafeMessage.of(message, "配置未通过发布检查"));
    }

    public String getLevel() { return level; }
    public String getCode() { return code; }
    public Long getNodeId() { return nodeId; }
    public String getField() { return field; }
    public String getMessage() { return message; }
}

