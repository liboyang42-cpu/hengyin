package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.LegacyCmsProvenanceMapper;
import com.chengyinhub.business.service.support.LegacyCmsProvenanceGuard;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.domain.TopicEditionReopenRequest;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ProductRoleMapper;
import com.chengyinhub.business.mapper.TopicEditionReopenMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.common.constant.ProductSlot;
import com.chengyinhub.common.constant.RoleEntityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 合同一：再开一期只能复制白名单结构，旧期商业事实绝不能跨期复活。 */
class TopicEditionReopenServiceImplTest {

    private static final Long SOURCE = 100L;
    private static final Long NEW_TOPIC = 200L;
    private static final Long SOURCE_CHAPTER = 101L;
    private static final Long NEW_CHAPTER = 201L;
    private static final Long SOURCE_NODE = 102L;
    private static final Long NEW_NODE = 202L;
    private static final Long PLATFORM_MEMBER = 900L;

    private TopicEditionReopenMapper reopenMapper;
    private ProductRoleMapper productRoleMapper;
    private CmsMemberTemplateMapper templateMapper;
    private ClubMapper clubMapper;
    private UmsMemberMapper memberMapper;
    private TopicEditionReopenServiceImpl service;

    @BeforeEach
    void setUp() {
        reopenMapper = mock(TopicEditionReopenMapper.class);
        productRoleMapper = mock(ProductRoleMapper.class);
        templateMapper = mock(CmsMemberTemplateMapper.class);
        clubMapper = mock(ClubMapper.class);
        memberMapper = mock(UmsMemberMapper.class);
        service = new TopicEditionReopenServiceImpl(reopenMapper, productRoleMapper, templateMapper,
                clubMapper, memberMapper, mock(com.chengyinhub.business.mapper.NpcProfileMapper.class));
        ReflectionTestUtils.setField(service, "platformMemberId", PLATFORM_MEMBER);
        LegacyCmsProvenanceMapper provenance = mock(LegacyCmsProvenanceMapper.class);
        when(provenance.selectLegacyBlockState(701L)).thenReturn(0);
        ReflectionTestUtils.setField(service, "legacyCmsProvenanceGuard", new LegacyCmsProvenanceGuard(provenance));
        com.chengyinhub.business.mapper.MerchantAuthoringCopyMapper authoringCopies =
                mock(com.chengyinhub.business.mapper.MerchantAuthoringCopyMapper.class);
        when(authoringCopies.selectCopyBlockState(701L)).thenReturn(0);
        ReflectionTestUtils.setField(service, "merchantAuthoringCopyGuard",
                new com.chengyinhub.business.service.support.MerchantAuthoringCopyGuard(authoringCopies));

        when(reopenMapper.insertReopenRequest(any(TopicEditionReopenRequest.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, TopicEditionReopenRequest.class).setId(801L);
            return 1;
        });
        when(reopenMapper.selectSourceTopicForUpdate(SOURCE)).thenReturn(sourceTopic());
        when(reopenMapper.selectSourceContentForUpdate(SOURCE)).thenReturn(content(RoleEntityType.PLATFORM, null));
        when(reopenMapper.selectSourceChapters(SOURCE)).thenReturn(Collections.singletonList(sourceChapter("PERK")));
        when(reopenMapper.selectSourceNodes(SOURCE)).thenReturn(Collections.singletonList(sourceNode()));
        when(templateMapper.selectCmsMemberTemplateById(701L)).thenReturn(usableTemplate(PLATFORM_MEMBER));
        when(productRoleMapper.insertProductRole(any(ProductRole.class))).thenReturn(1);
        when(reopenMapper.insertReopenedTopic(any(CmsTopic.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, CmsTopic.class).setId(NEW_TOPIC);
            return 1;
        });
        when(reopenMapper.insertReopenedChapter(any(CmsTopicChapter.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, CmsTopicChapter.class).setId(NEW_CHAPTER);
            return 1;
        });
        when(reopenMapper.insertReopenedNode(any(CmsTopicNode.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, CmsTopicNode.class).setId(NEW_NODE);
            return 1;
        });
        when(reopenMapper.updateReopenedChapterBlocks(any(Long.class), any(String.class))).thenReturn(1);
        when(reopenMapper.completeReopenRequest(any(Long.class), any(Long.class))).thenReturn(1);
    }

