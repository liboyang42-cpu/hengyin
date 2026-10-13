package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 模板成熟度是公开门禁：实验/试点内容不能出现在公共货架或被直链复制。 */
class TopicTemplateVisibilityContractTest {

    @Test
    void publicTemplateQueriesRequireVerifiedMetadata() throws Exception {
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
        assertTrue(xml.matches("(?s).*<sql id=\"publictopictemplateeligibility\".*?"
                        + "status = 1.*?user_status = 1.*?cms_topic_template_meta.*?"
                        + "template_status = 'verified'.*?</sql>.*"),
                "匿名模板 eligibility 必须同时要求审核通过、在架和 VERIFIED");
        assertTrue(xml.matches("(?s).*<select id=\"selectpublictopictemplatebyid\".*?"
                        + "publictopictemplateeligibility.*?</select>.*"),
                "匿名模板详情必须用一条 fail-closed 查询同时读取 eligibility 与详情快照");
    }
}
