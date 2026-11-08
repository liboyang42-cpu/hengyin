package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MarketingProductOrder;
import com.chengyinhub.business.domain.MarketingProductOrderLine;
import com.chengyinhub.business.service.GrantLine;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/** 商品订单。所有状态迁移都带原状态条件，重放/并发只允许一次生效。 */
public interface MarketingProductOrderMapper {

    int insert(MarketingProductOrder order);

    MarketingProductOrder selectById(@Param("id") Long id);

    MarketingProductOrder selectByOrderNo(@Param("orderNo") String orderNo);

    MarketingProductOrder selectByOrderNoForUpdate(@Param("orderNo") String orderNo);

    /** C07 退款事实按父 registration 回查营销单。 */
    MarketingProductOrder selectByPrimaryRegistrationId(@Param("registrationId") Long registrationId);

    /** 多作品包的子 registration 回查；只读映射，不是第二权益真源。 */
    MarketingProductOrder selectByRegistrationIdInJson(@Param("registrationId") Long registrationId);

    MarketingProductOrder selectByMemberRequest(@Param("memberId") Long memberId,
                                                @Param("requestId") String requestId);

    List<MarketingProductOrder> selectByMember(@Param("memberId") Long memberId,
                                               @Param("limit") int limit);

    /** 待激活：已付、首次使用型、且尚未由原注册服务发放。 */
    MarketingProductOrder selectPendingActivation(@Param("memberId") Long memberId,
                                                  @Param("topicId") Long topicId);

    /** 成交时冻结的分摊行；M2-F 首次使用激活只读本表。 */
    List<MarketingProductOrderLine> selectFrozenLines(@Param("orderNo") String orderNo);

    /** M2-F 激活读口：按订单号返回冻结 GrantLine（不解析 JSON）。 */
    List<GrantLine> selectGrantLines(@Param("orderNo") String orderNo);

    /** 单主题冻结行；找不到返回 null。 */
    GrantLine selectGrantLine(@Param("orderNo") String orderNo, @Param("topicId") Long topicId);

    int insertFrozenLine(MarketingProductOrderLine line);

    int markGrantState(@Param("id") Long id,
                       @Param("state") Integer state,
                       @Param("updateTime") Date updateTime);

    int markPointsPaid(@Param("id") Long id,
                       @Param("factId") Long factId,
                       @Param("paidAt") Date paidAt,
                       @Param("updateTime") Date updateTime);

    int markCashPaid(@Param("id") Long id,
                     @Param("factId") Long factId,
                     @Param("paidAt") Date paidAt,
                     @Param("updateTime") Date updateTime);

    /** M2-F grant 返回后绑定父/子 cms_registration；已绑定不覆盖（防第二次 grant 改身份）。 */
    int bindRegistrations(@Param("id") Long id,
                          @Param("primaryRegistrationId") Long primaryRegistrationId,
                          @Param("primaryRegistrationNo") String primaryRegistrationNo,
                          @Param("registrationIdsJson") String registrationIdsJson,
                          @Param("updateTime") Date updateTime);

    /**
     * 激活型多主题：把新产生的准入行 id 原子追加进 {@code registration_ids_json}。
     * 单条 SQL（JSON 侧追加），不做读-改-写，两个主题并发激活不会互相覆盖；
     * 不改 primary / 首次绑定的既有语义。
     */
    int appendRegistrationId(@Param("id") Long id,
                             @Param("registrationId") Long registrationId,
                             @Param("updateTime") Date updateTime);

    int markCancelled(@Param("id") Long id, @Param("updateTime") Date updateTime);

    int markRefunded(@Param("id") Long id,
                     @Param("reason") String reason,
                     @Param("refundedAt") Date refundedAt,
                     @Param("updateTime") Date updateTime);
}
