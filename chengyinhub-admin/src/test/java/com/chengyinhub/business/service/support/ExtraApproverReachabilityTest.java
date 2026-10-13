package com.chengyinhub.business.service.support;

import com.chengyinhub.system.service.ISysMenuService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「开售冻结的项目负责人真的按得动预批按钮吗」这条判断本身的契约。
 *
 * <p>★这里<b>只</b>把 {@code ISysMenuService}（RBAC 数据的库边界）替身掉，判断逻辑跑真的 ——
 * 上层 {@code ClubEditionCompensationServiceTest} 里那个 mock 掉整条可达性的做法只证得了接线，
 * 证不了「什么算可达」。两层各证各的。
 */
class ExtraApproverReachabilityTest {

    private static final long PROJECT_LEAD = 88L;

    private ISysMenuService menuService;
    private ExtraApproverReachability reachability;

    @BeforeEach
    void setUp() {
        menuService = mock(ISysMenuService.class);
        reachability = new ExtraApproverReachability(menuService);
    }

    @Test
    void aUserHoldingTheApprovePermissionIsReachable() {
        when(menuService.selectMenuPermsByUserId(PROJECT_LEAD))
                .thenReturn(perms("business:clubextra:approve", "business:topic:query"));

        assertTrue(reachability.canApproveExtraHours(PROJECT_LEAD));
    }

    /** 权限串必须精确匹配消费面那一个：拿一个相邻的资金权限换不来预批。 */
    @Test
    void aUserWithOnlyOtherFundsPermissionsIsNotReachable() {
        when(menuService.selectMenuPermsByUserId(PROJECT_LEAD))
                .thenReturn(perms("business:clubquality:confirm", "business:clubpayable:paid"));

        assertFalse(reachability.canApproveExtraHours(PROJECT_LEAD),
                "别的资金权限换不来 business:clubextra:approve —— 冻一个按不动的人就是永久阻断");
    }

    @Test
    void aUserWithNoPermissionRowsAtAllIsNotReachable() {
        when(menuService.selectMenuPermsByUserId(PROJECT_LEAD)).thenReturn(Collections.<String>emptySet());
        assertFalse(reachability.canApproveExtraHours(PROJECT_LEAD));

        when(menuService.selectMenuPermsByUserId(PROJECT_LEAD)).thenReturn(null);
        assertFalse(reachability.canApproveExtraHours(PROJECT_LEAD), "查不出来就是不可达，不许当成可达");
    }

    /** RuoYi 超管走通配权限，sys_role_menu 里不一定有逐条授权行。 */
    @Test
    void theSuperAdminIsReachableWithoutAnyExplicitGrant() {
        assertTrue(reachability.canApproveExtraHours(1L));
    }

    @Test
    void aWildcardPermissionIsAlsoReachable() {
        when(menuService.selectMenuPermsByUserId(PROJECT_LEAD)).thenReturn(perms("*:*:*"));
        assertTrue(reachability.canApproveExtraHours(PROJECT_LEAD));
    }

    /** 没登记 / 登记成 0 都不是一个人，绝不当成可达。 */
    @Test
    void aMissingOrZeroUserIdIsNeverReachable() {
        assertFalse(reachability.canApproveExtraHours(null));
        assertFalse(reachability.canApproveExtraHours(0L));
        assertFalse(reachability.canApproveExtraHours(-1L));
    }

    private static Set<String> perms(String... values) {
        Set<String> set = new HashSet<String>();
        Collections.addAll(set, values);
        return set;
    }
}
