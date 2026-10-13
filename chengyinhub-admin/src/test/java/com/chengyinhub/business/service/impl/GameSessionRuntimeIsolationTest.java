package com.chengyinhub.business.service.impl;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameSessionRuntimeIsolationTest {

    @Test
    void manualUnlockAndFinishCannotWriteLegacyCompletionRewardOrSettlement() throws Exception {
        String source = source();
        String unlock = between(source, "private ActionResult unlockChapter", "private ActionResult broadcast");
        String finish = between(source, "private ActionResult finish", "private GameSessionView playerView");
        assertNoExternalCompletionOrFinance(unlock + finish);

        assertThrows(AssertionError.class,
                () -> assertNoExternalCompletionOrFinance(unlock + " settleFinancialReward();"));
    }

    private void assertNoExternalCompletionOrFinance(String source) {
        String lower = source.toLowerCase();
        for (String forbidden : new String[]{"playprogress", "entitlement", "coupon", "reward",
                "settle", "withdraw", "finance", "redemption"}) {
            assertTrue(!lower.contains(forbidden), "forbidden boundary token: " + forbidden);
        }
    }

    private String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue(from >= 0 && to > from);
        return source.substring(from, to);
    }

    private String source() throws Exception {
        Path path = Paths.get("..", "chengyinhub-system", "src", "main", "java", "com", "chengyinhub",
                "business", "service", "impl", "GameSessionRuntimeServiceImpl.java");
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
