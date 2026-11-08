package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapterTemplateMeta;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.CmsTopicTemplateMeta;
import com.chengyinhub.business.domain.TopicSaleSnapshot;
import com.chengyinhub.business.domain.vo.ContentDeliveryMetadataVO;
import com.chengyinhub.business.domain.vo.TopicContentRevisionView;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.CmsTopicTemplateMetaMapper;
import com.chengyinhub.business.mapper.TopicSaleSnapshotMapper;
import com.chengyinhub.business.service.ITopicContentRevisionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BE-13 交付元数据投影:只转述既有权威,缺事实就是 UNKNOWN/null。
 */
class TopicDeliveryMetadataProjectionTest {

    private TopicDeliveryMetadataProjection projection;
    private CmsTopicTemplateMetaMapper metaMapper;
    private CmsTopicNodeMapper nodeMapper;
    private TopicSaleSnapshotMapper saleSnapshotMapper;
    private ITopicContentRevisionService revisions;

    @BeforeEach
    void setUp() {
        projection = new TopicDeliveryMetadataProjection();
        metaMapper = mock(CmsTopicTemplateMetaMapper.class);
        nodeMapper = mock(CmsTopicNodeMapper.class);
        saleSnapshotMapper = mock(TopicSaleSnapshotMapper.class);
        revisions = mock(ITopicContentRevisionService.class);
        ReflectionTestUtils.setField(projection, "metaMapper", metaMapper);
        ReflectionTestUtils.setField(projection, "nodeMapper", nodeMapper);
        ReflectionTestUtils.setField(projection, "saleSnapshotMapper", saleSnapshotMapper);
        ReflectionTestUtils.setField(projection, "contentRevisionService", revisions);
    }

    private CmsTopic topic() {
        CmsTopic topic = new CmsTopic();
        topic.setId(21L);
        topic.setCityCode("NL-UTR");
        return topic;
    }

    private CmsTopicTemplateMeta meta() {
        CmsTopicTemplateMeta meta = new CmsTopicTemplateMeta();
        meta.setTopicId(21L);
        meta.setTemplateStatus("PILOT");
        meta.setEvidenceLevel("ESTIMATED");
        meta.setIsOfficial(0);
        meta.setUpdateTime(new java.util.Date(1_700_000_000_000L));
        return meta;
    }

    private CmsTopicChapterTemplateMeta chapterMeta() {
        CmsTopicChapterTemplateMeta meta = new CmsTopicChapterTemplateMeta();
        meta.setChapterId(501L);
        meta.setTopicId(21L);
        meta.setRoleNeed("1名店员");
        meta.setEffortVenue("老城咖啡馆");
        meta.setEffortMaterials("印章 2 枚");
        meta.setEffortStaffMinutes(30);
        meta.setEffortPrepHours(new BigDecimal("2.5"));
        meta.setEffortMaxConcurrent(6);
        meta.setEffortNeedsBooking(1);
        meta.setEffortAvailableSlots("周三至周日 10:00-18:00");
        meta.setEffortQualifications("需能开具发票");
        return meta;
    }

    private CmsTopicNode node(long id, String address, String lat, String lng, String code, Long merchant, Long status) {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(id);
        node.setTopicId(21L);
        node.setAddress(address);
        node.setLatitude(lat);
        node.setLongitude(lng);
        node.setCheckinCode(code);
        node.setMerchantMemberId(merchant);
        node.setStatus(status);
        return node;
    }

    private TopicSaleSnapshot saleSnapshot(String rights) {
        TopicSaleSnapshot snapshot = new TopicSaleSnapshot();
        snapshot.setTopicId(21L);
        snapshot.setSchemaVersion("CANONICAL_15_V1");
        snapshot.setCanonicalJson("{\"contentRightsPackage\":" + rights + "}");
        return snapshot;
    }

    @Test
    void missingAuthoritiesProduceExplicitUnknownsRatherThanInventedFacts() {
        when(metaMapper.selectByTopicId(21L)).thenReturn(null);
        when(metaMapper.selectChapterMetaByTopicId(21L)).thenReturn(null);
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(Collections.emptyList());
        when(revisions.currentPublished(21L)).thenReturn(null);
        when(saleSnapshotMapper.selectSnapshotByTopicId(21L)).thenReturn(null);

        ContentDeliveryMetadataVO delivery = projection.build(topic(), true);

        assertNull(delivery.getRevision());
        assertNull(delivery.getRequiredCapabilities());
        assertNull(delivery.getEvidence());
        assertEquals(ContentDeliveryMetadataVO.LICENSE_UNKNOWN, delivery.getLicense().getStatus());
        assertEquals("SALE_SNAPSHOT_MISSING", delivery.getLicense().getUnavailableReason());
        assertNull(delivery.getLicense().getOwner());
        assertNull(delivery.getLicense().getLicensedEntity());
        assertEquals(ContentDeliveryMetadataVO.VISIBILITY_NOT_PROVIDED, delivery.getOperatingRequirementsVisibility());
        assertNull(delivery.getOperatingRequirements());
        // 语言:真实源语言 + 就一种,不假装多语。
        assertEquals("zh-CN", delivery.getSourceLocale());
        assertEquals(ContentDeliveryMetadataVO.TRANSLATION_SINGLE_LOCALE, delivery.getTranslationStatus());
        assertTrue(delivery.getTranslations().isEmpty());
        // 平台运行支持没有权威矩阵 → UNKNOWN,不是「全端可玩」。
        assertEquals(ContentDeliveryMetadataVO.PLATFORM_UNKNOWN, delivery.getRuntimeSupport().getPlatformExecution());
    }

