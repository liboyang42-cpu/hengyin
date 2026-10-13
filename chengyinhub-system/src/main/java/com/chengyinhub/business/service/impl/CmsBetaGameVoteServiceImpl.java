package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.CmsBetaGameVoteMapper;
import com.chengyinhub.business.domain.CmsBetaGameVote;
import com.chengyinhub.business.service.ICmsBetaGameVoteService;

/**
 * 游戏调查投票Service业务层处理
 * 
 * @author webxh
 * @date 2025-10-13
 */
@Service
public class CmsBetaGameVoteServiceImpl implements ICmsBetaGameVoteService 
{
    @Autowired
    private CmsBetaGameVoteMapper cmsBetaGameVoteMapper;

    /**
     * 查询游戏调查投票
     * 
     * @param id 游戏调查投票主键
     * @return 游戏调查投票
     */
    @Override
    public CmsBetaGameVote selectCmsBetaGameVoteById(Long id)
    {
        return cmsBetaGameVoteMapper.selectCmsBetaGameVoteById(id);
    }

    /**
     * 查询游戏调查投票列表
     * 
     * @param cmsBetaGameVote 游戏调查投票
     * @return 游戏调查投票
     */
    @Override
    public List<CmsBetaGameVote> selectCmsBetaGameVoteList(CmsBetaGameVote cmsBetaGameVote)
    {
        return cmsBetaGameVoteMapper.selectCmsBetaGameVoteList(cmsBetaGameVote);
    }

    /**
     * 新增游戏调查投票
     * 
     * @param cmsBetaGameVote 游戏调查投票
     * @return 结果
     */
    @Override
    public int insertCmsBetaGameVote(CmsBetaGameVote cmsBetaGameVote)
    {
        cmsBetaGameVote.setCreateTime(DateUtils.getNowDate());
        return cmsBetaGameVoteMapper.insertCmsBetaGameVote(cmsBetaGameVote);
    }

    /**
     * 修改游戏调查投票
     * 
     * @param cmsBetaGameVote 游戏调查投票
     * @return 结果
     */
    @Override
    public int updateCmsBetaGameVote(CmsBetaGameVote cmsBetaGameVote)
    {
        cmsBetaGameVote.setUpdateTime(DateUtils.getNowDate());
        return cmsBetaGameVoteMapper.updateCmsBetaGameVote(cmsBetaGameVote);
    }

    /**
     * 批量删除游戏调查投票
     * 
     * @param ids 需要删除的游戏调查投票主键
     * @return 结果
     */
    @Override
    public int deleteCmsBetaGameVoteByIds(Long[] ids)
    {
        return cmsBetaGameVoteMapper.deleteCmsBetaGameVoteByIds(ids);
    }

    /**
     * 删除游戏调查投票信息
     * 
     * @param id 游戏调查投票主键
     * @return 结果
     */
    @Override
    public int deleteCmsBetaGameVoteById(Long id)
    {
        return cmsBetaGameVoteMapper.deleteCmsBetaGameVoteById(id);
    }
}
