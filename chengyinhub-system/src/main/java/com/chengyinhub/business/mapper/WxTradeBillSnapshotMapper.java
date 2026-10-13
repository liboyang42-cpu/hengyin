package com.chengyinhub.business.mapper;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.WxTradeBillSnapshot;

/**
 * 微信交易账单本地留痕快照 Mapper(P1-6 N块)。
 *
 * <p>本轮只供四账对账 Service 只读消费,以及未来 P1-7 下载任务落库用;不含 update/delete
 * ——账单行是外部权威事实的留痕,不应在本地被改写,重新下载同一天账单靠 {@code uk_wx_trade_bill_no}
 * 幂等去重,不是先查后改。
 */
public interface WxTradeBillSnapshotMapper {

    /**
     * 落一行账单快照。撞 {@code uk_wx_trade_bill_no} 报 1062 = 该笔交易已入过账(重复下载同一天
     * 账单,或跨天账单重复出现),幂等在库层兜底。
     */
    int insertSnapshot(WxTradeBillSnapshot snapshot);

    /** 某账单日已入库的行数——四账对账用它判断"这天的微信账单是否已下载",0 行时不应误判为差异。 */
    int countByBillDate(@Param("billDate") Date billDate);

    /** 按商户订单号 + 业务类型汇总金额(如某笔报名对应的支付成功总额)。查无行时返回 null。 */
    BigDecimal sumAmountByOutTradeNoAndBizType(@Param("outTradeNo") String outTradeNo,
                                                @Param("bizType") int bizType);

    List<WxTradeBillSnapshot> selectByOutTradeNo(@Param("outTradeNo") String outTradeNo);
}
