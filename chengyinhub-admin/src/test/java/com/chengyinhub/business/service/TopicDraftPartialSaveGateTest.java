package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.dto.ActivityTicketRequest;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.NodeDTO;
import com.chengyinhub.business.domain.dto.TopicCreateDTO;
import com.chengyinhub.business.domain.dto.TopicUpdateDTO;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.business.service.support.TopicStateKeyRefService;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R19-DRAFT2:草稿允许半成品(只填名字也能存下服务端 id),完整度校验挪到发布时点。
 *
 * <p>钉死四件事:
 * <ol>
 *   <li>存草稿只保留身份 / 名称 / 已填字段合法性 —— 简单 AI 没有地点、自由探索没有招募截止、
 *       票种起止时间还没定,照样能存;</li>
 *   <li>草稿上已经填进来的坏值照旧拒(坐标越界、票价为负)——「填了就要合法,没填可以空」;</li>
 *   <li>草稿发布时把完整度补上:缺什么一次报全(节点/坐标、招募截止、票种时间);</li>
 *   <li>直接发布(不带 saveDraft)的既有完整度闸一条都没丢 —— 草稿分支放松不等于整条链路放松。</li>
 * </ol>
 *
 * <p>负控:删掉 {@code publishDraft} 里的 {@code assertPublishReady} ⇒ {@link #incompleteDraftPublishIsRejected}
 * 红;把 {@code assertAiSimpleNodeLocations(dto.getChapters(), !saveDraft)} 的 {@code !saveDraft} 改回
 * {@code true} ⇒ {@link #nameOnlyAiSimpleDraftSaves} 红;把 {@code saveTickets} 的 {@code if (draft)}
 * 分支删掉 ⇒ {@link #draftAllowsTicketWithoutTimeWindow} 红;把章节空判改回
 * {@code dto.getChapters().size()} ⇒ {@link #nameOnlyDraftWithNullChaptersSaves} 红。
 */
class TopicDraftPartialSaveGateTest {

    private static final Long MEMBER = 42L;
    private static final Long TOPIC = 700L;

    private CmsTopicServiceImpl svc;
    private CmsTopicMapper topicMapper;
    private CmsTopicNodeMapper nodeMapper;
    private OmsTicketMapper ticketMapper;
    private CmsTopicChapterMapper chapterMapper;

    @BeforeEach
    void setUp() {
        svc = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(svc, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        // createTopic/publishDraft 途经的协作者多且与本测试的判据无关:全量置 mock,
        // 再对断言目标取回引用(与 TopicCreateOpenClubPoolTest 同一手法)。
        for (java.lang.reflect.Field f : CmsTopicServiceImpl.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
            f.setAccessible(true);
            try {
                if (f.get(svc) == null) {
                    ReflectionTestUtils.setField(svc, f.getName(), mock(f.getType()));
                }
            } catch (Exception ignore) {
            }
        }
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.wireRelocatedCollaborators(svc, publicationField -> org.mockito.Mockito.mock(publicationField.getType()));
        topicMapper = (CmsTopicMapper) ReflectionTestUtils.getField(svc, "cmsTopicMapper");
        nodeMapper = (CmsTopicNodeMapper) ReflectionTestUtils.getField(svc, "cmsTopicNodeMapper");
        ticketMapper = (OmsTicketMapper) ReflectionTestUtils.getField(svc, "omsTicketMapper");
        chapterMapper = (CmsTopicChapterMapper) ReflectionTestUtils.getField(svc, "cmsTopicChapterMapper");
        // 状态键/引用的收集是无状态协作者:用真实现装进去,发布闸才算真跑过(不是被 mock 短路)。
        ReflectionTestUtils.setField(svc, "themeStateKeyService",
                new ThemeStateKeyService(mock(ThemeStateKeyMapper.class)));
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(svc, "topicStateKeyRefService",
                new TopicStateKeyRefService(nodeMapper, mock(CmsMemberTemplateMapper.class), chapterMapper));

        when(topicMapper.insertCmsTopic(any(CmsTopic.class))).thenAnswer(inv -> {
            CmsTopic topic = inv.getArgument(0);
            topic.setId(TOPIC);
            org.mockito.Mockito.doReturn(topic).when(topicMapper).selectCmsTopicById(TOPIC);
            return 1;
        });
        when(topicMapper.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);
        // when(currentRead) executes the bridge and records its nested ordinary read; thenAnswer
        // would overwrite that ordinary read with a self-call (the original XML's line-114 loop).
        // doAnswer attaches only to the current-read method and preserves the persisted-row port.
        org.mockito.Mockito.doAnswer(inv -> topicMapper.selectCmsTopicById(TOPIC))
                .when(topicMapper).selectCmsTopicByIdForUpdate(TOPIC);
        when(topicMapper.publishRouteSnapshot(anyLong(), any(), any())).thenReturn(1);
        java.util.List<CmsTopicChapter> writtenChapters = new ArrayList<>();
        java.util.List<CmsTopicNode> writtenNodes = new ArrayList<>();
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenAnswer(call -> new ArrayList<>(writtenChapters));
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenAnswer(call -> new ArrayList<>(writtenNodes));
        org.mockito.Mockito.doAnswer(call -> writtenChapters.removeIf(row -> row.getId().equals(call.getArgument(0))) ? 1 : 0)
                .when(chapterMapper).deleteCmsTopicChapterById(anyLong());
        org.mockito.Mockito.doAnswer(call -> writtenNodes.removeIf(row -> row.getId().equals(call.getArgument(0))) ? 1 : 0)
                .when(nodeMapper).deleteCmsTopicNodeById(anyLong());
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class)))
                .thenReturn(Collections.<OmsTicket>emptyList());
        // CORE OWN-C03-13/15：章节、自办活动插入后须生成有效身份，否则 fail-closed；夹具按真实插入回填 id。
        java.util.concurrent.atomic.AtomicLong ids = new java.util.concurrent.atomic.AtomicLong(600L);
        org.mockito.Mockito.lenient().when(chapterMapper.insertCmsTopicChapter(any(CmsTopicChapter.class))).thenAnswer(inv -> {
            CmsTopicChapter row = inv.getArgument(0); row.setId(ids.incrementAndGet()); writtenChapters.add(row);
            return 1;
        });
        org.mockito.Mockito.lenient().when(nodeMapper.insertCmsTopicNode(any(CmsTopicNode.class))).thenAnswer(inv -> {
            CmsTopicNode row = inv.getArgument(0); row.setId(ids.incrementAndGet()); writtenNodes.add(row);
            return 1;
        });
        CmsActivityMapper shellActivities = (CmsActivityMapper) ReflectionTestUtils.getField(svc, "cmsActivityMapper");
        org.mockito.Mockito.lenient().when(shellActivities.insertCmsActivity(any(CmsActivity.class))).thenAnswer(inv -> {
            ((CmsActivity) inv.getArgument(0)).setId(880001L);
            return 1;
        });
        svc = com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(svc);
    }

    // ---------- 夹具 ----------

    /** 简单 AI + 自由探索:两个「完整度闸」都挂在它身上,是最难存的组合。 */
    private TopicCreateDTO aiSimpleFreeExploreDto(Integer saveDraft) {
        TopicCreateDTO dto = new TopicCreateDTO();
        dto.setName("刚起了个名字的路线");
        dto.setProductType(ProductType.FREE_EXPLORE);
        dto.setPublishMode("ai_simple");
        dto.setChapters(new ArrayList<ChapterDTO>());
        dto.setTickets(new ArrayList<ActivityTicketRequest>());
        dto.setSaveDraft(saveDraft);
        return dto;
    }

    private CmsTopic aiSimpleDraftTopic() {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setMemberId(MEMBER);
        t.setName("刚起了个名字的路线");
        t.setPublishMode("ai_simple");
        t.setProductType(ProductType.FREE_EXPLORE);
        t.setStatus(0);
        t.setUserStatus(0);
        t.setLifecycle(0);
        t.setDelFlag(0);
        return t;
    }

    private CmsTopicNode nodeWithCoords(String lat, String lng) {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(11L);
        n.setTopicId(TOPIC);
        n.setName("第一站");
        n.setStatus(1L);
        n.setLatitude(lat);
        n.setLongitude(lng);
        return n;
    }

    private NodeDTO confirmedNode() {
        NodeDTO node = new NodeDTO();
        node.setName("已确认地点");
        node.setAddress("人民广场");
        node.setNodeTime(30);
        node.setLatitude("31.23");
        node.setLongitude("121.45");
        return node;
    }

    private void withConfirmedChapter(TopicCreateDTO dto) {
        ChapterDTO chapter = new ChapterDTO();
        chapter.setNodes(new ArrayList<NodeDTO>(Arrays.asList(confirmedNode())));
        dto.setChapters(new ArrayList<ChapterDTO>(Arrays.asList(chapter)));
    }

    private OmsTicket ticket(String name, Date start, Date end) {
        OmsTicket t = new OmsTicket();
        t.setOwnerType(1L);
        t.setOwnerId(TOPIC);
        t.setName(name);
        t.setPrice(new BigDecimal("9.90"));
        t.setTotalInventory(10L);
        t.setStartTime(start);
        t.setEndTime(end);
        return t;
    }

    // ---------- 1. 只填名字也能存草稿 ----------

    @Test
    void nameOnlyAiSimpleDraftSaves() {
        TopicCreateDTO dto = aiSimpleFreeExploreDto(1);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        Long id = com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto, MEMBER, "creator");

        assertEquals(TOPIC, id, "存草稿必须回服务端 id(注册状态键/试检查要靠它)");
        verify(topicMapper).insertCmsTopic(any(CmsTopic.class));
        verify(topicMapper).updateTopicDraftState(TOPIC, 1);
    }

    /** chapters 字段缺席(不是空数组也行):只填了名字就保存,不得 NPE。 */
    @Test
    void nameOnlyDraftWithNullChaptersSaves() {
        TopicCreateDTO dto = aiSimpleFreeExploreDto(1);
        dto.setChapters(null);
        dto.setTickets(null);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        Long id = com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto, MEMBER, "creator");

        assertEquals(TOPIC, id);
        verify(topicMapper).updateTopicDraftState(TOPIC, 1);
    }

    @Test
    void draftStillRequiresTopicName() {
        TopicCreateDTO dto = aiSimpleFreeExploreDto(1);
        dto.setName("  ");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto, MEMBER, "creator"));

        assertTrue(e.getMessage().contains("主题名称"), e.getMessage());
        verify(topicMapper, never()).insertCmsTopic(any());
    }

    // ---------- 2. 草稿上填了的坏值照旧拒 ----------

    @Test
    void draftRejectsFilledButIllegalCoordinate() {
        TopicCreateDTO dto = aiSimpleFreeExploreDto(1);
        NodeDTO node = new NodeDTO();
        node.setName("坐标写坏的点");
        node.setLatitude("999");
        node.setLongitude("121.45");
        ChapterDTO chapter = new ChapterDTO();
        chapter.setNodes(new ArrayList<NodeDTO>(Arrays.asList(node)));
        dto.setChapters(new ArrayList<ChapterDTO>(Arrays.asList(chapter)));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto, MEMBER, "creator"));

        assertTrue(e.getMessage().contains("纬度无效"), e.getMessage());
        verify(topicMapper, never()).insertCmsTopic(any());
    }

    /** 票的起止时间属完整度(草稿可空),票价为负属已填字段的合法性(草稿也拒)。 */
    @Test
    void draftRejectsNegativeTicketPrice() {
        TopicCreateDTO dto = aiSimpleFreeExploreDto(1);
        ActivityTicketRequest t = new ActivityTicketRequest();
        t.setName("早鸟票");
        t.setPrice(new BigDecimal("-1"));
        t.setTotalStock(10);
        dto.setTickets(new ArrayList<ActivityTicketRequest>(Arrays.asList(t)));

        ServiceException e = assertThrows(ServiceException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto, MEMBER, "creator"));

        assertTrue(e.getMessage().contains("价格"), e.getMessage());
        verify(ticketMapper, never()).insertOmsTicket(any());
    }

    @Test
    void draftAllowsTicketWithoutTimeWindow() {
        TopicCreateDTO dto = aiSimpleFreeExploreDto(1);
        ActivityTicketRequest t = new ActivityTicketRequest();
        t.setName("还没定时间的票");
        t.setPrice(new BigDecimal("9.90"));
        t.setTotalStock(10);
        dto.setTickets(new ArrayList<ActivityTicketRequest>(Arrays.asList(t)));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto, MEMBER, "creator");

        verify(ticketMapper).insertOmsTicket(any(OmsTicket.class));
    }

    // ---------- 3. 发布:草稿的完整度补齐、缺什么一次报全 ----------

    @Test
    void incompleteDraftPublishIsRejected() {
        when(topicMapper.clearDraftIfDraft(TOPIC)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(aiSimpleDraftTopic());
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class)))
                .thenReturn(Collections.singletonList(ticket("早鸟票", null, null)));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).publishDraft(TOPIC));

        String msg = e.getMessage();
        assertTrue(msg.contains("简单 AI 主题至少需要一个已确认地点"), "缺节点要指名:" + msg);
        assertTrue(msg.contains("招募截止"), "缺招募截止要指名:" + msg);
        assertTrue(msg.contains("票种「早鸟票」"), "缺票种时间要指名:" + msg);
        verify(topicMapper, never()).updateCmsTopic(any(CmsTopic.class));
    }

    @Test
    void completeDraftPublishesWithTheSameId() {
        when(topicMapper.clearDraftIfDraft(TOPIC)).thenReturn(1);
        CmsTopic draft = aiSimpleDraftTopic();
        draft.setRecruitDeadline(new Date(System.currentTimeMillis() + 86_400_000L));
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(draft);
        org.mockito.Mockito.doReturn(draft).when(topicMapper).selectCmsTopicByIdForUpdate(TOPIC);
        org.mockito.Mockito.doReturn(draft).when((com.chengyinhub.business.service.support.TopicSupplyReviewService)
                org.springframework.test.util.ReflectionTestUtils.getField(svc, "supplyReviewService")).lockTopic(TOPIC);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(nodeWithCoords("31.23", "121.45")));
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class))).thenReturn(Collections.singletonList(
                ticket("早鸟票", new Date(1_800_000_000_000L), new Date(1_800_003_600_000L))));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).publishDraft(TOPIC));

        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).updateCmsTopic(cap.capture());
        assertEquals(TOPIC, cap.getValue().getId(), "草稿 id 就是发布 id,不另起一条");
        assertEquals(Integer.valueOf(1), cap.getValue().getStatus(), "简单 AI 免审直发");
    }

    @Test
    void persistedReversedSaleWindowBlocksPublication() {
        when(topicMapper.clearDraftIfDraft(TOPIC)).thenReturn(1);
        CmsTopic draft = aiSimpleDraftTopic();
        draft.setRecruitDeadline(new Date(System.currentTimeMillis() + 86_400_000L));
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(draft);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(nodeWithCoords("31.23", "121.45")));
        OmsTicket invalid = ticket("倒序售票票种",
                new Date(1_800_000_000_000L), new Date(1_800_003_600_000L));
        invalid.setSaleStartTime(new Date(1_800_000_000_000L));
        invalid.setSaleEndTime(new Date(1_799_913_600_000L));
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class)))
                .thenReturn(Collections.singletonList(invalid));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).publishDraft(TOPIC));

        assertTrue(error.getMessage().contains("售票结束日期不能早于开始日期"), error.getMessage());
        verify(topicMapper, never()).updateCmsTopic(any(CmsTopic.class));
    }

    @Test
    void draftDisappearingBeforeLockedPublishDoesNotBecomePublic() {
        when(topicMapper.clearDraftIfDraft(TOPIC)).thenReturn(1);
        CmsTopic draft = aiSimpleDraftTopic();
        draft.setRecruitDeadline(new Date(System.currentTimeMillis() + 86_400_000L));
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(draft);
        org.mockito.Mockito.doReturn(null).when(topicMapper).selectCmsTopicByIdForUpdate(TOPIC);
        org.mockito.Mockito.doReturn(null).when((com.chengyinhub.business.service.support.TopicSupplyReviewService)
                org.springframework.test.util.ReflectionTestUtils.getField(svc, "supplyReviewService")).lockTopic(TOPIC);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(nodeWithCoords("31.23", "121.45")));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        assertThrows(IllegalStateException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).publishDraft(TOPIC));

        verify(topicMapper, never()).updateCmsTopic(any(CmsTopic.class));
        verify(topicMapper, never()).publishRouteSnapshot(anyLong(), any(), any());
    }

    // ---------- 4. 直接发布路径的既有完整度闸一条没丢 ----------

    @Test
    void directPublishStillRequiresAiSimpleLocation() {
        TopicCreateDTO dto = aiSimpleFreeExploreDto(null);
        dto.setRecruitDeadline(new Date(System.currentTimeMillis() + 86_400_000L));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto, MEMBER, "creator"));

        assertTrue(e.getMessage().contains("至少需要一个已确认地点"), e.getMessage());
        verify(topicMapper, never()).insertCmsTopic(any());
    }

    @Test
    void directPublishStillRequiresRecruitDeadlineForFreeExplore() {
        TopicCreateDTO dto = aiSimpleFreeExploreDto(null);
        withConfirmedChapter(dto);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto, MEMBER, "creator"));

        assertTrue(e.getMessage().contains("招募截止"), e.getMessage());
        verify(topicMapper, never()).insertCmsTopic(any());
    }

    @Test
    void directPublishStillRejectsTicketWithoutTimeWindow() {
        TopicCreateDTO dto = aiSimpleFreeExploreDto(null);
        dto.setRecruitDeadline(new Date(System.currentTimeMillis() + 86_400_000L));
        withConfirmedChapter(dto);
        ActivityTicketRequest t = new ActivityTicketRequest();
        t.setName("时间空的票");
        t.setPrice(new BigDecimal("9.90"));
        t.setTotalStock(10);
        dto.setTickets(new ArrayList<ActivityTicketRequest>(Arrays.asList(t)));

        ServiceException e = assertThrows(ServiceException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto, MEMBER, "creator"));

        assertTrue(e.getMessage().contains("票务结束时间"), e.getMessage());
        verify(ticketMapper, never()).insertOmsTicket(any());
    }

    // ---------- 5. 草稿编辑(/api/topic/update)同一分流 ----------

    @Test
    void draftUpdateSkipsPublishOnlyCompleteness() {
        CmsTopic draft = aiSimpleDraftTopic();
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(draft);
        when(topicMapper.selectTopicIsDraft(TOPIC)).thenReturn(1);
        TopicUpdateDTO dto = new TopicUpdateDTO();
        dto.setId(TOPIC);
        dto.setName("改了名字的草稿");
        dto.setProductType(ProductType.FREE_EXPLORE);
        dto.setPublishMode("ai_simple");
        dto.setChapters(new ArrayList<ChapterDTO>());
        dto.setTickets(new ArrayList<ActivityTicketRequest>());

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        Long id = com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).updateTopicByOwner(dto, MEMBER, "creator");

        assertEquals(TOPIC, id, "草稿编辑就地改,不新建");
        verify(topicMapper).updateCmsTopic(any(CmsTopic.class));
    }

    /** 负控(正面对照):同一份残缺内容走非草稿(待审)编辑,招募截止照旧被要求。 */
    @Test
    void pendingReviewUpdateStillRequiresRecruitDeadline() {
        CmsTopic pending = aiSimpleDraftTopic();
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(pending);
        when(topicMapper.selectTopicIsDraft(TOPIC)).thenReturn(null);
        TopicUpdateDTO dto = new TopicUpdateDTO();
        dto.setId(TOPIC);
        dto.setName("待审主题");
        dto.setProductType(ProductType.FREE_EXPLORE);
        dto.setPublishMode("ai_simple");
        withConfirmedChapter(dto);
        dto.setTickets(new ArrayList<ActivityTicketRequest>());

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).updateTopicByOwner(dto, MEMBER, "creator"));

        assertTrue(e.getMessage().contains("招募截止"), e.getMessage());
    }

    /** 草稿编辑也不接受空名称(与 /api/topic/create 的草稿口径一致);字段缺席则保留原值。 */
    @Test
    void draftUpdateRejectsBlankedName() {
        CmsTopic draft = aiSimpleDraftTopic();
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(draft);
        when(topicMapper.selectTopicIsDraft(TOPIC)).thenReturn(1);
        TopicUpdateDTO dto = new TopicUpdateDTO();
        dto.setId(TOPIC);
        dto.setName("");
        dto.setChapters(new ArrayList<ChapterDTO>());
        dto.setTickets(new ArrayList<ActivityTicketRequest>());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).updateTopicByOwner(dto, MEMBER, "creator"));

        assertTrue(e.getMessage().contains("主题名称"), e.getMessage());
        verify(topicMapper, never()).updateCmsTopic(any(CmsTopic.class));
    }

    /** 直接发布路径的自办活动壳仍在建(不是把整条链路摘了):与草稿分支的对照。 */
    @Test
    void directPublishStillBuildsSelfHostShell() {
        TopicCreateDTO dto = aiSimpleFreeExploreDto(null);
        dto.setRecruitDeadline(new Date(System.currentTimeMillis() + 86_400_000L));
        withConfirmedChapter(dto);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto, MEMBER, "creator");

        CmsActivityMapper activityMapper = (CmsActivityMapper) ReflectionTestUtils.getField(svc, "cmsActivityMapper");
        verify(activityMapper).insertCmsActivity(any(CmsActivity.class));
        verify(topicMapper, never()).updateTopicDraftState(anyLong(), any());
    }

    /** 草稿发布成功路径不得额外写草稿态位(回归 R18 的「发布沿用同一 id」语义)。 */
    @Test
    void publishDoesNotRewriteDraftFlag() {
        when(topicMapper.clearDraftIfDraft(TOPIC)).thenReturn(1);
        CmsTopic draft = aiSimpleDraftTopic();
        draft.setRecruitDeadline(new Date(System.currentTimeMillis() + 86_400_000L));
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(draft);
        org.mockito.Mockito.doReturn(draft).when(topicMapper).selectCmsTopicByIdForUpdate(TOPIC);
        org.mockito.Mockito.doReturn(draft).when((com.chengyinhub.business.service.support.TopicSupplyReviewService)
                org.springframework.test.util.ReflectionTestUtils.getField(svc, "supplyReviewService")).lockTopic(TOPIC);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(nodeWithCoords("31.23", "121.45")));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).publishDraft(TOPIC));

        verify(topicMapper).clearDraftIfDraft(eq(TOPIC));
        verify(topicMapper, never()).updateTopicDraftState(anyLong(), any());
        verify(topicMapper, never()).insertCmsTopic(any(CmsTopic.class));
    }

    /** 名称非空闸在非草稿创建上同样生效(不是只对草稿特殊照顾)。 */
    @Test
    void directPublishRequiresName() {
        TopicCreateDTO dto = aiSimpleFreeExploreDto(null);
        dto.setName(null);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        assertThrows(IllegalArgumentException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto, MEMBER, "creator"));
        verify(topicMapper, never()).insertCmsTopic(any());
    }
}
