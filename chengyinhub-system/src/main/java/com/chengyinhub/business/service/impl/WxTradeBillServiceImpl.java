package com.chengyinhub.business.service.impl;

import java.util.Date;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.service.IWxTradeBillService;

/**
 * 微信交易账单(T+1下载)服务骨架(P1-6 N块)。
 *
 * <p>★本轮故意不实现:接入真实微信「申请交易账单/资金账单」API 需要真实商户环境联调
 * (下载链接签名、GZip解压、CSV解析对齐真实字段名),属 P1-7 范围,不在本轮伪造假数据硬跑通。
 * 方法体保持骨架,调用即抛异常,避免被误当作"已实现但静默什么都不做"——那比不存在更危险
 * (会被以为对账已经在跑,实际微信侧数据从未落库)。
 *
 * <p>接口方法上的 TODO 注释已写明 P1-7 实现要点,见 {@link IWxTradeBillService}。
 */
@Service
public class WxTradeBillServiceImpl implements IWxTradeBillService {

    @Override
    public int downloadAndIngestBill(Date billDate) {
        throw new UnsupportedOperationException(
                "微信交易账单下载未实现(P1-7范围,需真实商户环境联调)。"
                        + "见 IWxTradeBillService#downloadAndIngestBill 的TODO实现要点。");
    }

    @Override
    public int downloadAndIngestFundFlowBill(Date billDate) {
        throw new UnsupportedOperationException(
                "微信资金账单下载未实现(P1-7范围,需真实商户环境联调)。"
                        + "见 IWxTradeBillService#downloadAndIngestFundFlowBill 的TODO实现要点。");
    }
}
