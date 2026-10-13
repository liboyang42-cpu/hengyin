package com.chengyinhub.business.mapper;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.chengyinhub.business.domain.MerchantReferralBounty;

/**
 * 拓展人激活赏金 Mapper。
 *
 * @author chengyinhub
 */
public interface MerchantReferralBountyMapper
{
    /** 按被推荐商家查(uk_merchant 保证至多一条)。 */
    MerchantReferralBounty selectByMerchantMemberId(Long merchantMemberId);

    /**
     * 占位插入。依赖 uk_merchant 唯一键做幂等:并发/重复触发只有一方成功。
     *
     * <p>⚠️ 调用方必须容忍 DuplicateKeyException = 「别人已占位」,不是错误。
     */
    int insertBounty(MerchantReferralBounty bounty);

    /**
     * CAS 置为已发放:仅 status=0(待激活)可置 1。
     *
     * @return 1=本次由我置位(该发钱);0=已被别人置过(别重复发)
     */
    int markPaidIfPending(@Param("id") Long id,
                          @Param("activatedAt") Date activatedAt,
                          @Param("snapshot") String snapshot,
                          @Param("amount") java.math.BigDecimal amount,
                          @Param("paidAt") Date paidAt,
                          @Param("frozenUntil") Date frozenUntil);

    /**
     * CAS 追回:仅 status=1(已发放)可置 2。
     *
     * @return 1=本次由我追回;0=没发过或已追过
     */
    int markClawedBackIfPaid(@Param("id") Long id,
                             @Param("clawedBackAt") Date clawedBackAt,
                             @Param("reason") String reason);

    /**
     * CAS 置失效:仅 status=0(待激活)可置 3。超 30 天窗口未达标时用。
     *
     * <p>⚠️ 必须是独立方法 —— 早先图省事复用 markPaidIfPending 传 null,那会把 status 置成
     * <b>1(已发放)却不发钱</b>,直接造出「记录说发了、余额没动」的假账,正是本轨要消灭的病。
     *
     * @return 1=本次由我置失效;0=状态已不是待激活
     */
    int markExpiredIfPending(@Param("id") Long id);

    /** 某拓展人**已发放**的家数(封顶 20 用;不含已追回 —— 追回后名额应释放)。 */
    int countPaidByInviter(@Param("inviterMemberId") Long inviterMemberId);

    /**
     * ★ 该会员当前**仍在 48h 冻结期内**的赏金总额 —— 提现时要从可用余额里刨掉。
     *
     * <p><b>为什么必须有这个方法</b>:{@code frozen_until} 列建出来的那一刻起,全仓<b>零读方</b>。
     * 提现({@code UmsMemberWithdrawalServiceImpl.withdrawalRequest})只校验
     * {@code balance >= amount},压根不认冻结。没有这个查询,「48h 冻结」就只是列名里的一句话 ——
     * 钱当天就能提走,clawback 追回时只能把余额扣成负数,而挂账抵扣(定稿 D14)依赖收付通、尚未到位。
     * 这正是本轨要消灭的「规则只活在字段名里」。
     *
     * @return 冻结总额;无冻结时返回 0(不是 null —— XML 里用 COALESCE 兜住)
     */
    java.math.BigDecimal sumFrozenByInviter(@Param("inviterMemberId") Long inviterMemberId,
                                            @Param("now") Date now);

    /**
     * ★ 该商家**自己扫码核销**、且报名发生在窗口内的**去重人头数**。
     *
     * <p>与既有的 {@code CmsRegistrationMapper.selectVerifiedHeadsByTopicAndMerchant} 的区别:
     * 那个**绑死单个 topicId**(答「该商家在这个主题里核销几个人头」),而赏金要的是
     * **跨主题的总数**。故不能复用,必须单写。
     */
    int countVerifiedHeadsByMerchantInWindow(@Param("merchantMemberId") Long merchantMemberId,
                                             @Param("windowStart") Date windowStart,
                                             @Param("windowEnd") Date windowEnd);

    /**
     * ★ 反套利闸:窗口内该商家核销的报名**合计实付**多少。
     *
     * <p><b>为什么必须有</b>:{@code payment_status=2} 不代表真付了钱 —— 生产实证(2026-07-17)
     * reg#47 / reg#71 实付均 <b>0.00</b> 而 payment_status=2;72 张票里最低票价 0.00。
     * 没有金额下限,建个 0 元票主题 + 3 个小号自扫自核 = <b>零成本套走赏金</b>。
     *
     * <p>只认 payable_amount,不回落 total_amount —— 详见 XML 注释(reg#71 payable=0 而 total=39.9,
     * 回落就等于亲手打开要堵的那个洞)。
     */
    java.math.BigDecimal sumVerifiedPaidByMerchantInWindow(@Param("merchantMemberId") Long merchantMemberId,
                                                           @Param("windowStart") Date windowStart,
                                                           @Param("windowEnd") Date windowEnd);

    /**
     * 构成激活的报名 id —— <b>每个人头只取最早那一条</b>,取最早的 N 个人头,存快照用。
     *
     * <p>⚠️ 不是「最早的 N 条报名」:那样同一玩家报名两次会占掉两个坑,快照就不再代表 N 个人头,
     * 而 {@link #countStillValidAmong} 数的是行数 ⇒ clawback 会误追(见 XML 注释的推演)。
     */
    List<Long> selectVerifiedRegistrationIdsByMerchantInWindow(@Param("merchantMemberId") Long merchantMemberId,
                                                               @Param("windowStart") Date windowStart,
                                                               @Param("windowEnd") Date windowEnd,
                                                               @Param("limit") int limit);

    /** 快照里这些报名,现在还有几条是「有效核销」(未退款)= 仍有效的人头数。clawback 判定用。 */
    int countStillValidAmong(@Param("registrationIds") List<Long> registrationIds,
                             @Param("merchantMemberId") Long merchantMemberId);
}
