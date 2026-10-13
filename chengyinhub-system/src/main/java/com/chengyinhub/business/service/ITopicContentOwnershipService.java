package com.chengyinhub.business.service;

/**
 * 主题内容归属的高风险改判入口。
 *
 * <p>内容槽决定票款归属，必须与普通主题编辑权限、普通编辑状态机分离。</p>
 */
public interface ITopicContentOwnershipService {

    /**
     * 将主题内容槽改判到指定内容主体。
     *
     * @throws IllegalArgumentException 当主题不存在、已删除或已经进入执行/结算阶段时
     */
    boolean reassignContentSlot(Long topicId, String entityType, Long entityId);
}
