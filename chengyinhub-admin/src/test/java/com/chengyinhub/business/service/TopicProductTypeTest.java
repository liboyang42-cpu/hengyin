package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.dto.ActivityTicketRequest;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.TopicCreateDTO;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * P0-02/P0-03 产品真源上收 + 混票归一。
 *
 * <p>病:「这是哪种产品」没有任何字段能回答,唯一的「权威」oms_ticket.mode 语义是主题级、存储却在
 * 票级 ⇒ 混票是默认行为。后端自己也知道票之间 mode 可能不一致(建题时用的是
 * {@code tickets.anyMatch(mode==1)} 而不是 {@code get(0)}),却没拦。
 *
 * <p>修:主题定型 product_type,票 mode 降级为它的派生列 —— saveTickets 里**覆盖**而非透传。
 *
 * ⚠️ 关于测私有方法:本类用 ReflectionTestUtils 直接打 resolveProductType / saveTickets。
 *    saveTickets 由**编译器**兜底(签名新增 productType 参数,调用方不传就编译不过);
 *    但 `topic.setProductType(...)` 那一行编译器管不着 —— 删掉它照样编译、照样全绿。
 *    那条由 {@link TopicProductTypeWiringTest} 守(评审用 mutation 实测过本类漏了它)。
 *
 * ⚠️ 负控已实测:
 *    - 把 saveTickets 里的 setMode 那行删掉 ⇒ ticketModeOverriddenByProductType 系列红
 *    - 把 setMode 挪到 copyProperties **之前** ⇒ 同样红(会被 DTO 值盖回去)
 *    - 混票分支改回 throw ⇒ mixedTicketsNormalizedToEntryMode 红
 */
class TopicProductTypeTest {

