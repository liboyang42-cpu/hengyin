package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.mapper.ProductRoleMapper;
import com.chengyinhub.business.service.impl.ProductRoleServiceImpl;
import com.chengyinhub.common.constant.ProductSlot;
import com.chengyinhub.common.constant.RoleEntityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-35 角色槽(D6)。
 *
 * <p><b>这张表存在的理由</b>:在它之前,「这个主题是不是自营」在代码里**没有任何一列直说** ——
 * 最接近的是 {@code CmsTopicServiceImpl:208} 那句 {@code setMemberId(100000L)},
 * 一个全仓只出现一次、无名无注释的裸魔数。而 ③-自营 是首轮内测标的(D12),
 * 且票款归属就挂在内容槽上(D8)。这与 P0-02 治好的病同源:别再用组合约定反推事实。
 *
 * <p><b>本类的重点是 {@code isPlatformOwned} 的 fail-closed 语义</b> —— 它是 P0-50 REVSHARE
 * 范围闸的判据,而那道闸拦的是 ③-第三方 的第三方间分钱 = **二清**。判错方向的代价不对称:
 * 错拒 = BD 打个电话改个配置;错放 = 合规事故。
 *
 * ⚠️ 库级约束(双生效 CONTENT 撞 1062、标失效后能换人、PLATFORM 不得带 entityId、
 * 非法 slot 被 CHECK 拒)已在**真 MySQL 8** 上实测,不在本类范围 —— 见迁移文件头。
 *
 * ⚠️ 负控已实测:
 *  - isPlatformOwned 的 null 判断去掉(改成只比 entityType)⇒ NPE,noContentSlot_* 红
 *  - fail-closed 反向(查不到当自营)⇒ noContentSlot_notPlatformOwned 红
 *  - declareContentSlot 的 entityIdConsistent 校验删掉 ⇒ platformMustNotCarryEntityId 红
 */
class ProductRoleServiceTest {

    private static final Long TOPIC = 900L;

