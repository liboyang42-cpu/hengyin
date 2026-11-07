package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-22 owner 裁决：货架展示所有已发布模板，不再要求 VERIFIED 元数据；VERIFIED 只用于 recommendedOnly 推荐口径。 */
class TopicTemplateVisibilityContractTest {

    @Test
    void publicTemplateQueriesRequirePublishedNotVerified() throws Exception {
        Path mapper = Paths.get("..", "chengyinhub-system", "src", "main", "resources",
                "mapper", "business", "CmsTopicMapper.xml");
        String xml = new String(Files.readAllBytes(mapper), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ")
                .toLowerCase();

        assertTrue(xml.matches("(?s).*<select id=\"selecttopictemplatelist\".*?"
                        + "publictopictemplateeligibility.*?</select>.*"),
                "公共模板列表必须复用统一公共 eligibility");
        assertTrue(xml.matches("(?s).*<select id=\"selecttopicistemplate\".*?"
                        + "publictopictemplateeligibility.*?</select>.*"),
                "模板复制直链必须复用统一公共 eligibility");
        assertTrue(xml.matches("(?s).*<sql id=\"publictopictemplateeligibility\">"
                        + " del_flag = 0 and is_template = 1 and status = 1 and user_status = 1"
                        + " and not exists \\( select 1 from cms_topic_template_meta template_meta"
                        + " where template_meta.topic_id = cms_topic.id and template_meta.del_flag = 0"
                        + " and template_meta.template_status = 'retired' \\) </sql>.*"),
                "公共模板保留四项门禁，只排除有效 RETIRED 元数据；EXPERIMENTAL 与无元数据仍可采用");
        assertTrue(xml.matches("(?s).*<select id=\"selectpublictopictemplatebyid\".*?"
                        + "publictopictemplateeligibility.*?</select>.*"),
                "匿名模板详情必须用一条 fail-closed 查询同时读取 eligibility 与详情快照");
    }
}
