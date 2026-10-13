package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.RoamStamp;
import com.chengyinhub.business.domain.RoamStampExchange;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 漫游集邮 Mapper。列表必须按 member 过滤 + 分页,不能全量拉。 */
public interface RoamStampMapper {

    int insertStamp(RoamStamp stamp);

    /** 我的邮票:按时间倒序分页。只返回未删除且未被判违规的。 */
    List<RoamStamp> selectByMember(@Param("memberId") Long memberId,
                                   @Param("offset") int offset,
                                   @Param("limit") int limit);

    int countByMember(@Param("memberId") Long memberId);

    RoamStamp selectById(@Param("id") Long id);

    /** 按幂等键回查。insert 撞 1062 时用它区分「撞的是 uk_member_idem」——
     *  查得到=幂等命中返同一张;查不到=撞的是另一道 uk(如盖销 uk_member_geohash),不能当幂等吞掉。 */
    RoamStamp selectByMemberAndIdemKey(@Param("memberId") Long memberId, @Param("idemKey") String idemKey);

    /** 送检成功后回填 trace_id,供回调按 trace 找回本票。 */
    int updateCheckTrace(@Param("id") Long id, @Param("checkTrace") String checkTrace);

    /** 机审通过:仅从 0 迁到 1。带 check_state=0 条件是为了绝不复活已判违规(2)的票 —— 违规必须是单向的。 */
    int markCheckPassed(@Param("id") Long id);

    /** 机审违规:无条件置 2。违规判定永远赢,不管当前是 0 还是 1。 */
    int markCheckRisky(@Param("id") Long id);

    // ── 投一张换一张 ────────────────────────────────────────────────────────────

    /**
     * 可以换给我的候选票 id,「上一个人那张」优先(按入册时间倒序)。
     *
     * 三条硬筛,少一条都会出事:
     *   ① member_id != 我     —— 换回自己那张不是交换
     *   ② check_state = 1     —— 只发机审通过的。未送检(0)是「先发后审」,给自己看可以,
     *                            发给陌生人不行;违规(2)更不用说
     *   ③ 我没收到过           —— 换出的必须是没见过的
     * 取多条是为了让应用层在撞 uk 时能顺位重试,不是为了随机。
     */
    List<Long> selectExchangeCandidates(@Param("memberId") Long memberId,
                                                  @Param("limit") int limit);

    int insertExchange(RoamStampExchange exchange);

    /** 按「我投出的那张」回查交换记录。insert 撞 uk_member_given 时用它返回同一枚回礼。 */
    RoamStampExchange selectExchangeByGiven(@Param("memberId") Long memberId,
                                            @Param("givenStampId") Long givenStampId);

    /** 我换回来的那些票,按换到的时间倒序分页。 */
    List<RoamStamp> selectReceivedByMember(@Param("memberId") Long memberId,
                                                     @Param("offset") int offset,
                                                     @Param("limit") int limit);

    int countReceivedByMember(@Param("memberId") Long memberId);
}
