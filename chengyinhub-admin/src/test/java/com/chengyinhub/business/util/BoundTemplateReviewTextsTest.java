package com.chengyinhub.business.util;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class BoundTemplateReviewTextsTest {
    @Test
    void everyStringFieldIncludingFutureGameFieldsIsCollected() throws Exception {
        CmsMemberTemplate template = new CmsMemberTemplate();
        for (Field field : CmsMemberTemplate.class.getDeclaredFields()) {
            if (field.getType() == String.class && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                field.set(template, "待审-" + field.getName());
            }
        }
        List<String> texts = JourneyReviewTexts.collectBoundTemplate(template).stream()
                .map(JourneyReviewTexts.Entry::getText).collect(Collectors.toList());
        for (Field field : CmsMemberTemplate.class.getDeclaredFields()) {
            if (field.getType() == String.class && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) assertTrue(texts.contains("待审-" + field.getName()), field.getName());
        }
    }

    @Test
    void arbitraryNestedTextAndEscapedJsonLeavesAreDecodedAndMalformedTextIsStillScanned() {
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setAdvancedConfigJson("{\"futureGame\":{\"newField\":[\"\\u8fdd\\u7981\",{\"arbitrary\":\"深层文案\"}],\"count\":2,\"enabled\":true}}");
        template.setPreferenceJson("[{\"unknown\":\"偏好文案\"}]");
        template.setStoryJson("{broken story 仍须送检");
        List<String> texts = JourneyReviewTexts.collectBoundTemplate(template).stream()
                .map(JourneyReviewTexts.Entry::getText).collect(Collectors.toList());
        assertTrue(texts.contains("违禁"));
        assertTrue(texts.contains("深层文案"));
        assertTrue(texts.contains("偏好文案"));
        assertTrue(texts.contains("{broken story 仍须送检"));
        assertFalse(texts.contains("2"));
        assertFalse(texts.contains("true"));
    }

    @Test
    void jsonLookingPlainTextNeverDropsVisibleSuffixOrObjectKeys() {
        for (String raw : List.of("{} 违禁正文", "[] 违禁正文", "{\"safe\":\"正常\"} 违禁正文",
                "{\"违禁正文\":1}", "{broken story 违禁正文")) {
            CmsMemberTemplate template = new CmsMemberTemplate();
            template.setStoryText(raw);
            template.setDescription(raw);
            assertEquals(1L, JourneyReviewTexts.collectBoundTemplate(template).stream()
                    .filter(entry -> raw.equals(entry.getText())).count(), raw);
            assertTrue(JourneyReviewTexts.collectBoundTemplate(template).stream()
                    .anyMatch(entry -> raw.equals(entry.getText())), raw);
            com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest draft =
                    new com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest();
            draft.setStoryText(raw);
            assertTrue(JourneyReviewTexts.collectTemplateFields(draft).stream()
                    .anyMatch(entry -> raw.equals(entry.getText())), raw);
        }
    }

    @Test
    void encodedNestedJsonStillYieldsDecodedTextAlongsideRawInput() throws Exception {
        CmsMemberTemplate template = new CmsMemberTemplate();
        String raw = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString("{\"futureField\":\"违禁正文\"}");
        template.setStoryText(raw);
        List<String> texts = JourneyReviewTexts.collectBoundTemplate(template).stream()
                .map(JourneyReviewTexts.Entry::getText).collect(Collectors.toList());
        assertTrue(texts.contains(raw));
        assertTrue(texts.contains("违禁正文"));
    }
}
