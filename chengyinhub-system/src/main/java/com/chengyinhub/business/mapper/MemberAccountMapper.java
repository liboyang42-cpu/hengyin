package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MyAppealRow;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/** 文档二 L8 玩家「我的申诉」跨局列表(C1)与账号信誉(X7)的只读查询。 */
public interface MemberAccountMapper {

    /** 本人的计分申诉 + 成绩争议,新的在前。open = null 全部 / true 处理中 / false 已有结论。 */
    List<MyAppealRow> selectMyAppeals(@Param("memberId") Long memberId, @Param("open") Boolean open,
                                      @Param("offset") int offset, @Param("limit") int limit);

    int countMyAppeals(@Param("memberId") Long memberId, @Param("open") Boolean open);

    /** 仍有效的俱乐部治理封禁条数(与 ClubParticipationGuardImpl 同口径:俱乐部封禁未到期、平台封禁无期限)。 */
    int countEffectiveBans(@Param("memberId") Long memberId, @Param("now") Date now);
}
