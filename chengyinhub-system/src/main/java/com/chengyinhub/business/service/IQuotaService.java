package com.chengyinhub.business.service;

import java.util.function.Supplier;

/**
 * 配额校验 Service（RBAC R3）。
 *
 * 创建主题/活动/优惠券前，按角色配额 user_permission 校验「同时存在」数量与创建权限。
 */
public interface IQuotaService
{
    String TYPE_THEME = "theme";
    String TYPE_EVENT = "event";
    String TYPE_COUPON = "coupon";

    /**
     * 校验某会员能否再创建一个指定类型的内容。
     *
     * @param memberId 会员ID
     * @param type     theme/event/coupon
     * @return null=放行；非空=拦截提示文案
     */
    String checkCreate(Long memberId, String type);

    /**
     * 主题节点数量不限；保留发布主体存在及权限配置校验。
     *
     * @param memberId  主题发布主体
     * @param nodeCount 本次提交的节点总数
     * @return null=放行；非空=拦截提示文案
     */
    String checkNodesPerTheme(Long memberId, int nodeCount);

    /**
     * 上架/上线校验:在架公开主题(user_status=1,非 selfPlay)不得超过角色 maxThemes 上限。
     * 草稿/待审(user_status=0)与 selfPlay 不占额度;下架即释放。maxThemes=null 视为不限。
     *
     * @param memberId       会员ID
     * @param excludeTopicId 排除的主题ID(重复上架幂等,可为 null)
     * @return null=放行；非空=拦截提示文案
     */
    String checkThemeOnline(Long memberId, Long excludeTopicId);

    /**
     * 竞态安全地执行一次「主题上架」写操作:在单个事务内 ①对会员行加悲观锁(串行化该会员的上架)
     * ②复查在架数(checkThemeOnline) ③执行 writeAction 写库。check-then-write 因此原子化,
     * 杜绝并发下两个请求同时通过校验双双上架、越过 maxThemes 上限(资损)。
     *
     * 校验不通过时抛 {@link com.chengyinhub.common.exception.ServiceException},事务回滚,writeAction 不执行。
     *
     * @param memberId       会员ID
     * @param excludeTopicId 复查在架数时排除的主题ID(重复上架幂等,可为 null)
     * @param writeAction    在锁与校验通过后执行的写操作(如置 user_status=1 / publishDraft / createTopic)
     * @param <T>            写操作返回类型
     * @return writeAction 的返回值
     */
    <T> T runWithThemeOnlineGuard(Long memberId, Long excludeTopicId, Supplier<T> writeAction);

    /**
     * 配额闸的「锁+复查」两步(不抛异常变体):对会员行加悲观锁串行化,持锁复查 checkThemeOnline,
     * 返回 null=可上架 / 错误文案=配额不满足。供不能承受 ServiceException 毒化外层事务的调用方
     * (如审核过审自动上架,其外层 review() 事务已提交审核记录)在自己的事务内复用同一把闸,
     * 保证「锁→复查」不变式只有一份实现。★调用方必须已处于事务中,否则行锁立即释放、闸失效。
     *
     * @param memberId       会员ID
     * @param excludeTopicId 复查在架数时排除的主题ID(可为 null)
     * @return null=通过;非 null=不可上架的原因文案
     */
    String lockAndCheckThemeOnline(Long memberId, Long excludeTopicId);

    /**
     * 竞态安全地执行一次「创建活动/优惠券」写操作:单事务内 ①对会员行加悲观锁 ②复查 checkCreate(type)
     * ③执行 writeAction 写库。消除 checkCreate(count)-then-insert 的 TOCTOU 窗口,防并发越过
     * maxEvents/maxCoupons 上限。校验不通过抛 {@link com.chengyinhub.common.exception.ServiceException} 回滚。
     *
     * @param memberId    会员ID
     * @param type        {@link #TYPE_EVENT} / {@link #TYPE_COUPON}
     * @param writeAction 锁与校验通过后执行的写操作
     * @param <T>         写操作返回类型
     * @return writeAction 的返回值
     */
    <T> T runWithCreateGuard(Long memberId, String type, Supplier<T> writeAction);
}