    @Test
    void evidenceNeverUpgradesEstimatedToVerified() {
        when(metaMapper.selectByTopicId(21L)).thenReturn(meta());
        when(metaMapper.selectChapterMetaByTopicId(21L)).thenReturn(null);
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(Collections.emptyList());
        when(saleSnapshotMapper.selectSnapshotByTopicId(21L)).thenReturn(null);

        ContentDeliveryMetadataVO delivery = projection.build(topic(), false);

        assertEquals("PILOT", delivery.getEvidence().getTemplateStatus());
        assertEquals("ESTIMATED", delivery.getEvidence().getEvidenceLevel());
        assertEquals(Integer.valueOf(0), delivery.getEvidence().getIsOfficial());
    }

    @Test
    void operatingRequirementsFollowTheExistingPartnerGate() {
        when(metaMapper.selectByTopicId(21L)).thenReturn(meta());
        when(metaMapper.selectChapterMetaByTopicId(21L))
                .thenReturn(Collections.singletonList(chapterMeta()));
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(Collections.emptyList());
        when(saleSnapshotMapper.selectSnapshotByTopicId(21L)).thenReturn(null);

        ContentDeliveryMetadataVO anonymous = projection.build(topic(), false);
        assertEquals(ContentDeliveryMetadataVO.VISIBILITY_PARTNER_ONLY, anonymous.getOperatingRequirementsVisibility());
        assertNull(anonymous.getOperatingRequirements(), "非合作方不能拿到商家成本数值");

        ContentDeliveryMetadataVO partner = projection.build(topic(), true);
        assertEquals(ContentDeliveryMetadataVO.VISIBILITY_INCLUDED, partner.getOperatingRequirementsVisibility());
        ContentDeliveryMetadataVO.OperatingRequirements operating = partner.getOperatingRequirements();
        assertEquals(Collections.singletonList("老城咖啡馆"), operating.getVenues());
        assertEquals(Collections.singletonList("印章 2 枚"), operating.getMaterials());
        assertEquals(Collections.singletonList("1名店员"), operating.getRoleNeeds());
        assertEquals(Integer.valueOf(30), operating.getStaffMinutesMax());
        assertEquals(Integer.valueOf(6), operating.getMaxConcurrent());
        assertEquals(Boolean.TRUE, operating.getNeedsBooking());
        assertEquals(0, new BigDecimal("2.5").compareTo(operating.getPrepHoursMax()));
    }

    @Test
    void locationSummaryCountsOnlyRealEnabledStationsWithoutCoordinates() {
        when(metaMapper.selectByTopicId(21L)).thenReturn(null);
        when(metaMapper.selectChapterMetaByTopicId(21L)).thenReturn(null);
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(java.util.Arrays.asList(
                node(11L, "老城广场 1 号", "52.09", "5.12", null, null, 1L),
                node(12L, null, null, null, "CODE-1", null, 1L),
                node(13L, "旧邮局", null, null, null, 700L, 1L),
                node(14L, "已下架", "52.1", "5.2", "CODE-2", null, 0L)));
        when(saleSnapshotMapper.selectSnapshotByTopicId(21L)).thenReturn(null);

        ContentDeliveryMetadataVO.LocationRequirement location = projection.build(topic(), true).getLocationRequirement();

        assertEquals(Integer.valueOf(3), location.getStationCount(), "停用节点不计入");
        assertEquals(Integer.valueOf(2), location.getStationWithAddressCount());
        assertEquals(Integer.valueOf(2), location.getStationWithScanCheckpointCount());
        assertEquals(Integer.valueOf(1), location.getRtlStationCount());
        assertEquals("NL-UTR", location.getCityCode());
        assertEquals(Boolean.TRUE, location.getPhysicalPlaceRequired());
        // 摘要类型里没有坐标 getter:地点需求不下发经纬度。
        for (java.lang.reflect.Method method : ContentDeliveryMetadataVO.LocationRequirement.class.getMethods()) {
            assertFalse(method.getName().toLowerCase().contains("longitude"));
            assertFalse(method.getName().toLowerCase().contains("latitude"));
        }
    }

