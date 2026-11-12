package com.chengyinhub.business.statemachine;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.enums.AuditTaskState;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * qx-sm：审核单显式状态机的迁移表、非法迁移与「状态列只能在登记的写入口改」lint。
 * 新增状态/写入口：先改 AuditTaskState 与本测试的 REGISTERED_STATUS_WRITERS，再改 Mapper。
 */
class AuditTaskStateMachineTest {

    /** audit_task.status 的全部登记写入口（Mapper statement id）。新增写入口必须在此登记并说明迁移。 */
    private static final Set<String> REGISTERED_STATUS_WRITERS = new TreeSet<>(List.of(
            "insertAuditTask",                // 新建：只落 PENDING（服务层 setStatus(STATUS_PENDING)）
            "updateAuditTaskReviewCas",       // 裁决：CAS expectedStatus，迁移由 canReviewTransition 守卫
            "revertAuditTaskDecisionCas",     // 撤回裁决 → PENDING，CAS expectedStatus + 时窗
            "cancelTopicPublicationTaskCas",  // 未结 → CANCELLED
            "cancelOpenAuditTaskCas",         // 未结 → CANCELLED
            "cancelOpenTemplateTasks"));      // 未结 → CANCELLED

    private static final Path MAPPER_DIR = locate("chengyinhub-system/src/main/resources/mapper");

    @Test
    void legacyConstantsMatchEnumCodes() {
        assertEquals(AuditTask.STATUS_PENDING.intValue(), AuditTaskState.PENDING.getCode());
        assertEquals(AuditTask.STATUS_APPROVED.intValue(), AuditTaskState.APPROVED.getCode());
        assertEquals(AuditTask.STATUS_REJECTED.intValue(), AuditTaskState.REJECTED.getCode());
        assertEquals(AuditTask.STATUS_ESCALATED.intValue(), AuditTaskState.ESCALATED.getCode());
        assertEquals(AuditTask.STATUS_CANCELLED.intValue(), AuditTaskState.CANCELLED.getCode());
    }

    @Test
    void terminalStatesAcceptNoReviewDecision() {
        for (AuditTaskState terminal : List.of(AuditTaskState.APPROVED, AuditTaskState.REJECTED, AuditTaskState.CANCELLED)) {
            assertFalse(terminal.isOpen());
            assertTrue(terminal.reviewTargets().isEmpty(), terminal.name());
            for (AuditTaskState to : AuditTaskState.values()) {
                assertFalse(AuditTaskState.canReviewTransition(terminal.getCode(), to.getCode()), terminal + "->" + to);
            }
        }
    }

    @Test
    void illegalAndUnknownTransitionsAreRejected() {
        assertFalse(AuditTaskState.canReviewTransition(0, 0), "PENDING 不能裁决为 PENDING");
        assertFalse(AuditTaskState.canReviewTransition(0, 4), "撤销不走裁决");
        assertFalse(AuditTaskState.canReviewTransition(3, 3), "已升级不能再升级");
        assertFalse(AuditTaskState.canReviewTransition(3, 0));
        assertFalse(AuditTaskState.canReviewTransition(null, 1));
        assertFalse(AuditTaskState.canReviewTransition(0, null));
        assertFalse(AuditTaskState.canReviewTransition(7, 1));
        assertNull(AuditTaskState.fromCodeOrNull(7));
        assertFalse(AuditTaskState.isOpenCode(null));
        assertFalse(AuditTaskState.isOpenCode(7));
    }

    @Test
    void openCodesSqlMatchesEnumOpenSet() {
        String fromEnum = Stream.of(AuditTaskState.values()).filter(AuditTaskState::isOpen)
                .map(s -> String.valueOf(s.getCode())).collect(Collectors.joining(", "));
        assertEquals(fromEnum, AuditTaskState.OPEN_CODES_SQL);
    }

    /** AuditTaskMapper.xml 里每个 status in (...) 都必须是「未结」集合，避免某条 SQL 悄悄漏掉 ESCALATED。 */
    @Test
    void everyStatusInListInAuditTaskMapperIsTheOpenSet() throws IOException {
        String xml = Files.readString(MAPPER_DIR.resolve("business/AuditTaskMapper.xml"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\\bstatus\\s+in\\s*\\(([^)]*)\\)").matcher(xml);
        int seen = 0;
        while (m.find()) {
            seen++;
            assertEquals(AuditTaskState.OPEN_CODES_SQL, m.group(1).trim().replaceAll("\\s*,\\s*", ", "),
                    "非未结集合的 status in 字面量: " + m.group());
        }
        assertTrue(seen > 0);
    }

    /** lint：audit_task.status 只能由登记的 statement 写；写 CANCELLED 的必须带未结 CAS。 */
    @Test
    void statusColumnIsOnlyWrittenByRegisteredStatements() throws IOException {
        Pattern statement = Pattern.compile("<(update|insert)\\s+id=\"([^\"]+)\"[^>]*>(.*?)</\\1>", Pattern.DOTALL);
        Pattern writesTable = Pattern.compile("(?is)(update\\s+audit_task\\b|insert\\s+into\\s+audit_task\\b)");
        Pattern writesStatus = Pattern.compile("(?is)(\\bset\\b.*?[\\s,]status\\s*=|status\\s*,|\\(\\s*status\\b|<if test=\"status != null\">status)");
        List<String> writers = new ArrayList<>();
        List<String> unguardedCancels = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAPPER_DIR)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".xml")).collect(Collectors.toList())) {
                String xml = Files.readString(file, StandardCharsets.UTF_8);
                Matcher m = statement.matcher(xml);
                while (m.find()) {
                    String body = m.group(3);
                    if (!writesTable.matcher(body).find()) continue;
                    String head = body.split("(?i)\\bwhere\\b", 2)[0];
                    if (!writesStatus.matcher(head).find()) continue;
                    writers.add(m.group(2));
                    if (head.matches("(?is).*\\bstatus\\s*=\\s*4\\b.*") && !body.contains("status in (" + AuditTaskState.OPEN_CODES_SQL + ")")) {
                        unguardedCancels.add(m.group(2));
                    }
                }
            }
        }
        assertEquals(REGISTERED_STATUS_WRITERS, new TreeSet<>(writers),
                "audit_task.status 写入口与登记不一致；新增写入口请先在 AuditTaskState/本测试登记");
        assertTrue(unguardedCancels.isEmpty(), "撤销未带未结 CAS: " + unguardedCancels);
    }

    private static Path locate(String relative) {
        Path here = Paths.get("").toAbsolutePath();
        for (Path p = here; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve(relative))) return p.resolve(relative);
        }
        throw new IllegalStateException("找不到 " + relative);
    }
}
