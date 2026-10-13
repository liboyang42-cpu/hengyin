package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.TopicContentPublisherDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** P0-35 角色槽。 */
public interface ProductRoleMapper {

    int insertProductRole(ProductRole role);

    /** 锁主题行后判断 ORGANIZER 是否仍可改；缺行/已删除返回 null。 */
    CmsTopic selectTopicForOrganizerUpdate(@Param("topicId") Long topicId);

    /**
     * 某主题某槽的**生效**行。
     *
     * <p>⚠️ 单例槽(CONTENT/ORGANIZER)最多 1 行 —— 由库里的生成列唯一键保证,不是靠这里 limit 1。
     * 返回 List 是因为 SUPPLY/DISTRIBUTION 可多行,同一个方法两用。
     */
    List<ProductRole> selectActiveByTopicAndSlot(@Param("topicId") Long topicId, @Param("slot") String slot);

    /** 列表页批量读取生效 CONTENT 槽，避免逐主题查询。 */
    List<ProductRole> selectActiveContentByTopicIds(@Param("topicIds") List<Long> topicIds);

    /**
     * app 端「来源」栏的批量最小投影：生效 CONTENT 槽的主体类型 + 俱乐部名 / 会员昵称。
     *
     * <p>与 {@link #selectActiveContentByTopicIds} 的分工：那个返回槽实体（含 entityId），
     * 供后台与授权判据使用；本方法<b>不返回 entityId</b>，只服务于对外展示。
     *
     * <p>⚠️ 无生效 CONTENT 槽的主题<b>不出现在结果里</b>（存量主题的正常状态）。
     * 调用方必须把「缺行」渲染成中性态，不得默认成官方。
     */
    List<TopicContentPublisherDTO> selectActiveContentPublisherByTopicIds(
            @Param("topicIds") List<Long> topicIds);

    /** 把某主题某槽的生效行标失效(不删行,留痕)。返回受影响行数。 */
    int deactivateByTopicAndSlot(@Param("topicId") Long topicId, @Param("slot") String slot);
}
