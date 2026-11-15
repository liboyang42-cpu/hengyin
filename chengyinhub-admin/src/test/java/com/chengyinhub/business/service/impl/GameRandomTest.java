package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.GameRandomAudit;
import com.chengyinhub.business.mapper.GameRandomAuditMapper;
import com.chengyinhub.business.service.IGameRandomService;
import com.chengyinhub.business.service.support.AdvancedGameRandom;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GameRandomTest {

    @Test
    void seededInstancesProduceIdenticalDeterministicSequence() {
        GameRandom a = new GameRandom(20261123L);
        GameRandom b = new GameRandom(20261123L);
        for (int i = 0; i < 25; i++) {
            assertEquals(a.d20("d20-" + i, 1L, "check", "c"), b.d20("d20-" + i, 1L, "check", "c"));
            assertEquals(a.d6(IGameRandomService.DOMAIN_CITY_DICE, "d6-" + i, 1L, "board", "b"),
                    b.d6(IGameRandomService.DOMAIN_CITY_DICE, "d6-" + i, 1L, "board", "b"));
            assertEquals(a.flipCoin("coin-" + i, 1L, "kit", "k"), b.flipCoin("coin-" + i, 1L, "kit", "k"));
        }
    }

    @Test
    void distributionsStayWithinValidBounds() {
        GameRandom rng = new GameRandom(99L);
        int[] d6Counts = new int[7];
        int heads = 0;
        for (int i = 0; i < 1200; i++) {
            int roll = rng.d6(IGameRandomService.DOMAIN_PLAYKIT_DICE, null, 1L, "t", "1");
            assertTrue(roll >= 1 && roll <= 6, "d6 out of range: " + roll);
            d6Counts[roll]++;
            int coin = rng.flipCoin(null, 1L, "t", "1");
            assertTrue(coin == 0 || coin == 1);
            if (coin == 0) heads++;
            int d20 = rng.d20(null, 1L, "t", "1");
            assertTrue(d20 >= 1 && d20 <= 20);
        }
        for (int face = 1; face <= 6; face++) {
            assertTrue(d6Counts[face] > 100, "Face " + face + " underrepresented: " + d6Counts[face]);
        }
        assertTrue(heads > 450 && heads < 750, "Coin skew suspicious: " + heads);
    }

    @Test
    void replayAndScopedAdapterRecordAuditsIdempotently() {
        InMemoryAuditMapper mapper = new InMemoryAuditMapper();
        GameRandom rng = new GameRandom(12345L, mapper);

        IGameRandomService.DrawResult first = rng.draw(
                IGameRandomService.DOMAIN_JOURNEY_CHECK, 20, "chk:1:2", 77L, "check", "c1", t -> "{\"roll\":" + (t + 1) + "}");
        assertFalse(first.replayed());
        IGameRandomService.DrawResult second = rng.draw(
                IGameRandomService.DOMAIN_JOURNEY_CHECK, 20, "chk:1:2", 77L, "check", "c1", t -> "{\"roll\":" + (t + 1) + "}");
        assertTrue(second.replayed());
        assertEquals(first.ticket(), second.ticket());
        assertEquals(1, mapper.rows.size());

        AdvancedGameRandom scoped = rng.scoped(IGameRandomService.DOMAIN_PLAYKIT_D20, "pk:99", 77L, "session", "99");
        int r0 = scoped.nextInt(20);
        int r1 = scoped.nextInt(20);
        assertTrue(r0 >= 0 && r0 < 20 && r1 >= 0 && r1 < 20);
        assertNotNull(mapper.selectByDomainAndReceipt(IGameRandomService.DOMAIN_PLAYKIT_D20, "pk:99"));
        assertNotNull(mapper.selectByDomainAndReceipt(IGameRandomService.DOMAIN_PLAYKIT_D20, "pk:99:1"));
    }

    @Test
    void weightedPickRespectsWeights() {
        GameRandom rng = new GameRandom(7L);
        List<String> items = List.of("A", "B");
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 500; i++) {
            IGameRandomService.PickResult<String> picked = rng.weightedPick(
                    IGameRandomService.DOMAIN_TASK_DISPATCH,
                    items,
                    s -> "A".equals(s) ? 90 : 10,
                    null,
                    1L,
                    "pool",
                    "1",
                    s -> s);
            counts.merge(picked.item(), 1, Integer::sum);
        }
        assertTrue(counts.getOrDefault("A", 0) > counts.getOrDefault("B", 0) * 4);
    }

    private static final class InMemoryAuditMapper implements GameRandomAuditMapper {
        final List<GameRandomAudit> rows = new ArrayList<>();

        @Override
        public int insertAudit(GameRandomAudit audit) {
            if (selectByDomainAndReceipt(audit.getDomain(), audit.getReceiptId()) != null) {
                throw new DuplicateKeyException("dup");
            }
            audit.setId((long) (rows.size() + 1));
            rows.add(audit);
            return 1;
        }

        @Override
        public GameRandomAudit selectByDomainAndReceipt(String domain, String receiptId) {
            return rows.stream()
                    .filter(r -> domain.equals(r.getDomain()) && receiptId.equals(r.getReceiptId()))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public List<GameRandomAudit> selectAuditList(GameRandomAudit filter) {
            return List.copyOf(rows);
        }
    }
}
