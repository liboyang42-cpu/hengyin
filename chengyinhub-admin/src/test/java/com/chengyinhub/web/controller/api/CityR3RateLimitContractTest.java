package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.enums.LimitType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** MED-S4: the r3 claim / check-in / dice / chance / channel endpoints are member rate-limited. */
class CityR3RateLimitContractTest
{
    private static final Map<Class<?>, List<String>> LIMITED = Map.of(
            ApiCityMonopolyController.class, List.of("claimCommand", "upgradeCommand", "challengeCommand",
                    "scout", "shield", "chance", "purchase", "map", "property", "leaderboard"),
            ApiRoamTerritoryController.class, List.of("nearby", "claim", "checkin", "leaderboard"),
            ApiCityGameController.class, List.of("unifiedDiceRoll", "action", "diceAudit"),
            ApiMemberAttributeController.class, List.of("equip"),
            ApiPlayEnvironmentController.class, List.of("environment"),
            ApiInGameChannelController.class, List.of("resolve", "summary"),
            ApiRoamController.class, List.of("checkin"));

    @Test
    void everyListedEndpointHasMemberRateLimiter()
    {
        LIMITED.forEach((cls, methods) -> {
            for (String name : methods)
            {
                Method[] candidates = Arrays.stream(cls.getDeclaredMethods())
                        .filter(m -> m.getName().equals(name)
                                && (m.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class)
                                || m.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class)))
                        .toArray(Method[]::new);
                assertTrue(candidates.length > 0, cls.getSimpleName() + "#" + name + " endpoint exists");
                for (Method m : candidates)
                {
                    RateLimiter rl = m.getAnnotation(RateLimiter.class);
                    assertNotNull(rl, cls.getSimpleName() + "#" + name + " must be rate-limited");
                    assertEquals(LimitType.MEMBER, rl.limitType());
                    assertTrue(rl.count() > 0 && rl.count() <= 60, cls.getSimpleName() + "#" + name);
                }
            }
        });
    }
}
