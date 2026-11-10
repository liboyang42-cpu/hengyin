package com.chengyinhub.business.domain.enums;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 审核单 audit_task.status 唯一值域与迁移表。
 *
 * <p>迁移（写入口见 docs/architecture/state-machines/README.md）：
 * <ul>
 *   <li>裁决：PENDING → APPROVED / REJECTED / ESCALATED；ESCALATED → APPROVED / REJECTED（{@link #canReviewTransition}）</li>
 *   <li>撤销：PENDING / ESCALATED → CANCELLED（Mapper CAS：{@code status in (0, 3)}）</li>
 *   <li>撤回裁决：APPROVED / REJECTED / ESCALATED → PENDING（Mapper CAS {@code revertAuditTaskDecisionCas}，限时窗）</li>
 * </ul>
 * 未结（open）= PENDING 或 ESCALATED；Mapper XML 里的 {@code status in (0, 3)} 必须与 {@link #OPEN_CODES_SQL} 一致，
 * 由 AuditTaskStateMachineTest 守住。null 与未登记值一律视为「非未结、不可裁决」（fail-closed）。
 */
public enum AuditTaskState {
    PENDING(0, "待审核"),
    APPROVED(1, "已通过"),
    REJECTED(2, "已驳回"),
    ESCALATED(3, "已升级"),
    CANCELLED(4, "已撤销");

    /** Mapper XML 中「未结」集合的唯一字面量。 */
    public static final String OPEN_CODES_SQL = "0, 3";

    private static final Set<AuditTaskState> OPEN = Collections.unmodifiableSet(EnumSet.of(PENDING, ESCALATED));

    private final int code;
    private final String label;

    AuditTaskState(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() { return code; }
    public String getLabel() { return label; }

    public boolean isOpen() {
        return OPEN.contains(this);
    }

    /** 审核员裁决允许的目标态；撤销与撤回裁决走各自 Mapper CAS，不在此表。 */
    public Set<AuditTaskState> reviewTargets() {
        switch (this) {
            case PENDING: return Collections.unmodifiableSet(EnumSet.of(APPROVED, REJECTED, ESCALATED));
            case ESCALATED: return Collections.unmodifiableSet(EnumSet.of(APPROVED, REJECTED));
            default: return Collections.emptySet();
        }
    }

    /** 未登记值返回 null，由调用方 fail-closed。 */
    public static AuditTaskState fromCodeOrNull(Integer code) {
        if (code == null) return null;
        for (AuditTaskState state : values()) {
            if (state.code == code) return state;
        }
        return null;
    }

    /** 未结（待审/已升级）；null/未知 → false。 */
    public static boolean isOpenCode(Integer code) {
        AuditTaskState state = fromCodeOrNull(code);
        return state != null && state.isOpen();
    }

    /** 审核员裁决迁移守卫；null/未知 → false。 */
    public static boolean canReviewTransition(Integer from, Integer to) {
        AuditTaskState source = fromCodeOrNull(from);
        AuditTaskState target = fromCodeOrNull(to);
        return source != null && target != null && source.reviewTargets().contains(target);
    }
}
