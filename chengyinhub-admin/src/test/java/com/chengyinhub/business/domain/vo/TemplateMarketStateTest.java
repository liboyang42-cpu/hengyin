package com.chengyinhub.business.domain.vo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C:U-046:my-list 下发的 auditState/marketState 与小程序兜底推导共用同一份用例。 */
class TemplateMarketStateTest {

    private static Integer intOrNull(JsonNode n, String f) { return n.hasNonNull(f) ? n.get(f).asInt() : null; }
    private static Long longOrNull(JsonNode n, String f) { return n.hasNonNull(f) ? n.get(f).asLong() : null; }

    @Test
    void serverStatesMatchSharedCases() throws Exception {
        JsonNode cases = new ObjectMapper().readTree(Paths.get(System.getProperty("user.dir"),
                "../chengyinhub-xcx/tests/fixtures/template-market-state-cases.json").toFile()).get("cases");
        assertTrue(cases.size() > 50);
        for (JsonNode c : cases) {
            JsonNode in = c.get("input");
            MemberTemplateVO vo = new MemberTemplateVO();
            vo.setDraftStatus(intOrNull(in, "draftStatus"));
            vo.setStatus(longOrNull(in, "status"));
            vo.setCheckStatus(intOrNull(in, "checkStatus"));
            vo.setMarketTemplateId(longOrNull(in, "marketTemplateId"));
            vo.setMarketStatus(longOrNull(in, "marketStatus"));
            vo.setMarketPublishStatus(longOrNull(in, "marketPublishStatus"));
            assertEquals(c.get("auditState").asText(), vo.getAuditState(), in.toString());
            assertEquals(c.get("marketState").asText(), vo.getMarketState(), in.toString());
        }
    }

    @Test
    void myListJsonCarriesStates() {
        MemberTemplateVO vo = new MemberTemplateVO();
        vo.setDraftStatus(0);
        JsonNode json = new ObjectMapper().valueToTree(vo);
        assertEquals("DRAFT", json.get("auditState").asText());
        assertEquals("NONE", json.get("marketState").asText());
    }
}
