package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MarketingOffer;
import com.chengyinhub.business.domain.MarketingOfferItem;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * 促销供给(组合包/礼品)读写。只读写 marketing_offer 与 marketing_offer_item。
 *
 * <p>{@link #selectEnabledByCode(String)} 是报价侧唯一入口:客户端只给业务码,
 * 价格与成员构成全部由这里读出来,不信任入参。
 */
public interface MarketingOfferMapper {

    /** 启用中且未删除的供给;不判时间窗(窗口由服务层按 now 判,便于给出可解释的排除原因)。 */
    MarketingOffer selectEnabledByCode(@Param("offerCode") String offerCode);

    List<MarketingOfferItem> selectItemsByOfferId(@Param("offerId") Long offerId);

    /** 当前可展示的供给(启用 + 未删除 + 在有效期内)。 */
    List<MarketingOffer> selectActiveByScope(@Param("ownerType") Integer ownerType,
                                             @Param("ownerId") Long ownerId,
                                             @Param("now") Date now);

    List<MarketingOffer> selectOfferList(MarketingOffer query);

    /** 含已软删:业务码一旦用过就不可复用(库上有唯一键,不能等 DB 报错才暴露)。 */
    int countByCode(@Param("offerCode") String offerCode);

    MarketingOffer selectOfferById(@Param("id") Long id);

    /**
     * 建单事务内锁住供给行,用于把 {@code max_per_member} 的「读使用数 → 校验 → 写使用事实」串行化。
     *
     * <p>为什么必须锁:报价侧只在展示、不长持锁;{@code countByOfferAndMember} 读到的数到
     * insert 之间存在窗口,同一会员并发两笔首购会双双读到 0 而各用一次。锁的是<b>同一 offer 行</b>,
     * 因此只对同一组合包的并发建单生效 —— 不新建计数表、不用 Redis、不泛化锁框架。
     *
     * <p>与 {@link #selectOfferById(Long)} 不同:不过滤 {@code del_flag}/{@code status},
     * 让调用方能区分「行不存在」与「已下架/已删除」,给出不同拒绝方向。
     */
    MarketingOffer selectOfferByIdForUpdate(@Param("id") Long id);

    int insertOffer(MarketingOffer offer);

    int updateOffer(MarketingOffer offer);

    int deleteOfferByIds(@Param("ids") Long[] ids);

    int deleteItemsByOfferId(@Param("offerId") Long offerId);

    int insertItem(MarketingOfferItem item);
}
