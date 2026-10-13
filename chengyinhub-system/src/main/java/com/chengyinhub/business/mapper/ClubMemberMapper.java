package com.chengyinhub.business.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.ClubMember;

/**
 * 俱乐部成员 Mapper 接口
 */
public interface ClubMemberMapper
{
    public ClubMember selectClubMemberById(Long id);

    /** 查某会员在某俱乐部的成员记录 */
    public ClubMember selectByClubAndMember(@Param("clubId") Long clubId, @Param("memberId") Long memberId);

    /**
     * 锁定某会员当前有效的俱乐部关系。支付回调在先锁 club 行后调用，
     * 与退出/移除的 club -> club_member 锁序一致，避免离会后补发有效权益。
     */
    public ClubMember selectByClubAndMemberForUpdate(@Param("clubId") Long clubId,
                                                      @Param("memberId") Long memberId);

    /** 含待审/已拒绝，用于状态机；绝不能拿来做权限判断。 */
    public ClubMember selectAnyByClubAndMember(@Param("clubId") Long clubId, @Param("memberId") Long memberId);

    /** 成员列表（join ums_member 取昵称/头像） */
    public List<ClubMember> selectClubMemberList(ClubMember clubMember);

    /** 某会员加入的俱乐部ID集合 */
    public List<Long> selectClubIdsByMember(Long memberId);

    public int countByClub(Long clubId);

    /** D22:该俱乐部现有管理员数(role=1 且 status=1 正常态)。不含主理人 —— 他不在本表里。 */
    public int countAdminsByClub(Long clubId);

    public int insertClubMember(ClubMember clubMember);

    public int updateClubMember(ClubMember clubMember);

    public int updateMembershipState(@Param("id") Long id, @Param("status") Integer status,
                                     @Param("role") Integer role, @Param("joinTime") java.util.Date joinTime);

    /** 重新申请时覆盖入会留言。只动这一列,别牵动 updateMembershipState 的其它调用点。 */
    public int updateJoinMessage(@Param("id") Long id, @Param("joinMessage") String joinMessage);

    public List<ClubMember> selectPendingByClub(Long clubId);

    public int countPendingByClub(Long clubId);

    /** 退出：物理删除关系行 */
    public int deleteByClubAndMember(@Param("clubId") Long clubId, @Param("memberId") Long memberId);

    /**
     * 本团贡献榜的原始度量(每成员一行:clearCount/mileage/durationMin/hostedCount)。
     * 不排序、不截断 —— 综合分加权与排序在 ClubMemberServiceImpl(权重是 Java 常量)。
     */
    /**
     * 本团贡献榜的三段聚合。
     *
     * @param sinceInclusive 统计窗起点(含)。传 null = 全时段。
     *        ★生产恒传「本周一 0 点」——UI 上写死了「本团贡献榜 · 本周」「榜单每周一 0 点重置」,
     *        在此之前 SQL 里一个时间条件都没有,用户看到的是终身累计榜,文案是假的。
     *        留 null 这一档是给 IT 用的:用时分段那几条用例要跨月造数据,不该被窗口挡掉。
     */
    public java.util.List<java.util.Map<String, Object>> selectClubContribution(
            @Param("clubId") Long clubId, @Param("sinceInclusive") java.util.Date sinceInclusive);
}
