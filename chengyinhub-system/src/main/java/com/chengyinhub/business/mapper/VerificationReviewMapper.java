package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.VerificationReview;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

public interface VerificationReviewMapper {
    int insertIgnore(VerificationReview review);
    Map<String, Object> selectPendingQueueStats();
    Map<String, Object> selectVerifierStats(@Param("verifierMemberId") Long verifierMemberId,
                                             @Param("registrationId") Long registrationId);
    List<VerificationReview> selectQueue(VerificationReview query);

    /** 处置前解析对象归属(topic_id)用;端点必须先读它再判范围,不能凭前端传的 topicId 放行。 */
    VerificationReview selectById(@Param("id") Long id);

    /**
     * 批 5 · T3:人工处置(0 待复核 → 1 已确认正常 / 2 确认异常)。
     *
     * <p>WHERE 同时带 {@code topic_id} 与 {@code status = 0} 两条守卫:
     * 前者把「端点判过范围的那一行」写进条件本身,消掉读-判-写之间的 TOCTOU;
     * 后者让重复处置返回 0 行而不是把别人刚下的结论覆写掉。调用方只认返回值。
     *
     * <p>本方法**只改 status**。不得在这里连带改 {@code redemption} / 退款 / 任何资金 ——
     * 队列是人审标记,不是自动处置引擎(审计 3 · T3)。
     */
    int updateStatus(@Param("id") Long id, @Param("topicId") Long topicId,
                     @Param("status") Integer status);
}
