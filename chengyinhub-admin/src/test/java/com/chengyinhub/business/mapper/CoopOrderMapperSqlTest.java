package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopOrderMapperSqlTest {

    @Test
    void effectiveLineupExcludesHistoricalType2Invites() throws Exception {
        String xml = readMapper();
        String query = select(xml, "selectEffectiveOrdersByTopicId");

        assertTrue(query.contains("not exists") && query.contains("from coop_invite")
                        && query.contains("invite_type = 2"),
                "历史 type2 合作单不得进入有效阵容或后续结算写链");

        String clubTopics = select(xml, "selectEffectiveTopicIdsByClubId");
        assertTrue(clubTopics.contains("not exists") && clubTopics.contains("from coop_invite")
                        && clubTopics.contains("invite_type = 2"),
                "历史 type2 合作单不得继续作为俱乐部有效承接关系");
    }

    private static String readMapper() throws Exception {
        Path path = Paths.get("..", "chengyinhub-system", "src", "main", "resources", "mapper",
                "business", "CoopOrderMapper.xml");
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).toLowerCase();
    }

    private static String select(String xml, String id) {
        int start = xml.indexOf("<select id=\"" + id.toLowerCase() + "\"");
        return xml.substring(start, xml.indexOf("</select>", start));
    }
}
