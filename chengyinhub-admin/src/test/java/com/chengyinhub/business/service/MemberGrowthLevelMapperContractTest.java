package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 成长中心等级:按 growth_level_config(M7 探索值→等级梯度)由累计探索值现算,
 * 不再写入 floor(exp/100)+1 的无上限等级(126,806 EXP 曾显示为 Lv.1269)。
 */
class MemberGrowthLevelMapperContractTest {

    private static final String LEVEL_EXPR =
            "coalesce((select max(c.level) from growth_level_config c where c.need_exp is not null"
                    + " and c.need_exp &lt;= g.exp_value), 1) as levelNo";

    @Test
    void readsDeriveLevelFromConfiguredExpLadder() throws Exception {
        String xml = read();
        for (String id : new String[] {"selectByMemberId", "selectAdminGrowthList"}) {
            String q = block(xml, "<select id=\"" + id + "\"", "</select>");
            assertTrue(q.contains(LEVEL_EXPR), id + " 等级必须由 growth_level_config 按累计探索值现算,无档位兜底 Lv.1");
            assertFalse(q.contains("level_no as levelNo"), id + " 不得再把 level_no 列当展示真源");
        }
    }

    @Test
    void rewardUpdateNoLongerWritesUncappedLevel() throws Exception {
        String q = block(read(), "<update id=\"updateMemberGrowthReward\"", "</update>");
        assertFalse(q.contains("level_no"), "发探索值不再按 exp/100 递增 level_no");
        assertFalse(q.contains("/ 100"), "不再有 exp/100 的等级公式");
        assertTrue(q.contains("exp_value = exp_value + #{expValue}"));
    }

    private String read() throws Exception {
        return new String(Files.readAllBytes(Paths.get("..", "chengyinhub-system", "src", "main",
                "resources", "mapper", "business", "MemberGrowthMapper.xml")), StandardCharsets.UTF_8);
    }

    private String block(String xml, String open, String close) {
        int start = xml.indexOf(open);
        assertTrue(start >= 0, open);
        return xml.substring(start, xml.indexOf(close, start));
    }
}
