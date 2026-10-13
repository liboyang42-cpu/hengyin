package com.chengyinhub.common.utils;

import java.util.Objects;

/**
 * 资源归属校验工具。
 *
 * <p>历史上各 Api 控制器混用了三种写法：
 * <pre>
 *   a.getMemberId().equals(b)                              // 脏数据缺 member_id 时 NPE → 500
 *   a.getMemberId() == null || !a.getMemberId().equals(b)  // 判空在前，返回干净错误
 *   a.getMemberId() != null &amp;&amp; a.getMemberId().equals(b)
 * </pre>
 * 统一收敛到本工具：归属未知（任一侧为 null）一律判定为「不属于」，
 * 由调用方返回业务错误，绝不抛异常。
 *
 * @author chengyinhub
 */
public class OwnershipUtils
{
    private OwnershipUtils()
    {
    }

    /**
     * 判断资源是否属于指定主体。任一侧为 null 时返回 false（归属不明即视为无权）。
     *
     * @param ownerId     资源上记录的归属 memberId，可为 null
     * @param principalId 当前请求主体的 memberId，可为 null
     * @return 归属明确且一致时返回 true
     */
    public static boolean isOwner(Long ownerId, Long principalId)
    {
        return ownerId != null && principalId != null && Objects.equals(ownerId, principalId);
    }

    /**
     * {@link #isOwner(Long, Long)} 的反义，供 {@code if (notOwner(...)) return error(...)} 使用。
     */
    public static boolean notOwner(Long ownerId, Long principalId)
    {
        return !isOwner(ownerId, principalId);
    }
}
