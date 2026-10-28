package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.dto.CmsTopicCreateDTO;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.common.constant.RoleEntityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Task 2 负控:后台发主题时【内容槽必须跟着发布者走】。
 *
 * <p>核心不变式(用户 2026-07-17 口述):「官方发的就是官方发的,俱乐部发的就是俱乐部发的。」
 * 定稿 §6.5-B:票款归内容槽持有者 ⇒ 槽判错 = 票款判给错的人 = 一笔错账。
 *
 * <p>为什么这些必须是负控而不是「信任调用方」:内容槽是票款归属的【唯一真源】,
 * 而它一旦落错,下游 REVSHARE / settleCoopTopic 全部照错的槽算钱,且不会报错。
 */
class TopicPublisherSlotTest {

    private CmsTopicServiceImpl svc;
    private CmsTopicMapper topicMapper;
    private IProductRoleService roleService;
    private IClubService clubService;

    @BeforeEach
    void setUp() {
        svc = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(svc, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        topicMapper = mock(CmsTopicMapper.class);
        roleService = mock(IProductRoleService.class);
        clubService = mock(IClubService.class);
        // insertCmsTopic 是「插入后回填自增 id」的 RuoYi 风格,模拟它
        doAnswer(inv -> {
            ((CmsTopic) inv.getArgument(0)).setId(700L);
            return 1;
        }).when(topicMapper).insertCmsTopic(any());
        ReflectionTestUtils.setField(svc, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(svc, "productRoleService", roleService);
        ReflectionTestUtils.setField(svc, "clubService", clubService);
        ReflectionTestUtils.setField(svc, "platformMemberId", 100000L);
        com.chengyinhub.business.domain.ProductRole persistedPlatformSlot =
                new com.chengyinhub.business.domain.ProductRole();
        persistedPlatformSlot.setEntityType(RoleEntityType.PLATFORM);
        org.mockito.Mockito.when(roleService.activeContentSlot(700L)).thenReturn(persistedPlatformSlot);
        Club club = new Club();
        club.setId(55L);
        club.setMemberId(88L);
        org.mockito.Mockito.when(clubService.selectClubByIdForUpdate(55L)).thenReturn(club);
    }

    private CmsTopicCreateDTO req(String type, Long publisherId, Long payee) {
        CmsTopicCreateDTO r = new CmsTopicCreateDTO();
        r.setName("测试主题");
        r.setPublisherType(type);
        r.setPublisherId(publisherId);
        r.setPayeeMemberId(payee);
        return r;
    }

    // ── 正控:三种发布者各自落对槽 ────────────────────────────────────

    @Test
    void platformPublisherGetsPlatformSlotWithNullEntityId() {
        com.chengyinhub.business.service.support.TopicCopyTestWiring.wire(svc).createTopicByPublisher(req(RoleEntityType.PLATFORM, null, null), "operator");
        // PLATFORM 的 entity_id 必须为 NULL —— 平台不是某个 member,库里有 ck_role_entity_id 兜底
        verify(roleService).declareContentSlot(eq(700L), eq(RoleEntityType.PLATFORM), eq(null));
    }


    @Test
    void platformPublisherFailsClosedWhenContentSlotDoesNotPersist() {
        org.mockito.Mockito.when(roleService.activeContentSlot(700L)).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> com.chengyinhub.business.service.support.TopicCopyTestWiring.wire(svc).createTopicByPublisher(req(RoleEntityType.PLATFORM, null, null), "operator"));

        verify(topicMapper).insertCmsTopic(any());
    }

    @Test
    void clubPublisherGetsClubSlotAndOwnerIsTheClubNotPlatform() {
        com.chengyinhub.business.service.support.TopicCopyTestWiring.wire(svc).createTopicByPublisher(req(RoleEntityType.CLUB, 55L, 88L), "operator");
        // ★ 本轨的核心:俱乐部发的就是俱乐部发的,不该落成 PLATFORM
        verify(roleService).declareContentSlot(eq(700L), eq(RoleEntityType.CLUB), eq(55L));
        verify(roleService, never()).declareContentSlot(anyLong(), eq(RoleEntityType.PLATFORM), any());
        verify(clubService).selectClubByIdForUpdate(55L);
    }

    @Test
    void dissolvedClubPublisherIsRejectedBeforeTopicInsert() {
        org.mockito.Mockito.when(clubService.selectClubByIdForUpdate(55L)).thenReturn(null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> com.chengyinhub.business.service.support.TopicCopyTestWiring.wire(svc).createTopicByPublisher(req(RoleEntityType.CLUB, 55L, 88L), "operator"));

        assertTrue(error.getMessage().contains("已解散"));
        verify(topicMapper, never()).insertCmsTopic(any());
    }

    @Test
    void memberPublisherGetsMemberSlot() {
        com.chengyinhub.business.service.support.TopicCopyTestWiring.wire(svc).createTopicByPublisher(req(RoleEntityType.MEMBER, 88L, null), "operator");
        verify(roleService).declareContentSlot(eq(700L), eq(RoleEntityType.MEMBER), eq(88L));
    }

    // ── 负控 1:发布者没声明 → 必须拒 ────────────────────────────────

    @Test
    void missingPublisherTypeIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> com.chengyinhub.business.service.support.TopicCopyTestWiring.wire(svc).createTopicByPublisher(req(null, null, 88L), "operator"));
        assertTrue(e.getMessage().contains("publisherType"), "应点明缺的是发布者声明,实际:" + e.getMessage());
        verify(topicMapper, never()).insertCmsTopic(any());
        verify(roleService, never()).declareContentSlot(anyLong(), anyString(), any());
    }

    /** 商家不占内容槽:商家经报名进【供给槽】,放行 MERCHANT 等于给商家开发主题的后门 */
    @Test
    void merchantPublisherIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> com.chengyinhub.business.service.support.TopicCopyTestWiring.wire(svc).createTopicByPublisher(req(RoleEntityType.MERCHANT, 99L, 99L), "operator"));
        verify(topicMapper, never()).insertCmsTopic(any());
    }

    @Test
    void unknownPublisherTypeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> com.chengyinhub.business.service.support.TopicCopyTestWiring.wire(svc).createTopicByPublisher(req("GOVERNMENT", 1L, 1L), "operator"));
    }

    // ── 负控 2:发布者声明自相矛盾 → 必须拒(与库里 ck_role_entity_id 同口径)────

    @Test
    void platformWithEntityIdIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> com.chengyinhub.business.service.support.TopicCopyTestWiring.wire(svc).createTopicByPublisher(req(RoleEntityType.PLATFORM, 55L, null), "operator"));
        assertTrue(e.getMessage().contains("publisherId"));
        verify(topicMapper, never()).insertCmsTopic(any());
    }

    @Test
    void clubWithoutPublisherIdIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> com.chengyinhub.business.service.support.TopicCopyTestWiring.wire(svc).createTopicByPublisher(req(RoleEntityType.CLUB, null, 88L), "operator"));
        verify(topicMapper, never()).insertCmsTopic(any());
    }

    // ── 负控 3:收款人无从确定 → 必须拒(否则发布者分润【静默不打款】)────

    /**
     * ★ 俱乐部不是一个 ums_member,收不了款。distributePublisherCommission 打的是 topic.memberId,
     * 落空就静默不打款 —— 「发布者分润没到账」是查不出来的那类 bug,所以宁可在入口就拒。
     */
    @Test
    void clubWithoutPayeeMemberIdIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> com.chengyinhub.business.service.support.TopicCopyTestWiring.wire(svc).createTopicByPublisher(req(RoleEntityType.CLUB, 55L, null), "operator"));
        assertTrue(e.getMessage().contains("payeeMemberId") || e.getMessage().contains("主理人"),
                "应点明俱乐部必须给主理人,实际:" + e.getMessage());
        verify(topicMapper, never()).insertCmsTopic(any());
    }

    // ── 归属落到 topic 行上 ──────────────────────────────────────────

    @Test
    void payeeAndClubIdLandOnTheTopicRow() {
        org.mockito.ArgumentCaptor<CmsTopic> c = org.mockito.ArgumentCaptor.forClass(CmsTopic.class);
        com.chengyinhub.business.service.support.TopicCopyTestWiring.wire(svc).createTopicByPublisher(req(RoleEntityType.CLUB, 55L, 88L), "operator");
        verify(topicMapper).insertCmsTopic(c.capture());

        assertEquals(Long.valueOf(88L), c.getValue().getMemberId(), "票款收款人 = 俱乐部主理人");
        assertEquals(Long.valueOf(55L), c.getValue().getClubId());
        // 后台建的题不该自带特权:审核/上架/生命周期一律从头走
        assertEquals(Integer.valueOf(0), c.getValue().getStatus());
        assertEquals(Integer.valueOf(0), c.getValue().getUserStatus());
        assertEquals(Integer.valueOf(0), c.getValue().getLifecycle());
        assertEquals(Integer.valueOf(0), c.getValue().getIsSettlement());
    }

    @Test
    void platformPayeeFallsBackToConfiguredPlatformAccountNotAMagicLiteral() {
        org.mockito.ArgumentCaptor<CmsTopic> c = org.mockito.ArgumentCaptor.forClass(CmsTopic.class);
        ReflectionTestUtils.setField(svc, "platformMemberId", 12345L);   // 换个值证明它真读配置
        com.chengyinhub.business.service.support.TopicCopyTestWiring.wire(svc).createTopicByPublisher(req(RoleEntityType.PLATFORM, null, null), "operator");
        verify(topicMapper).insertCmsTopic(c.capture());

        assertEquals(Long.valueOf(12345L), c.getValue().getMemberId(),
                "平台收款账号应来自配置,不是写死的 100000");
        assertNull(c.getValue().getClubId());
    }


    @Test
    void platformPublisherCannotOverrideConfiguredPayee() {
        CmsTopicCreateDTO request = req(RoleEntityType.PLATFORM, null, 7788L);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> com.chengyinhub.business.service.support.TopicCopyTestWiring.wire(svc).createTopicByPublisher(request, "operator"));

        assertTrue(error.getMessage().contains("payeeMemberId"));
        verify(topicMapper, never()).insertCmsTopic(any());
    }
}
