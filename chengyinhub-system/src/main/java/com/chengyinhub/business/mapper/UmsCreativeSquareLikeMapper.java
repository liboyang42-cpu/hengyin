package com.chengyinhub.business.mapper;

import java.util.List;
import com.chengyinhub.business.domain.UmsCreativeSquareLike;

/**
 * 创意广场点赞Mapper接口
 * 
 * @author ruoyi
 * @date 2025-08-29
 */
public interface UmsCreativeSquareLikeMapper 
{

    /**
     * 查询用户点赞数
     *
     * @param member_id 用户ID
     * @return 结果
     */
    public int selectUmsMemberLikeNumByMemberId(Long member_id);


    /**
     * 查询创意广场点赞
     * 
     * @param id 创意广场点赞主键
     * @return 创意广场点赞
     */
    public UmsCreativeSquareLike selectUmsCreativeSquareLikeById(Long id);

    /**
     * 查询创意广场点赞列表
     * 
     * @param umsCreativeSquareLike 创意广场点赞
     * @return 创意广场点赞集合
     */
    public List<UmsCreativeSquareLike> selectUmsCreativeSquareLikeList(UmsCreativeSquareLike umsCreativeSquareLike);

    /**
     * 新增创意广场点赞
     * 
     * @param umsCreativeSquareLike 创意广场点赞
     * @return 结果
     */
    public int insertUmsCreativeSquareLike(UmsCreativeSquareLike umsCreativeSquareLike);

    /**
     * 修改创意广场点赞
     * 
     * @param umsCreativeSquareLike 创意广场点赞
     * @return 结果
     */
    public int updateUmsCreativeSquareLike(UmsCreativeSquareLike umsCreativeSquareLike);

    /**
     * 删除创意广场点赞
     * 
     * @param id 创意广场点赞主键
     * @return 结果
     */
    public int deleteUmsCreativeSquareLikeById(Long id);

    /**
     * 批量删除创意广场点赞
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteUmsCreativeSquareLikeByIds(Long[] ids);
}