    @Test
    void sameKeyRetry_returnsSameEditionWithoutDuplicatingChapterOrNode() {
        when(reopenMapper.insertReopenRequest(any(TopicEditionReopenRequest.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, TopicEditionReopenRequest.class).setId(801L);
            return 1;
        }).thenReturn(0);
        TopicEditionReopenRequest existing = new TopicEditionReopenRequest();
        existing.setNewTopicId(NEW_TOPIC);
        when(reopenMapper.selectReopenRequest(SOURCE, "edition-2026-08-11")).thenReturn(existing);

        assertEquals(NEW_TOPIC, service.reopenNextEdition(SOURCE, "edition-2026-08-11"));
        assertEquals(NEW_TOPIC, service.reopenNextEdition(SOURCE, "edition-2026-08-11"));

        verify(reopenMapper, times(1)).insertReopenedTopic(any(CmsTopic.class));
        verify(reopenMapper, times(1)).insertReopenedChapter(any(CmsTopicChapter.class));
        verify(reopenMapper, times(1)).insertReopenedNode(any(CmsTopicNode.class));
        verify(reopenMapper, times(1)).completeReopenRequest(any(Long.class), eq(NEW_TOPIC));
    }

    @Test
    void differentKeys_createSeparateDraftsAndCopyOnlyWhitelistFields() {
        AtomicLong topicIds = new AtomicLong(NEW_TOPIC);
        AtomicLong chapterIds = new AtomicLong(NEW_CHAPTER);
        AtomicLong nodeIds = new AtomicLong(NEW_NODE);
        doAnswer(invocation -> {
            CmsTopic topic = invocation.getArgument(0, CmsTopic.class);
            topic.setId(topicIds.getAndIncrement());
            return 1;
        }).when(reopenMapper).insertReopenedTopic(any(CmsTopic.class));
        doAnswer(invocation -> {
            CmsTopicChapter chapter = invocation.getArgument(0, CmsTopicChapter.class);
            chapter.setId(chapterIds.getAndIncrement());
            return 1;
        }).when(reopenMapper).insertReopenedChapter(any(CmsTopicChapter.class));
        doAnswer(invocation -> {
            CmsTopicNode node = invocation.getArgument(0, CmsTopicNode.class);
            node.setId(nodeIds.getAndIncrement());
            return 1;
        }).when(reopenMapper).insertReopenedNode(any(CmsTopicNode.class));

        assertEquals(NEW_TOPIC, service.reopenNextEdition(SOURCE, "edition-a"));
        assertEquals(NEW_TOPIC + 1, service.reopenNextEdition(SOURCE, "edition-b"));

        ArgumentCaptor<CmsTopic> topics = ArgumentCaptor.forClass(CmsTopic.class);
        verify(reopenMapper, times(2)).insertReopenedTopic(topics.capture());
        CmsTopic copy = topics.getAllValues().get(0);
        assertEquals(SOURCE, copy.getSourceTopicId(), "追溯只能写 source_topic_id，不能复用历史 topic_id");
        assertNull(copy.getTopicId(), "topic_id 不是期次追溯字段，必须保持空");
        assertEquals(PLATFORM_MEMBER, copy.getMemberId(), "PLATFORM 收款主体必须由当前平台配置重新声明");
        assertEquals(1L, copy.getTopicType(), "PLATFORM 新期必须是平台主题");
        assertNull(copy.getStartDate(), "履约开始日期不是复制白名单，必须重新配置");
        assertNull(copy.getEndDate(), "履约结束日期不是复制白名单，必须重新配置");
        assertNull(copy.getSaleEndAt(), "售止不是复制白名单，必须重新配置");
        assertNull(copy.getSelfPlayPrice(), "自玩票价不能跨期复制");
        assertNull(copy.getSelfPlayQuota(), "自玩库存不能跨期复制");
        assertNull(copy.getFeePlatformRate(), "定价费率快照不能跨期复制");
        assertNull(copy.getCompleteRewardCouponId(), "完成奖励券不得复制");
        assertEquals(TopicLifecycleState.DRAFT.getCode(), copy.getLifecycle(), "新期必须从 DRAFT 重新走状态机");
        assertEquals(0, copy.getStatus(), "发布状态必须初始化");

        ArgumentCaptor<CmsTopicNode> nodes = ArgumentCaptor.forClass(CmsTopicNode.class);
        verify(reopenMapper, times(2)).insertReopenedNode(nodes.capture());
        CmsTopicNode copiedNode = nodes.getAllValues().get(0);
        assertEquals(NEW_TOPIC, copiedNode.getTopicId());
        assertEquals(NEW_CHAPTER, copiedNode.getChapterId());
        assertFalse(SOURCE_NODE.equals(copiedNode.getId()), "节点必须重新生成 id");
        assertFalse("source-checkin".equals(copiedNode.getCheckinCode()), "核销码必须重新生成");
        assertNull(copiedNode.getMerchantMemberId(), "商家承接不能跨期复制");
        assertNull(copiedNode.getNodeAuditStatus(), "节点审核快照不能跨期复制");
        assertNull(copiedNode.getNodeAuditReason(), "节点审核快照不能跨期复制");
        assertNull(copiedNode.getNodeAuditMemberId(), "节点审核快照不能跨期复制");
        assertNull(copiedNode.getNodeAuditTime(), "节点审核快照不能跨期复制");

        ArgumentCaptor<String> blocks = ArgumentCaptor.forClass(String.class);
        verify(reopenMapper, times(2)).updateReopenedChapterBlocks(any(Long.class), blocks.capture());
        assertTrue(blocks.getAllValues().get(0).contains("202"), "blocks_json 必须指向副本节点 id");
        assertFalse(blocks.getAllValues().get(0).contains("102"), "blocks_json 不得保留来源节点 id");
    }