    @Test
    void licenseSummaryOnlyComesFromFrozenSaleCanonical() {
        when(metaMapper.selectByTopicId(21L)).thenReturn(null);
        when(metaMapper.selectChapterMetaByTopicId(21L)).thenReturn(null);
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(Collections.emptyList());
        when(saleSnapshotMapper.selectSnapshotByTopicId(21L)).thenReturn(saleSnapshot(
                "{\"assetRef\":\"ASSET-9\",\"owner\":\"作者工作室\",\"licensedEntity\":\"城瘾平台\","
                        + "\"channels\":[\"WECHAT_MINI\",\"H5\"],\"validFrom\":\"2026-01-01\","
                        + "\"validTo\":\"2027-01-01\",\"editingRight\":false,"
                        + "\"humanPortraitConsent\":true,\"petPortraitConsent\":true}"));

        ContentDeliveryMetadataVO.LicenseSummary license = projection.build(topic(), true).getLicense();

        assertEquals(ContentDeliveryMetadataVO.LICENSE_AUTHORITATIVE, license.getStatus());
        assertNull(license.getUnavailableReason());
        assertEquals("ASSET-9", license.getAssetRef());
        assertEquals("作者工作室", license.getOwner());
        assertEquals("城瘾平台", license.getLicensedEntity());
        assertEquals(java.util.Arrays.asList("WECHAT_MINI", "H5"), license.getChannels());
        assertEquals("2026-01-01", license.getValidFrom());
        assertEquals("2027-01-01", license.getValidTo());
        assertEquals(Boolean.FALSE, license.getEditingRight());
    }

    @Test
    void missingRightsPackageIsUnknownNotImplicitlyLicensed() {
        when(metaMapper.selectByTopicId(21L)).thenReturn(null);
        when(metaMapper.selectChapterMetaByTopicId(21L)).thenReturn(null);
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(Collections.emptyList());
        when(saleSnapshotMapper.selectSnapshotByTopicId(21L)).thenReturn(saleSnapshot("{}"));

        ContentDeliveryMetadataVO.LicenseSummary license = projection.build(topic(), true).getLicense();

        assertEquals(ContentDeliveryMetadataVO.LICENSE_UNKNOWN, license.getStatus());
        assertEquals("CONTENT_RIGHTS_MISSING", license.getUnavailableReason());
        assertNull(license.getOwner());
        assertNull(license.getLicensedEntity());
        assertNull(license.getChannels());
    }

    @Test
    void revisionAndCapabilitiesComeFromIndexedRevisionOnly() {
        when(metaMapper.selectByTopicId(21L)).thenReturn(meta());
        when(metaMapper.selectChapterMetaByTopicId(21L)).thenReturn(null);
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(Collections.emptyList());
        when(saleSnapshotMapper.selectSnapshotByTopicId(21L)).thenReturn(null);
        TopicContentRevisionView view = new TopicContentRevisionView();
        view.setId(900L);
        view.setTopicId(21L);
        view.setRevisionNo(4L);
        view.setSchemaVersion(1);
        view.setContentSha256("sha-4");
        view.setOrigin("PUBLISH");
        view.setPublicManifestJson("{\"manifestVersion\":1,\"requiredCapabilities\":[{\"key\":\"media.audio\",\"minVersion\":2}]}");
        when(revisions.currentPublished(21L)).thenReturn(view);

        ContentDeliveryMetadataVO delivery = projection.build(topic(), true);

        assertEquals(Long.valueOf(900L), delivery.getRevision().getContentRevisionId());
        assertEquals(Long.valueOf(4L), delivery.getRevision().getRevisionNo());
        assertEquals("sha-4", delivery.getRevision().getContentSha256());
        assertEquals(1, delivery.getRequiredCapabilities().size());
        assertEquals("media.audio", delivery.getRequiredCapabilities().get(0).getKey());
        assertEquals(Integer.valueOf(2), delivery.getRequiredCapabilities().get(0).getMinVersion());
        assertEquals("media.audio", delivery.getRuntimeSupport().getCapabilities().get(0).getKey());
        // 只有声明了的能力,不额外补一条;也不读私有 snapshot 造能力。
        verify(revisions, never()).frozenSnapshot(anyLong(), anyLong());
    }

    @Test
    void revisionIndexFailureDoesNotBreakShelfDetail() {
        when(metaMapper.selectByTopicId(21L)).thenReturn(meta());
        when(metaMapper.selectChapterMetaByTopicId(21L)).thenReturn(null);
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(Collections.emptyList());
        when(saleSnapshotMapper.selectSnapshotByTopicId(21L)).thenReturn(null);
        when(revisions.currentPublished(21L)).thenThrow(new IllegalStateException("db down"));

        ContentDeliveryMetadataVO delivery = projection.build(topic(), true);

        assertNull(delivery.getRevision());
        assertNull(delivery.getRequiredCapabilities());
        assertEquals("PILOT", delivery.getEvidence().getTemplateStatus());
    }

    @Test
    void soldOutTopicWithoutNodesStillProjectsHonestDelivery() {
        when(metaMapper.selectByTopicId(21L)).thenReturn(null);
        when(metaMapper.selectChapterMetaByTopicId(21L)).thenReturn(null);
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(null);
        when(saleSnapshotMapper.selectSnapshotByTopicId(21L)).thenReturn(null);

        ContentDeliveryMetadataVO delivery = projection.build(topic(), true);

        assertEquals(Integer.valueOf(0), delivery.getLocationRequirement().getStationCount());
        assertEquals(Boolean.FALSE, delivery.getLocationRequirement().getPhysicalPlaceRequired());
    }
}
