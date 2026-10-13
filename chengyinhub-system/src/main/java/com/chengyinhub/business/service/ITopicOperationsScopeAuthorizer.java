package com.chengyinhub.business.service;

/**
 * 主题统一待办的对象级读写边界。
 *
 * <p>实现只能依据可查询、可撤销、可审计的“后台账号 → 主题受托范围”真源裁决；
 * 不得用菜单权限、前端路由、memberId 或内容槽归属推断放行。</p>
 */
public interface ITopicOperationsScopeAuthorizer {

    /** 当前后台账号是否拥有全局主题运营范围，可创建官方主题或查看无主题 ID 的敏感调证。 */
    boolean hasGlobalTopicScope();

    /** 当前后台账号能否读取该主题的关系摘要。 */
    boolean canViewTopic(Long topicId);

    /**
     * 当前后台账号能否修改该主题。
     *
     * <p>写授权合同必须独立于读授权；即使当前结果相同，未来扩大受托读取范围也不得自动获得写权限。</p>
     */
    boolean canEditTopic(Long topicId);
}
