package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.ClubLeader;

/**
 * 俱乐部主理人(达人)档案 Service 接口
 */
public interface IClubLeaderService
{
    public ClubLeader selectClubLeaderById(Long id);

    /** 按会员ID取主理人档案 */
    public ClubLeader selectClubLeaderByMemberId(Long memberId);

    public List<ClubLeader> selectClubLeaderList(ClubLeader clubLeader);

    public int insertClubLeader(ClubLeader clubLeader);

    public int updateClubLeader(ClubLeader clubLeader);

    public int deleteClubLeaderById(Long id);

    public int deleteClubLeaderByIds(Long[] ids);
}
