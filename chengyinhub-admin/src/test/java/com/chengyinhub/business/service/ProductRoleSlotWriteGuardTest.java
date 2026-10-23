package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.mapper.ProductRoleMapper;
import com.chengyinhub.business.service.impl.ProductRoleServiceImpl;
import com.chengyinhub.common.constant.ProductSlot;
import com.chengyinhub.common.constant.RoleEntityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * O-15 角色槽写入闸:slot 是封闭取值,未知槽在写之前以机器码 {@link ProductSlot#ERR_SLOT_INVALID} 拒绝;
 * 读路径不校验,存量越界行照常可读。
 *
 * <p>⚠️ revenue_share 的范围/合计规则本轮**未落**:现有代码与测试推不出唯一规则(见 PR 说明),
 * 这里只断言「存量带任意 revenue_share 的行不影响读」。
 *
 * ⚠️ 负控:删掉任一写路径前的 {@code ProductSlot.requireValid} ⇒ everyInsertIsGuarded 红;
 * requireValid 改成大小写不敏感 ⇒ unknownSlotRejectedWithMachineCode 红。
 */
class ProductRoleSlotWriteGuardTest {

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

    @Test
    void closedSlotValuesAccepted() {
        for (String slot : Arrays.asList(ProductSlot.CONTENT, ProductSlot.ORGANIZER,
                ProductSlot.SUPPLY, ProductSlot.DISTRIBUTION)) {
            assertEquals(slot, ProductSlot.requireValid(slot));
        }
    }

    @Test
    void unknownSlotRejectedWithMachineCode() {
        for (String slot : Arrays.asList(null, "", "BOGUS", "content", " CONTENT", "CONTENT ", "PLATFORM")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> ProductSlot.requireValid(slot), "必须拒绝 slot=[" + slot + "]");
            assertEquals("PRODUCT_ROLE_SLOT_INVALID", e.getMessage());
        }
    }

    /** 每一处 insertProductRole 之前都必须过写入闸 —— 新增写路径漏了它就红。 */
    @Test
    void everyInsertIsGuarded() throws Exception {
        String[] files = {
                "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ProductRoleServiceImpl.java",
                "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicEditionReopenServiceImpl.java",
        };
        for (String file : files) {
            String src = WiringSource.read(file);
            int inserts = count(src, "insertProductRole(");
            int guarded = count(src, "ProductSlot.requireValid(");
            assertTrue(inserts > 0, file + " 应包含 insertProductRole");
            assertEquals(inserts, guarded, file + " 每个 insertProductRole 前都要有 ProductSlot.requireValid");
        }
    }

    // ---------------------------------------------------- 存量行:读不校验

    private static ProductRole legacy(String slot, String entityType, Long entityId, String share) {
        ProductRole r = new ProductRole();
        r.setId(1L);
        r.setTopicId(TOPIC);
        r.setSlot(slot);
        r.setEntityType(entityType);
        r.setEntityId(entityId);
        r.setRevenueShare(share == null ? null : new BigDecimal(share));
        r.setStatus(1);
        return r;
    }

    @Test
    void legacyRowWithOutOfRangeShareStillReadable() {
        when(mapper.selectActiveByTopicAndSlot(TOPIC, ProductSlot.CONTENT)).thenReturn(
                Collections.singletonList(legacy(ProductSlot.CONTENT, RoleEntityType.PLATFORM, null, "-3.5000")));
        when(mapper.selectActiveByTopicAndSlot(TOPIC, ProductSlot.ORGANIZER)).thenReturn(
                Collections.singletonList(legacy(ProductSlot.ORGANIZER, RoleEntityType.PLATFORM, null, "999999.9999")));

        ProductRole content = service.activeContentSlot(TOPIC);
        assertNotNull(content);
        assertEquals(new BigDecimal("-3.5000"), content.getRevenueShare());
        assertTrue(service.isPlatformOwned(TOPIC));
        assertEquals(new BigDecimal("999999.9999"), service.activeOrganizerSlot(TOPIC).getRevenueShare());
    }

    @Test
    void legacyRowWithUnknownSlotDoesNotBreakBatchRead() {
        ProductRole bogus = legacy("LEGACY_SLOT", RoleEntityType.PLATFORM, null, "2.0000");
        ProductRole ok = legacy(ProductSlot.CONTENT, RoleEntityType.CLUB, 7L, "1.0000");
        ok.setTopicId(901L);
        when(mapper.selectActiveContentByTopicIds(any())).thenReturn(Arrays.asList(bogus, ok));

        Map<Long, ProductRole> rows = service.activeContentSlots(Arrays.asList(TOPIC, 901L));
        assertEquals(1, rows.size());
        assertEquals(new BigDecimal("1.0000"), rows.get(901L).getRevenueShare());
    }

    private static int count(String src, String needle) {
        Matcher m = Pattern.compile(Pattern.quote(needle)).matcher(src);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }
}