    private ProductRoleMapper mapper;
    private ProductRoleServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(ProductRoleMapper.class);
        when(mapper.insertProductRole(any(ProductRole.class))).thenReturn(1);
        service = new ProductRoleServiceImpl();
        ReflectionTestUtils.setField(service, "productRoleMapper", mapper);
    }

    private void givenContentSlot(String entityType, Long entityId) {
        ProductRole r = new ProductRole();
        r.setTopicId(TOPIC);
        r.setSlot(ProductSlot.CONTENT);
        r.setEntityType(entityType);
        r.setEntityId(entityId);
        r.setStatus(1);
        when(mapper.selectActiveByTopicAndSlot(TOPIC, ProductSlot.CONTENT))
                .thenReturn(new ArrayList<ProductRole>(Collections.singletonList(r)));
        when(mapper.deactivateByTopicAndSlot(TOPIC, ProductSlot.CONTENT)).thenReturn(1);
    }

    // ---------------------------------------------------- isPlatformOwned:P0-50 范围闸的判据

    /** 内容槽 = PLATFORM ⇒ 自营 ⇒ REVSHARE 可用。 */
    @Test
    void platformContentSlot_isPlatformOwned() {
        givenContentSlot(RoleEntityType.PLATFORM, null);
        assertTrue(service.isPlatformOwned(TOPIC));
    }

    /**
     * <b>fail-closed 的核心</b>:查不到内容槽 ⇒ **不是自营**。
     *
     * <p>存量主题一行都没有(迁移刻意不回填:「判不清人工确认,禁静默回填」)⇒ 它们的 REVSHARE
     * 全部被拒。这是**故意的**,不是遗漏 —— 反过来(查不到就当自营)会让每一个存量主题
     * 都拿到计酬资格,而其中任何一个第三方主题用了 REVSHARE 就是二清。
     */
    @Test
    void noContentSlot_notPlatformOwned() {
        when(mapper.selectActiveByTopicAndSlot(anyLong(), anyString()))
                .thenReturn(new ArrayList<ProductRole>());
        assertFalse(service.isPlatformOwned(TOPIC), "查不到内容槽必须当作「不是自营」—— 错放的代价是合规事故");
    }

    /** mapper 返 null(而不是空表)⇒ 同样 fail-closed,不 NPE。 */
    @Test
    void nullResult_notPlatformOwned() {
        when(mapper.selectActiveByTopicAndSlot(anyLong(), anyString())).thenReturn(null);
        assertFalse(service.isPlatformOwned(TOPIC));
        assertNull(service.activeContentSlot(TOPIC));
    }

    /** topicId 为 null ⇒ fail-closed,且不去查库。 */
    @Test
    void nullTopic_notPlatformOwnedAndNoQuery() {
        assertFalse(service.isPlatformOwned(null));
        verify(mapper, never()).selectActiveByTopicAndSlot(anyLong(), anyString());
    }

    /** 其余三类主体都**不是**自营 —— 逐个钉住,别让将来加枚举时漏掉一个。 */
    @Test
    void nonPlatformContentSlot_notPlatformOwned() {
        for (String t : Arrays.asList(RoleEntityType.MEMBER, RoleEntityType.CLUB, RoleEntityType.MERCHANT)) {
            givenContentSlot(t, 7L);
            assertFalse(service.isPlatformOwned(TOPIC), t + " 不是平台自营 —— 它的计酬 = 第三方间分钱 = 二清");
        }
    }

    // ---------------------------------------------------- declareContentSlot:写入

    /** 正常声明:槽/类型/主体/生效态都对。 */
    @Test
    void declarePlatformSlot_writesRow() {
        service.declareContentSlot(TOPIC, RoleEntityType.PLATFORM, null);

        ArgumentCaptor<ProductRole> cap = ArgumentCaptor.forClass(ProductRole.class);
        verify(mapper).insertProductRole(cap.capture());
        ProductRole r = cap.getValue();
        assertEquals(TOPIC, r.getTopicId());
        assertEquals(ProductSlot.CONTENT, r.getSlot());
        assertEquals(RoleEntityType.PLATFORM, r.getEntityType());
        assertNull(r.getEntityId(), "PLATFORM 不是某个 member,entityId 必须为空");
        assertEquals(Integer.valueOf(1), r.getStatus());
    }

    /** 俱乐部内容槽带 clubId。 */
    @Test
    void declareClubSlot_writesEntityId() {
        service.declareContentSlot(TOPIC, RoleEntityType.CLUB, 42L);

        ArgumentCaptor<ProductRole> cap = ArgumentCaptor.forClass(ProductRole.class);
        verify(mapper).insertProductRole(cap.capture());
        assertEquals(RoleEntityType.CLUB, cap.getValue().getEntityType());
        assertEquals(Long.valueOf(42L), cap.getValue().getEntityId());
    }

    @Test
    void saleFreezeWritesAndReadsBackTheExecutingClubDistributionSlot() {
        ProductRole stored = new ProductRole();
        stored.setId(19L);
        stored.setTopicId(TOPIC);
        stored.setSlot(ProductSlot.DISTRIBUTION);
        stored.setEntityType(RoleEntityType.CLUB);
        stored.setEntityId(42L);
        stored.setStatus(1);
        stored.setDeclaredBy(71L);
        when(mapper.selectActiveByTopicAndSlot(TOPIC, ProductSlot.DISTRIBUTION))
                .thenReturn(Collections.<ProductRole>emptyList(), Collections.singletonList(stored));

        ProductRole result = service.declareDistributionSlotForSale(TOPIC, 42L, 71L);

        assertEquals(19L, result.getId());
        ArgumentCaptor<ProductRole> cap = ArgumentCaptor.forClass(ProductRole.class);
        verify(mapper).insertProductRole(cap.capture());
        assertEquals(ProductSlot.DISTRIBUTION, cap.getValue().getSlot());
        assertEquals(RoleEntityType.CLUB, cap.getValue().getEntityType());
        assertEquals(Long.valueOf(42L), cap.getValue().getEntityId());
        assertEquals(Long.valueOf(71L), cap.getValue().getDeclaredBy());
    }

    @Test
    void saleDistributionReplayReadsBackTheSameAuditedRowWithoutDuplicateInsert() {
        ProductRole stored = distribution(42L, 71L);
        when(mapper.selectActiveByTopicAndSlot(TOPIC, ProductSlot.DISTRIBUTION))
                .thenReturn(Collections.singletonList(stored));

        assertEquals(stored, service.declareDistributionSlotForSale(TOPIC, 42L, 99L));
        verify(mapper, never()).insertProductRole(any(ProductRole.class));
    }

    @Test
    void conflictingSaleDistributionFailsClosedWithoutReassignment() {
        when(mapper.selectActiveByTopicAndSlot(TOPIC, ProductSlot.DISTRIBUTION))
                .thenReturn(Collections.singletonList(distribution(43L, 71L)));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.declareDistributionSlotForSale(TOPIC, 42L, 71L));

        assertEquals("SALE_DISTRIBUTION_CONFLICT", error.getMessage());
        verify(mapper, never()).insertProductRole(any(ProductRole.class));
        verify(mapper, never()).deactivateByTopicAndSlot(TOPIC, ProductSlot.DISTRIBUTION);
    }

    @Test
    void multipleActiveSaleDistributionsFailClosedWithoutPickingOne() {
        when(mapper.selectActiveByTopicAndSlot(TOPIC, ProductSlot.DISTRIBUTION))
                .thenReturn(Arrays.asList(distribution(42L, 71L), distribution(42L, 71L)));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.declareDistributionSlotForSale(TOPIC, 42L, 71L));

        assertEquals("SALE_DISTRIBUTION_CONFLICT", error.getMessage());
        verify(mapper, never()).insertProductRole(any(ProductRole.class));
    }

    private static ProductRole distribution(Long clubId, Long declaredBy) {
        ProductRole stored = new ProductRole();
        stored.setId(19L);
        stored.setTopicId(TOPIC);
        stored.setSlot(ProductSlot.DISTRIBUTION);
        stored.setEntityType(RoleEntityType.CLUB);
        stored.setEntityId(clubId);
        stored.setStatus(1);
        stored.setDeclaredBy(declaredBy);
        return stored;
    }

    /**
     * <b>PLATFORM 带 entityId = 自相矛盾,必须拒</b>(库有 ck_role_entity_id 兜底,这里先拦是为了
     * 给出能定位的日志而不是吃一个 3819)。
     * <p>为什么较真:票款归属挂在这个槽上(D8)。一行「PLATFORM 但 entity_id=123」的数据,
     * 到底该把钱付给平台还是 123 号?没有答案 —— 所以它根本不该存在。
     */
    @Test
    void platformMustNotCarryEntityId() {
        service.declareContentSlot(TOPIC, RoleEntityType.PLATFORM, 123L);
        verify(mapper, never()).insertProductRole(any(ProductRole.class));
    }

    /** 非 PLATFORM 不带 entityId ⇒ 同样拒(「是个玩家,但不知道是哪个」不是有效声明)。 */
    @Test
    void nonPlatformMustCarryEntityId() {
        service.declareContentSlot(TOPIC, RoleEntityType.MEMBER, null);
        verify(mapper, never()).insertProductRole(any(ProductRole.class));
    }

    /** 非法或不允许的内容主体类型 ⇒ 拒,不写脏行。 */
    @Test
    void invalidEntityType_rejected() {
        service.declareContentSlot(TOPIC, "BOGUS", 1L);
        service.declareContentSlot(TOPIC, RoleEntityType.MERCHANT, 42L);
        verify(mapper, never()).insertProductRole(any(ProductRole.class));
    }

    /** 商家可承接供给,但绝不能成为内容/票款归属主体。 */
    @Test
    void declareMerchantContentSlot_rejectedBeforeWrite() {
        service.declareContentSlot(TOPIC, RoleEntityType.MERCHANT, 77L);

        verify(mapper, never()).insertProductRole(any(ProductRole.class));
    }

    /** topicId 为 null ⇒ 不写。 */
    @Test
    void nullTopicId_noWrite() {
        service.declareContentSlot(null, RoleEntityType.PLATFORM, null);
        verify(mapper, never()).insertProductRole(any(ProductRole.class));
    }

    /**
     * 落库抛异常(比如撞唯一键 = 重复建题)**不能把建题炸掉**,但必须留声。
     * <p>槽表是票款归属的真源,这里静默失败 = 归属说不清 ⇒ 只能 log.error,不能 log.debug。
     */
    @Test
    void insertThrows_doesNotPropagate() {
        when(mapper.insertProductRole(any(ProductRole.class))).thenThrow(new RuntimeException("1062"));
        service.declareContentSlot(TOPIC, RoleEntityType.PLATFORM, null);   // 不抛
    }

    // ---------------------------------------------------- reassignContentSlot:后台改判

    /**
     * 改判 = **先失效再插**,顺序不能反,且旧行**不删**(留痕:归属从谁变成了谁)。
     * <p>顺序反了(先插再失效)会撞唯一键 —— 库里同主题只允许一个生效 CONTENT 槽。
     */
    @Test
    void reassign_deactivatesThenInserts() {
        givenContentSlot(RoleEntityType.MEMBER, 9L);

        assertTrue(service.reassignContentSlot(TOPIC, RoleEntityType.PLATFORM, null));

        org.mockito.InOrder o = org.mockito.Mockito.inOrder(mapper);
        o.verify(mapper).deactivateByTopicAndSlot(TOPIC, ProductSlot.CONTENT);
        o.verify(mapper).insertProductRole(any(ProductRole.class));
        // 旧行永不删:ProductRoleMapper **刻意没有 delete 方法** —— 归属变更史是审计证据,
        // 删了就再也答不出「当初这张票的钱该归谁」。这条由接口的形状保证,不是靠自觉。
        for (java.lang.reflect.Method m : ProductRoleMapper.class.getMethods()) {
            assertFalse(m.getName().toLowerCase().contains("delete"),
                    "ProductRoleMapper 不该有 delete 方法,发现:" + m.getName()
                            + " —— 旧内容槽是钱归属的变更史,删不得");
        }
    }

    /** 改判后落的是新主体。 */
    @Test
    void reassign_writesNewEntity() {
        givenContentSlot(RoleEntityType.MEMBER, 9L);
        service.reassignContentSlot(TOPIC, RoleEntityType.PLATFORM, null);

        ArgumentCaptor<ProductRole> cap = ArgumentCaptor.forClass(ProductRole.class);
        verify(mapper).insertProductRole(cap.capture());
        assertEquals(RoleEntityType.PLATFORM, cap.getValue().getEntityType());
        assertNull(cap.getValue().getEntityId());
        assertEquals(Integer.valueOf(1), cap.getValue().getStatus());
    }

    /**
     * <b>参数自相矛盾时必须什么都不做</b> —— 尤其**不能先把旧槽失效掉**。
     *
     * <p>若先失效再校验,一个手滑的请求就会让这个主题**没有内容槽**:票款归属凭空消失,
     * 而 isPlatformOwned 会 fail-closed 把它当第三方 —— 一次静默降级,没人会发现。
     */
    @Test
    void reassign_invalidArgs_touchesNothing() {
        givenContentSlot(RoleEntityType.MEMBER, 9L);

        assertFalse(service.reassignContentSlot(TOPIC, RoleEntityType.PLATFORM, 123L), "PLATFORM 不得带 entityId");
        assertFalse(service.reassignContentSlot(TOPIC, RoleEntityType.MEMBER, null), "MEMBER 必须带 entityId");
assertFalse(service.reassignContentSlot(TOPIC, RoleEntityType.MERCHANT, 77L),
                "商家只能承接供给,不能成为内容或票款归属主体");
        assertFalse(service.reassignContentSlot(TOPIC, "BOGUS", 1L), "类型非法");
        assertFalse(service.reassignContentSlot(null, RoleEntityType.PLATFORM, null), "topicId 为空");

        verify(mapper, never()).deactivateByTopicAndSlot(anyLong(), anyString());
        verify(mapper, never()).insertProductRole(any(ProductRole.class));
    }

    /** 存量主题(本来没有槽)也能直接改判 —— 这正是首轮回填的用法。 */
    @Test
    void reassign_fromNoSlot_works() {
        when(mapper.selectActiveByTopicAndSlot(anyLong(), anyString()))
                .thenReturn(new ArrayList<ProductRole>());
        assertTrue(service.reassignContentSlot(TOPIC, RoleEntityType.PLATFORM, null));
        verify(mapper).insertProductRole(any(ProductRole.class));
    }


    @Test
    void reassign_insertFailureThrowsSoTheTransactionRollsBackTheOldSlot() {
        givenContentSlot(RoleEntityType.MEMBER, 9L);
        when(mapper.insertProductRole(any(ProductRole.class))).thenReturn(0);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.reassignContentSlot(TOPIC, RoleEntityType.PLATFORM, null));

        assertEquals("内容主体保存失败，请刷新后重试", error.getMessage());
    }

    @Test
    void reassign_changedActiveSlotCountThrowsBeforeInsert() {
        givenContentSlot(RoleEntityType.MEMBER, 9L);
        when(mapper.deactivateByTopicAndSlot(TOPIC, ProductSlot.CONTENT)).thenReturn(0);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.reassignContentSlot(TOPIC, RoleEntityType.PLATFORM, null));

        assertEquals("内容主体状态已变化，请刷新后重试", error.getMessage());
        verify(mapper, never()).insertProductRole(any(ProductRole.class));
    }

    // ---------------------------------------------------- 常量契约

    /** 单例槽与库里两条 generated guard 的圈定必须一致,否则库拦不住的应用层会以为拦得住。 */
    @Test
    void singletonSlots_matchDbGeneratedColumn() {
        assertTrue(ProductSlot.isSingleton(ProductSlot.CONTENT));
        assertTrue(ProductSlot.isSingleton(ProductSlot.ORGANIZER));
        assertFalse(ProductSlot.isSingleton(ProductSlot.SUPPLY), "一章多商家,供给槽必须可多行");
        assertTrue(ProductSlot.isSingleton(ProductSlot.DISTRIBUTION), "首三期只有唯一执行俱乐部");
    }

    /** entityIdConsistent 与库里 ck_role_entity_id 同口径。 */
    @Test
    void entityIdConsistency_matchesDbCheck() {
        assertTrue(RoleEntityType.entityIdConsistent(RoleEntityType.PLATFORM, null));
        assertFalse(RoleEntityType.entityIdConsistent(RoleEntityType.PLATFORM, 1L));
        assertTrue(RoleEntityType.entityIdConsistent(RoleEntityType.MEMBER, 1L));
        assertFalse(RoleEntityType.entityIdConsistent(RoleEntityType.MEMBER, null));
    }

    /** 四个槽 / 四种主体的合法性判定,别漏 PLATFORM(它是本次的重点)。 */
    @Test
    void validity() {
        assertTrue(ProductSlot.isValid(ProductSlot.SUPPLY));
        assertFalse(ProductSlot.isValid("BOGUS"));
        assertTrue(RoleEntityType.isValid(RoleEntityType.PLATFORM),
                "PLATFORM 是 ③-自营 的唯一表达方式,原枚举没有它");
        assertTrue(RoleEntityType.isContentOwner(RoleEntityType.PLATFORM));
        assertTrue(RoleEntityType.isContentOwner(RoleEntityType.MEMBER));
        assertTrue(RoleEntityType.isContentOwner(RoleEntityType.CLUB));
        assertFalse(RoleEntityType.isContentOwner(RoleEntityType.MERCHANT),
                "商家可作为通用角色主体,但不能成为内容或票款归属主体");
        assertFalse(RoleEntityType.isValid("PLAYER"), "旧枚举名 PLAYER 已不存在,别让它悄悄复活");
    }
}
