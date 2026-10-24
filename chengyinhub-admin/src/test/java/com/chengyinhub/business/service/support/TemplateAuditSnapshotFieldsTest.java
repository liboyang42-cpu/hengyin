package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B:P1-032 / B:P1-035:审核快照字段清单必须与实体字段对得上。
 * 字段改名或删掉而清单没跟着改时,快照会静默写 null,审核比对就看不到这块内容。
 */
class TemplateAuditSnapshotFieldsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Set<String> jsonProperties(Class<?> type) {
        JavaType javaType = JSON.constructType(type);
        BeanDescription desc = JSON.getSerializationConfig().introspect(javaType);
        return desc.findProperties().stream().map(BeanPropertyDefinition::getName).collect(Collectors.toSet());
    }

    @Test
    void everyContentFieldExistsOnAtLeastOneTemplateEntity() {
        Set<String> personal = jsonProperties(CmsMemberTemplate.class);
        Set<String> market = jsonProperties(CmsTemplateLibrary.class);
        List<String> missing = TemplateAuditSnapshot.CONTENT.stream()
                .filter(f -> !personal.contains(f) && !market.contains(f))
                .collect(Collectors.toList());
        assertTrue(missing.isEmpty(), "快照字段在两个实体上都不存在:" + missing);
    }

    @Test
    void contentFieldsAreUniqueAndCountIsFrozen() {
        assertEquals(TemplateAuditSnapshot.CONTENT.size(), new HashSet<>(TemplateAuditSnapshot.CONTENT).size(),
                "快照字段不能重复");
        // 增删被审字段时同步改这里,并确认审核比对口径
        assertEquals(48, TemplateAuditSnapshot.CONTENT.size());
    }

    @Test
    void snapshotCarriesEveryContentFieldInOrder() throws Exception {
        CmsMemberTemplate t = new CmsMemberTemplate();
        t.setId(1L);
        t.setTitle("t");
        List<String> keys = JSON.readTree(TemplateAuditSnapshot.personal(t)).properties().stream()
                .map(java.util.Map.Entry::getKey).collect(Collectors.toList());
        assertEquals(List.of("bizType", "bizId"), keys.subList(0, 2));
        assertEquals(TemplateAuditSnapshot.CONTENT, keys.subList(2, keys.size()));
        assertEquals("t", JSON.readTree(TemplateAuditSnapshot.personal(t)).get("title").asText());
    }
}
