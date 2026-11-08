package com.chengyinhub.business.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.chengyinhub.business.domain.VideoBountyAccrual;

/**
 * 视频悬赏应计 Mapper。insert-only + 幂等键;重算用 SUPERSEDED 标记而非覆盖历史。
 *
 * @author chengyinhub
 */
public interface VideoBountyAccrualMapper
{
    VideoBountyAccrual selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    /**
     * 该 (campaign, group) 既有应计行的最大 id（无行返回 null）。
     *
     * <p>用作重算的<b>代次</b>：整组替换只在"写入新行"时才作废旧行，因此这个值单调不减、
     * 且每次真正写入后必增大。用它区分"同一输入的第 N 次写入"与"回到旧组成的旧键"，
     * 不需要随机数，也不需要第二张账本。
     */
    Long selectMaxIdByGroup(@Param("campaignId") Long campaignId,
                            @Param("groupKey") String groupKey);

    List<VideoBountyAccrual> selectBySubmission(@Param("submissionId") Long submissionId);

    List<VideoBountyAccrual> selectByGroup(@Param("campaignId") Long campaignId,
                                           @Param("groupKey") String groupKey);

    List<VideoBountyAccrual> selectAdminPage(@Param("campaignId") Long campaignId,
                                             @Param("offset") int offset,
                                             @Param("limit") int limit);

    int insertAccrual(VideoBountyAccrual accrual);

    /**
     * 把某投稿仍"有效"的旧应计标记为 SUPERSEDED —— <b>排除刚写入的那一条</b>。
     *
     * <p>为什么必须排除:并发重算时,输家在唯一键上失败并读回赢家的新行;若把"所有有效行"
     * 都置 SUPERSEDED,赢家那条新行会被输家抹掉,结果"算过却没有任何有效应计"。
     */
    int supersedeActiveBySubmissionExcept(@Param("submissionId") Long submissionId,
                                          @Param("keepId") Long keepId);

    /**
     * 把<b>同一原视频组</b>内其他投稿的仍有效应计标记为 SUPERSEDED（排除刚写入的那一条）。
     *
     * <p>为什么必须按组而不是按投稿：封顶是「组内合计 ≤ per_video_cap」，额度按组内播放量
     * 比例分摊。只 supersede 自己这一条时，组内另一条仍是旧额度下的旧值 ——
     * 例：A 10 万→50、B 10 万→50（合计 100 = cap）；B 涨到 30 万后重算得 75，
     * 若 A 的 50 仍有效，组内合计 125 就超过了封顶。<b>每次重算必须重写整组。</b>
     *
     * <p>由调用方在同一事务、同一活动行锁内执行；不影响其他组与其他活动。
     */
    int supersedeActiveByGroupExcept(@Param("campaignId") Long campaignId,
                                     @Param("groupKey") String groupKey,
                                     @Param("keepId") Long keepId);

    /**
     * 整组替换：把同组内<b>不在保留集</b>的有效应计全部置 SUPERSEDED。
     *
     * <p>一次重算要为组内每个投稿都落一条新行（额度按组内播放量比例分摊），
     * 因此保留集是"这一轮写入的全部行"，而不是单独一条。
     */
    int supersedeActiveByGroupExceptIds(@Param("campaignId") Long campaignId,
                                        @Param("groupKey") String groupKey,
                                        @Param("keepIds") java.util.List<Long> keepIds);

    /** 撤销:把某投稿有效应计置 REVERSED(不删除,不留白)。 */
    int reverseActiveBySubmission(@Param("submissionId") Long submissionId,
                                  @Param("note") String note);

    /** 该投稿当前有效应计(最多一条)。 */
    VideoBountyAccrual selectActiveBySubmission(@Param("submissionId") Long submissionId);
}
