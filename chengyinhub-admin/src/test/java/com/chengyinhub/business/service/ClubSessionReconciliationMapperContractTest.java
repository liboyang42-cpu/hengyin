package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 对账 SQL 必须直接从生效 club 合作单反查缺失的带队场次，不能靠 Java 猜。 */
class ClubSessionReconciliationMapperContractTest {

    @Test
    void reconciliationFindsEffectiveClubOrderWithoutBoundSession() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get("..", "chengyinhub-system", "src", "main",
                "resources", "mapper", "business", "CmsActivityMapper.xml")), StandardCharsets.UTF_8)
                .toLowerCase().replaceAll("\\s+", " ");
        int start = xml.indexOf("<select id=\"selectclubordertopicswithoutclubsession\"");
        String query = xml.substring(start, xml.indexOf("</select>", start));

        assertTrue(query.contains("from coop_order"));
        assertTrue(query.contains("to_type = 'club'"));
        assertTrue(query.contains("status = 0") && query.contains("del_flag = 0"),
                "只允许当前 CoopOrder 状态机里的生效合作单触发告警");
        assertTrue(query.contains("not exists") && query.contains("activity.topic_id =")
                        && query.contains("activity.club_id ="),
                "必须按 topic + club 精确检查带队场次，不能只看任意 club_id 非空");
    }
}
