package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapterTemplateMeta;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.CmsTopicTemplateMeta;
import com.chengyinhub.business.domain.TopicSaleSnapshot;
import com.chengyinhub.business.domain.vo.ContentDeliveryMetadataVO;
import com.chengyinhub.business.domain.vo.ContentManifestVO;
import com.chengyinhub.business.domain.vo.TopicContentRevisionView;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.CmsTopicTemplateMetaMapper;
import com.chengyinhub.business.mapper.TopicSaleSnapshotMapper;
import com.chengyinhub.business.service.ITopicContentRevisionService;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 创作者交付元数据投影(BE-13)。
 *
 * <p>只做投影,不新增权威:修订来自既有不可变修订(BE-06),证据等级来自
 * {@code cms_topic_template_meta},运营投入来自 {@code cms_topic_chapter_template_meta},
 * 许可来自已冻结的销售 canonical({@code topic_sale_snapshot.canonical_json})。
 * 任何一处没有权威事实,就如实给 {@code UNKNOWN} / null,不推算、不假设授权。
 *
 * <p>运营投入仍受原招募闸约束({@code viewerCanViewRecruitment});非合作方拿到的只是
 * {@code PARTNER_ONLY} 标记,商家成本数值不下发 —— 不为了「详情更完整」放宽既有可见性。
 */
@Service
public class TopicDeliveryMetadataProjection {

