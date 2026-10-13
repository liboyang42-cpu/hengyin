package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.vo.CommunityReportPlatformVO;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 社区举报原因与首响分级的单一真源。
 *
 * <p>这里保存的是可执行的产品合同，不保存审核结论：原因只负责分流，最终处置仍必须由
 * 案件证据、规则版本和人工审核共同决定。V1 采用实施路线中的首发值：现实危险、未成年人
 * 安全和自伤风险 15 分钟首响，其余原因 24 小时首响。</p>
 */
public final class CommunityReportPolicy {
    public static final String VERSION = "COMMUNITY_REPORT_POLICY_V1";
    /** Marker assigned after deployment to rows created before versioned policy snapshots existed. */
    public static final String LEGACY_VERSION = "COMMUNITY_REPORT_POLICY_LEGACY";
    public static final String PRIORITY_URGENT = "URGENT";
    public static final String PRIORITY_STANDARD = "STANDARD";

    private static final int URGENT_FIRST_RESPONSE_MINUTES = 15;
    private static final int STANDARD_FIRST_RESPONSE_MINUTES = 24 * 60;

    private static final List<Reason> REASONS = Collections.unmodifiableList(Arrays.asList(
            urgent("DANGEROUS", "现实人身危险或违法行为"),
            urgent("MINOR_SAFETY", "未成年人安全"),
            urgent("SELF_HARM", "自伤或自杀风险"),
            standard("SEXUAL_CONTENT", "色情低俗内容"),
            standard("HARASSMENT", "人身攻击或骚扰"),
            standard("HATE", "仇恨或歧视"),
            standard("FRAUD", "诈骗"),
            standard("IMPERSONATION", "冒充他人"),
            standard("FALSE_INFORMATION", "虚假信息"),
            standard("SPAM", "垃圾广告或站外导流"),
            standard("PRIVACY", "隐私泄露"),
            standard("INTELLECTUAL_PROPERTY", "知识产权或内容盗用"),
            standard("OTHER", "其他")
    ));

    private static final Map<String, Reason> BY_CODE;
    private static final Set<String> CODES;
    private static final List<String> EMERGENCY_CODES;

    static {
        Map<String, Reason> byCode = new LinkedHashMap<>();
        List<String> emergencyCodes = new ArrayList<>();
        for (Reason reason : REASONS) {
            byCode.put(reason.code, reason);
            if (reason.emergency) emergencyCodes.add(reason.code);
        }
        BY_CODE = Collections.unmodifiableMap(byCode);
        CODES = Collections.unmodifiableSet(new LinkedHashSet<>(byCode.keySet()));
        EMERGENCY_CODES = Collections.unmodifiableList(emergencyCodes);
    }

    private CommunityReportPolicy() {
    }

    public static Set<String> codes() {
        return CODES;
    }

    public static List<String> orderedCodes() {
        return Collections.unmodifiableList(new ArrayList<>(BY_CODE.keySet()));
    }

    public static List<String> emergencyCodes() {
        return EMERGENCY_CODES;
    }

    public static List<Map<String, Object>> publicReasons() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Reason reason : REASONS) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", reason.code);
            item.put("label", reason.label);
            item.put("priority", reason.priority);
            item.put("firstResponseMinutes", reason.firstResponseMinutes);
            item.put("emergency", reason.emergency);
            result.add(Collections.unmodifiableMap(item));
        }
        return Collections.unmodifiableList(result);
    }

    public static Reason find(String code) {
        return BY_CODE.get(code);
    }

    /** Adds policy metadata to the privacy-safe platform queue projection. */
    public static void enrich(CommunityReportPlatformVO row, Date now) {
        if (row == null) return;
        Reason currentReason = find(row.getReasonCode());
        boolean validSnapshot = validSnapshot(row);
        boolean currentSnapshotMatches = validSnapshot
                && VERSION.equals(row.getPolicyVersion())
                && currentReason != null
                && currentReason.label.equals(row.getReasonLabel())
                && currentReason.priority.equals(row.getPriority())
                && currentReason.firstResponseMinutes == row.getFirstResponseMinutes();
        boolean historicalSnapshot = row.getPolicyVersion() != null
                && !row.getPolicyVersion().trim().isEmpty()
                && !VERSION.equals(row.getPolicyVersion())
                && validSnapshot;
        boolean policyMatched = validSnapshot && (currentSnapshotMatches || historicalSnapshot);
        row.setPolicyMatched(policyMatched);
        if (!policyMatched) {
            row.setReasonLabel("未知或损坏的规则快照");
            row.setPriority(PRIORITY_URGENT);
            row.setFirstResponseMinutes(URGENT_FIRST_RESPONSE_MINUTES);
        }
        if (row.getCreateTime() == null) {
            row.setFirstResponseDueAt(null);
            row.setFirstResponseOverdue(false);
            return;
        }
        Date dueAt = new Date(row.getCreateTime().getTime()
                + row.getFirstResponseMinutes() * 60L * 1000L);
        row.setFirstResponseDueAt(dueAt);
        Date firstResponseAt = row.getFirstResponseAt();
        row.setFirstResponseOverdue(firstResponseAt == null
                ? now != null && now.after(dueAt)
                : firstResponseAt.after(dueAt));
    }

    private static boolean validSnapshot(CommunityReportPlatformVO row) {
        if (row.getReasonLabel() == null || row.getReasonLabel().trim().isEmpty()) return false;
        if (!PRIORITY_URGENT.equals(row.getPriority())
                && !PRIORITY_STANDARD.equals(row.getPriority())) return false;
        Integer minutes = row.getFirstResponseMinutes();
        return minutes != null && minutes > 0 && minutes <= 7 * 24 * 60;
    }

    private static Reason urgent(String code, String label) {
        return new Reason(code, label, PRIORITY_URGENT, URGENT_FIRST_RESPONSE_MINUTES, true);
    }

    private static Reason standard(String code, String label) {
        return new Reason(code, label, PRIORITY_STANDARD, STANDARD_FIRST_RESPONSE_MINUTES, false);
    }

    /** Internal immutable definition used to enrich the platform queue. */
    public static final class Reason {
        private final String code;
        private final String label;
        private final String priority;
        private final int firstResponseMinutes;
        private final boolean emergency;

        private Reason(String code, String label, String priority,
                       int firstResponseMinutes, boolean emergency) {
            this.code = code;
            this.label = label;
            this.priority = priority;
            this.firstResponseMinutes = firstResponseMinutes;
            this.emergency = emergency;
        }

        public String getCode() { return code; }
        public String getLabel() { return label; }
        public String getPriority() { return priority; }
        public int getFirstResponseMinutes() { return firstResponseMinutes; }
        public boolean isEmergency() { return emergency; }
    }
}
