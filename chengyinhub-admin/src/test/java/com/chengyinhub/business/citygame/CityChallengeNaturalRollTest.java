package com.chengyinhub.business.citygame;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B10 regression: challenge/command built its reply with {@code roll == 20 ? 20 : roll == 1 ? 1 : null}. The outer
 * ternary is typed int, so the inner null was unboxed and every roll from 2 to 19 threw a NullPointerException.
 */
class CityChallengeNaturalRollTest {

    @ParameterizedTest(name = "d20 roll {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20})
    void everyD20RollResolvesItsNaturalMarker(int roll) {
        Integer nat = assertDoesNotThrow(() -> CityMonopolyService.natural(roll));
        if (roll == 20) assertEquals(Integer.valueOf(20), nat);
        else if (roll == 1) assertEquals(Integer.valueOf(1), nat);
        else assertNull(nat, "mid rolls carry no natural marker");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("roll", roll);
        out.put("nat", CityMonopolyService.natural(roll));
        assertTrue(out.containsKey("nat"), "the reply keeps the nat key (null for 2-19)");
        assertEquals(nat, out.get("nat"));
    }

    /** Guards the city game, dice and runtime sources against the same int/null nested ternary. */
    @Test
    void noPrimitiveNullNestedTernaryInGameSources() throws Exception {
        Path system = Path.of("../chengyinhub-system/src/main/java/com/chengyinhub/business");
        if (!Files.isDirectory(system)) system = Path.of("chengyinhub-system/src/main/java/com/chengyinhub/business");
        assertTrue(Files.isDirectory(system), "system sources not found from " + Path.of("").toAbsolutePath());
        Pattern bad = Pattern.compile("\\?\\s*-?\\d+[lL]?\\s*:\\s*[^;?]*\\?\\s*-?\\d+[lL]?\\s*:\\s*null\\b");
        List<Path> roots = List.of(system.resolve("citygame"), system.resolve("service/support"), system.resolve("service/impl"));
        StringBuilder hits = new StringBuilder();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    List<String> lines = Files.readAllLines(f);
                    for (int i = 0; i < lines.size(); i++) {
                        if (bad.matcher(lines.get(i)).find()) hits.append(f).append(':').append(i + 1).append(' ').append(lines.get(i).strip()).append('\n');
                    }
                }
            }
        }
        assertEquals("", hits.toString(), "int/null nested ternary unboxes null (B10)");
    }
}
