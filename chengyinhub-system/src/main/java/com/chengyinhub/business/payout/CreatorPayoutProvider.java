package com.chengyinhub.business.payout;

/**
 * 创作者打款服务商（持牌灵活用工平台等）的可插拔接口。平台不经手个人收款：
 * 由服务商以其资质给创作者结算并开票。新增服务商只需实现本接口并注册为 Spring Bean，
 * 用 {@code payout.creator.provider=<code>} 选中。
 */
public interface CreatorPayoutProvider {

    /** 与 {@code payout.creator.provider} 匹配的代码。 */
    String code();

    /** 提交打款；必须按 outPayoutNo 幂等。 */
    Result submit(Request request);

    /** 查询打款结果。 */
    Result query(String outPayoutNo);

    record Request(String outPayoutNo, long creatorMemberId, long amountFen, String sourceType, long sourceId) { }

    /** state：SUBMITTED / SUCCEEDED / FAILED。 */
    record Result(String state, String providerRef, String failReason) { }
}
