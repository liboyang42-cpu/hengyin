package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ProductRole;

import java.util.Collection;
import java.util.Map;

/** P0-35 角色槽(D6)。 */
public interface IProductRoleService {

    /**
     * 建题时声明内容槽。**一个主题只有一个生效的 CONTENT 槽**(库里生成列唯一键保证)。
     *
     * @param topicId    主题 id
* @param entityType 仅允许 PLATFORM、MEMBER、CLUB;商家不能成为内容或票款归属主体
     * @param entityId   主体 id;PLATFORM 时必须传 null
     */
    void declareContentSlot(Long topicId, String entityType, Long entityId);

    /**
     * 该主题是否为 <b>③-自营</b>(CONTENT 槽 = PLATFORM)。
     *
     * <p><b>这是 P0-50 REVSHARE 范围闸的判据</b>:计酬档只对自营主题开放 —— 自营的票款是平台自营
     * 收入,计酬是平台的采购/推广费支出(应付台账 → 对公打款 → 商家开票),零收付通依赖;
     * 而 ③-第三方 的计酬 = **第三方之间分钱 = 二清**,必须等收付通(P0-43)。
     *
     * <p>⚠️ <b>fail-closed</b>:查不到 CONTENT 槽(存量主题一行都没有 —— 迁移刻意不回填)
     * ⇒ 返回 false ⇒ 当作「不是自营」⇒ REVSHARE 拒绝。
     * 宁可当场拒绝,也不要静默放过一个未来会变成二清的口子。
     */
    boolean isPlatformOwned(Long topicId);

    /** 该主题生效的内容槽;无则 null(存量主题的正常状态,别当异常)。 */
    ProductRole activeContentSlot(Long topicId);

    /** 该主题生效的组织方 actor 槽；无则 null。 */
    ProductRole activeOrganizerSlot(Long topicId);

    /**
     * 声明或改判 ORGANIZER actor；主题行锁与 mapper 双层保证开售后不可修改。
     * FREE_EXPLORE 只允许 PLATFORM，operatorId 只作审计并随开售 canonical 冻结。
     */
    boolean declareRequiredOrganizerSlot(Long topicId, Long operatorId);

    /** 开售事务内冻结唯一执行俱乐部为 DISTRIBUTION 资格槽，并读回自证。 */
    ProductRole declareDistributionSlotForSale(Long topicId, Long clubId, Long operatorId);

    /**
     * 批量读取列表页需要的生效 CONTENT 槽。
     * 无槽不进入结果；异常 entityType 也必须保留给调用方标记“主体异常”，不能伪装成历史待归属。
     */
    Map<Long, ProductRole> activeContentSlots(Collection<Long> topicIds);

    /**
     * 后台改判内容槽 —— 把旧的标失效(**不删行**),再落一行新的。
     *
     * <p><b>为什么需要它</b>:建题入口只有两条,而它们推出的主体是死的
     * (后台 ⇒ PLATFORM,小程序创作者 ⇒ CLUB/MEMBER)。首轮的 ③-自营 主题若是从小程序发布器建的,
     * 拿到的是 MEMBER ⇒ P0-50 的 REVSHARE 会 fail-closed 拒掉它。此时正确的动作是**把槽改对**,
     * 而不是把闸放宽。
     *
     * <p>⚠️ <b>这是一个改钱归属的操作</b> —— 票款归内容槽持有者(D8),主体仅能是
     * PLATFORM、MEMBER 或 CLUB;商家只能承接供给。所以:
     * <ul>
     *   <li>旧行标失效**不删**(留痕:谁在什么时候把归属从谁改成了谁);</li>
     *   <li>调用方必须带 {@code @Log} 审计;</li>
     *   <li>已开卖的主题改归属会让「卖的时候答应谁的钱」和「现在归谁」分叉 —— 本方法**不拦**,
     *       因为首轮的用法是「建完立刻改、还没开卖」;真要拦得等 P0-46 把票款结算接到槽上之后,
     *       那时才有明确的「已产生分账」判据。<b>在此之前这是一条纪律,不是一道闸。</b></li>
     * </ul>
     *
     * @return true = 改成功
     */
    boolean reassignContentSlot(Long topicId, String entityType, Long entityId);
}
