package com.chengyinhub.business.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 发布检查结果：阻塞项必须清零，风险项可在 OPTIONAL_PARTNERS 场景展示但不伪装成通过。 */
public class OfficialEventV2PublishCheck {
    public static final String LEVEL_BLOCK = "BLOCK";
    public static final String LEVEL_WARN = "WARN";

    private final List<String> blockers = new ArrayList<>();
    private final List<String> risks = new ArrayList<>();
    /** 提示项(WARN)：不阻断发布，只随检查结果返回给主办方；与阻塞项(BLOCK)分列，不计入 isPass。 */
    private final List<String> warnings = new ArrayList<>();
    /** 分级开关(official_event.publish_check_warn_enabled)关闭时为 false，不输出 warnings/results，响应与旧版一致。 */
    private boolean tiered;

    public List<String> getBlockers() { return blockers; }
    public List<String> getRisks() { return risks; }
    public boolean isPass() { return blockers.isEmpty(); }
    public void block(String message) { blockers.add(message); }
    public void risk(String message) { risks.add(message); }

    public void warn(String message) { warnings.add(message); }
    public void enableTiers() { tiered = true; }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public List<String> getWarnings() { return tiered ? warnings : null; }

    /** 分级结果：先 BLOCK 后 WARN；OPTIONAL_PARTNERS 的风险项按 WARN 输出(本就不阻断)。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public List<Result> getResults() {
        if (!tiered) return null;
        List<Result> results = new ArrayList<>();
        for (String message : blockers) results.add(new Result(LEVEL_BLOCK, message));
        for (String message : risks) results.add(new Result(LEVEL_WARN, message));
        for (String message : warnings) results.add(new Result(LEVEL_WARN, message));
        return Collections.unmodifiableList(results);
    }

    public static final class Result {
        private final String level;
        private final String message;

        public Result(String level, String message) {
            this.level = level;
            this.message = message;
        }

        public String getLevel() { return level; }
        public String getMessage() { return message; }
    }
}
