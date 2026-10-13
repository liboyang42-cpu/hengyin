package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.RoamExploreDayCandidate;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoamExploreDayCandidateMapperContractTest {

    @Test
    void mapperXml可被MyBatis真实解析且只读商业真源而非legacy报名池() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("RoamExploreDayCandidate", RoamExploreDayCandidate.class);
        String resource = "mapper/business/RoamExploreDayCandidateMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }

        MappedStatement statement = configuration.getMappedStatement(
                "com.chengyinhub.business.mapper.RoamExploreDayCandidateMapper.selectPotentialCandidates");
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("minLat", 31.2d);
        parameters.put("maxLat", 31.3d);
        parameters.put("minLng", 121.4d);
        parameters.put("maxLng", 121.5d);
        String sql = statement.getBoundSql(parameters).getSql().replaceAll("\\s+", " ").toLowerCase();

        assertTrue(sql.contains("join cms_activity activity on activity.topic_id = topic.id"));
        assertTrue(sql.contains("ticket.owner_type = 2 and ticket.owner_id = activity.id"));
        assertTrue(sql.contains("left join mms_merchant merchant on merchant.member_id = topic.member_id"),
                "平台自建期没有商家行，也必须进入候选");
        assertTrue(sql.contains("topic_project_review"));
        assertTrue(sql.contains("topic_sale_review"));
        assertTrue(sql.contains("project_review.snapshot_json"));
        assertTrue(sql.contains("ticket.gather_lat between ? and ?"));
        assertTrue(sql.contains("ticket.gather_lng between ? and ?"));
        assertFalse(sql.contains("cms_registration_merchant"));

        Set<String> mappedProperties = statement.getResultMaps().get(0).getResultMappings().stream()
                .map(ResultMapping::getProperty).collect(Collectors.toSet());
        assertTrue(mappedProperties.contains("projectReviewSnapshotJson"));
        assertTrue(mappedProperties.contains("roamReferralEnabled"));
        assertTrue(mappedProperties.contains("ticketOwnerType"));
        assertTrue(mappedProperties.contains("gatherLat"));
    }

    @Test
    void sharedTopicSelectFragmentMustNotDependOnRoamReferralMigration() throws Exception {
        String resource = "mapper/business/CmsTopicMapper.xml";
        String xml;
        try (Reader input = Resources.getResourceAsReader(resource);
             BufferedReader reader = new BufferedReader(input)) {
            xml = reader.lines().collect(Collectors.joining("\n"));
        }
        String fragment = xml.substring(xml.indexOf("<sql id=\"selectCmsTopicVo\">"),
                xml.indexOf("</sql>", xml.indexOf("<sql id=\"selectCmsTopicVo\">")));

        assertFalse(fragment.contains("roam_referral_enabled"),
                "共享主题查询不得被 5-4 migration 新列拖死");
        assertTrue(xml.contains("roam_referral_enabled = #{roamReferralEnabled}"),
                "后台逐期开关仍须由既有 updateCmsTopic 动态白名单持久化");
    }
}
