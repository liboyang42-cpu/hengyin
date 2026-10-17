package com.chengyinhub.business.service;

import java.util.Date;

/**
 * 微信 T+1 交易和资金账单：验签/摘要校验后导入原始事实，不自动调账。
 * 交易金额沿用支付 total/退款 refund 合同，应结额、券额、费用另存。
 */
public interface IWxTradeBillService {
    /** @return 新增交易原始明细行数；成功快照另按成功状态导出。 */
    int downloadAndIngestBill(Date billDate);

    /** 兼容原调用：下载 BASIC 账户资金账单。 */
    int downloadAndIngestFundFlowBill(Date billDate);

    /** 独立导入 BASIC/OPERATION/FEES；不合并账户，不将资金行映射成支付/退款。 */
    int downloadAndIngestFundFlowBill(Date billDate, String accountType);

    /** 官方尚无账单或仍在生成；已记录待核批次，不能当成零交易证明。 */
    class BillUnavailableException extends IllegalStateException {
        public BillUnavailableException(String type, String code) {
            super("微信账单待核 type=" + type + " code=" + code);
        }
    }
}
