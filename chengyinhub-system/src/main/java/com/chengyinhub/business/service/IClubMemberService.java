package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;

/**
 * 俱乐部成员 Service 接口
 */
public interface IClubMemberService
{
    String JOIN_STATE_JOINED = "joined";
    String JOIN_STATE_PENDING = "pending";

    public ClubMember selectByClubAndMember(Long clubId, Long memberId);

    /**
     * 俱乐部治理权限的唯一判据:主理人本人,或 status=1 且 role=1 的管理员。
     * 【★ 权限只认这一个入口】前端把入口藏起来不是权限;凡是「管人 / 内容治理」类接口
     * 一律调它,别在各自的 controller 里各写一份 owner.equals(me)。
     * 「碰钱和合同」的接口(提/撤管理员、解散、清退退款、经营配置、合作邀约)
     * **不要用它** —— 那些仍是 owner-only。
     */
    public boolean canGovernClub(Long clubId, Long actorId);

    /**
     * 同上,但吃调用方**已经拿到的** club 对象。
     * 事务里已经 selectClubByIdForUpdate 过的地方必须用这个 —— 再走一次
     * selectClubById 既多一次库读,又可能读到锁外的旧行。
     */
    public boolean canGovernClub(Club club, Long actorId);

    /** 含待审/已拒绝的关系行，仅用于入会状态机，不得用于权限判断。 */
    public ClubMember selectMembershipRecord(Long clubId, Long memberId);

    public List<ClubMember> selectClubMemberList(ClubMember clubMember);

    public List<Long> selectClubIdsByMember(Long memberId);

    public int countByClub(Long clubId);

    /**
     * 俱乐部成员数的**全端唯一口径**:{@code club_member} 行数 + 主理人(他没有成员行时计 1)。
     *
     * <p>{@code club.member_count} 这一列不含主理人,直接下发会出现同一个俱乐部
     * 在目录里「0 位」、在详情里「1 名」的自相矛盾(CU-M-02 / CU-M-94)。凡是往端上
     * 写「多少成员」的地方一律走这个方法,不要再各自复制那三行判据。
     */
    public int countEffectiveMembers(Club club);

    public int insertClubMember(ClubMember clubMember);

    public int updateClubMember(ClubMember clubMember);

    /**
     * D22:memberId 是不是 topicId 的**承接俱乐部**的主理人或管理员?—— 组织槽核销资格的唯一真源。
     *
     * <p>承接俱乐部取自生效的 {@code to_type='club'} 合作单,**不用 cms_topic.club_id**:
     * 移交承接先挂 club_id 再邀约(ApiTopicController:382),窗口期内 club_id 已为真但俱乐部还没接受。
     * 与 settleTopic:551 的路由同源(selectEffectiveOrdersByTopicId),避免两处口径漂移。
     *
     * <p>资格统一由 ClubAccess 的 {@code club:activity:manage} 权限判定；Owner 仍由
     * {@code club.member_id} 派生，历史 {@code club_member.role} 不再直接授权。
     */
    public boolean isTopicOrganizer(Long topicId, Long memberId);

    /**
     * D22 指定承接俱乐部的主题级组织资格：memberId 是否具备该俱乐部的活动管理权限。
     *
     * <p>团码和核销台账都绑定 {@code cms_activity.id}，不能再用「同一主题下任一承接俱乐部」
     * 代替该场次的责任俱乐部，否则同一领队承接多个俱乐部场次时会越权串团。</p>
     */
    public boolean isTopicOrganizer(Long topicId, Long clubId, Long memberId);

    /** 资金动作专用：该会员是否为主题承接俱乐部的主理人（管理员不包含在内）。 */
    public boolean isTopicOwner(Long topicId, Long memberId);

    /** 资金动作专用场次级判据：必须是指定承接俱乐部的主理人。 */
    public boolean isTopicOwner(Long topicId, Long clubId, Long memberId);

    /** D22:该主题的组织槽有没有人(存在生效的 to_type='club' 合作单)—— 决定谁核销玩家票。 */
    public boolean topicHasOrganizer(Long topicId);

    /**
     * D22:设置成员角色,置管理员时受「至多 2 个」上限约束(主理人之外额外 2 个)。
     *
     * <p>为什么不放在 controller 里「先 count 再拒」:那是**读判**不是条件置位,两个 set-member-role
     * 并发都能过 count → 3 个管理员。管理员上限仍是组织治理约束；玩家票核销另用
     * {@link #isTopicOwner(Long, Long)} 收窄为仅主理人。
     *
     * @return true 已置位(含幂等重复置位);false 已达管理员上限
     */
    public boolean setMemberRoleWithAdminCap(Long clubId, Long memberId, int role);

    public int deleteByClubAndMember(Long clubId, Long memberId);

    /** 原子加入:插入成员关系 + member_count+1(同一事务)。已加入则返回 false 不重复插入。 */
    public boolean joinMember(ClubMember clubMember);

    /** 按俱乐部策略申请加入；返回 joined / pending。 */
    public String requestJoin(Long clubId, Long memberId);

    /** 稿 P3 30:2:申请人可以附一句话。留言只在需审批的团里有意义,公开直进的团直接进,没人看。 */
    public String requestJoin(Long clubId, Long memberId, String joinMessage);

    /** 只有主理人可将待审申请通过或拒绝；管理员不获得审批权限。 */
    public void reviewJoinRequest(Long clubId, Long memberId, Long ownerId, boolean approve);

    void reviewJoinRequest(Long clubId, Long memberId, Long ownerId, boolean approve, String reason);

    /** 只有主理人可读待审列表。 */
    public List<ClubMember> selectPendingJoinRequests(Long clubId, Long ownerId);

    public int countPendingJoinRequests(Long clubId);

    /** 原子退出/移除:成员关系、member_count 与 IM 群隐藏同一事务。返回删除行数。 */
    public int quitMember(Long clubId, Long memberId);

    /**
     * 原子退出/移除并清理 IM 群成员关系。IM 清理异常必须回滚业务成员删除，且即使成员行已不存在
     * 也会执行一次幂等 IM 清理，用于修复历史分叉数据。
     */
    public int quitMemberAndLeaveGroup(Long clubId, Long memberId);

    /** 管理方移除成员时显式携带真实操作者，供角色撤销审计使用。 */
    public int quitMemberAndLeaveGroup(Long clubId, Long memberId, Long actorMemberId,
                                       String actorRoleCode, String reason);

    /**
     * 俱乐部「本团贡献榜」。sortBy: composite(综合分,默认) / mileage(里程) /
     * pace(配速) / duration(完成用时)。
     * 非法值兜底 composite。返回前 50。
     */
    public java.util.List<java.util.Map<String, Object>> selectClubLeaderboard(Long clubId, String sortBy);
}
