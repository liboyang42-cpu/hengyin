package com.chengyinhub.business.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.CoopGroupVerify;
import com.chengyinhub.business.domain.dto.ClubFulfillmentWorkspaceDTO;
import com.chengyinhub.business.domain.dto.CoopGroupSettlementBasis;

/**
 * 团核销台账 Mapper 接口(D22)
 */
public interface CoopGroupVerifyMapper {

    /**
     * 记一条「该商家接待了该场次的团」。幂等靠
     * uk_group_verify_activity_merchant(activity_id, merchant_member_id)
     * + INSERT IGNORE:商家重复扫同一个团的码不叠加基数(叠加 = 凭一次接待拿两份钱)。
     */
    public int insertIgnoreCoopGroupVerify(CoopGroupVerify coopGroupVerify);

    /** 某商家实际接待到的带队场次核销基数。 */
    CoopGroupSettlementBasis selectSettlementBasisByTopicAndMerchant(@Param("topicId") Long topicId,
                                                                       @Param("merchantMemberId") Long merchantMemberId);

    /** 只读证据口径：包含未删除活动壳；旧现金结算必须继续调用上面的单腿方法。 */
    CoopGroupSettlementBasis selectSettlementEvidenceBasisByTopicAndMerchantIncludingActivityShell(
            @Param("topicId") Long topicId, @Param("merchantMemberId") Long merchantMemberId);

    /** 主题工作台批量读取全部团核销记录，避免按合作商家逐条查询。 */
    public List<CoopGroupVerify> selectByTopicId(@Param("topicId") Long topicId);

    /** 后台核销证据台(§4.5 只读):连主题名/商家/领队昵称,三筛全可空 */
    public List<java.util.Map<String, Object>> selectAdminList(@Param("topicId") Long topicId,
                                                               @Param("merchantMemberId") Long merchantMemberId,
                                                               @Param("leaderMemberId") Long leaderMemberId);

    /** 商家端「已接待团」列表:只读本商家主体的接待记录,附带是否已进入结算。 */
    List<java.util.Map<String, Object>> selectReceptionsByMerchant(@Param("merchantMemberId") Long merchantMemberId);

    /** 撤销前锁定这条接待记录,防止两个店员同时撤销或撤销与重扫交错。 */
    CoopGroupVerify selectByIdForUpdate(@Param("id") Long id);

    /**
     * 锁定主题行并读结算领取位。结算入口 claimTopicForSettlement 会对同一行做 0→1 更新,
     * 撤销持锁期间结算领取必须等待,结算已领取则撤销读到 1 后拒绝。
     */
    Integer selectTopicSettlementFlagForUpdate(@Param("topicId") Long topicId);

    /** 物理删除接待记录(审计表保留完整撤销前快照),删后该团可被重新扫码登记。 */
    int deleteByIdAndMerchant(@Param("id") Long id, @Param("merchantMemberId") Long merchantMemberId);

    /** 俱乐部 360 批量读取已承接主题的团核销证据。 */
    List<ClubFulfillmentWorkspaceDTO.GroupVerification> selectFulfillmentVerificationsByClub(@Param("clubId") Long clubId);
}
