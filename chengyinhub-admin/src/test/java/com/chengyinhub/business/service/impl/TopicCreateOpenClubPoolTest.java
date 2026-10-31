package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.NodeDTO;
import com.chengyinhub.business.domain.dto.TopicCreateDTO;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.service.support.TopicPublicationTestWiring;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICouponRewardAuthorizationService;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

/**
 * P0-4 开放范围:小程序发布链(TopicCreateDTO → createTopic)必须把 openClubPool 落到
 * cms_topic.open_club_pool(1=开放给俱乐部承接),未传按 0 收口 —— 防「前端开了开关、后端静默丢字段」。
 */
class TopicCreateOpenClubPoolTest {

    private CmsTopicServiceImpl svc;
    private CmsTopicMapper cmsTopicMapper;
    private final List<CmsTopicChapter> writtenChapters = new ArrayList<>();
    private final List<CmsTopicNode> writtenNodes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        svc = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(svc, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        // createTopic 途经的协作者很多且与本测试无关:全部依赖统一置 mock(默认返回 null/空),
        // 只对断言目标 cmsTopicMapper 捕参。
        for (Field f : CmsTopicServiceImpl.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
            f.setAccessible(true);
            try {
                if (f.get(svc) == null) {
                    ReflectionTestUtils.setField(svc, f.getName(), Mockito.mock(f.getType()));
                }
            } catch (Exception ignore) {
            }
        }
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.wireRelocatedCollaborators(svc, publicationField -> org.mockito.Mockito.mock(publicationField.getType()));
        cmsTopicMapper = (CmsTopicMapper) ReflectionTestUtils.getField(svc, "cmsTopicMapper");
        // CORE OWN-C03-15：自办活动插入后须生成有效身份，否则 fail-closed；夹具按真实插入回填 id。
        com.chengyinhub.business.mapper.CmsActivityMapper activities = (com.chengyinhub.business.mapper.CmsActivityMapper)
                ReflectionTestUtils.getField(svc, "cmsActivityMapper");
        Mockito.lenient().when(activities.insertCmsActivity(any())).thenAnswer(inv -> {
            ((com.chengyinhub.business.domain.CmsActivity) inv.getArgument(0)).setId(880001L);
            return 1;
        });
        when(cmsTopicMapper.insertCmsTopic(any(CmsTopic.class))).thenAnswer(invocation -> {
            CmsTopic topic = invocation.getArgument(0);
            topic.setId(700L);
            when(cmsTopicMapper.selectCmsTopicById(700L)).thenReturn(topic);
            return 1;
        });
        ReflectionTestUtils.setField(svc, "themeStateKeyService", new com.chengyinhub.business.service.support.ThemeStateKeyService(
                Mockito.mock(com.chengyinhub.business.mapper.ThemeStateKeyMapper.class)));
        wirePublicationInputs();
        svc = com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(svc);
    }

