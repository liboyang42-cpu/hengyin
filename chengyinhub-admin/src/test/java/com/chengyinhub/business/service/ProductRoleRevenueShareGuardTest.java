package com.chengyinhub.business.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.mapper.ProductRoleMapper;
import com.chengyinhub.business.service.impl.ProductRoleServiceImpl;
import com.chengyinhub.common.constant.RoleEntityType;

/**
 * O-15(2026-11-11):revenue_share 写入闸。列允许 null,写值必须是 [0,1] 且小数位 ≤ 4;
 * 服务层四条 insert 路径都在 {@code ProductSlot.requireValid} 之后调同一个闸。
 */
class ProductRoleRevenueShareGuardTest {

    @Test
    void nullMeansNotInSplit_andIsAccepted() {
        assertNull(ProductRole.requireValidRevenueShare(null));
    }

    @Test
    void zeroToOneInclusive_accepted() {
        assertEquals(BigDecimal.ZERO, ProductRole.requireValidRevenueShare(BigDecimal.ZERO));
        assertEquals(BigDecimal.ONE, ProductRole.requireValidRevenueShare(BigDecimal.ONE));
        assertEquals(new BigDecimal("0.1500"), ProductRole.requireValidRevenueShare(new BigDecimal("0.1500")));
        assertEquals(new BigDecimal("0.3333"), ProductRole.requireValidRevenueShare(new BigDecimal("0.3333")));
    }

    @Test
    void outOfRangeOrTooPrecise_rejectedWithMachineCode() {
        for (String bad : new String[] {"-0.0001", "1.0001", "15", "0.33333"}) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> ProductRole.requireValidRevenueShare(new BigDecimal(bad)), bad);
            assertEquals(ProductRole.ERR_REVENUE_SHARE_INVALID, ex.getMessage(), bad);
        }
    }

    /** 现有写路径不带 revenue_share(null)⇒ 闸放行,行照写。 */
    @Test
    void declareContentSlot_withoutShare_stillWrites() {
        ProductRoleMapper mapper = mock(ProductRoleMapper.class);
        when(mapper.insertProductRole(any(ProductRole.class))).thenReturn(1);
        ProductRoleServiceImpl service = new ProductRoleServiceImpl();
        ReflectionTestUtils.setField(service, "productRoleMapper", mapper);

        service.declareContentSlot(9001L, RoleEntityType.PLATFORM, null);

        verify(mapper, times(1)).insertProductRole(any(ProductRole.class));
    }

    /** 源码契约:服务层每一处 insertProductRole 之前都过 revenue_share 闸(防止新增写路径漏掉)。 */
    @Test
    void everyInsertPathCallsTheGuard() throws Exception {
        Path src = Paths.get("..", "chengyinhub-system", "src", "main", "java", "com", "chengyinhub", "business",
                "service", "impl", "ProductRoleServiceImpl.java");
        if (!Files.exists(src)) {
            src = Paths.get("chengyinhub-system", "src", "main", "java", "com", "chengyinhub", "business",
                    "service", "impl", "ProductRoleServiceImpl.java");
        }
        String code = new String(Files.readAllBytes(src), StandardCharsets.UTF_8);
        int inserts = count(code, "productRoleMapper.insertProductRole(");
        int guards = count(code, "ProductRole.requireValidRevenueShare(");
        assertEquals(4, inserts, "insert 路径数变了,请同步本测试与闸");
        assertEquals(inserts, guards, "每条 insert 路径前都要调 requireValidRevenueShare");
    }

    private static int count(String haystack, String needle) {
        Matcher m = Pattern.compile(Pattern.quote(needle)).matcher(haystack);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }
}
