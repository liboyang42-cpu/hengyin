package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.CmsBetaGameMapper;
import com.chengyinhub.business.domain.CmsBetaGame;
import com.chengyinhub.business.service.ICmsBetaGameService;

/**
 * 活动Service业务层处理
 * 
 * @author webxh
 * @date 2025-10-13
 */
@Service
public class CmsBetaGameServiceImpl implements ICmsBetaGameService 
{
    @Autowired
    private CmsBetaGameMapper cmsBetaGameMapper;

    /**
     * 查询活动
     * 
     * @param id 活动主键
     * @return 活动
     */
    @Override
    public CmsBetaGame selectCmsBetaGameById(Long id)
    {
        return cmsBetaGameMapper.selectCmsBetaGameById(id);
    }

    /**
     * 查询活动列表
     * 
     * @param cmsBetaGame 活动
     * @return 活动
     */
    @Override
    public List<CmsBetaGame> selectCmsBetaGameList(CmsBetaGame cmsBetaGame)
    {
        return cmsBetaGameMapper.selectCmsBetaGameList(cmsBetaGame);
    }

    /**
     * 新增活动
     * 
     * @param cmsBetaGame 活动
     * @return 结果
     */
    @Override
    public int insertCmsBetaGame(CmsBetaGame cmsBetaGame)
    {
        cmsBetaGame.setCreateTime(DateUtils.getNowDate());
        return cmsBetaGameMapper.insertCmsBetaGame(cmsBetaGame);
    }

    /**
     * 修改活动
     * 
     * @param cmsBetaGame 活动
     * @return 结果
     */
    @Override
    public int updateCmsBetaGame(CmsBetaGame cmsBetaGame)
    {
        cmsBetaGame.setUpdateTime(DateUtils.getNowDate());
        return cmsBetaGameMapper.updateCmsBetaGame(cmsBetaGame);
    }

    /**
     * 批量删除活动
     * 
     * @param ids 需要删除的活动主键
     * @return 结果
     */
    @Override
    public int deleteCmsBetaGameByIds(Long[] ids)
    {
        return cmsBetaGameMapper.deleteCmsBetaGameByIds(ids);
    }

    /**
     * 删除活动信息
     * 
     * @param id 活动主键
     * @return 结果
     */
    @Override
    public int deleteCmsBetaGameById(Long id)
    {
        return cmsBetaGameMapper.deleteCmsBetaGameById(id);
    }


    /**
     * 批量更新游戏调查删除状态
     *
     * @param ids
     * @return 结果
     */
    @Override
    public int updateCmsBetaGameDelStatusByIds(Long[] ids){
        return cmsBetaGameMapper.updateCmsBetaGameDelStatusByIds(ids);
    }
}