    /** Feed the existing real compiler the rows actually inserted from the request, not an empty freeze stub. */
    private void wirePublicationInputs() {
        CmsTopicChapterMapper chapters = (CmsTopicChapterMapper) TopicPublicationTestWiring.get(svc, "cmsTopicChapterMapper");
        CmsTopicNodeMapper nodes = (CmsTopicNodeMapper) TopicPublicationTestWiring.get(svc, "cmsTopicNodeMapper");
        AtomicLong chapterIds = new AtomicLong(801L), nodeIds = new AtomicLong(901L);
        Mockito.doAnswer(call -> {
            CmsTopicChapter row = call.getArgument(0); row.setId(chapterIds.getAndIncrement());
            writtenChapters.add(row); return 1;
        }).when(chapters).insertCmsTopicChapter(any());
        Mockito.doAnswer(call -> {
            CmsTopicNode row = call.getArgument(0); row.setId(nodeIds.getAndIncrement());
            writtenNodes.add(row); return 1;
        }).when(nodes).insertCmsTopicNode(any());
        when(chapters.selectCmsTopicChapterList(any())).thenAnswer(call -> new ArrayList<>(writtenChapters));
        when(nodes.selectCmsTopicNodeList(any())).thenAnswer(call -> new ArrayList<>(writtenNodes));
        when(chapters.selectByTopicForUpdate(700L)).thenAnswer(call -> new ArrayList<>(writtenChapters));
        when(nodes.selectByTopicForUpdate(700L)).thenAnswer(call -> new ArrayList<>(writtenNodes));
        Mockito.doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            DataSourceTransactionManager manager = (DataSourceTransactionManager) ReflectionTestUtils.getField(svc, "transactionManager");
            assertTrue(TransactionSynchronizationManager.hasResource(manager.getDataSource()));
            var frozen = new com.fasterxml.jackson.databind.ObjectMapper().readTree((String) call.getArgument(1));
            assertEquals(1, frozen.path("runtimeSnapshotVersion").asInt());
            assertEquals(writtenNodes.size(), frozen.path("routeStations").size());
            assertEquals(writtenNodes.get(0).getId().longValue(), frozen.path("routeStations").get(0).path("nodeId").asLong());
            assertTrue(frozen.path("nodeAnswers").path(String.valueOf(writtenNodes.get(0).getId())).path("templateId").isNull());
            return 1;
        }).when(cmsTopicMapper).publishRouteSnapshot(any(), any(), any());
    }

    private ArrayList<ChapterDTO> runnableChapters() {
        NodeDTO node = new NodeDTO(); node.setName("原请求地点"); node.setAddress("人民广场"); node.setNodeTime(30);
        ChapterDTO chapter = new ChapterDTO(); chapter.setName("原请求章节");
        chapter.setNodes(new ArrayList<>(Collections.singletonList(node)));
        return new ArrayList<>(Collections.singletonList(chapter));
    }

    private TopicCreateDTO freeExploreDto(Integer openClubPool) {
        TopicCreateDTO dto = new TopicCreateDTO();
        dto.setName("测试自由探索");
        dto.setProductType(2); // FREE_EXPLORE
        dto.setRecruitDeadline(new Date(System.currentTimeMillis() + 86400000L));
        dto.setPublishMode("simple");
        dto.setChapters(runnableChapters());
        dto.setOpenClubPool(openClubPool);
        return dto;
    }

    /**
     * spec §2.1 起「邀请俱乐部带队」只属于城市定向,所以「开着要落库」这条只能在城市定向上验。
     *
     * <p>⚠️ 不要改 {@link #freeExploreDto} 本身去迁就这条 —— 本文件另有多条用例(章节招商、
     * 名额上下界)的语义**依赖它是自由探索**,改 helper 会把那些用例变成假绿。
     */
    private TopicCreateDTO cityOrientationDto(Integer openClubPool) {
        TopicCreateDTO dto = new TopicCreateDTO();
        dto.setName("测试城市定向");
        dto.setProductType(ProductType.ORIENTEERING);
        dto.setPublishMode("simple");
        dto.setChapters(runnableChapters());
        dto.setOpenClubPool(openClubPool);
        return dto;
    }

    @Test
    void createTopic_persists_open_club_pool_flag_for_city_orientation() {
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(cityOrientationDto(1), 42L, "tester");
        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(cmsTopicMapper).insertCmsTopic(cap.capture());
        assertEquals(1, cap.getValue().getOpenClubPool());
    }

    /**
     * spec §2.1:自由探索没有俱乐部带队。小程序在自由探索下已不渲染这个开关,但直调发布接口
     * 必须 fail-closed —— 落成 1 之后 {@code CoopClubApplyServiceImpl} 就会放俱乐部进来申请
     * 承接一条本不该有带队方的主题。
     */
    @Test
    void createTopic_rejectsClubLeadForFreeExploreBeforePersisting() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(freeExploreDto(1), 42L, "tester"));

        // 断到具体这道闸:同一段写入前校验里章节招商闸抛的是同型 IllegalStateException,
        // 只断"抛了"会被相邻那道闸接住 = 假绿。
        assertTrue(ex.getMessage().contains("俱乐部带队"),
                "必须是「邀请俱乐部带队」这道闸拦下的,实际:" + ex.getMessage());
        verify(cmsTopicMapper, Mockito.never()).insertCmsTopic(Mockito.any(CmsTopic.class));
    }

    /**
     * D4→D7 链路的中段:DTO 上的值确实被搬到了递给 {@code insertCmsTopic} 的实体上。
     *
     * <p>整条链是三段,三段各有各的测试,少一段就会「修了也没值可写」:
     * <ol>
     *   <li>线上格式 → DTO:{@code TopicCreateDtoDateFormatTest}(D4,@JsonFormat)</li>
     *   <li><b>DTO → 实体:本条</b></li>
     *   <li>实体 → 库:{@code TopicInsertColumnCoverageMyBatisIT}(D7,mapper 漏列)</li>
     * </ol>
     * ★ 2026-08-18 合并时按【更晚的设计】订正了后半条断言。
     *
     * <p>本条原来断言「传了招募截止 ⇒ 实体上 lifecycle=RECRUITING(1)」。那是 H3 立项审
     * 落地之前的口径。现在建期路径明确写着:<b>只保存招募截止,生命周期保持 DRAFT</b>,
     * 因为「章节/票尚未落库前无法客观判断 H4 三条商业事实,绝不能在这里直接写 lifecycle=1
     * 绕过立项审」({@code CmsTopicServiceImpl} 建期段注释原文)。进 RECRUITING 的唯一出口是
     * {@code topicCommercialReviewService.submitProjectReview} 之后的立项审通过
     * —— {@code TopicLifecycleServiceImpl.assertReviewGate} 对商业主题的 DRAFT→RECRUITING
     * 是直接抛「商业主题必须先提交并通过立项审核」的。
     *
     * <p>所以这里改成断言两件事:① recruitDeadline 确实搬到了实体上(D7 那条链的中段仍要守);
     * ② 实体上<b>不能</b>带 RECRUITING —— 带了就等于建期即绕过立项审,那是资损面。
     */
    @Test
    void createTopic_carriesRecruitDeadlineButMustNotJumpStraightIntoRecruiting() {
        TopicCreateDTO dto = freeExploreDto(null);
        Date deadline = dto.getRecruitDeadline();

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto, 42L, "tester");

        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(cmsTopicMapper).insertCmsTopic(cap.capture());
        assertEquals(deadline, cap.getValue().getRecruitDeadline(),
                "招募截止必须搬到实体上,否则 mapper 就算写这一列也没值可写");
        org.junit.jupiter.api.Assertions.assertNotEquals(Integer.valueOf(1), cap.getValue().getLifecycle(),
                "建期实体不得直接带 RECRUITING(1):进招募的唯一出口是立项审通过,"
                        + "在这里写死就是绕过 H4 三条商业事实的判定");
    }

    @Test
    void createTopic_defaults_open_club_pool_to_open_for_city_orientation() {
        // 2026-09-15 裁决 12B:未传 = 开(专业版与简易版统一默认开,主办可手动关)。
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(cityOrientationDto(null), 42L, "tester");
        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(cmsTopicMapper).insertCmsTopic(cap.capture());
        assertEquals(1, cap.getValue().getOpenClubPool(),
                "未传字段的新建主题必须默认开放承接池 —— 前端显式上送是同一个口径的前半段,后端缺省是后半段");
    }

    /**
     * D3(2026-09-05,业主 2026-10-31 再次确认):商家发的城市定向只能自己带,不邀俱乐部 ——
     * 以商家主体(scope=MERCHANT)发布时,不论显式开还是未传(12B 的缺省是开),公开承接池都必须落 0。
     */
    @Test
    void createTopic_neverOpensClubPoolForMerchantScopedCityOrientation() {
        for (Integer requested : Arrays.asList(1, null)) {
            Mockito.clearInvocations(cmsTopicMapper);
            com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
            TopicCreateDTO dto = cityOrientationDto(requested);
            dto.setScope("MERCHANT");
            com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto, 42L, "tester");
            ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
            verify(cmsTopicMapper).insertCmsTopic(cap.capture());
            assertEquals(0, cap.getValue().getOpenClubPool(),
                    "商家城市定向的承接池必须恒 0(requested=" + requested + ") —— 默认开不能替商家打开");
        }
    }

    @Test
    void createTopic_keeps_explicit_closed_switch_for_city_orientation() {
        // 主办手动关 = 显式 0,必须如实落库(默认开不等于写死开)。
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(cityOrientationDto(0), 42L, "tester");
        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(cmsTopicMapper).insertCmsTopic(cap.capture());
        assertEquals(0, cap.getValue().getOpenClubPool());
    }

    /**
     * 自由探索没有俱乐部带队(spec §2.1):即使未传,也不能被「默认开」的缺省替它打开 ——
     * 否则默认值自己就成了 assertClubLeadOnlyForCityOrienteering 的绕过口。
     */
    @Test
    void createTopic_neverOpensClubPoolForFreeExplore() {
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(freeExploreDto(null), 42L, "tester");
        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(cmsTopicMapper).insertCmsTopic(cap.capture());
        assertEquals(0, cap.getValue().getOpenClubPool());
    }

    /**
     * 2026-09-15 补充裁决(审查D §1 第 4 条):归属俱乐部的主题(俱乐部自办团)不开放公开承接池。
     * 「默认开」不得覆盖 club 锁 —— 未传也不例外,池的读侧一直把 club_id 非空的主题排除在外。
     */
    @Test
    void createTopic_clubBound_neverOpensPublicClubPool() {
        stubMyOwnedClub(9L);
        TopicCreateDTO dto = cityOrientationDto(null);
        dto.setClubId(9L);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto, 42L, "tester");

        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(cmsTopicMapper).insertCmsTopic(cap.capture());
        assertEquals(Long.valueOf(9L), cap.getValue().getClubId(), "归属俱乐部必须落库");
        assertEquals(0, cap.getValue().getOpenClubPool(), "俱乐部自办团不进公开承接池");
    }

    /** 显式上送 1 也不行:club 锁优先于表单值,不能靠改客户端绕开。 */
    @Test
    void createTopic_clubBound_ignoresExplicitOpen() {
        stubMyOwnedClub(9L);
        TopicCreateDTO dto = cityOrientationDto(1);
        dto.setClubId(9L);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto, 42L, "tester");

        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(cmsTopicMapper).insertCmsTopic(cap.capture());
        assertEquals(0, cap.getValue().getOpenClubPool(), "显式 1 也压回 0");
    }

    /** 未传 clubId 但会员有俱乐部(经典定向自动挂靠):同样是俱乐部主题,恒 0。 */
    @Test
    void createTopic_autoAttachedClub_neverOpensPublicClubPool() {
        stubMyOwnedClub(9L);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(cityOrientationDto(null), 42L, "tester");

        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(cmsTopicMapper).insertCmsTopic(cap.capture());
        assertEquals(Long.valueOf(9L), cap.getValue().getClubId(), "经典定向未传时自动挂靠本人俱乐部");
        assertEquals(0, cap.getValue().getOpenClubPool());
    }

    /** 名下俱乐部:显式挂靠与自动挂靠两条路都靠它。 */
    private void stubMyOwnedClub(long clubId) {
        IClubService clubService = (IClubService) ReflectionTestUtils.getField(svc, "clubService");
        Club club = new Club();
        club.setId(clubId);
        club.setMemberId(42L);
        Mockito.when(clubService.selectClubByIdForUpdate(clubId)).thenReturn(club);
        Mockito.when(clubService.selectClubByMemberId(42L)).thenReturn(club);
    }

    /**
     * V1 章节招商只属于自由探索：不能仅靠小程序隐藏工作台，直调发布接口也必须 fail-closed。
     */
    @Test
    void createTopic_rejectsChapterRecruitmentForNonFreeExploreBeforePersisting() {
        ISysCategoryService categoryService = (ISysCategoryService) com.chengyinhub.business.service.support.TopicPublicationTestWiring.get(svc, "sysCategoryService");
        SysCategory activeMerchantCategory = new SysCategory();
        activeMerchantCategory.setId(18L);
        activeMerchantCategory.setType(1L);
        activeMerchantCategory.setParentId(0L);
        activeMerchantCategory.setStatus(1);
        activeMerchantCategory.setDelFlag(0);
        Mockito.when(categoryService.selectSysCategoryById(18L)).thenReturn(activeMerchantCategory);

        ChapterDTO chapter = new ChapterDTO();
        chapter.setName("不能开放的章节");
        chapter.setNodes(new ArrayList<>());
        chapter.setRecruitEnabled(1);
        chapter.setCategoryId(18);
        chapter.setTermsMode("PERK");

        TopicCreateDTO dto = freeExploreDto(1);
        dto.setProductType(ProductType.ORIENTEERING);
        dto.setChapters(Collections.singletonList(chapter));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto, 42L, "tester"));

        assertTrue(ex.getMessage().contains("仅自由探索主题"));
        verify(cmsTopicMapper, Mockito.never()).insertCmsTopic(Mockito.any(CmsTopic.class));
    }

    /**
     * max_merchant 是有符号 TINYINT：0 表示不限，负数不能被当成“不限”，128 也不能留给数据库溢出。
     */
    @Test
    void createTopic_rejectsRecruitmentMaxMerchantOutsideZeroTo127() {
        ISysCategoryService categoryService = (ISysCategoryService) com.chengyinhub.business.service.support.TopicPublicationTestWiring.get(svc, "sysCategoryService");
        SysCategory activeMerchantCategory = new SysCategory();
        activeMerchantCategory.setId(18L);
        activeMerchantCategory.setType(1L);
        activeMerchantCategory.setParentId(0L);
        activeMerchantCategory.setStatus(1);
        activeMerchantCategory.setDelFlag(0);
        Mockito.when(categoryService.selectSysCategoryById(18L)).thenReturn(activeMerchantCategory);

        for (int maxMerchant : new int[] { -1, 128 }) {
            ChapterDTO chapter = new ChapterDTO();
            chapter.setName("名额非法章节");
            chapter.setNodes(new ArrayList<>());
            chapter.setRecruitEnabled(1);
            chapter.setCategoryId(18);
            chapter.setTermsMode("PERK");
            chapter.setMaxMerchant(maxMerchant);

            // openClubPool 传 0:本例要验的是名额上下界那道闸,开着俱乐部带队会先被 §2.1 的闸拦下,
            // 断言就落到了错的闸上(相邻多闸同型异常互相接住)。
            TopicCreateDTO dto = freeExploreDto(0);
            dto.setChapters(Collections.singletonList(chapter));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto, 42L, "tester"));
            assertTrue(ex.getMessage().contains("0到127"), "maxMerchant=" + maxMerchant + ", 实际:" + ex.getMessage());
        }
    }

    @Test
    void createTopic_persists_owned_completion_reward_coupon() {
        TopicCreateDTO dto = freeExploreDto(0);
        dto.setCompleteRewardCouponId(77L);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto, 42L, "tester");

        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(cmsTopicMapper).insertCmsTopic(cap.capture());
        assertEquals(Long.valueOf(77L), cap.getValue().getCompleteRewardCouponId());
        ICouponRewardAuthorizationService authorizationService = (ICouponRewardAuthorizationService)
                ReflectionTestUtils.getField(svc, "couponRewardAuthorizationService");
        verify(authorizationService).assertOwnedRewardCoupon(77L, 42L);
    }

    @Test
    void createAiSimpleTopic_rejectsUnconfirmedNodeLocations() {
        TopicCreateDTO dto = freeExploreDto(0);
        dto.setPublishMode("ai_simple");
        dto.setChapters(new ArrayList<>()); // Preserve the original absent-location negative control.

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        assertThrows(IllegalArgumentException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto, 42L, "tester"));
    }

    @Test
    void createAiSimpleTopic_rejectsNodeWithoutCoordinatesAsValidationError() {
        TopicCreateDTO dto = freeExploreDto(0);
        dto.setPublishMode("ai_simple");
        NodeDTO node = new NodeDTO();
        node.setName("未确认地点");
        ChapterDTO chapter = new ChapterDTO();
        chapter.setNodes(Arrays.asList(node));
        dto.setChapters(Arrays.asList(chapter));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        assertThrows(IllegalArgumentException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto, 42L, "tester"));
    }

    @Test
    void createAiSimpleTopic_rejectsGameConfigurationEvenWhenLocationIsConfirmed() {
        TopicCreateDTO dto = freeExploreDto(0);
        dto.setPublishMode("ai_simple");
        NodeDTO node = new NodeDTO();
        node.setName("已确认地点");
        node.setAddress("上海市静安区");
        node.setLongitude("121.45");
        node.setLatitude("31.23");
        node.setTemplateId(8L);
        ChapterDTO chapter = new ChapterDTO();
        chapter.setNodes(Arrays.asList(node));
        dto.setChapters(Arrays.asList(chapter));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        assertThrows(IllegalArgumentException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).createTopic(dto, 42L, "tester"));
    }
}
