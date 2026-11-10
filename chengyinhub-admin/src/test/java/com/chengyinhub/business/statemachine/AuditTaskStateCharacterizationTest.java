package com.chengyinhub.business.statemachine;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.service.impl.AuditTaskServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * qx-sm 特征测试：在重构前钉住审核单状态机的既有行为（裁决迁移表 + 「未结」判定），
 * 重构后同一测试必须原样通过，证明行为未变。覆盖 null 与未登记值。
 */
class AuditTaskStateCharacterizationTest {

    private static final List<Integer> CODES = Arrays.asList(null, -1, 0, 1, 2, 3, 4, 5, 99);

    /** 现网 canTransition 的完整真值表：只有 0→1/2/3 与 3→1/2 放行，其余（含 null/未知）一律拒绝。 */
    private static boolean expectedReviewTransition(Integer from, Integer to) {
        if (from == null || to == null) return false;
        if (from == 0) return to == 1 || to == 2 || to == 3;
        if (from == 3) return to == 1 || to == 2;
        return false;
    }

    @Test
    void reviewTransitionTruthTableIsPinned() {
        AuditTaskServiceImpl service = new AuditTaskServiceImpl();
        for (Integer from : CODES) {
            for (Integer to : CODES) {
                Boolean actual = ReflectionTestUtils.invokeMethod(service, "canTransition", from, to);
                assertEquals(expectedReviewTransition(from, to), actual, from + "->" + to);
            }
        }
    }

    /** 现网「未结」= PENDING(0) 或 ESCALATED(3)；举报独立槽的未结判定沿用同一集合。 */
    @Test
    void openReportPredicateIsPinned() {
        for (Integer code : CODES) {
            AuditTask task = new AuditTask();
            task.setBizType("TOPIC");
            task.setSourceType(AuditTask.SOURCE_REPORT);
            task.setStatus(code);
            boolean expectedOpen = code != null && (code == 0 || code == 3);
            assertEquals(expectedOpen, task.isOpenReport(), "status=" + code);
        }
    }
}
