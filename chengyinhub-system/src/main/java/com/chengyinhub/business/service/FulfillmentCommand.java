package com.chengyinhub.business.service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 成交命令。金额单位:元(两位小数)。全部由服务端读配置/事实填入,客户端不得提供。
 *
 * <p>字段是 public 的:它只在 M2/M3/M8 之间的服务端调用里传递,不是 HTTP 绑定对象 ——
 * 没有 Jackson 反序列化入口,也就不存在"客户端多传一个字段"的面。
 */
public final class FulfillmentCommand {

    /** 商品订单幂等键(String,≤64),与 cms_registration.request_id 同类型。 */
    public String requestId;

    /** 商品订单号(marketing_product_order.order_no),写入每行 parent_order_no。 */
    public String parentOrderNo;

    public long memberId;

    /**
     * 商品 id。渠道成交必填(要核对 SKU/受益人);站内成交可空(由 marketing_product_order 决定)。
     */
    public Long productId;

    /**
     * 购买份数。渠道成交必填(要与渠道事实的 quantity 逐值核对)。
     * 站内成交=0,按 {@code lines.size()} 兜底(一站内单每主题一行、每行 1 份)。
     *
     * <p>⚠️ 不能用 {@code lines.size()} 代替份数:"1 个主题买 2 份"时 lines=1、quantity=2,
     * 两者混用会与渠道事实不符而被拒。
     */
    public int quantity;

    /**
     * 席位总数(= 商品每份席位数 × 份数)。渠道成交由券冻结值给出;
     * 落 {@code cms_registration.order_num}(既有席位口径:seatNo 1..N 挂同一条 registration)。
     * 0 = 按份数兜底。
     */
    public int seatCount;

    /**
     * 内容指纹(sha256 hex):由调用方对"商品+修订+分摊+付款事实引用"算好。
     * 仅用于**同一 requestId 的内容变更检测**,不与报名侧 requestFingerprint 混用。
     */
    public String requestFingerprint;

    /** 已核验付款事实(现金必须来自 WechatVerifiedPaymentFactService.requireVerified)。 */
    public PaymentFacts payment;

    /** 每覆盖主题一行,至少 1 行;顺序决定"首行",尾差归首行。 */
    public List<GrantLine> lines;
}
