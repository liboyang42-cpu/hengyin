package com.chengyinhub.business.service;

import java.util.Date;

/**
 * 微信交易账单(T+1下载)服务合同(P1-6 N块)。
 *
 * <p>★架构决策(已定稿,不再评估选型):落地方式 = T+1 账单下载入库,不是实时拉取查单接口。
 * 微信官方为对账场景设计了「交易账单」(download_bill)和「资金账单」(download_fund_flow_bill)
 * 两个官方接口,T+1 早10点后可下载 T 日完整账单——这是微信官方给的"当天应该有哪些交易"的权威清单,
 * 实时查单接口做不了批量交叉对账(不知道要问哪些单号)。
 *
 * <p>⚠️ <b>本轮范围到此为止</b>:只冻结接口签名,方法体是 TODO 骨架,不接入真实微信下载 API,
 * 不跑通,不伪造假数据硬跑通。真实实现属 P1-7(需要真实商户环境联调)。
 */
public interface IWxTradeBillService {

    /**
     * 下载并解析入库指定日期的微信交易账单(交易账单,含支付成功+退款成功记录)。
     *
     * <p><b>TODO(P1-7)实现要点</b>,先记在合同上,免得实现时重新调研:
     * <ol>
     *   <li>调用微信支付 v3「申请交易账单」接口(bill_type=ALL),拿到 download_url;</li>
     *   <li>用 download_url 下载账单文件(GZip压缩的CSV,微信侧签名的临时链接,有效期30秒);</li>
     *   <li>解析 CSV,每行按 {@link com.chengyinhub.business.domain.WxTradeBillSnapshot} 落库,
     *       只落"交易状态"为成功的行(SUCCESS/REFUND.SUCCESS),失败/关闭态不落;</li>
     *   <li>落库走 {@code insertSnapshot},撞 {@code uk_wx_trade_bill_no} 视为已入库,吞掉重复键异常
     *       继续下一行(不能让一行重复中断整批解析);</li>
     *   <li>账单当天可能不存在(T日无交易)或尚未生成(T+1 10点前查询会返回"资源尚未生成"错误码),
     *       两种情况都不是异常,是正常的"暂无数据"态,调用方(定时任务)应据此判断是否需要延后重试。</li>
     * </ol>
     *
     * @param billDate 账单归属日期(T日)
     * @return 本次成功落库的行数
     * @throws UnsupportedOperationException 本轮未实现,骨架方法
     */
    int downloadAndIngestBill(Date billDate);

    /**
     * 下载并解析入库指定日期的微信资金账单(资金账单,反映账户资金变动,用于与交易账单交叉校验)。
     *
     * <p>TODO(P1-7):调用「申请资金账单」接口(account_type=BASIC),解析后可用于交叉校验交易账单
     * 是否有遗漏(理论上资金账单的资金流水应能与交易账单的支付/退款一一对应)。本轮不实现。
     *
     * @throws UnsupportedOperationException 本轮未实现,骨架方法
     */
    int downloadAndIngestFundFlowBill(Date billDate);
}
