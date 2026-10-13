package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.ViewProductLike;

/**
 * VIEWService接口
 * 
 * @author webxh
 * @date 2025-08-30
 */
public interface IViewProductLikeService 
{
    /**
     * 查询VIEW
     * 
     * @param id VIEW主键
     * @return VIEW
     */
    public ViewProductLike selectViewProductLikeById(Long id);

    /**
     * 查询VIEW列表
     * 
     * @param viewProductLike VIEW
     * @return VIEW集合
     */
    public List<ViewProductLike> selectViewProductLikeList(ViewProductLike viewProductLike);

    /**
     * 新增VIEW
     * 
     * @param viewProductLike VIEW
     * @return 结果
     */
    public int insertViewProductLike(ViewProductLike viewProductLike);

    /**
     * 修改VIEW
     * 
     * @param viewProductLike VIEW
     * @return 结果
     */
    public int updateViewProductLike(ViewProductLike viewProductLike);

    /**
     * 批量删除VIEW
     * 
     * @param ids 需要删除的VIEW主键集合
     * @return 结果
     */
    public int deleteViewProductLikeByIds(Long[] ids);

    /**
     * 删除VIEW信息
     * 
     * @param id VIEW主键
     * @return 结果
     */
    public int deleteViewProductLikeById(Long id);
}
