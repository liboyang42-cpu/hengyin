package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.ICmsRegistrationService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「PAYING 单确认未支付后怎么收口」只允许有一份实现:{@link ICmsRegistrationService#settlePayingOne}。
 *
 * <p>为什么要这道闸:对账任务与主办方取消活动各写过一份,已经实测漂移过 ——
 * 积分回补一边 CAS(claimPointsReturn)一边读改写,权益失效原因一边 REFUND 一边 ORGANIZER_CANCEL。
 * 这种漂移 compile 通过、测试各自绿,只有看两份代码并排才看得出来,所以必须钉在源码层。
 * 允许的差异只有 settlePayingOne 的三个参数(releaseReason、updateBy、finalStatus)。
 */
class PayingSettlementSingleImplementationContractTest {

    @Test
    void 取消活动路径不得自建一份PAYING关单回补() throws Exception {
        String teamGroup = read(Paths.get(
                "..", "chengyinhub-system", "src", "main", "java", "com", "chengyinhub",
                "business", "service", "impl", "TeamGroupServiceImpl.java"));
        // 取消路径的 PAYING 收口横跨这两个方法(第二段把本地关单抽成了 closeLocally)。
        String cancelPaying = section(teamGroup, "private void settlePayingRegistrationsBeforeCancel")
                + section(teamGroup, "private void closeLocally");

        assertTrue(cancelPaying.contains("cmsRegistrationService.settleClosedPayingOne("),
                "取消活动路径必须复用原 attempt 守卫和 settlePayingOne 资源释放");
        assertFalse(cancelPaying.contains("closeExpiredPaying("),
                "不得自己再调条件关单:关单与回补必须同一份实现");
        for (String local : new String[] {"restoreInventory(", "returnPoints(", "releasePerks("}) {
            assertFalse(cancelPaying.contains(local),
                    "不得自己再写一份回补(" + local + "):口径会和对账任务漂移");
        }
    }

    @Test
    void 对账任务不得自建一份PAYING关单回补() throws Exception {
        String task = read(Paths.get(
                "..", "chengyinhub-system", "src", "main", "java", "com", "chengyinhub",
                "business", "task", "OrderCloseTask.java"));
        assertTrue(task.contains("cmsRegistrationService.settleClosedPayingOne("),
                "对账任务必须复用原 attempt 守卫和 settlePayingOne 资源释放");
        assertFalse(task.contains("restoreInventory(") || task.contains("addPoints("),
                "对账任务不得自己回补库存/积分");
        String registration = read(Paths.get("..", "chengyinhub-system", "src", "main", "java", "com", "chengyinhub",
                "business", "service", "impl", "CmsRegistrationServiceImpl.java"));
        String guardedClose = section(registration, "public boolean settleClosedPayingOne");
        assertTrue(guardedClose.contains("RegistrationTopicFence.lock("));
        assertTrue(guardedClose.contains("lockClosablePaymentAttemptForUpdate("));
        assertTrue(guardedClose.contains("return settlePayingOne(current.getId(), releaseReason, updateBy, finalStatus)"));
        assertFalse(guardedClose.contains("closeExpiredPaying("));
    }

    /**
     * 从方法签名截到方法体的收尾大括号(同缩进 4 空格的 "\n    }")为止。
     * 不用「截到下一个 private/public」——下一个成员换个修饰符就会静默退化成「截到文件末尾」,
     * 那时本闸会把整份文件都扫进来、永远绿(spec 自审里正好指出过这个退化)。
     */
    private String section(String source, String signature) {
        int from = source.indexOf(signature);
        assertTrue(from >= 0, "找不到方法:" + signature);
        int to = source.indexOf("\n    }", from + signature.length());
        assertTrue(to > from, "找不到方法体收尾:" + signature);
        return source.substring(from, to);
    }

    private String read(Path... candidates) throws Exception {
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromRepoRoot = candidate.getName(0).toString().equals("..")
                    ? candidate.subpath(1, candidate.getNameCount()) : candidate;
            if (Files.exists(fromRepoRoot)) {
                return new String(Files.readAllBytes(fromRepoRoot), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("找不到文件: " + candidates[0]);
    }
}
