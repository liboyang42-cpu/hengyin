package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.CmsBetaGameVote;

/**
 * 游戏调查投票Service接口
 * 
 * @author webxh
 * @date 2025-10-13
 */
public interface ICmsBetaGameVoteService 
{
    /**
     * 查询游戏调查投票
     * 
     * @param id 游戏调查投票主键
     * @return 游戏调查投票
     */
    public CmsBetaGameVote selectCmsBetaGameVoteById(Long id);

    /**
     * 查询游戏调查投票列表
     * 
     * @param cmsBetaGameVote 游戏调查投票
     * @return 游戏调查投票集合
     */
    public List<CmsBetaGameVote> selectCmsBetaGameVoteList(CmsBetaGameVote cmsBetaGameVote);

    /**
     * 新增游戏调查投票
     * 
     * @param cmsBetaGameVote 游戏调查投票
     * @return 结果
     */
    public int insertCmsBetaGameVote(CmsBetaGameVote cmsBetaGameVote);

    /**
     * 修改游戏调查投票
     * 
     * @param cmsBetaGameVote 游戏调查投票
     * @return 结果
     */
    public int updateCmsBetaGameVote(CmsBetaGameVote cmsBetaGameVote);

    /**
     * 批量删除游戏调查投票
     * 
     * @param ids 需要删除的游戏调查投票主键集合
     * @return 结果
     */
    public int deleteCmsBetaGameVoteByIds(Long[] ids);

    /**
     * 删除游戏调查投票信息
     * 
     * @param id 游戏调查投票主键
     * @return 结果
     */
    public int deleteCmsBetaGameVoteById(Long id);
}
