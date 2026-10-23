package com.chengyinhub.business.service.support;

/**
 * O-05 #2:审核单风险等级随内容重算(只升不降)。
 *
 * <p>只认 HIGH &gt; MEDIUM &gt; 其它(NORMAL 及 EDIT_REVIEW 等标记值)三档;重算结果严格高于当前档才替换,
 * 同档或更低一律保持原值 —— 自动路径永不降级,降级只能由人工处理。
 */
public final class AuditRiskLevels {
    public static final String NORMAL = "NORMAL";
    public static final String MEDIUM = "MEDIUM";
    public static final String HIGH = "HIGH";

    private AuditRiskLevels() {
    }

    public static int rank(String level) {
        if (HIGH.equals(level)) return 2;
        if (MEDIUM.equals(level)) return 1;
        return 0;
    }

    /**
     * @param current        开放单当前 risk_level
     * @param candidate      本次进件(门禁/重提)给出的风险,可为 null
     * @param openReporters  该内容所有开放审核单上的去重举报人数
     * @param reportThreshold 举报人数达到即升 HIGH;&lt;=0 表示不按举报升级
     * @return 重算后的等级;不高于当前档时原样返回 current
     */
    public static String recompute(String current, String candidate, int openReporters, int reportThreshold) {
        String result = current;
        if (rank(candidate) > rank(result)) result = candidate;
        if (reportThreshold > 0 && openReporters >= reportThreshold && rank(HIGH) > rank(result)) result = HIGH;
        return result;
    }
}
