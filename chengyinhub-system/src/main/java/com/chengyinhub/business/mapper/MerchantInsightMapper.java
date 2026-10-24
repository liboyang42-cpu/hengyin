package com.chengyinhub.business.mapper;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.chengyinhub.business.domain.dto.MerchantInsightCrowdFact;
import com.chengyinhub.business.domain.dto.MerchantInsightSupplyFact;
import com.chengyinhub.business.domain.dto.MerchantInsightVisitFact;
import com.chengyinhub.business.domain.dto.MerchantInsightVisitorEvent;

/**
 * 商家店铺参谋数据面。全部只读;join 骨架抄 ShopDayMerchantRosterMapper(写读双 scope),
 * 但跨全部主题按商家聚合,不按单 topic。
 *
 * @author chengyinhub
 */
public interface MerchantInsightMapper
{
    /** 窗口内(按购票时间)本商家的 entitlement 级到店/核销事实,含未到店行(做核销率分母)。 */
    List<MerchantInsightVisitFact> selectVisitFacts(@Param("merchantMemberId") Long merchantMemberId,
                                                    @Param("since") Date since);

    /** 窗口内到过店(打卡或核销任一)的去重会员画像行。 */
    List<MerchantInsightCrowdFact> selectCrowdFacts(@Param("merchantMemberId") Long merchantMemberId,
                                                    @Param("since") Date since);

    /** 当前在架供给(status=1 且未过期)。 */
    List<MerchantInsightSupplyFact> selectSupplyFacts(@Param("merchantMemberId") Long merchantMemberId);

    /** 曝光(升级5):本商家参与节点所在主题的窗口内浏览数(tms_browse_record,browse_type=1 主题)。 */
    int countTopicExposure(@Param("merchantMemberId") Long merchantMemberId, @Param("since") java.util.Date since);

    /** 曝光人数(UV):同 {@link #countTopicExposure} 口径,按 member_id 去重。 */
    int countTopicExposureVisitors(@Param("merchantMemberId") Long merchantMemberId, @Param("since") java.util.Date since);
    /**
     * 全历史访客事件行(有打卡或核销的 entitlement,带昵称/头像/主题名)。
     * 单客行/来源主题/环比/新老客全部由 Java 从这一份聚合;绝不进 AI 事实包。
     */
    List<MerchantInsightVisitorEvent> selectVisitorEvents(@Param("merchantMemberId") Long merchantMemberId);
}
