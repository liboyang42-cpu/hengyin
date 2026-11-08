package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.dto.PlayerAffiliateBucketRow;
import com.chengyinhub.business.domain.dto.PlayerAffiliateCampaignRow;
import com.chengyinhub.business.domain.dto.PlayerAffiliateCredentialRow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 玩家本人推广/推荐收入只读查询。
 *
 * <p>全部语句只有一个过滤真源：{@code sharer_member_id = 调用者}
 * 或 {@code line.recipient_member_id = 调用者}，由服务层从登录身份取得，
 * <b>不接受客户端传 memberId</b>。本接口没有 insert/update/delete。
 */
public interface PlayerAffiliateMapper {

    /** 本人名下凭据元数据（不含 token 明文/摘要），按签发时间倒序。 */
    List<PlayerAffiliateCredentialRow> listOwnCredentials(@Param("memberId") long memberId,
                                                          @Param("limit") int limit);

    /** 本人作为分享人的 campaign 成交/退款/待应计聚合。 */
    List<PlayerAffiliateCampaignRow> listOwnCampaigns(@Param("memberId") long memberId,
                                                      @Param("limit") int limit);

    /** 本人作为推荐人的收入分录，按 (分配状态, 币种, 分录方向) 聚合。 */
    List<PlayerAffiliateBucketRow> listReferrerBuckets(@Param("memberId") long memberId);
}
