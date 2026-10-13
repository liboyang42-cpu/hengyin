package com.chengyinhub.business.service;

/**
 * 身份能力位的服务端强制点（X03）。
 *
 * <p><b>为什么需要它</b>：{@code /api/role/info} 下发的能力位里，
 * {@code canBranch / canGameMechanic / canDesignMedal / canCreateGroup} 四位
 * 此前两端都不拦——只在 {@code components/cy/profile} 的「权益清单」里显示
 * 「已解锁 / 未解锁」。界面告诉低阶身份「未解锁勋章设计」，却没有任何东西阻止他用：
 * 模板发布请求里的 {@code medalName / medalImg} 后端照收。
 * {@code utils/roleGuard.js} 文件头写着「三端权限单一事实源」，这四位让那句话不成立。
 *
 * <p><b>本接口不发明产品策略</b>：它强制的就是界面已经向用户作出的那句承诺，
 * 一字不多。权益清单里没提的机制（random / timeWindow / blindTaste / silentOrder /
 * diyName / musicCorner / steps / dailySign）一概不拦——拦它们才是发明。
 *
 * <p>返回约定与 {@link IQuotaService} 一致：<b>放行返回 null</b>，
 * 不放行返回可直接透出给用户的文案。不抛异常，由调用方 {@code return error(msg)}。
 *
 * @author chengyinhub
 */
public interface ICapabilityGuard
{
    /**
     * 高级玩法配置里启用的机制，是否都在该会员的能力位之内。
     *
     * <p>映射严格对齐权益清单的文案：
     * <ul>
     *   <li>{@code branch} → 「分支 / 条件玩法」= {@code canBranch}</li>
     *   <li>{@code timer} / {@code leaderboard} / {@code multiplayer}
     *       → 「博弈机制（竞速/对抗/协作）」= {@code canGameMechanic}</li>
     * </ul>
     *
     * @param memberId            会员
     * @param advancedConfigJson  高级玩法配置原文，空/无机制时直接放行
     * @return 放行返回 null
     */
    String checkAdvancedMechanics(Long memberId, String advancedConfigJson);

    /**
     * 是否可以设计勋章（权益清单「勋章设计」= {@code canDesignMedal}）。
     *
     * @param memberId 会员
     * @return 放行返回 null
     */
    String checkDesignMedal(Long memberId);

    /**
     * 是否可以建群（权益清单「建群」= {@code canCreateGroup}）。
     * 「群」在本产品里就是俱乐部自带的群聊，所以闸落在创建俱乐部这一步。
     *
     * @param memberId 会员
     * @return 放行返回 null
     */
    String checkCreateGroup(Long memberId);
}
