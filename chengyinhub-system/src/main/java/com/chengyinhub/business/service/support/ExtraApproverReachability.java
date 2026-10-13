package com.chengyinhub.business.service.support;

import com.chengyinhub.common.utils.SecurityUtils;
import com.chengyinhub.system.service.ISysMenuService;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 「这个 sys_user 真的按得动『额外工时事前预批』那个按钮吗」。
 *
 * <p>为什么开售前必须问一次：{@code club_edition_compensation_terms.extra_approver_user_id} 是
 * <b>不可变</b>的冻结事实（{@code freezeTermsForSale} 幂等读回原件，全仓没有 terms 更正方法）。
 * 冻进去一个按不动那个按钮的人，本期的 EXTRA 预批与更正就会被
 * {@code EDITION_EXTRA_APPROVER_NOT_FROZEN} 永久挡死，只能改库 —— 方向是少付，
 * 但那是一条<b>没有截止日期的阻断</b>。开售是唯一还能拦住它的时点。
 *
 * <p>⚠️ 这只回答「有没有权限位」，不代替 {@code requireFrozenApprover}：后者管的是
 * 「动手的是不是<b>当初冻的那一个人</b>」，两件事都要。
 */
@Component
public class ExtraApproverReachability {

    /** 消费面的权限：与 {@code ClubEditionCompensationController.preapproveExtra} 的 @PreAuthorize 同一个串。 */
    public static final String APPROVE_PERMISSION = "business:clubextra:approve";
    /** RuoYi 超管的通配权限。 */
    private static final String ALL_PERMISSION = "*:*:*";

    private final ISysMenuService menuService;

    public ExtraApproverReachability(ISysMenuService menuService) {
        this.menuService = menuService;
    }

    public boolean canApproveExtraHours(Long userId) {
        if (userId == null || userId <= 0) return false;
        // 超管走通配，menu 表里不一定有逐条授权行。
        if (SecurityUtils.isAdmin(userId)) return true;
        Set<String> perms = menuService.selectMenuPermsByUserId(userId);
        if (perms == null) return false;
        return perms.contains(APPROVE_PERMISSION) || perms.contains(ALL_PERMISSION);
    }
}