    @Test
    void templateWithoutNewContentSubjectUseRight_rollsBackBeforeCopyWrites() {
        when(templateMapper.selectCmsMemberTemplateById(701L)).thenReturn(usableTemplate(901L));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.reopenNextEdition(SOURCE, "template-denied"));

        assertTrue(error.getMessage().contains(TopicEditionReopenServiceImpl.TEMPLATE_USE_RIGHTS_GATE),
                "必须点名 template 使用权闸，而不是只抛运行时异常");
        verify(reopenMapper, never()).insertReopenedTopic(any(CmsTopic.class));
        verify(reopenMapper, never()).insertReopenedChapter(any(CmsTopicChapter.class));
        verify(reopenMapper, never()).insertReopenedNode(any(CmsTopicNode.class));
        verify(productRoleMapper, never()).insertProductRole(any(ProductRole.class));
    }

    @Test
    void revshareWithNonPlatformContent_requiresTermsReconfigurationAndWritesNothing() {
        when(reopenMapper.selectSourceContentForUpdate(SOURCE)).thenReturn(content(RoleEntityType.MEMBER, 88L));
        when(reopenMapper.selectSourceChapters(SOURCE)).thenReturn(Collections.singletonList(sourceChapter("REVSHARE")));
        when(memberMapper.selectUmsMemberById(88L)).thenReturn(activeMember(88L));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.reopenNextEdition(SOURCE, "revshare-denied"));

        assertTrue(error.getMessage().contains(TopicEditionReopenServiceImpl.TERMS_RECONFIGURATION_GATE),
                "REVSHARE 非 PLATFORM 必须返回专用条款重配闸");
        verify(reopenMapper, never()).insertReopenedTopic(any(CmsTopic.class));
        verify(reopenMapper, never()).insertReopenedChapter(any(CmsTopicChapter.class));
        verify(reopenMapper, never()).insertReopenedNode(any(CmsTopicNode.class));
        verify(productRoleMapper, never()).insertProductRole(any(ProductRole.class));
    }

    @Test
    void sourceBeforeSelling_isRejectedAtLifecycleGate() {
        CmsTopic source = sourceTopic();
        source.setLifecycle(TopicLifecycleState.PRICING.getCode());
        when(reopenMapper.selectSourceTopicForUpdate(SOURCE)).thenReturn(source);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.reopenNextEdition(SOURCE, "too-early"));

        assertTrue(error.getMessage().contains(TopicEditionReopenServiceImpl.SOURCE_LIFECYCLE_GATE),
                "来源未冻结必须明确由 lifecycle 闸拒绝");
        verify(reopenMapper, never()).insertReopenedTopic(any(CmsTopic.class));
        verify(reopenMapper, never()).insertReopenedChapter(any(CmsTopicChapter.class));
        verify(reopenMapper, never()).insertReopenedNode(any(CmsTopicNode.class));
    }

    @Test
    void pendingReviewAndUnknownOrdinalCannotMasqueradeAsSelling() {
        for (Integer lifecycle : Arrays.asList(TopicLifecycleState.PENDING_REVIEW.getCode(), 99)) {
            CmsTopic source = sourceTopic();
            source.setLifecycle(lifecycle);
            when(reopenMapper.selectSourceTopicForUpdate(SOURCE)).thenReturn(source);

            assertThrows(IllegalStateException.class,
                    () -> service.reopenNextEdition(SOURCE, "ordinal-" + lifecycle));
        }
        verify(reopenMapper, never()).insertReopenedTopic(any(CmsTopic.class));
    }

    @Test
    void copiesJourneyRulesJson_toNextEdition_withoutGameModuleConfig() {
        CmsTopic source = sourceTopic();
        source.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true,\"hp\":{\"max\":6}}");
        source.setGameModuleConfigJson("{\"modules\":[\"GAME\"]}");
        when(reopenMapper.selectSourceTopicForUpdate(SOURCE)).thenReturn(source);

        assertEquals(NEW_TOPIC, service.reopenNextEdition(SOURCE, "journey-rules"));

        ArgumentCaptor<CmsTopic> topics = ArgumentCaptor.forClass(CmsTopic.class);
        verify(reopenMapper).insertReopenedTopic(topics.capture());
        assertEquals("{\"schemaVersion\":1,\"checkEnabled\":true,\"hp\":{\"max\":6}}",
                topics.getValue().getJourneyRulesJson(), "数值规则必须随新一期沿用,否则新一期检定静默关闭");
        assertNull(topics.getValue().getGameModuleConfigJson(), "活动级游戏模组配置刻意不跨期复制");
    }

    @Test
    void doesNotInventJourneyRules_whenSourceHasNone() {
        when(reopenMapper.selectSourceTopicForUpdate(SOURCE)).thenReturn(sourceTopic());

        assertEquals(NEW_TOPIC, service.reopenNextEdition(SOURCE, "no-journey-rules"));

        ArgumentCaptor<CmsTopic> topics = ArgumentCaptor.forClass(CmsTopic.class);
        verify(reopenMapper).insertReopenedTopic(topics.capture());
        assertNull(topics.getValue().getJourneyRulesJson(), "来源未配置时新一期不得凭空生成规则");
    }

    @Test
    void reopenCommand_isTransactional() throws Exception {
        Transactional tx = TopicEditionReopenServiceImpl.class
                .getMethod("reopenNextEdition", Long.class, String.class)
                .getAnnotation(Transactional.class);
        assertTrue(tx != null && Arrays.asList(tx.rollbackFor()).contains(Exception.class),
                "占位、授权、复制与 new_topic_id 回填必须处于同一事务，失败才不会留下半份副本");
    }

    private static CmsTopic sourceTopic() {
        CmsTopic source = new CmsTopic();
        source.setId(SOURCE);
        source.setLifecycle(TopicLifecycleState.SELLING.getCode());
        source.setProductType(2);
        source.setName("来源主题");
        source.setSubtitle("副标题");
        source.setDescription("描述");
        source.setAddressName("南京路");
        source.setTotalTime(120L);
        source.setTotalMileage(new BigDecimal("3.5"));
        source.setLocationCount(1L);
        source.setTemplateCount(1L);
        source.setImgUrl("https://media/source.png");
        source.setImgArr("[\"https://media/source-2.png\"]");
        source.setCategoryIds("1,2");
        source.setPublishMode("story");
        source.setAudioUrl("https://media/source.mp3");
        source.setAudioDuration(23);
        source.setSelfPlay(1);
        source.setXpBudget(120);
        source.setFinishMedalName("完成章");
        source.setFinishMedalImg("https://media/medal.png");
        source.setStartDate(new java.util.Date());
        source.setEndDate(new java.util.Date());
        source.setSaleEndAt(new java.util.Date());
        source.setSelfPlayPrice(new BigDecimal("88"));
        source.setSelfPlayQuota(99);
        source.setFeePlatformRate(new BigDecimal("0.10"));
        source.setCompleteRewardCouponId(77L);
        return source;
    }

    private static CmsTopicChapter sourceChapter(String termsMode) {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(SOURCE_CHAPTER);
        chapter.setTopicId(SOURCE);
        chapter.setName("第一章");
        chapter.setDescription("章节描述");
        chapter.setBlocksJson("[{\"type\":\"text\",\"content\":\"开场\"},{\"type\":\"node\",\"nodeId\":102}]");
        chapter.setImgArr("[\"https://media/chapter.png\"]");
        chapter.setTotalTime(60L);
        chapter.setTotalMileage(new BigDecimal("1.2"));
        chapter.setLocationCount(1L);
        chapter.setTemplateCount(1L);
        chapter.setSortId(1L);
        chapter.setCategory("咖啡");
        chapter.setCategoryId(3);
        chapter.setRecruitEnabled(1);
        chapter.setPerkMinValue(new BigDecimal("10"));
        chapter.setMaxMerchant(2);
        chapter.setRequired(1);
        chapter.setTermsMode(termsMode);
        chapter.setMaxPerHeadFee(new BigDecimal("20"));
        chapter.setAllowedValidationMethods("1,2");
        chapter.setMaxNodeXp(10);
        return chapter;
    }

    private static CmsTopicNode sourceNode() {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(SOURCE_NODE);
        node.setTopicId(SOURCE);
        node.setChapterId(SOURCE_CHAPTER);
        node.setName("节点");
        node.setDescription("节点描述");
        node.setAddress("虹口");
        node.setLongitude("121.1");
        node.setLatitude("31.2");
        node.setImgUrl("https://media/node.png");
        node.setNodeTime(20);
        node.setTemplateId(701L);
        node.setCheckinCode("source-checkin");
        node.setBusinessTime("09:00-18:00");
        node.setStatus(1L);
        node.setSortId(1L);
        node.setXpValue(8);
        node.setHookText("hook");
        node.setCardHookLong("card hook");
        node.setFragmentText("fragment");
        node.setMerchantMemberId(555L);
        node.setNodeAuditStatus(1);
        node.setNodeAuditReason("旧审核");
        node.setNodeAuditMemberId(556L);
        node.setNodeAuditTime(new java.util.Date());
        return node;
    }

    private static ProductRole content(String type, Long entityId) {
        ProductRole role = new ProductRole();
        role.setTopicId(SOURCE);
        role.setSlot(ProductSlot.CONTENT);
        role.setEntityType(type);
        role.setEntityId(entityId);
        role.setStatus(1);
        return role;
    }

    private static CmsMemberTemplate usableTemplate(Long memberId) {
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(701L);
        template.setMemberId(memberId);
        template.setStatus(1L);
        template.setDraftStatus(1);
        template.setDelFlag(0);
        return template;
    }

    private static UmsMember activeMember(Long memberId) {
        UmsMember member = new UmsMember();
        member.setId(memberId);
        member.setStatus(1L);
        member.setDelFlag(0);
        return member;
    }
}