    private static final Logger LOG = LoggerFactory.getLogger(TopicDeliveryMetadataProjection.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String SOURCE_LOCALE = "zh-CN";

    @Autowired
    private CmsTopicTemplateMetaMapper metaMapper;
    @Autowired
    private CmsTopicNodeMapper nodeMapper;
    @Autowired(required = false)
    private TopicSaleSnapshotMapper saleSnapshotMapper;
    @Autowired(required = false)
    private ITopicContentRevisionService contentRevisionService;

    /**
     * @param viewerCanViewRecruitment 与原详情同一道招募闸;false 时运营投入数值不下发。
     */
    public ContentDeliveryMetadataVO build(CmsTopic topic, boolean viewerCanViewRecruitment) {
        if (topic == null || topic.getId() == null) return null;
        CmsTopicTemplateMeta meta = metaMapper == null ? null : metaMapper.selectByTopicId(topic.getId());
        List<CmsTopicChapterTemplateMeta> chapterMetas = metaMapper == null
                ? null : metaMapper.selectChapterMetaByTopicId(topic.getId());

        ContentDeliveryMetadataVO vo = new ContentDeliveryMetadataVO();
        vo.setSourceLocale(SOURCE_LOCALE);
        vo.setTranslationStatus(ContentDeliveryMetadataVO.TRANSLATION_SINGLE_LOCALE);
        vo.setTranslations(new ArrayList<String>());

        // 修订索引只读一次:下面 revision()/capabilities() 共用这一份视图,不给详情加重复查询。
        TopicContentRevisionView current = currentRevision(topic.getId());
        vo.setRevision(revision(current));
        vo.setEvidence(evidence(meta));
        List<ContentManifestVO.Requirement> capabilities = capabilities(current);
        vo.setRequiredCapabilities(capabilities);
        vo.setRuntimeSupport(runtimeSupport(capabilities));
        vo.setLocationRequirement(locationRequirement(topic));
        attachOperating(vo, chapterMetas, viewerCanViewRecruitment);
        vo.setLicense(license(topic.getId()));
        return vo;
    }

    /**
     * 当前修订的公共视图。索引不可用 ≠ 没有内容:失败时只让交付元数据留空,
     * 老内容继续按在线方式读取,不让货架详情 500。
     */
    private TopicContentRevisionView currentRevision(Long topicId) {
        if (contentRevisionService == null) return null;
        try {
            return contentRevisionService.currentPublished(topicId);
        } catch (RuntimeException unavailable) {
            LOG.warn("内容修订读取失败 topic={} failureType={}", topicId, unavailable.getClass().getSimpleName());
            return null;
        }
    }

    /** 修订视图只投影公共字段;私有 snapshot 不经过本投影。 */
    private ContentDeliveryMetadataVO.Revision revision(TopicContentRevisionView view) {
        if (view == null) return null;
        ContentDeliveryMetadataVO.Revision revision = new ContentDeliveryMetadataVO.Revision();
        revision.setContentRevisionId(view.getId());
        revision.setRevisionNo(view.getRevisionNo());
        revision.setSchemaVersion(view.getSchemaVersion());
        revision.setContentSha256(view.getContentSha256());
        revision.setOrigin(view.getOrigin());
        return revision;
    }

    /**
     * 端能力:优先用修订自己声明的 {@code requiredCapabilities};没有声明时才从该修订的
     * 冻结内容推导(复用 {@link ContentCapabilityRequirements})。都没有 = null(未知),
     * 不是空数组冒充「无要求」。
     */
    private List<ContentManifestVO.Requirement> capabilities(TopicContentRevisionView view) {
        if (view == null || contentRevisionService == null) return null;
        try {
            List<ContentManifestVO.Requirement> declared = declaredCapabilities(view.getPublicManifestJson());
            if (declared != null) return declared;
            String frozen = contentRevisionService.frozenSnapshot(view.getTopicId(), view.getId());
            if (StringUtils.isBlank(frozen)) return null;
            return ContentManifestProjection.capabilities(JSON.readTree(frozen));
        } catch (RuntimeException | java.io.IOException unavailable) {
            LOG.warn("端能力推导失败 topic={} failureType={}", view.getTopicId(), unavailable.getClass().getSimpleName());
            return null;
        }
    }

    private List<ContentManifestVO.Requirement> declaredCapabilities(String publicManifestJson) {
        if (StringUtils.isBlank(publicManifestJson)) return null;
        try {
            JsonNode rows = JSON.readTree(publicManifestJson).path("requiredCapabilities");
            if (!rows.isArray()) return null;
            List<ContentManifestVO.Requirement> result = new ArrayList<ContentManifestVO.Requirement>(rows.size());
            for (JsonNode row : rows) {
                String key = row.path("key").asText(null);
                if (StringUtils.isBlank(key)) continue;
                ContentManifestVO.Requirement item = new ContentManifestVO.Requirement();
                item.setKey(key);
                item.setMinVersion(row.has("minVersion") ? Integer.valueOf(row.path("minVersion").asInt(1)) : null);
                result.add(item);
            }
            return result;
        } catch (Exception unreadable) {
            return null;
        }
    }

    private ContentDeliveryMetadataVO.RuntimeSupport runtimeSupport(List<ContentManifestVO.Requirement> capabilities) {
        ContentDeliveryMetadataVO.RuntimeSupport support = new ContentDeliveryMetadataVO.RuntimeSupport();
        support.setCapabilities(capabilities);
        // 本基线没有平台运行时矩阵:如实 UNKNOWN,不能把「内容能结算」说成「某端已验证可运行」。
        support.setPlatformExecution(ContentDeliveryMetadataVO.PLATFORM_UNKNOWN);
        support.setPlatformExecutionReason("NO_PLATFORM_RUNTIME_MATRIX");
        return support;
    }

    private ContentDeliveryMetadataVO.EvidenceSummary evidence(CmsTopicTemplateMeta meta) {
        if (meta == null) return null;
        ContentDeliveryMetadataVO.EvidenceSummary evidence = new ContentDeliveryMetadataVO.EvidenceSummary();
        evidence.setTemplateStatus(meta.getTemplateStatus());
        evidence.setEvidenceLevel(meta.getEvidenceLevel());
        evidence.setIsOfficial(meta.getIsOfficial());
        evidence.setUpdatedAt(meta.getUpdateTime());
        return evidence;
    }

    /**
     * 地点需求:只数真实节点行。{@code physicalPlaceRequired} 只在确实有带地址/扫码点位的站点时为 true,
     * 其余为 false —— 不把纯线上内容说成要出门,也不隐藏确实要到店的事实。
     */
    private ContentDeliveryMetadataVO.LocationRequirement locationRequirement(CmsTopic topic) {
        ContentDeliveryMetadataVO.LocationRequirement location = new ContentDeliveryMetadataVO.LocationRequirement();
        location.setCityCode(topic.getCityCode());
        List<CmsTopicNode> nodes = null;
        if (nodeMapper != null) {
            CmsTopicNode probe = new CmsTopicNode();
            probe.setTopicId(topic.getId());
            nodes = nodeMapper.selectCmsTopicNodeList(probe);
        }
        int stationCount = 0, withAddress = 0, withScan = 0, rtl = 0;
        if (nodes != null) for (CmsTopicNode node : nodes) {
            if (node == null || node.getStatus() == null || node.getStatus() != 1L) continue;
            stationCount++;
            if (StringUtils.isNotBlank(node.getAddress())) withAddress++;
            if (StringUtils.isNotBlank(node.getCheckinCode())
                    || node.getMerchantMemberId() != null && node.getMerchantMemberId() > 0L) withScan++;
            if (StringUtils.isNotBlank(node.getLatitude()) && StringUtils.isNotBlank(node.getLongitude())) rtl++;
        }
        location.setStationCount(stationCount);
        location.setStationWithAddressCount(withAddress);
        location.setStationWithScanCheckpointCount(withScan);
        location.setRtlStationCount(rtl);
        location.setPhysicalPlaceRequired(withAddress > 0 || withScan > 0);
        return location;
    }

    /** 运营投入沿用原闸:合作伙伴看数值,其他人只看「此项仅对合作方开放」。 */
    private void attachOperating(ContentDeliveryMetadataVO vo, List<CmsTopicChapterTemplateMeta> chapterMetas,
            boolean viewerCanViewRecruitment) {
        boolean any = false;
        if (chapterMetas != null) for (CmsTopicChapterTemplateMeta meta : chapterMetas) {
            if (meta == null) continue;
            if (StringUtils.isNotBlank(meta.getEffortVenue()) || StringUtils.isNotBlank(meta.getEffortMaterials())
                    || StringUtils.isNotBlank(meta.getRoleNeed()) || meta.getEffortStaffMinutes() != null
                    || meta.getEffortPrepHours() != null || meta.getEffortMaxConcurrent() != null
                    || meta.getEffortNeedsBooking() != null || StringUtils.isNotBlank(meta.getEffortAvailableSlots())
                    || StringUtils.isNotBlank(meta.getEffortQualifications())) {
                any = true;
                break;
            }
        }
        if (!any) {
            vo.setOperatingRequirements(null);
            vo.setOperatingRequirementsVisibility(ContentDeliveryMetadataVO.VISIBILITY_NOT_PROVIDED);
            return;
        }
        if (!viewerCanViewRecruitment) {
            vo.setOperatingRequirements(null);
            vo.setOperatingRequirementsVisibility(ContentDeliveryMetadataVO.VISIBILITY_PARTNER_ONLY);
            return;
        }
        Set<String> venues = new LinkedHashSet<String>();
        Set<String> materials = new LinkedHashSet<String>();
        Set<String> roleNeeds = new LinkedHashSet<String>();
        Set<String> slots = new LinkedHashSet<String>();
        Set<String> qualifications = new LinkedHashSet<String>();
        Integer staffMinutesMax = null;
        Integer maxConcurrent = null;
        Boolean needsBooking = null;
        java.math.BigDecimal prepHoursMax = null;
        for (CmsTopicChapterTemplateMeta meta : chapterMetas) {
            if (meta == null) continue;
            add(venues, meta.getEffortVenue());
            add(materials, meta.getEffortMaterials());
            add(roleNeeds, meta.getRoleNeed());
            add(slots, meta.getEffortAvailableSlots());
            add(qualifications, meta.getEffortQualifications());
            if (meta.getEffortStaffMinutes() != null)
                staffMinutesMax = staffMinutesMax == null ? meta.getEffortStaffMinutes()
                        : Math.max(staffMinutesMax, meta.getEffortStaffMinutes());
            if (meta.getEffortMaxConcurrent() != null)
                maxConcurrent = maxConcurrent == null ? meta.getEffortMaxConcurrent()
                        : Math.max(maxConcurrent, meta.getEffortMaxConcurrent());
            if (meta.getEffortNeedsBooking() != null)
                needsBooking = Boolean.TRUE.equals(needsBooking) || Integer.valueOf(1).equals(meta.getEffortNeedsBooking());
            if (meta.getEffortPrepHours() != null)
                prepHoursMax = prepHoursMax == null ? meta.getEffortPrepHours() : prepHoursMax.max(meta.getEffortPrepHours());
        }
        ContentDeliveryMetadataVO.OperatingRequirements operating = new ContentDeliveryMetadataVO.OperatingRequirements();
        operating.setVenues(new ArrayList<String>(venues));
        operating.setMaterials(new ArrayList<String>(materials));
        operating.setRoleNeeds(new ArrayList<String>(roleNeeds));
        operating.setAvailableSlots(new ArrayList<String>(slots));
        operating.setQualifications(new ArrayList<String>(qualifications));
        operating.setStaffMinutesMax(staffMinutesMax);
        operating.setMaxConcurrent(maxConcurrent);
        operating.setNeedsBooking(needsBooking);
        operating.setPrepHoursMax(prepHoursMax);
        vo.setOperatingRequirements(operating);
        vo.setOperatingRequirementsVisibility(ContentDeliveryMetadataVO.VISIBILITY_INCLUDED);
    }

    /**
     * 许可摘要只读已冻结的销售 canonical。没有冻结快照 / 没有 contentRightsPackage /
     * 字段缺失,一律 UNKNOWN + 具体原因,绝不默认「已授权」。
     */
    private ContentDeliveryMetadataVO.LicenseSummary license(Long topicId) {
        ContentDeliveryMetadataVO.LicenseSummary summary = new ContentDeliveryMetadataVO.LicenseSummary();
        summary.setStatus(ContentDeliveryMetadataVO.LICENSE_UNKNOWN);
        if (saleSnapshotMapper == null) {
            summary.setUnavailableReason("SALE_SNAPSHOT_UNAVAILABLE");
            return summary;
        }
        TopicSaleSnapshot snapshot;
        try {
            snapshot = saleSnapshotMapper.selectSnapshotByTopicId(topicId);
        } catch (RuntimeException unavailable) {
            LOG.warn("销售快照读取失败 topic={} failureType={}", topicId, unavailable.getClass().getSimpleName());
            summary.setUnavailableReason("SALE_SNAPSHOT_UNAVAILABLE");
            return summary;
        }
        if (snapshot == null || StringUtils.isBlank(snapshot.getCanonicalJson())) {
            summary.setUnavailableReason("SALE_SNAPSHOT_MISSING");
            return summary;
        }
        JsonNode rights;
        try {
            rights = JSON.readTree(snapshot.getCanonicalJson()).path("contentRightsPackage");
        } catch (Exception unreadable) {
            summary.setUnavailableReason("SALE_SNAPSHOT_UNREADABLE");
            return summary;
        }
        if (rights == null || !rights.isObject() || rights.isEmpty()) {
            summary.setUnavailableReason("CONTENT_RIGHTS_MISSING");
            return summary;
        }
        summary.setStatus(ContentDeliveryMetadataVO.LICENSE_AUTHORITATIVE);
        summary.setUnavailableReason(null);
        summary.setAssetRef(text(rights, "assetRef"));
        summary.setOwner(text(rights, "owner"));
        summary.setLicensedEntity(text(rights, "licensedEntity"));
        summary.setValidFrom(text(rights, "validFrom"));
        summary.setValidTo(text(rights, "validTo"));
        summary.setEditingRight(rights.has("editingRight") && rights.get("editingRight").isBoolean()
                ? Boolean.valueOf(rights.get("editingRight").asBoolean()) : null);
        JsonNode channels = rights.get("channels");
        if (channels != null && channels.isArray()) {
            List<String> values = new ArrayList<String>();
            for (JsonNode channel : channels) if (channel.isTextual() && StringUtils.isNotBlank(channel.asText()))
                values.add(channel.asText().trim());
            summary.setChannels(values);
        }
        return summary;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && StringUtils.isNotBlank(value.asText())
                ? value.asText().trim() : null;
    }

    private static void add(Set<String> target, String value) {
        if (StringUtils.isNotBlank(value)) target.add(value.trim());
    }
}
