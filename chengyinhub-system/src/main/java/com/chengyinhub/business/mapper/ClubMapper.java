package com.chengyinhub.business.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubPendingSettlement;
import com.chengyinhub.business.domain.ClubPendingDeposit;

/**
 * 俱乐部 Mapper 接口
 */
public interface ClubMapper
{
    public Club selectClubById(Long id);

    /**
     * 行锁读 —— 把「管理员至多 2」这类跨行计数约束串行化。锁的是**分组键**(club 行),
     * 不是被计数的 club_member 行。⚠️ 必须在事务里调,否则锁随语句释放 = 没锁。
     */
    public Club selectClubByIdForUpdate(Long id);

    public Club selectClubByMemberId(Long memberId);

    /** 按会员ID取全部俱乐部(多俱乐部 C1;cap 计数与 /my owned 用) */
    public List<Club> selectClubsByMemberId(Long memberId);

    /** 配额写路径 current read；调用方须先锁 ums_member，并在同一事务内持有到提交。 */
    public List<Long> selectOwnedClubIdsForUpdate(Long memberId);

    /** 全局主理人能力收口 current read；只返回仍在营的非删除俱乐部。 */
    public List<Long> selectActiveOwnedClubIdsForUpdate(Long memberId);

    /** 工作台批量补齐俱乐部显示名，避免对申请/邀约/合作单逐条查询。 */
    public List<Club> selectClubsByIds(@Param("clubIds") List<Long> clubIds);

    /**
     * 俱乐部名称全站唯一(用户 2026-10-20 决定):数去空格后同名、待审或在营、未删除的俱乐部。
     * memberId 不为空时跳过该主理人自己的行(本人同名按重放处理);excludeClubId 不为空时跳过正在改名的那一行。
     */
    public int countClubsHoldingName(@Param("name") String name, @Param("memberId") Long memberId,
            @Param("excludeClubId") Long excludeClubId);

    public List<Club> selectClubList(Club club);

    public int insertClub(Club club);

    public int updateClub(Club club);

    public int deleteClubById(Long id);

    public int deleteClubByIds(Long[] ids);

    /** 成员数增减 delta(+1/-1) */
    /** 稿 M「开放设置 · 俱乐部公开可见」。0=仅成员能搜到和打开 1=所有人。 */
    public int updatePublicVisible(@Param("clubId") Long clubId, @Param("enabled") Integer enabled);

    /** 稿 M「开放设置 · 允许成员发帖」。0=仅管理者可发 1=所有成员可发。 */
    public int updateMemberPostAllowed(@Param("clubId") Long clubId, @Param("enabled") Integer enabled);

    /** 入会欢迎语:单独读写,不进 selectClubVo —— 列未落库时只影响这一项,不拖垮整个俱乐部查询。 */
    public String selectWelcomeNote(@Param("clubId") Long clubId);

    public int updateWelcomeNote(@Param("clubId") Long clubId, @Param("note") String note);

    /** 稿 M「开放设置 · 开放商家承接」。0=本俱乐部主题不进商家可承接列表 1=进。 */
    public int updateMerchantUndertakeOpen(@Param("clubId") Long clubId, @Param("enabled") Integer enabled);

    public int updateMemberCount(@Param("clubId") Long clubId, @Param("delta") int delta);

    /** 内容安全:清空俱乐部资料图片(logo/封面/案例图),违规图回调下架用 */
    public int clearProfileMedia(@Param("id") Long id);

    /** 迁移兼容探针：不引用尚不存在的业务列。 */
    public int countJoinPolicyColumn();

    public Integer selectJoinPolicy(@Param("clubId") Long clubId);

    public int updateJoinPolicy(@Param("clubId") Long clubId, @Param("joinPolicy") Integer joinPolicy);

    /** 本团仍在营且 is_settlement=0 的主题；不下钻重复场次。 */
    public int countUnsettledRevenueByClub(@Param("clubId") Long clubId);

    /** 本团主题的待打款义务，以及直接待打给本团的带队费；返回逐笔待收/待付引用。 */
    public List<ClubPendingSettlement> selectUnpaidSettlementsByClub(@Param("clubId") Long clubId);

    /** 尚未结算完成的生效承接合作；解散会让后续结算失去俱乐部收款主体。 */
    public int countActiveCooperationsByClub(@Param("clubId") Long clubId);

    /** 生效合作缺少父主题，无法判定结算状态；仍阻塞并供服务记录异常日志。 */
    public int countActiveCooperationsWithMissingTopicByClub(@Param("clubId") Long clubId);

    /** 支付中、冻结待退或应缴未缴的合作保证金；返回邀约 ID 供用户直达逐笔处理。 */
    public List<ClubPendingDeposit> selectPendingDepositsByClub(@Param("clubId") Long clubId);

    /** 尚未结束且未取消的已发布场次。 */
    public int countOngoingSessionsByClub(@Param("clubId") Long clubId);

    /** 待支付/支付中，或已支付但尚未核销的报名。 */
    public int countIncompleteRegistrationsByClub(@Param("clubId") Long clubId);

    /** 生命周期解散专用：遵循全库软删约定 del_flag=2。 */
    public int dissolveClubById(@Param("clubId") Long clubId);
}
