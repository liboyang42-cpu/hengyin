package com.chengyinhub.business.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.OfficialEvent;

/**
 * 官方活动 Mapper
 *
 * @author cy
 * @date 2026-07-08
 */
public interface OfficialEventMapper
{
    int insertOfficialEvent(OfficialEvent officialEvent);

    int updateOfficialEvent(OfficialEvent officialEvent);

    OfficialEvent selectOfficialEventById(Long id);

    /** V2 写路径的串行化锁：草稿替换、发布与限额报名必须基于同一活动快照重验。 */
    OfficialEvent selectOfficialEventByIdForUpdate(Long id);

    /** 玩家端可见列表:status 在 [1,8](排除草稿0/下线9),city 为 null 时返回全部城市。 */
    List<OfficialEvent> selectPublicList(@Param("city") String city);

    /** Quartz 状态流转:取仍在生命周期内的活动(status 1..4)。 */
    List<OfficialEvent> selectActiveForFlow();

    /** 仅 V2 UMS_MEMBER 身份域内，发布者自己发过的活动；禁止用历史裸 sender_account 猜测身份。 */
    List<OfficialEvent> selectBySender(@Param("senderAccount") Long senderAccount);

    /** 后台管理:按条件列出全部活动(status/city/category/title 非空即过滤),倒序。 */
    List<OfficialEvent> selectOfficialEventList(OfficialEvent criteria);
}
