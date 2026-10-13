package com.chengyinhub.business.service;

/**
 * 商家营业状态门禁(拍板 2026-09-16 #10):商家点「已打烊/临时闭店」后,相关的新报名/购票不可下单,
 * 已售订单照常核销与履约。
 *
 * <p>与「现场暂停接待」(game session 站点暂停 + 已批准备用方案)是两个轴,刻意不合并:
 * 营业状态是**店级、售前**闸(挡新单),暂停接待是**场次级、售中/售后台**闸(挡到店提交与核验)。
 * 两者都会在履约链路上体现,但判据、写入方、恢复方式都不同 —— 合成一个开关会让「打烊」也要
 * 先配备用方案,或者「暂停一站」连带停售整个商家。
 */
public interface IMerchantBusinessStatusService {

    /** 商家闭店时的统一对外文案:下单/报名入口提示与后端拒绝都用这一句,两端不许各写一份。 */
    String MERCHANT_CLOSED_MESSAGE = "商家暂停营业，暂不可报名";

    /**
     * 下单目标的归属商家当前是否「暂停营业/已打烊」。
     *
     * <p>归属判定按下单事实:主题单(ownerType=1)取主题发布者;场次单(ownerType=2)取场次创建者,
     * 创建者不是生效商家时回落到场次所属主题的发布者。找不到商家、商家未生效(未过审/停用/删除)
     * 一律返回 false —— 那些由各自的资格闸处理,不在本闸重复拦。
     */
    boolean isTargetMerchantClosed(Integer ownerType, Long ownerId);
}