    @Test
    void publisherRevshareRequiresOrdinaryClubContentAndExplicitFeeCap() {
        com.chengyinhub.business.mapper.CmsTopicMapper topics = mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        IProductRoleService roles = mock(IProductRoleService.class);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(service, "productRoleService", roles);
        com.chengyinhub.business.domain.CmsTopic topic = new com.chengyinhub.business.domain.CmsTopic();
        topic.setId(31L); topic.setProductType(2);
        com.chengyinhub.business.domain.ProductRole role = new com.chengyinhub.business.domain.ProductRole();
        role.setEntityType("CLUB"); role.setEntityId(61L);
        org.mockito.Mockito.when(topics.selectCmsTopicById(31L)).thenReturn(topic);
        org.mockito.Mockito.when(roles.activeContentSlot(31L)).thenReturn(role);
        ChapterDTO chapter = new ChapterDTO(); chapter.setTermsMode("REVSHARE");
        chapter.setMaxPerHeadFee(new java.math.BigDecimal("0.01"));
        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(com.chengyinhub.business.service.support.TopicGraphTestWiring.guard(service), "assertChapterShapeForTopic", chapter, 31L));
        chapter.setMaxPerHeadFee(null);
        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(com.chengyinhub.business.service.support.TopicGraphTestWiring.guard(service), "assertChapterShapeForTopic", chapter, 31L));
        chapter.setMaxPerHeadFee(new java.math.BigDecimal("0.01"));
        role.setEntityType("MEMBER");
        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(com.chengyinhub.business.service.support.TopicGraphTestWiring.guard(service), "assertChapterShapeForTopic", chapter, 31L));
        role.setEntityType("CLUB"); topic.setCircleThemeCode("circle-test");
        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(com.chengyinhub.business.service.support.TopicGraphTestWiring.guard(service), "assertChapterShapeForTopic", chapter, 31L));
    }

    private CmsTopicServiceImpl service;
    private OmsTicketMapper ticketMapper;
    private ISysCategoryService categoryService;

    @BeforeEach
    void setUp() {
        service = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ticketMapper = mock(OmsTicketMapper.class);
        categoryService = mock(ISysCategoryService.class);
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "sysCategoryService", categoryService);
    }

    private ActivityTicketRequest ticket(String name, Integer mode) {
        ActivityTicketRequest t = new ActivityTicketRequest();
        t.setName(name);
        t.setMode(mode);
        t.setTotalStock(10);
        t.setStartTime(new Date(1_800_000_000_000L));
        t.setEndTime(new Date(1_800_003_600_000L));
        return t;
    }

    private Integer resolveProductType(TopicCreateDTO dto) {
        return ReflectionTestUtils.invokeMethod(service, "resolveProductType", dto);
    }

    private List<OmsTicket> savedTickets(List<ActivityTicketRequest> tickets, Integer productType) {
        ReflectionTestUtils.invokeMethod(service, "saveTickets", tickets, 100L, 7L, "tester", productType, false);
        ArgumentCaptor<OmsTicket> captor = ArgumentCaptor.forClass(OmsTicket.class);
        verify(ticketMapper, org.mockito.Mockito.atLeastOnce()).insertOmsTicket(captor.capture());
        return captor.getAllValues();
    }

    private void assertChapterShape(ChapterDTO chapter) {
        ReflectionTestUtils.invokeMethod(com.chengyinhub.business.service.support.TopicGraphTestWiring.guard(service), "assertChapterShape", chapter);
    }

    // ------------------------------------------------ resolveProductType:定型

    /** 前端显式传 productType ⇒ 以它为准,票里的 mode 一概不看(哪怕票说的是另一个)。 */
    @Test
    void explicitProductTypeWins() {
        TopicCreateDTO dto = new TopicCreateDTO();
        dto.setProductType(ProductType.FREE_EXPLORE);
        dto.setTickets(Collections.singletonList(ticket("票", 1)));   // 票说 1,主题说 2

        assertEquals(Integer.valueOf(ProductType.FREE_EXPLORE), resolveProductType(dto),
                "显式主题真源必须压过票里的 mode");
    }

    /** 创建入口的经典定向门禁也必须读 productType,不能再绕回票的派生 mode。 */
    @Test
    void topicCreateGateReadsProductTypeResolver() throws Exception {
        String source = WiringSource.read("chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiTopicController.java");
        assertTrue(source.contains("TopicProductTypeResolver.resolve(dto)"),
                "创建门禁必须与服务层共享 productType 解析器");
        assertTrue(!source.contains("PlayMode.isLinear(t.getMode())"),
                "创建门禁不得从票的 mode 反推主题模式");
    }

    /** 旧版前端没传 ⇒ 从票推断(全票一致)。 */
    @Test
    void derivesFromTicketsWhenNotSupplied() {
        TopicCreateDTO dto = new TopicCreateDTO();
        dto.setTickets(Arrays.asList(ticket("a", 2), ticket("b", 2)));

        assertEquals(Integer.valueOf(ProductType.FREE_EXPLORE), resolveProductType(dto));
    }

    /**
     * 混票且没传主题真源 ⇒ **取入口模式(tickets[0])归一,不拒**。
     *
     * <p>初版写的是抛异常,评审证伪了:小程序走微信审核独立发布、后端合并即自动部署,
     * 「新后端 + 旧版小程序」是结构性存在数天的窗口。旧版 fabu 从自由定向入口加第二张票就是混票
     * 且不传 productType ⇒ 抛异常会让那几天自由定向多票主题一条都发不出去。
     *
     * <p>取 tickets[0] 是因为新旧两版的 applyEntryMode 都把它设成用户选的入口模式 ——
     * 它携带真实意图。这比旧代码的 anyMatch(mode==1) 更准(那个会把自由定向入口判成经典定向)。
     */
    @Test
    void mixedTicketsNormalizedToEntryMode() {
        TopicCreateDTO dto = new TopicCreateDTO();
        dto.setTickets(Arrays.asList(ticket("自由(入口)", 2), ticket("旧版加的", 1)));

        assertEquals(Integer.valueOf(ProductType.FREE_EXPLORE), resolveProductType(dto),
                "混票要按入口模式(tickets[0])归一,不能拒 —— 旧版小程序必造混票,拒了就发不出去");
    }

    /** 反方向同样成立:经典入口 + 混进来的自由票 ⇒ 归一为经典。 */
    @Test
    void mixedTicketsNormalizedToEntryMode_orienteering() {
        TopicCreateDTO dto = new TopicCreateDTO();
        dto.setTickets(Arrays.asList(ticket("经典(入口)", 1), ticket("混进来的", 2)));

        assertEquals(Integer.valueOf(ProductType.ORIENTEERING), resolveProductType(dto));
    }

    /** tickets[0] 的 mode 为空时,取第一张有 mode 的票(别因为首张没填就整单退化)。 */
    @Test
    void mixedTicketsFirstModeNull_takesFirstNonNull() {
        TopicCreateDTO dto = new TopicCreateDTO();
        dto.setTickets(Arrays.asList(ticket("没填", null), ticket("自由", 2), ticket("经典", 1)));

        assertEquals(Integer.valueOf(ProductType.FREE_EXPLORE), resolveProductType(dto));
    }

    /** 混票但显式传了主题真源 ⇒ 以显式值为准,连 tickets[0] 都不看。 */
    @Test
    void mixedTicketsAcceptedWhenProductTypeExplicit() {
        TopicCreateDTO dto = new TopicCreateDTO();
        dto.setProductType(ProductType.ORIENTEERING);
        dto.setTickets(Arrays.asList(ticket("经典", 1), ticket("自由", 2)));

        assertEquals(Integer.valueOf(ProductType.ORIENTEERING), resolveProductType(dto));
    }

    /** 无票(草稿 / 纯自玩主题)⇒ 经典定向,与 PlayMode 契约的历史兜底同口径。 */
    @Test
    void noTicketsDefaultsToOrienteering() {
        TopicCreateDTO dto = new TopicCreateDTO();
        dto.setTickets(new ArrayList<>());

        assertEquals(Integer.valueOf(ProductType.ORIENTEERING), resolveProductType(dto));
    }

    /** 票有但 mode 全空 ⇒ 同上兜底,不得抛。 */
    @Test
    void ticketsWithNullModesDefaultToOrienteering() {
        TopicCreateDTO dto = new TopicCreateDTO();
        dto.setTickets(Arrays.asList(ticket("a", null), ticket("b", null)));

        assertEquals(Integer.valueOf(ProductType.ORIENTEERING), resolveProductType(dto));
    }

    /** 显式产品类型非法必须 fail-closed，不能借票的 mode 偷渡未知产品。 */
    @Test
    void invalidExplicitProductTypeRejected() {
        TopicCreateDTO dto = new TopicCreateDTO();
        dto.setProductType(99);
        dto.setTickets(Collections.singletonList(ticket("自由", 2)));

        assertThrows(IllegalArgumentException.class, () -> resolveProductType(dto));
    }

    /** 兼容旧客户端时，票里也只能出现两个已知产品类型。 */
    @Test
    void invalidLegacyTicketModeRejected() {
        TopicCreateDTO dto = new TopicCreateDTO();
        dto.setTickets(Collections.singletonList(ticket("伪造模式", 999)));

        assertThrows(IllegalArgumentException.class, () -> resolveProductType(dto));
    }

    /** 开放商家承接的章节才是可选业务单元；类别必须是受控商家品类，0 是明确的不限名额。 */
    @Test
    void recruitmentChapterRequiresControlledProfile() {
        SysCategory category = new SysCategory();
        category.setId(18L);
        category.setType(1L);
        category.setParentId(0L);
        category.setStatus(1);
        category.setDelFlag(0);
        org.mockito.Mockito.when(categoryService.selectSysCategoryById(18L)).thenReturn(category);

        ChapterDTO chapter = new ChapterDTO();
        chapter.setRecruitEnabled(1);
        chapter.setTermsMode("PERK");
        chapter.setCategoryId(18);
        chapter.setMaxMerchant(2);

        assertDoesNotThrow(() -> assertChapterShape(chapter));

        chapter.setCategoryId(null);
        IllegalStateException missingCategory = assertThrows(IllegalStateException.class,
                () -> assertChapterShape(chapter));
        assertTrue(missingCategory.getMessage().contains("商家品类"));

        chapter.setCategoryId(18);
        chapter.setMaxMerchant(-1);
        IllegalStateException invalidCapacity = assertThrows(IllegalStateException.class,
                () -> assertChapterShape(chapter));
        assertTrue(invalidCapacity.getMessage().contains("0到127"));

        chapter.setMaxMerchant(0);
        assertDoesNotThrow(() -> assertChapterShape(chapter), "0 是明确的不限商家名额");

        chapter.setRecruitEnabled(0);
        chapter.setCategoryId(null);
        chapter.setMaxMerchant(null);
        assertDoesNotThrow(() -> assertChapterShape(chapter), "未开放承接的章节仍可作为路线分组");
    }

    // ------------------------------------------------ saveTickets:派生覆盖

    /**
     * 核心断言:票 mode 由主题 product_type **覆盖**,DTO 传什么都不算数。
     * 这条成立后,前端 addTicket/openTicketEditor 硬编码 mode:1 也造不出混票了。
     */
    @Test
    void ticketModeOverriddenByProductType() {
        List<OmsTicket> saved = savedTickets(
                Arrays.asList(ticket("a", 1), ticket("b", null), ticket("c", 2)),   // DTO 里三种 mode
                ProductType.FREE_EXPLORE);

        assertEquals(3, saved.size());
        for (OmsTicket t : saved) {
            assertEquals(Integer.valueOf(ProductType.FREE_EXPLORE), t.getMode(),
                    "票 mode 必须被主题 product_type 覆盖,票[" + t.getName() + "] 却是 " + t.getMode());
        }
    }

    /** 经典定向方向同样成立(别只在一个方向对)。 */
    @Test
    void ticketModeOverriddenToOrienteering() {
        List<OmsTicket> saved = savedTickets(
                Arrays.asList(ticket("a", 2), ticket("b", 2)),
                ProductType.ORIENTEERING);

        for (OmsTicket t : saved) {
            assertEquals(Integer.valueOf(ProductType.ORIENTEERING), t.getMode());
        }
    }

    /** 覆盖 mode 不得把票的其他字段搞坏(确认 setMode 放在 copyProperties 之后但没盖别的)。 */
    @Test
    void overridingModeKeepsOtherTicketFields() {
        List<OmsTicket> saved = savedTickets(
                Collections.singletonList(ticket("自由定向票", 1)),
                ProductType.FREE_EXPLORE);

        OmsTicket t = saved.get(0);
        assertEquals("自由定向票", t.getName());
        assertEquals(Long.valueOf(10L), t.getTotalInventory());
        assertEquals(Long.valueOf(10L), t.getRemainingInventory());
        assertEquals(Long.valueOf(1L), t.getOwnerType());
        assertEquals(Long.valueOf(100L), t.getOwnerId());
    }
}
