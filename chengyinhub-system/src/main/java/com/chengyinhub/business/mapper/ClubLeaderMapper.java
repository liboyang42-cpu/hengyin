package com.chengyinhub.business.mapper;

import java.util.List;
import com.chengyinhub.business.domain.ClubLeader;

/**
 * 俱乐部主理人(达人)档案 Mapper 接口
 */
public interface ClubLeaderMapper
{
    public ClubLeader selectClubLeaderById(Long id);

    /** 按会员ID取主理人档案(一人一档) */
    public ClubLeader selectClubLeaderByMemberId(Long memberId);

    public List<ClubLeader> selectClubLeaderList(ClubLeader clubLeader);

    public int insertClubLeader(ClubLeader clubLeader);

    public int updateClubLeader(ClubLeader clubLeader);

    public int deleteClubLeaderById(Long id);

    public int deleteClubLeaderByIds(Long[] ids);
}
