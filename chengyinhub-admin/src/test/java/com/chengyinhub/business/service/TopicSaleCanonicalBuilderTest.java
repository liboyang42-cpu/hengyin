package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapterTemplateMeta;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.CoopPerk;
import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.CmsTopicTemplateMetaMapper;
import com.chengyinhub.business.mapper.CoopPerkMapper;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;
import com.chengyinhub.business.service.support.CoopOrderLineupSource;
import com.chengyinhub.business.service.support.TopicSaleCanonicalBuilder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TopicSaleCanonicalBuilderTest {
    private static final Long TOPIC = 83L;
    private static final ObjectMapper JSON = new ObjectMapper();
    private CommercialTopicPolicy policy;
    private TopicSaleCanonicalBuilder builder;
    private Map<String, Object> live;
    private CmsTopicTemplateMetaMapper metaMapper;
    private IProductRoleService productRoleService;

    @BeforeEach
    void setUp() {
        policy = mock(CommercialTopicPolicy.class);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        metaMapper = mock(CmsTopicTemplateMetaMapper.class);
        CoopPerkMapper perkMapper = mock(CoopPerkMapper.class);
        CoopOrderLineupSource lineupSource = mock(CoopOrderLineupSource.class);
        productRoleService = mock(IProductRoleService.class);
        builder = new TopicSaleCanonicalBuilder(policy, nodeMapper, metaMapper, perkMapper, lineupSource,
                productRoleService);
        live = liveFacts();
        when(policy.evaluate(TOPIC)).thenReturn(CommercialTopicPolicy.Evaluation.ugc());
        when(policy.saleParameters(any(CmsTopic.class), any(CommercialTopicPolicy.Evaluation.class)))
                .thenAnswer(invocation -> live);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(nodes());
        when(metaMapper.selectChapterMetaByTopicId(TOPIC)).thenReturn(metas());
        when(perkMapper.selectById(any(Long.class))).thenAnswer(invocation -> perk(invocation.getArgument(0)));
        when(lineupSource.readEffectiveLineup(TOPIC)).thenReturn(new CoopOrderLineupSource.ReadResult(
                Collections.singletonList(new CoopOrderLineupSource.LineupEntry(
                        "CLUB", 7L, "ORGANIZER", "TOPIC", TOPIC, "COOP_ORDER", 9L, false)),
                Collections.emptyList()));
        ProductRole organizer = new ProductRole();
        organizer.setId(91L);
        organizer.setTopicId(TOPIC);
        organizer.setSlot(com.chengyinhub.common.constant.ProductSlot.ORGANIZER);
        organizer.setEntityType(com.chengyinhub.common.constant.RoleEntityType.PLATFORM);
        organizer.setDeclaredBy(9001L);
        organizer.setCreatedAt(new Date(123456L));
        organizer.setStatus(1);
        when(productRoleService.activeOrganizerSlot(TOPIC)).thenReturn(organizer);
    }

    @Test
    void rebuildUsesLiveAuthorityAndProducesCompleteCanonical15() {
        TopicSaleCanonicalV1 rebuilt = builder.rebuild(topic(), TopicSaleCanonicalTestFixture.canonical());

        rebuilt.validateComplete();
        assertEquals(4, rebuilt.getStoreCount().intValue());
        assertEquals(1, rebuilt.maxTicketPerAccount());
        assertEquals("20", rebuilt.getMicroExperienceCatalog().path("perks").get(0)
                .path("retailValue").asText(), "真实 DECIMAL 整十值必须冻结为无科学计数法的 plain string");
        assertEquals("PLATFORM", rebuilt.getClubWorkPackage().path("organizerActor").path("actorType").asText());
        assertEquals(91L, rebuilt.getClubWorkPackage().path("organizerActor").path("roleId").asLong());
        assertEquals(9001L, rebuilt.getClubWorkPackage().path("organizerActor").path("declaredBy").asLong());
        assertEquals(123456L, rebuilt.getClubWorkPackage().path("organizerActor").path("declaredAt").asLong());
    }

    @Test
    void missingNestedLiveTicketFieldFailsWithItsCanonicalCode() {
        @SuppressWarnings("unchecked")
        Map<String, Object> ticket = ((List<Map<String, Object>>) live.get("tickets")).get(0);
        ticket.remove("signupDeadline");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> builder.rebuild(topic(), TopicSaleCanonicalTestFixture.canonical()));

        assertEquals("SALE_SNAPSHOT_MISSING_SESSION_WINDOWS", error.getMessage());
    }

    /**
     * W3f 补测:存量 meta 行只有服务容量三数、工作包六项为 NULL(或任缺一项)时,builder 写出的 JSON null
     * 必须被 validateMerchantWork 判缺 —— 不能因为「行存在」就算有工作包。
     */
    @Test
    void storedMetaRowWithNullWorkPackageFieldsIsRejected() {
        List<java.util.function.Consumer<CmsTopicChapterTemplateMeta>> nullers = java.util.Arrays.asList(
                m -> m.setRoleNeed(null), m -> m.setEffortStaffMinutes(null), m -> m.setEffortVenue(null),
                m -> m.setEffortMaterials(null), m -> m.setEffortAvailableSlots(null),
                m -> m.setEffortQualifications(null));
        for (int i = 0; i < nullers.size(); i++) {
            List<CmsTopicChapterTemplateMeta> rows = metas();
            nullers.get(i).accept(rows.get(0));
            when(metaMapper.selectChapterMetaByTopicId(TOPIC)).thenReturn(rows);
            // rebuild 内部即做完整校验;即便将来挪出去,validateComplete 也必须拒。
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> builder.rebuild(topic(), TopicSaleCanonicalTestFixture.canonical()).validateComplete(),
                    "nuller#" + i);
            assertEquals("SALE_SNAPSHOT_MISSING_MERCHANT_WORK_PACKAGE", error.getMessage(), "nuller#" + i);
        }
    }

    @Test
    void ordinaryFreeUsesActualContentPublisherWhenThereIsNoExternalOrganizer() throws Exception {
        CmsTopic topic = topic();
        topic.setProductType(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE);
        topic.setMemberId(501L);
        ProductRole content = new ProductRole();
        content.setId(92L);
        content.setEntityType("CLUB");
        content.setEntityId(7L);
        content.setCreatedAt(new Date(123456L));
        when(productRoleService.activeOrganizerSlot(TOPIC)).thenReturn(null);
        when(productRoleService.activeContentSlot(TOPIC)).thenReturn(content);
        ObjectNode value = TopicSaleCanonicalTestFixture.json();
        ((ObjectNode) value.path("completionPolicy")).put("evidenceRule", "ALL_REQUIRED_CHAPTERS");
        TopicSaleCanonicalV1 ordinary = JSON.treeToValue(value, TopicSaleCanonicalV1.class);

        TopicSaleCanonicalV1 rebuilt = builder.rebuild(topic, ordinary);

        assertEquals("CLUB", rebuilt.getClubWorkPackage().path("organizerActor").path("actorType").asText());
        assertEquals(501L, rebuilt.getClubWorkPackage().path("organizerActor").path("declaredBy").asLong());
        when(productRoleService.activeContentSlot(TOPIC)).thenReturn(null);
        assertEquals("SALE_SNAPSHOT_MISSING_ORGANIZER_ACTOR", assertThrows(IllegalStateException.class,
                () -> builder.rebuild(topic, ordinary)).getMessage());
    }

    private static CmsTopic topic() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        return topic;
    }

    private static Map<String, Object> liveFacts() {
        ObjectNode root = TopicSaleCanonicalTestFixture.json();
        Map<String, Object> facts = new LinkedHashMap<String, Object>();
        facts.put("tickets", JSON.convertValue(root.path("sessionWindows").path("tickets"),
                new TypeReference<List<Map<String, Object>>>() {}));
        facts.put("chapters", JSON.convertValue(root.path("microExperienceCatalog").path("chapters"),
                new TypeReference<List<Map<String, Object>>>() {}));
        facts.put("offers", JSON.convertValue(root.path("microExperienceCatalog").path("offers"),
                new TypeReference<List<Map<String, Object>>>() {}));
        facts.put("startDate", 1000L);
        facts.put("endDate", 2000L);
        facts.put("saleEndAt", 900L);
        return facts;
    }

    private static List<CmsTopicNode> nodes() {
        List<CmsTopicNode> values = new ArrayList<CmsTopicNode>();
        for (int i = 0; i < 4; i++) {
            CmsTopicNode node = new CmsTopicNode();
            node.setId(500L + i);
            node.setTopicId(TOPIC);
            node.setChapterId(100L + i);
            node.setMerchantMemberId(200L + i);
            node.setNodeAuditStatus(1);
            node.setAddress("门店" + i);
            node.setLongitude("121." + i);
            node.setLatitude("31." + i);
            values.add(node);
        }
        return values;
    }

    private static List<CmsTopicChapterTemplateMeta> metas() {
        List<CmsTopicChapterTemplateMeta> values = new ArrayList<CmsTopicChapterTemplateMeta>();
        for (int i = 0; i < 4; i++) {
            CmsTopicChapterTemplateMeta meta = new CmsTopicChapterTemplateMeta();
            meta.setChapterId(100L + i);
            meta.setRoleNeed("店员");
            meta.setEffortStaffMinutes(30);
            meta.setEffortVenue("柜台");
            meta.setEffortMaterials("礼包");
            meta.setEffortAvailableSlots("10:00-18:00");
            meta.setEffortQualifications("在岗");
            values.add(meta);
        }
        return values;
    }

    private static CoopPerk perk(Long id) {
        CoopPerk perk = new CoopPerk();
        perk.setId(id);
        perk.setName("权益" + id);
        perk.setPerkType(0);
        perk.setRetailValue(new BigDecimal("20.00"));
        perk.setRedeemRule("到店核销");
        perk.setDelFlag(0);
        return perk;
    }
}
