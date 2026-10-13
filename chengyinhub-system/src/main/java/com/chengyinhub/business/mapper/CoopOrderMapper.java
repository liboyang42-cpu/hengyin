package com.chengyinhub.business.mapper;

import java.util.List;
import com.chengyinhub.business.domain.CoopOrder;

/**
 * 合作单 Mapper 接口(D-4)
 */
public interface CoopOrderMapper {

    public int insertCoopOrder(CoopOrder coopOrder);

    public List<CoopOrder> selectCoopOrderByInviteId(Long inviteId);

    /** A-13:按 reg_id 查生效合作单,createCoopOrder 幂等复用(库 reg_id 无 UNIQUE 前的代码兜底) */
    public List<CoopOrder> selectCoopOrderByRegId(Long regId);

    /** 某主题所有生效(status=0)合作单 —— 定价引擎阵容输入 */
    public List<CoopOrder> selectEffectiveOrdersByTopicId(Long topicId);

    /** 某俱乐部承接的生效合作主题 id 去重列表 —— 主理人主动开团候选。 */
    public List<Long> selectEffectiveTopicIdsByClubId(Long clubId);

    /** ④无损退:作废某邀约对应的生效合作单(status 0→1),结算不再计 */
    public int voidOrderByInviteId(Long inviteId);

    /** T7:某发起方(发布者)名下有合作单的主题 id 去重列表(俱乐部财务页) */
    public List<Long> selectTopicIdsByFromId(Long fromId);
}
