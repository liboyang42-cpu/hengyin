package com.chengyinhub.business.service.support;

import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.mapper.MerchantChapterApplicationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * W2 招募工作流写侧 seam 的唯一实现。语义只有一句:<b>招募窗口关了,把还悬着的东西收干净</b>。
 *
 * <p><b>为什么这个类必须存在</b>:在它落地之前,这个接口全仓<b>零实现</b> ——
 * {@code getIfAvailable()} 在生产上恒为 null,于是
 * {@code TopicSaleFinalizationServiceImpl:140-142} 永远拒绝开售,
 * {@code CmsTopicServiceImpl} 的招募到期任务永远走 null 分支。
 * 换句话说 RECRUITING → PRICING → SELLING 这条链在生产上<b>从来没有通过电</b>,
 * 而单测全绿是因为每个用例都把这个 seam 塞成 mock,替生产补上了一个生产里不存在的 bean。
 *
 * <p><b>本轮只做两件事,不许有第三件</b>:
 * <ol>
 *   <li>该期 pending 的 {@code coop_invite} 置 5已过期;</li>
 *   <li>该期 {@code status=0} 的 {@code merchant_chapter_application} 置 2已拒绝。</li>
 * </ol>
 * 不发通知、不退款、不释放名额、不改 offer、不碰 entitlement —— 那三类各有独立的资金/通知语义。
 *
 * <p><b>先数后写</b>:两步都先用<b>与 update 逐字同口径</b>的 where 数一遍并打印,再写,
 * 然后比对受影响行数。不一致即抛 —— 调用方已持有 topic 行锁,数与写之间本不该有人插进来;
 * 真出现了就是锁假设被破坏,那种时候继续写比停下来危险。
 *
 * <p><b>幂等</b>:两条 update 都把状态条件写在 where 里(不是先查后写),重放第二次命中 0 行。
 * 招募到期任务本身会重试,这一条是必需的。
 *
 * <p><b>只圈本期</b>:两条语句都以 {@code topic_id = #{topicId}} 为唯一作用域键,绝不跨期。
 */
@Component
public class PendingRecruitWorkflowTerminatorImpl implements PendingRecruitWorkflowTerminator {

    private static final Logger logger = LoggerFactory.getLogger(PendingRecruitWorkflowTerminatorImpl.class);

    /** 落在 audit_remark 里的机器原因;audit_member_id 保持 NULL —— 没有人审过它。 */
    static final String CLOSED_REMARK = "招募窗口已关闭，未在窗口内完成审核，系统统一置为未通过";

    private final CoopInviteMapper coopInviteMapper;
    private final MerchantChapterApplicationMapper applicationMapper;

    public PendingRecruitWorkflowTerminatorImpl(CoopInviteMapper coopInviteMapper,
                                                MerchantChapterApplicationMapper applicationMapper) {
        this.coopInviteMapper = coopInviteMapper;
        this.applicationMapper = applicationMapper;
    }

    @Override
    public void terminatePendingUnderTopicLock(Long topicId, Date now) {
        if (topicId == null || now == null) {
            throw new IllegalArgumentException("topicId 与 now 不能为空");
        }

        int expectedInvites = coopInviteMapper.countPendingByTopic(topicId);
        logger.info("W2招募终结-主题 {} 待终结 pending 邀约 {} 条", topicId, expectedInvites);
        int expiredInvites = coopInviteMapper.expirePendingInvitesByTopic(topicId, now);
        assertMatched(topicId, "coop_invite", expectedInvites, expiredInvites);

        int expectedApplications = applicationMapper.countPendingByTopic(topicId);
        logger.info("W2招募终结-主题 {} 待终结 pending 承接申请 {} 条", topicId, expectedApplications);
        int rejectedApplications = applicationMapper.rejectPendingByTopic(topicId, CLOSED_REMARK, now);
        assertMatched(topicId, "merchant_chapter_application", expectedApplications, rejectedApplications);

        logger.info("W2招募终结-主题 {} 完成:邀约过期 {} 条,承接申请置未通过 {} 条",
                topicId, expiredInvites, rejectedApplications);
    }

    private static void assertMatched(Long topicId, String table, int expected, int actual) {
        if (expected != actual) {
            throw new IllegalStateException("W2招募终结-主题 " + topicId + " 的 " + table
                    + " 先数到 " + expected + " 行、实际写了 " + actual
                    + " 行:调用方本应持有 topic 行锁,行数对不上说明锁假设被破坏，拒绝继续");
        }
    }
}
