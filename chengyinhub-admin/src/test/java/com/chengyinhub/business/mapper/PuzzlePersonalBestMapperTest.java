package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 个人最佳只接受完整场次，不能把未玩完的一局误当最高分。 */
class PuzzlePersonalBestMapperTest {

    @Test
    void bestScoreGroupsByExistingSessionAndRequiresEveryPuzzleNode() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get("..", "chengyinhub-system", "src", "main",
                "resources", "mapper", "business", "PlayerNodeProgressMapper.xml")), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ")
                .toLowerCase();

        assertTrue(xml.contains("group by coalesce(p.activity_id, 0)"));
        assertTrue(xml.contains("join cms_topic_node pn on pn.id = p.node_id"));
        assertTrue(xml.contains("scored_session.done_count = ( select count(*)"));
        assertTrue(xml.contains("scored_session.puzzle_count = ( select count(*)"));
        assertTrue(xml.contains("n.status = 1"));
        assertTrue(xml.contains("n.merchant_member_id is null or n.node_audit_status = 1"));
        assertTrue(xml.contains("t.validation_method in (1, 3)"));
    }
}
