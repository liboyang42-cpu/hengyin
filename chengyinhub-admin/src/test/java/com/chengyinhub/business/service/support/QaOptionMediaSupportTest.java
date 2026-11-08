package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 0.3「图片选项」/ CT-07 最终一题:图片只做呈现,判定仍是原 QA 的 optionId。 */
class QaOptionMediaSupportTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode pick() {
        ObjectNode qa = JSON.createObjectNode();
        qa.put("enabled", true).put("mode", "PICK");
        ArrayNode options = qa.putArray("options");
        options.addObject().put("id", "o1").put("label", "张医生");
        options.addObject().put("id", "o2").put("label", "李师傅");
        return qa;
    }

    private static ObjectNode first(JsonNode qa) {
        return (ObjectNode) qa.path("options").get(0);
    }

    @Test
    void pickOptionImageMustCarryAltText() {
        ObjectNode qa = pick();
        first(qa).put("img", "/profile/upload/2026/11/08/a.png");

        IllegalArgumentException missingAlt =
                assertThrows(IllegalArgumentException.class, () -> QaOptionMediaSupport.validate(qa));
        assertTrue(missingAlt.getMessage().contains("替代文字"), missingAlt.getMessage());

        first(qa).put("alt", "张医生");
        QaOptionMediaSupport.validate(qa);
    }

    @Test
    void altWithoutImageIsRejectedInsteadOfDangling() {
        ObjectNode qa = pick();
        first(qa).put("alt", "张医生");
        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> QaOptionMediaSupport.validate(qa));
        assertTrue(error.getMessage().contains("没有配图"), error.getMessage());

        // 空串图片也等于没配图,同样不能留一条悬空替代文字
        ObjectNode blank = pick();
        first(blank).put("img", "   ").put("alt", "张医生");
        assertThrows(IllegalArgumentException.class, () -> QaOptionMediaSupport.validate(blank));
    }

    @Test
    void imageReferenceKeepsTheExistingAuthorMediaRule() {
        for (String rejected : new String[]{
                "http://cdn.example.com/a.png",
                "//cdn.example.com/a.png",
                "data:image/png;base64,AAAA",
                "/profile\\upload\\a.png",
                "profile/upload/a.png",
                ""}) {
            ObjectNode qa = pick();
            first(qa).put("img", rejected).put("alt", "张医生");
            assertThrows(IllegalArgumentException.class, () -> QaOptionMediaSupport.validate(qa),
                    "应拒绝非法图片地址: " + rejected);
        }
        for (String accepted : new String[]{
                "/profile/upload/2026/11/08/a.png",
                "https://www.chengyinhub.com/prod-api/profile/template-covers/a.png",
                "media-asset:12"}) {
            ObjectNode qa = pick();
            first(qa).put("img", accepted).put("alt", "张医生");
            QaOptionMediaSupport.validate(qa);
        }
        assertFalse(QaOptionMediaSupport.isAllowedReference("http://cdn.example.com/a.png"));
        assertTrue(QaOptionMediaSupport.isAllowedReference("media-asset:12"));
    }

    /**
     * 本类不再自带 32 / 512 这类专用阈值:单字段长短由整份 advanced_config_json
     * 的既有字节预算约束。这里确认超长文案不会被本类拒(否则就是没有来源的硬阈值)。
     */
    @Test
    void noDedicatedLengthThresholdBeyondTheConfigBudget() {
        ObjectNode qa = pick();
        first(qa).put("img", "https://cdn.example.com/" + "a".repeat(2000))
                .put("alt", "很".repeat(500));
        QaOptionMediaSupport.validate(qa);
    }

    @Test
    void typedAndDisabledQuestionsAreNotTouched() {
        QaOptionMediaSupport.validate(null);

        ObjectNode disabled = pick();
        disabled.put("enabled", false);
        first(disabled).put("img", "data:image/png;base64,AA");
        QaOptionMediaSupport.validate(disabled);

        ObjectNode typed = JSON.createObjectNode();
        typed.put("enabled", true).put("mode", "TYPE");
        ((ObjectNode) typed.putArray("options").addObject()).put("img", "data:image/png;base64,AA");
        QaOptionMediaSupport.validate(typed);
    }

    @Test
    void projectionKeepsOptionIdsAndNeverExposesAnswers() {
        ObjectNode qa = pick();
        ObjectNode answer = first(qa);
        answer.put("img", "media-asset:7").put("alt", "张医生")
                .put("fb", "你揭穿了他").put("correct", true);
        answer.putArray("effects").addObject().put("var", "clue.a").put("value", 1);

        ArrayNode projected = QaOptionMediaSupport.project(qa, null);

        assertEquals(2, projected.size());
        // 判定靠这些 optionId,投影里一个都不能变
        assertEquals("o1", projected.get(0).path("id").asText());
        assertEquals("o2", projected.get(1).path("id").asText());
        assertEquals("media-asset:7", projected.get(0).path("img").asText());
        assertEquals("张医生", projected.get(0).path("alt").asText());
        String wire = projected.toString();
        assertFalse(wire.contains("correct"), wire);
        assertFalse(wire.contains("\"fb\""), wire);
        assertFalse(wire.contains("effects"), wire);
    }

    @Test
    void projectionDropsMediaThatIsNotVisibleYet() {
        ObjectNode qa = pick();
        first(qa).put("img", "media-asset:7").put("alt", "张医生");

        ArrayNode hidden = QaOptionMediaSupport.project(qa, reference -> "");
        assertEquals(2, hidden.size());
        assertFalse(hidden.get(0).has("img"));
        assertFalse(hidden.get(0).has("alt"));

        ArrayNode shown = QaOptionMediaSupport.project(qa, reference -> "https://cdn.example.com/a.png");
        assertEquals("https://cdn.example.com/a.png", shown.get(0).path("img").asText());
    }

    @Test
    void emptyProjectionForDisabledQuestionAndNoMediaForBareOption() {
        ObjectNode disabled = pick();
        disabled.put("enabled", false);
        assertEquals(0, QaOptionMediaSupport.project(disabled, null).size());

        JsonNode option = JSON.createObjectNode().put("id", "o9").put("label", "甲");
        assertTrue(QaOptionMediaSupport.media(option).isEmpty());
        assertEquals("o9", option.path("id").asText());
    }
}
