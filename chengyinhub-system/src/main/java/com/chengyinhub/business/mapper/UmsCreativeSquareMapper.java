package com.chengyinhub.business.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.UmsCreativeSquare;

/**
 * 创意广场-用户发布Mapper接口
 * 
 * @author ruoyi
 * @date 2025-08-28
 */
public interface UmsCreativeSquareMapper 
{
    /**
     * 查询创意广场-用户发布
     * 
     * @param id 创意广场-用户发布主键
     * @return 创意广场-用户发布
     */
    public UmsCreativeSquare selectUmsCreativeSquareById(Long id);

/** 锁定当前行，使互动与删除、隐藏按同一事务顺序生效。 */
    UmsCreativeSquare selectUmsCreativeSquareByIdForUpdate(Long id);

    /** R10-03 发布意图键回读(账号内唯一),丢回包重试据此幂等。 */
    UmsCreativeSquare selectUmsCreativeSquareByMemberAndRequestId(@Param("memberId") Long memberId,
                                                                  @Param("requestId") String requestId);

    /**
     * 查询创意广场-用户发布列表
     * 
     * @param umsCreativeSquare 创意广场-用户发布
     * @return 创意广场-用户发布集合
     */
    public List<UmsCreativeSquare> selectUmsCreativeSquareList(UmsCreativeSquare umsCreativeSquare);

    /**
     * 新增创意广场-用户发布
     * 
     * @param umsCreativeSquare 创意广场-用户发布
     * @return 结果
     */
    public int insertUmsCreativeSquare(UmsCreativeSquare umsCreativeSquare);

    /**
     * 修改创意广场-用户发布
     * 
     * @param umsCreativeSquare 创意广场-用户发布
     * @return 结果
     */
    public int updateUmsCreativeSquare(UmsCreativeSquare umsCreativeSquare);

    /**
     * 删除创意广场-用户发布
     * 
     * @param id 创意广场-用户发布主键
     * @return 结果
     */
    public int deleteUmsCreativeSquareById(Long id);

    /**
     * 批量删除创意广场-用户发布
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteUmsCreativeSquareByIds(Long[] ids);

    /** [6-09] 主题下架/删除/驳回:把该主题仍公开的同步帖(data_type=2)置为 status=3「随主题隐藏」(不是平台下架的 2)。 */
    int hideTopicSyncPosts(@Param("topicId") Long topicId);

    /**
     * [6-09] 主题恢复公开:把该主题此前被联动隐藏的同步帖放回可见。
     * 只恢复 status=3(联动隐藏)且未被自身审核驳回(check_status≠2)且未删(del_flag=0)的行;平台下架的 2 不动。
     */
    int restoreTopicSyncPosts(@Param("topicId") Long topicId);
}
