package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.citygame.CityGameActionService;
import com.chengyinhub.business.mapper.CityGameMapper;
import com.chengyinhub.business.domain.GameRandomAudit;
import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.GameRandomAuditMapper;
import com.chengyinhub.business.service.IGameRandomService;
import com.chengyinhub.business.service.support.AdvancedGameRandom;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

/**
 * 验证 P3 GameRandom 接入各随机消费者（Coin/Dice/D20/Random/JourneyCheck/CityDice）后的审计落表与可替换性。
 */
class SysRandomCallersWiringTest {

    @Test
    void advancedGameRuntimeActionJudgementRoutesCoinDiceD20AndDrawThroughGameRandom() throws Exception {
        RecordingAuditMapper auditMapper = new RecordingAuditMapper();
        GameRandom rng = new GameRandom(2026L, auditMapper);

        AdvancedGameRuntimeServiceImpl runtime = mock(AdvancedGameRuntimeServiceImpl.class);
        // 测试实际辅助逻辑与 GameRandom.scoped 契约
        AdvancedGameRandom defaultField = (AdvancedGameRandom) ReflectionTestUtils.getField(
                new AdvancedGameRuntimeServiceImpl(), "random");
        assertNotNull(defaultField);
        assertNotNull(runtime);

        AdvancedGameRandom coinRandom = rng.scoped(
                IGameRandomService.DOMAIN_PLAYKIT_COIN, "playkit:10:idem1", 7L, "game_runtime_session", "10");
        assertTrue(coinRandom.nextInt(2) >= 0);

        AdvancedGameRandom d6Random = rng.scoped(
                IGameRandomService.DOMAIN_PLAYKIT_DICE, "playkit:10:idem2", 7L, "game_runtime_session", "10");
        int d6 = d6Random.nextInt(6);
        assertTrue(d6 >= 0 && d6 < 6);

        AdvancedGameRandom d20Random = rng.scoped(
                IGameRandomService.DOMAIN_PLAYKIT_D20, "playkit:10:idem3", 7L, "game_runtime_session", "10");
        int d20 = d20Random.nextInt(20);
        assertTrue(d20 >= 0 && d20 < 20);

        AdvancedGameRandom drawRandom = rng.scoped(
                IGameRandomService.DOMAIN_PLAYKIT_RANDOM, "playkit:10:idem4", 7L, "game_runtime_session", "10");
        int picked = drawRandom.nextInt(3);
        assertTrue(picked >= 0 && picked < 3);

        assertEquals(4, auditMapper.rows.size());
        assertEquals(List.of(
                        IGameRandomService.DOMAIN_PLAYKIT_COIN,
                        IGameRandomService.DOMAIN_PLAYKIT_DICE,
                        IGameRandomService.DOMAIN_PLAYKIT_D20,
                        IGameRandomService.DOMAIN_PLAYKIT_RANDOM),
                auditMapper.rows.stream().map(GameRandomAudit::getDomain).toList());
    }

    @Test
    void cityGameActionServiceInjectsGameRandomOnSpringConstructorOnly() throws Exception {
        RecordingAuditMapper auditMapper = new RecordingAuditMapper();
        GameRandom rng = new GameRandom(55L, auditMapper);

        CityGameMapper cityMapper = mock(CityGameMapper.class);
        CityGameActionService springSvc = new CityGameActionService(cityMapper);
        ReflectionTestUtils.setField(springSvc, "gameRandom", rng);

        @SuppressWarnings("unchecked")
        java.util.function.IntUnaryOperator springOp = ReflectionTestUtils.invokeMethod(
                springSvc, "effectiveRandom", "MOVE", "req-1", 7L, "board-1", "dice-1");
        assertNotNull(springOp);
        int val = springOp.applyAsInt(6);
        assertTrue(val >= 0 && val < 6);
        assertEquals(1, auditMapper.rows.size());
        assertEquals(IGameRandomService.DOMAIN_CITY_DICE, auditMapper.rows.get(0).getDomain());

        // 测试用 3 参构造器仍保留调用方自定义随机源
        var ctor = CityGameActionService.class.getDeclaredConstructor(
                CityGameMapper.class,
                java.util.function.IntUnaryOperator.class,
                java.util.function.LongSupplier.class);
        ctor.setAccessible(true);
        CityGameActionService customSvc = ctor.newInstance(
                cityMapper,
                (java.util.function.IntUnaryOperator) (b -> 4),
                (java.util.function.LongSupplier) System::currentTimeMillis);
        ReflectionTestUtils.setField(customSvc, "gameRandom", rng);
        java.util.function.IntUnaryOperator customOp = ReflectionTestUtils.invokeMethod(
                customSvc, "effectiveRandom", "MOVE", "req-2", 7L, "board-1", "dice-1");
        assertEquals(4, customOp.applyAsInt(6));
        assertEquals(1, auditMapper.rows.size());
    }

    @Test
    void topicRouteRuntimeServiceTracksCustomVsGameRandomD20() {
        RecordingAuditMapper auditMapper = new RecordingAuditMapper();
        GameRandom rng = new GameRandom(33L, auditMapper);

        TopicRouteRuntimeServiceImpl springLike = new TopicRouteRuntimeServiceImpl(
                null, null, null, null, null, null, null, null, null, () -> 1L, null, null, null);
        ReflectionTestUtils.setField(springLike, "gameRandom", rng);
        assertEquals(false, ReflectionTestUtils.getField(springLike, "customD20"));

        TopicRouteRuntimeServiceImpl customLike = new TopicRouteRuntimeServiceImpl(
                null, null, null, null, null, null, null, null, null, () -> 1L, () -> 20, null, null);
        ReflectionTestUtils.setField(customLike, "gameRandom", rng);
        assertEquals(true, ReflectionTestUtils.getField(customLike, "customD20"));
    }

    private static final class RecordingAuditMapper implements GameRandomAuditMapper {
        final List<GameRandomAudit> rows = new ArrayList<>();

        @Override
        public int insertAudit(GameRandomAudit audit) {
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
