package com.chengyinhub.business.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.ViewComment;

/**
 * VIEWMapper接口
 * 
 * @author ruoyi
 * @date 2025-08-29
 */
public interface ViewCommentMapper 
{
    /**
     * 查询VIEW
     * 
     * @param id VIEW主键
     * @return VIEW
     */
    public ViewComment selectViewCommentById(Long id);

    /**
     * 查询VIEW列表
     * 
     * @param viewComment VIEW
     * @return VIEW集合
     */
    public List<ViewComment> selectViewCommentList(ViewComment viewComment);

    /** 同一对象下，本页评论的可见直接回复数；空集合不匹配任何回复。 */
    List<ViewComment> selectVisibleReplyCountsByParents(@Param("ownerType") Long ownerType,
            @Param("ownerId") Long ownerId, @Param("parentIds") List<Long> parentIds);

    /**
     * 新增VIEW
     * 
     * @param viewComment VIEW
     * @return 结果
     */
    public int insertViewComment(ViewComment viewComment);

    /**
     * 修改VIEW
     * 
     * @param viewComment VIEW
     * @return 结果
     */
    public int updateViewComment(ViewComment viewComment);

    /**
     * 删除VIEW
     * 
     * @param id VIEW主键
     * @return 结果
     */
    public int deleteViewCommentById(Long id);

    /**
     * 批量删除VIEW
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteViewCommentByIds(Long[] ids);
}
