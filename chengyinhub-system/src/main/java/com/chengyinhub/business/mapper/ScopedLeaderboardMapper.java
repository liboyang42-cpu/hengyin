package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.vo.ScopedLeaderboardEntryVO;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * BE-14 多作用域榜单只读 Mapper（MySQL 8）。
 *
 * <p>全部方法都是 SELECT：分数与排名由现有完成事实（{@code player_node_progress.status=1}）
 * 与现有资格事实（{@code cms_registration} 有效报名，口径同 {@code PostGameOutcomeMapper}）
 * 派生，不新建对局表、不接收客户端 score/passed、缺数据不补 0。
 *
 * <p>run 身份 = {@code (member_id, topic_id, activity_id, play_instance_id)}，
 * 与 {@code topic_route_session.uk_topic_route_owner} 唯一键同一组列；
 * 同一局重放只会命中同一行，不会重复计分。
 *
 * <p>city/country 只读**该局冻结快照**
 * （{@code topic_route_session.config_snapshot_json.themeConfig.contentGeography}），
 * 没有冻结值的旧局按 UNKNOWN 排除，不取当前可编辑值补历史。
 */
public interface ScopedLeaderboardMapper {

    /**
     * 作用域内前 N 名：score = 完成节点数；ties 沿现实现的线性口径
     * （最后一次完成更早者靠前，再按 member_id）。
     *
     * @param scope       world/city/country/quest/club（调用方已白名单校验）
     * @param scopeId     city=冻结 cityCode / country=冻结 ISO 国家码 / quest=主题id / club=俱乐部id；world 可空
     * @param scopeIdLong quest/club 的数值形态；world/city/country 可为 null
     * @param weekStart   周榜窗口起点（含），累计榜传 null
     * @param weekEnd     周榜窗口终点（不含）
     */
    List<ScopedLeaderboardEntryVO> selectScopedTop(@Param("scope") String scope,
                                                   @Param("scopeId") String scopeId,
                                                   @Param("scopeIdLong") Long scopeIdLong,
                                                   @Param("weekStart") Date weekStart,
                                                   @Param("weekEnd") Date weekEnd,
                                                   @Param("limit") int limit);

    /** 榜外「我」：同一作用域口径下的本人成绩；无有效对局返回 null。 */
    ScopedLeaderboardEntryVO selectScopedMemberRow(@Param("scope") String scope,
                                                   @Param("scopeId") String scopeId,
                                                   @Param("scopeIdLong") Long scopeIdLong,
                                                   @Param("weekStart") Date weekStart,
                                                   @Param("weekEnd") Date weekEnd,
                                                   @Param("memberId") Long memberId);

    /** 与我同作用域同口径的严格名次（严格高于我的人数 + 1）；本人无有效对局返回 null。 */
    Integer selectScopedMemberRank(@Param("scope") String scope,
                                   @Param("scopeId") String scopeId,
                                   @Param("scopeIdLong") Long scopeIdLong,
                                   @Param("weekStart") Date weekStart,
                                   @Param("weekEnd") Date weekEnd,
                                   @Param("memberId") Long memberId);

    /**
     * 有完成节点记录但无当前有效报名的对局组数（未支付/取消/过期/已退款/无法归因的旧局）。
     * 用于 coverage，不参与排名。
     */
    long countUnverifiedRuns(@Param("scope") String scope,
                             @Param("scopeId") String scopeId,
                             @Param("scopeIdLong") Long scopeIdLong,
                             @Param("weekStart") Date weekStart,
                             @Param("weekEnd") Date weekEnd);

    /**
     * city/country 专用：报名有效但该局没有请求维度的冻结地理归属（UNKNOWN）的对局组数。
     * 其它作用域恒 0。
     */
    long countUnattributedRuns(@Param("scope") String scope,
                               @Param("scopeId") String scopeId,
                               @Param("scopeIdLong") Long scopeIdLong,
                               @Param("weekStart") Date weekStart,
                               @Param("weekEnd") Date weekEnd);

    /** 指定作用域下进入排名的人数。 */
    long countRankedMembers(@Param("scope") String scope,
                            @Param("scopeId") String scopeId,
                            @Param("scopeIdLong") Long scopeIdLong,
                            @Param("weekStart") Date weekStart,
                            @Param("weekEnd") Date weekEnd);
}
