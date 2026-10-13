package com.chengyinhub.business.service.impl;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合作中心「官方活动」tab 线上恒空(2026-09-17 模拟回放发现):商家侧 isMerchantRecruitableEvent 只认
 * fulfillmentPolicy ∈ {OPTIONAL_PARTNERS, REQUIRED_FULFILLMENT},而 /api/official/events 的 v2 行来自
 * selectPublicV2Events(resultType=map),原来没选 fulfillment_policy ⇒ 字段恒缺 ⇒ 商家侧全部过滤掉。
 * 与详情 eventBase 同口径:原样下发列值。负控:删掉这一列 → 本测试红。
 */
class OfficialEventPublicListMapperContractTest {

    private static String select(String id) throws Exception {
        Path p = Paths.get("../chengyinhub-system/src/main/resources/mapper/business/OfficialEventV2Mapper.xml");
        if (!Files.exists(p)) p = Paths.get("chengyinhub-system/src/main/resources/mapper/business/OfficialEventV2Mapper.xml");
        String xml = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        int at = xml.indexOf("<select id=\"" + id + "\"");
        assertTrue(at >= 0, "OfficialEventV2Mapper 缺 " + id);
        return xml.substring(at, xml.indexOf("</select>", at));
    }

    @Test
    void 公开列表必须下发fulfillmentPolicy_否则商家合作中心官方活动恒空() throws Exception {
        String sel = select("selectPublicV2Events");
        assertTrue(sel.matches("(?s).*\\be\\.fulfillment_policy\\s+(as\\s+)?fulfillmentPolicy\\b.*"),
                "selectPublicV2Events 必须 select e.fulfillment_policy fulfillmentPolicy(map 键名即前端字段名)");
    }
}
