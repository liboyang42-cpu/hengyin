package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.vo.ContentDeliveryMetadataVO;
import com.chengyinhub.business.it.MybatisItDatabase;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.CmsTopicTemplateMetaMapper;
import com.chengyinhub.business.mapper.TopicSaleSnapshotMapper;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BE-13 交付元数据的真库验收:真 mapper XML、真 SQL、一次性 MySQL。
 *
 * <p>覆盖三件只有真库才能证明的事:
 * ①元数据 / 章节投入按 topic 正确聚合;②商家节点仍受数据层可见性闸约束(未审节点不计入公开交付摘要);
 * ③许可摘要只从 {@code topic_sale_snapshot.canonical_json} 读,缺快照就是 UNKNOWN。
 *
 * <p>本机不跑;在批准测试环境 / CI 由统一负责人执行。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TopicDeliveryMetadataMySqlIT {

    private String database;
    private SqlSession session;
    private TopicDeliveryMetadataProjection projection;

    @BeforeAll
    void setup() throws Exception {
        assertTrue("127.0.0.1".equals(MybatisItDatabase.host()) || "localhost".equals(MybatisItDatabase.host()),
                "Only localhost scratch MySQL");
        String suite = "delivery_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String url = MybatisItDatabase.url(suite);
        database = MybatisItDatabase.databaseName(suite);
        DriverManagerDataSource source = new DriverManagerDataSource(url, MybatisItDatabase.user(),
                MybatisItDatabase.password());
        try (Connection connection = source.getConnection(); Statement st = connection.createStatement()) {
            schema(st);
            // 主题 21:有元数据 + 两章投入 + 四个节点(一个未审商家节点必须被数据层闸挡掉)。
            st.execute("INSERT INTO cms_topic_template_meta (id, topic_id, template_status, evidence_level,"
                    + " is_official, del_flag, update_time) VALUES (1, 21, 'PILOT', 'ESTIMATED', 0, 0, NOW())");
            st.execute("INSERT INTO cms_topic_chapter_template_meta (id, topic_id, chapter_id, role_need,"
                    + " effort_venue, effort_materials, effort_staff_minutes, effort_prep_hours,"
                    + " effort_max_concurrent, effort_needs_booking, del_flag)"
                    + " VALUES (1, 21, 501, '1名店员', '老城咖啡馆', '印章 2 枚', 30, 2.5, 6, 1, 0)");
            st.execute("INSERT INTO cms_topic_chapter_template_meta (id, topic_id, chapter_id, role_need,"
                    + " effort_venue, effort_materials, effort_staff_minutes, del_flag)"
                    + " VALUES (2, 21, 502, '店主', '旧邮局', '地图 1 份', 45, 0)");
            // 软删的章节元数据不得进入交付摘要。
            st.execute("INSERT INTO cms_topic_chapter_template_meta (id, topic_id, chapter_id, role_need,"
                    + " effort_venue, del_flag) VALUES (3, 21, 503, '幽灵员工', '已删场地', 1)");
            st.execute("INSERT INTO cms_topic_node (id, topic_id, chapter_id, name, address, longitude, latitude,"
                    + " merchant_member_id, node_audit_status, checkin_code, status, sort_id)"
                    + " VALUES (11, 21, 501, '第一站', '老城广场 1 号', 52.09, 5.12, NULL, NULL, NULL, 1, 0)");
            st.execute("INSERT INTO cms_topic_node (id, topic_id, chapter_id, name, address, longitude, latitude,"
                    + " merchant_member_id, node_audit_status, checkin_code, status, sort_id)"
                    + " VALUES (12, 21, 502, '第二站', NULL, NULL, NULL, NULL, NULL, 'CODE-1', 1, 1)");
            // 已过审商家节点:计入交付摘要。
            st.execute("INSERT INTO cms_topic_node (id, topic_id, chapter_id, name, address, longitude, latitude,"
                    + " merchant_member_id, node_audit_status, checkin_code, status, sort_id)"
                    + " VALUES (13, 21, 502, '商家站', '旧邮局', NULL, NULL, 700, 1, NULL, 1, 2)");
            // 未过审商家节点:公开侧不可见,不得计入。
            st.execute("INSERT INTO cms_topic_node (id, topic_id, chapter_id, name, address, longitude, latitude,"
                    + " merchant_member_id, node_audit_status, checkin_code, status, sort_id)"
                    + " VALUES (14, 21, 502, '待审商家站', '秘密地址', NULL, NULL, 700, 0, NULL, 1, 3)");
            // 停用节点(下线)不计入。
            st.execute("INSERT INTO cms_topic_node (id, topic_id, chapter_id, name, address, longitude, latitude,"
                    + " merchant_member_id, node_audit_status, checkin_code, status, sort_id)"
                    + " VALUES (15, 21, 501, '已下架', '旧地址', NULL, NULL, NULL, NULL, NULL, 0, 4)");
            // 主题 22:没有任何交付事实。
            st.execute("INSERT INTO cms_topic (id, name, status, user_status, del_flag) VALUES (22, 'empty', 1, 1, 0)");
            // 主题 23:有销售快照且带 contentRightsPackage。
            st.execute("INSERT INTO topic_sale_snapshot (id, topic_id, schema_version, parameter_version,"
                    + " source_review_task_id, canonical_json, frozen_at) VALUES (1, 23, 'CANONICAL_15_V1', 3, 9,"
                    + " '{\"contentRightsPackage\":{\"assetRef\":\"ASSET-9\",\"owner\":\"作者工作室\","
                    + "\"licensedEntity\":\"城瘾平台\",\"channels\":[\"WECHAT_MINI\",\"H5\"],"
                    + "\"validFrom\":\"2026-01-01\",\"validTo\":\"2027-01-01\",\"editingRight\":false}}', NOW())");
            // 主题 24:快照存在但无 contentRightsPackage → UNKNOWN。
            st.execute("INSERT INTO topic_sale_snapshot (id, topic_id, schema_version, parameter_version,"
                    + " source_review_task_id, canonical_json, frozen_at) VALUES (2, 24, 'CANONICAL_15_V1', 1, 9,"
                    + " '{}', NOW())");
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/CmsTopicTemplateMetaMapper.xml"),
                new ClassPathResource("mapper/business/CmsTopicNodeMapper.xml"),
                new ClassPathResource("mapper/business/TopicSaleSnapshotMapper.xml"));
        session = new SqlSessionTemplate(factory.getObject());
        projection = new TopicDeliveryMetadataProjection();
        ReflectionTestUtils.setField(projection, "metaMapper", session.getMapper(CmsTopicTemplateMetaMapper.class));
        ReflectionTestUtils.setField(projection, "nodeMapper", session.getMapper(CmsTopicNodeMapper.class));
        ReflectionTestUtils.setField(projection, "saleSnapshotMapper", session.getMapper(TopicSaleSnapshotMapper.class));
        // 本 IT 不建修订索引:交付元数据必须能在没有修订表的情况下正常工作。
        ReflectionTestUtils.setField(projection, "contentRevisionService", null);
    }

    @AfterAll
    void tearDown() throws Exception {
        if (session != null) session.close();
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(
                MybatisItDatabase.url(database).replace("/" + database + "?", "/mysql?"),
                MybatisItDatabase.user(), MybatisItDatabase.password()));
        admin.execute("DROP DATABASE IF EXISTS " + database);
    }

    private static void schema(Statement st) throws Exception {
        st.execute("DROP TABLE IF EXISTS cms_topic_node");
        st.execute("CREATE TABLE cms_topic_node (id BIGINT PRIMARY KEY, topic_id BIGINT, chapter_id BIGINT,"
                + " name VARCHAR(255), description TEXT, address VARCHAR(255), longitude VARCHAR(64),"
                + " latitude VARCHAR(64), img_url VARCHAR(512), node_time INT, template_id BIGINT,"
                + " merchant_member_id BIGINT, node_audit_status INT, node_audit_reason VARCHAR(255),"
                + " node_audit_member_id BIGINT, node_audit_time DATETIME, checkin_code VARCHAR(64),"
                + " business_time VARCHAR(255), status BIGINT, sort_id BIGINT, xp_value INT,"
                + " hook_text TEXT, card_hook_long TEXT, fragment_text TEXT, unlock_after_node_id BIGINT,"
                + " create_by VARCHAR(64), create_time DATETIME, update_by VARCHAR(64), update_time DATETIME)");
        st.execute("CREATE TABLE merchant_chapter_offer (id BIGINT PRIMARY KEY, chapter_id BIGINT,"
                + " merchant_id BIGINT, status INT, withdraw_reason VARCHAR(255))");
        st.execute("CREATE TABLE cms_topic_template_meta (id BIGINT PRIMARY KEY, topic_id BIGINT,"
                + " template_status VARCHAR(32), version VARCHAR(32), applicable_roles VARCHAR(255),"
                + " audience_who TEXT, audience_moment TEXT, audience_need TEXT, player_promise TEXT,"
                + " core_loop TEXT, main_skeleton_template_id BIGINT, evidence_level VARCHAR(32),"
                + " risk_tags VARCHAR(255), risk_qualifications TEXT, risk_cancellation TEXT,"
                + " risk_liability TEXT, risk_safety_notice TEXT, preview_before TEXT, preview_during TEXT,"
                + " preview_after TEXT, author VARCHAR(255), is_official INT, update_time DATETIME,"
                + " del_flag INT DEFAULT 0)");
        st.execute("CREATE TABLE cms_topic_chapter_template_meta (id BIGINT PRIMARY KEY, chapter_id BIGINT,"
                + " topic_id BIGINT, role_need TEXT, exclusivity_mode VARCHAR(32), exclusivity_scope VARCHAR(255),"
                + " exclusivity_period VARCHAR(255), exclusivity_reason TEXT, effort_prep_hours DECIMAL(6,2),"
                + " effort_staff_minutes INT, effort_venue TEXT, effort_materials TEXT, batch_size INT,"
                + " batch_interval_minutes INT, effort_max_concurrent INT, effort_available_slots TEXT,"
                + " effort_needs_booking INT, effort_cancel_cost TEXT, effort_qualifications TEXT,"
                + " del_flag INT DEFAULT 0, update_time DATETIME)");
        st.execute("CREATE TABLE topic_sale_snapshot (id BIGINT PRIMARY KEY, topic_id BIGINT,"
                + " schema_version VARCHAR(64), parameter_version BIGINT, source_review_task_id BIGINT,"
                + " canonical_json LONGTEXT, frozen_at DATETIME)");
        st.execute("CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, name VARCHAR(255), status INT,"
                + " user_status INT, del_flag INT)");
    }

    private CmsTopic topic(long id) {
        CmsTopic topic = new CmsTopic();
        topic.setId(id);
        return topic;
    }

    @Test
    void realMetadataRowsDriveEvidenceAndPartnerOperatingSummary() {
        ContentDeliveryMetadataVO partner = projection.build(topic(21L), true);

        assertEquals("PILOT", partner.getEvidence().getTemplateStatus());
        assertEquals("ESTIMATED", partner.getEvidence().getEvidenceLevel());
        assertEquals(ContentDeliveryMetadataVO.VISIBILITY_INCLUDED, partner.getOperatingRequirementsVisibility());
        assertEquals(2, partner.getOperatingRequirements().getVenues().size(), "软删的章节元数据不得进入摘要");
        assertTrue(partner.getOperatingRequirements().getVenues().contains("老城咖啡馆"));
        assertTrue(partner.getOperatingRequirements().getVenues().contains("旧邮局"));
        assertFalse(partner.getOperatingRequirements().getVenues().contains("已删场地"));
        assertEquals(Integer.valueOf(45), partner.getOperatingRequirements().getStaffMinutesMax());
        assertEquals(Integer.valueOf(6), partner.getOperatingRequirements().getMaxConcurrent());
        assertEquals(Boolean.TRUE, partner.getOperatingRequirements().getNeedsBooking());
        assertEquals(0, new java.math.BigDecimal("2.5")
                .compareTo(partner.getOperatingRequirements().getPrepHoursMax()));
    }

    @Test
    void nonPartnerViewerGetsNoOperatingNumbers() {
        ContentDeliveryMetadataVO anonymous = projection.build(topic(21L), false);
        assertEquals(ContentDeliveryMetadataVO.VISIBILITY_PARTNER_ONLY,
                anonymous.getOperatingRequirementsVisibility());
        assertNull(anonymous.getOperatingRequirements());
    }

    @Test
    void locationSummaryHonoursNodeVisibilityGateAndSkipsDelistedStations() {
        ContentDeliveryMetadataVO.LocationRequirement location =
                projection.build(topic(21L), true).getLocationRequirement();

        // 11(主办方)、12(主办方,含打卡码)、13(已过审商家)计入;14(待审商家)、15(停用)不计入。
        assertEquals(Integer.valueOf(3), location.getStationCount());
        assertEquals(Integer.valueOf(2), location.getStationWithAddressCount());
        assertEquals(Integer.valueOf(2), location.getStationWithScanCheckpointCount());
        assertEquals(Integer.valueOf(1), location.getRtlStationCount());
        assertEquals(Boolean.TRUE, location.getPhysicalPlaceRequired());
    }

    @Test
    void topicWithoutAnyDeliveryFactsStaysExplicitlyUnknown() {
        ContentDeliveryMetadataVO empty = projection.build(topic(22L), true);

        assertNull(empty.getEvidence());
        assertNull(empty.getRevision());
        assertNull(empty.getRequiredCapabilities());
        assertEquals(ContentDeliveryMetadataVO.VISIBILITY_NOT_PROVIDED, empty.getOperatingRequirementsVisibility());
        assertEquals(Integer.valueOf(0), empty.getLocationRequirement().getStationCount());
        assertEquals(Boolean.FALSE, empty.getLocationRequirement().getPhysicalPlaceRequired());
        assertEquals(ContentDeliveryMetadataVO.LICENSE_UNKNOWN, empty.getLicense().getStatus());
        assertEquals("SALE_SNAPSHOT_MISSING", empty.getLicense().getUnavailableReason());
    }

    @Test
    void frozenSaleCanonicalSuppliesLicenseSummary() {
        ContentDeliveryMetadataVO.LicenseSummary license = projection.build(topic(23L), true).getLicense();

        assertEquals(ContentDeliveryMetadataVO.LICENSE_AUTHORITATIVE, license.getStatus());
        assertEquals("ASSET-9", license.getAssetRef());
        assertEquals("作者工作室", license.getOwner());
        assertEquals("城瘾平台", license.getLicensedEntity());
        assertEquals(java.util.Arrays.asList("WECHAT_MINI", "H5"), license.getChannels());
        assertEquals(Boolean.FALSE, license.getEditingRight());
    }

    @Test
    void snapshotWithoutRightsPackageIsUnknown() {
        ContentDeliveryMetadataVO.LicenseSummary license = projection.build(topic(24L), true).getLicense();

        assertEquals(ContentDeliveryMetadataVO.LICENSE_UNKNOWN, license.getStatus());
        assertEquals("CONTENT_RIGHTS_MISSING", license.getUnavailableReason());
        assertNull(license.getLicensedEntity());
    }
}
