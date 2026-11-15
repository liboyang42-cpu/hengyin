package com.chengyinhub.business.sponsoredquest;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S3b review point: sponsored campaigns reach players only through the reviewed files below. If a fog, roam ranking or
 * search file (or anything else) starts reading the campaign tables or classes, this test fails, so a paid boost cannot
 * slip into the fog map, pin order or search order without a deliberate review.
 */
class SponsoredQuestIsolationGuardTest {
    private static final Set<String> REVIEWED = Set.of(
            "chengyinhub-system/src/main/java/com/chengyinhub/business/sponsoredquest/SponsoredQuestLabel.java",
            "chengyinhub-system/src/main/java/com/chengyinhub/business/sponsoredquest/SponsoredQuestCampaign.java",
            "chengyinhub-system/src/main/java/com/chengyinhub/business/sponsoredquest/SponsoredQuestService.java",
            "chengyinhub-system/src/main/java/com/chengyinhub/business/sponsoredquest/SponsoredQuestAdminService.java",
            "chengyinhub-system/src/main/java/com/chengyinhub/business/sponsoredquest/SponsoredQuestBudgetService.java",
            "chengyinhub-system/src/main/java/com/chengyinhub/business/sponsoredquest/mapper/SponsoredQuestMapper.java",
            "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiSponsoredQuestController.java",
            "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/SponsoredQuestAdminController.java");

    /** Fog tables and types (comments may say "fog map"; code may not name a fog table or class). */
    private static final Pattern FOG_CODE = Pattern.compile("(?i)roam_member_fog|roam_fog|fog_cell|fog_tile|\\bFog[A-Z]\\w*|PersonalFog");

    private static Path root() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("chengyinhub-system"))) p = p.getParent();
        if (p == null) throw new IllegalStateException("repo root not found");
        return p;
    }

    private static List<Path> mainSources(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        for (String module : List.of("chengyinhub-system", "chengyinhub-admin", "chengyinhub-framework", "chengyinhub-common", "chengyinhub-quartz")) {
            Path main = root.resolve(module).resolve("src/main");
            if (!Files.isDirectory(main)) continue;
            try (Stream<Path> s = Files.walk(main)) {
                s.filter(f -> f.toString().endsWith(".java") || f.toString().endsWith(".xml")).forEach(out::add);
            }
        }
        return out;
    }

    @Test
    void onlyReviewedFilesTouchSponsoredCampaigns() throws IOException {
        Path root = root();
        List<String> offenders = new ArrayList<>();
        int reviewedSeen = 0;
        for (Path f : mainSources(root)) {
            String rel = root.relativize(f).toString().replace('\\', '/');
            String text = Files.readString(f, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            boolean touches = text.contains("sponsored_quest") || text.contains("sponsoredquest");
            if (REVIEWED.contains(rel)) {
                reviewedSeen++;
            } else if (touches) {
                offenders.add(rel);
            }
        }
        assertEquals(REVIEWED.size(), reviewedSeen, "every reviewed file still exists");
        assertTrue(offenders.isEmpty(), "unreviewed files read sponsored campaigns: " + offenders);
    }

    @Test
    void sponsoredCodeNeverReachesFogRankingOrSearch() throws IOException {
        Path root = root();
        for (String rel : REVIEWED) {
            String text = Files.readString(root.resolve(rel), StandardCharsets.UTF_8);
            String lower = text.toLowerCase(Locale.ROOT);
            assertFalse(FOG_CODE.matcher(text).find(), rel + " must not reach the fog map");
            assertFalse(text.contains("RoamMapper") || text.contains("RoamFogCell") || lower.contains("content_search")
                    || text.contains("ContentSearch") || text.contains("RoamService"), rel + " must not reach roam ranking or search");
            assertFalse(lower.contains("update roam_poi") || lower.contains("roam_poi set"), rel + " must not change places");
        }
    }
}
