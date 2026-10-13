package com.chengyinhub.business.mapper;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.CoopPerk;

/**
 * 协作权益申报 Mapper 接口(D-3)
 */
public interface CoopPerkMapper {

    public int insertCoopPerk(CoopPerk p);

    /** 单条取权益(入驻校验用:要读它的 merchantId 归属与 retailValue 零售价)。 */
    public CoopPerk selectById(@Param("id") Long id);

    public List<CoopPerk> selectByInviteId(@Param("inviteId") Long inviteId);

    /** 清空某邀约已申报权益(重新申报前) */
    public int deleteByInviteId(@Param("inviteId") Long inviteId);

    /** T10:某主题已接受邀约申报的权益单人成本合计(定价成本构成 perkCost);无申报→0 */
    public BigDecimal sumUnitCostByTopicId(@Param("topicId") Long topicId);
}
