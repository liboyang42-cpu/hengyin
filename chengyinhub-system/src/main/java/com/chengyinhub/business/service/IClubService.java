package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubDissolutionBlockers;
import com.chengyinhub.business.domain.ClubLeader;

/**
 * 俱乐部 Service 接口
 */
public interface IClubService
{
    public Club selectClubById(Long id);

    /** 事务内锁定仍在营的俱乐部；所有会新建场次/报名/合作的写路径与解散共用此互斥点。 */
    public Club selectClubByIdForUpdate(Long id);

    public Club selectClubByMemberId(Long memberId);

    /** 按会员ID取全部俱乐部(多俱乐部 C1) */
    public List<Club> selectClubsByMemberId(Long memberId);

    public List<Club> selectClubList(Club club);

    public int insertClub(Club club);

    /** 同事务锁 owner 会员行、校验名下俱乐部配额并插入；返回插入后的名下数量。 */
    public int insertClubWithinOwnerQuota(Club club, int maxOwnedClubs);

    /** 同一会员身份锁内保存主理人档案、能力和默认视角；商家 owner/operator 一律拒绝。 */
    public ClubLeader saveLeaderProfileWithinIdentityMutex(Long memberId, ClubLeader profile);

    /** 兼容 apply：主理人入驻和首个俱乐部必须同事务成功或回滚。 */
    public int onboardLeaderAndInsertClubWithinIdentityMutex(
            Long memberId, ClubLeader profile, Club club, int maxOwnedClubs);

    public int updateClub(Club club);

    public int deleteClubByIds(Long[] ids);

    public int deleteClubById(Long id);

    /** 成员数增减 */
    public int updateMemberCount(Long clubId, int delta);

    /** join_policy 列是否已落库；缺列时所有调用降级为公开直进。 */
    public boolean isJoinPolicySupported();

    /** 读取加入策略；迁移未落库或值异常一律返回 0（公开直进）。 */
    public int selectJoinPolicy(Long clubId);

    /** 主理人资料与加入策略同一事务保存；返回当前库是否支持 join_policy。 */
    public boolean updateOwnedClubProfile(Club club, Long ownerId, Integer joinPolicy);

    /**
     * 稿 M 268:223「开放设置 · 允许成员发帖」。只有主理人能改。
     * 关掉之后,只有主理人/管理员/拿到内容管理权限的人还能在俱乐部发帖。
     */
    public void updateMemberPostAllowed(Long clubId, Long ownerId, boolean enabled);

    /**
     * 稿 M 268:223「开放设置 · 俱乐部公开可见」。只有主理人能改。
     * 关掉之后这个俱乐部不再出现在公开列表里,非成员也打不开详情。
     */
    public void updatePublicVisible(Long clubId, Long ownerId, boolean enabled);

    /**
     * 稿 M 268:223「开放设置 · 开放商家承接」。只有主理人能改。
     * 关掉之后本俱乐部的主题不再出现在商家合作中心的「开放承接路线」里。
     */
    public void updateMerchantUndertakeOpen(Long clubId, Long ownerId, boolean enabled);

    /**
     * 主理人解散俱乐部。返回受影响的正常成员数；资金、场次、报名、确认任一闸不过均抛具体异常。
     */
    public int dissolveClub(Long clubId, Long ownerId, boolean confirmed, boolean memberConsequencesConfirmed);

    /** 主理人专用：读取即使父对象已软删仍必须处理的资金阻断逐笔明细。 */
    public ClubDissolutionBlockers selectDissolutionBlockers(Long clubId, Long ownerId);
}
