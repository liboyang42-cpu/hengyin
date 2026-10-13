package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.CmsBetaGame;

/**
 * 活动Service接口
 * 
 * @author webxh
 * @date 2025-10-13
 */
public interface ICmsBetaGameService 
{
    /**
     * 查询活动
     * 
     * @param id 活动主键
     * @return 活动
     */
    public CmsBetaGame selectCmsBetaGameById(Long id);

    /**
     * 查询活动列表
     * 
     * @param cmsBetaGame 活动
     * @return 活动集合
     */
    public List<CmsBetaGame> selectCmsBetaGameList(CmsBetaGame cmsBetaGame);

    /**
     * 新增活动
     * 
     * @param cmsBetaGame 活动
     * @return 结果
     */
    public int insertCmsBetaGame(CmsBetaGame cmsBetaGame);

    /**
     * 修改活动
     * 
     * @param cmsBetaGame 活动
     * @return 结果
     */
    public int updateCmsBetaGame(CmsBetaGame cmsBetaGame);

    /**
     * 批量删除活动
     * 
     * @param ids 需要删除的活动主键集合
     * @return 结果
     */
    public int deleteCmsBetaGameByIds(Long[] ids);

    /**
     * 删除活动信息
     * 
     * @param id 活动主键
     * @return 结果
     */
    public int deleteCmsBetaGameById(Long id);

    /**
     * 批量更新游戏调查删除状态
     *
     * @param ids
     * @return 结果
     */
    public int updateCmsBetaGameDelStatusByIds(Long[] ids);
}
