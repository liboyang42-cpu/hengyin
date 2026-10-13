package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.ClubLeaderMapper;
import com.chengyinhub.business.domain.ClubLeader;
import com.chengyinhub.business.service.IClubLeaderService;

/**
 * 俱乐部主理人(达人)档案 Service 实现
 */
@Service
public class ClubLeaderServiceImpl implements IClubLeaderService
{
    @Autowired
    private ClubLeaderMapper clubLeaderMapper;

    @Override
    public ClubLeader selectClubLeaderById(Long id)
    {
        return clubLeaderMapper.selectClubLeaderById(id);
    }

    @Override
    public ClubLeader selectClubLeaderByMemberId(Long memberId)
    {
        return clubLeaderMapper.selectClubLeaderByMemberId(memberId);
    }

    @Override
    public List<ClubLeader> selectClubLeaderList(ClubLeader clubLeader)
    {
        return clubLeaderMapper.selectClubLeaderList(clubLeader);
    }

    @Override
    public int insertClubLeader(ClubLeader clubLeader)
    {
        clubLeader.setCreateTime(DateUtils.getNowDate());
        return clubLeaderMapper.insertClubLeader(clubLeader);
    }

    @Override
    public int updateClubLeader(ClubLeader clubLeader)
    {
        clubLeader.setUpdateTime(DateUtils.getNowDate());
        return clubLeaderMapper.updateClubLeader(clubLeader);
    }

    @Override
    public int deleteClubLeaderById(Long id)
    {
        return clubLeaderMapper.deleteClubLeaderById(id);
    }

    @Override
    public int deleteClubLeaderByIds(Long[] ids)
    {
        return clubLeaderMapper.deleteClubLeaderByIds(ids);
    }
}
