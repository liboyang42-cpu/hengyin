package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * P0-28 防双花接线测试:系统2(settlement_detail)不得具备打款能力。
 *
 * <p>城瘾曾有两套并行结算对同一笔 gross 各切一次:
 * <ul>
 *   <li><b>系统1</b> = {@code CmsActivityServiceImpl.settleActivity} / {@code CmsTopicServiceImpl.settleTopic},
 *       以 {@code is_settlement} 原子领取,给商户/设计者/平台三方加余额 —— <b>唯一真实打款方</b>。</li>
 *   <li><b>系统2</b> = {@code SettlementEntryServiceImpl} 在报名支付时按 {@code settlement_rule}
 *       拆出 merchant/platform/creator 明细,经 generateBatch/confirmBatch 消费。</li>
 * </ul>
 *
 * <p>两者覆盖面完全重合。系统2 一旦也打款即对同一笔重复打款,且二者互不感知:
 * 系统1 的销售额口径 {@code selectTotalSalesByActivityId} 只读 cms_registration,
 * 对 settlement_detail 零引用 —— 靠 {@code is_settlement} 做单向闸挡不住
 * 「系统2 先打(T+1)→ 系统1 后结(活动结束+12h)」这个常态顺序。
 * 故根治是拆除系统2 的打款能力,settlement_detail 退化为纯账本(入账 + 退款冲销)。
 *
 * <p>⚠️ 负控:把 addBalance / insertUmsMemberBalanceDetail 的调用加回 SettlementServiceImpl,
 * 或把 AutoSettlementTask 加回来 ⇒ 本测试红。
 *
 * <p>★★ <b>本测试的盲区(2026-07-17 P0-1 实证,别再被它的绿骗一次)</b>:
 * 它是对**单个文件**的源码文本断言,只证明「系统2 不再加钱」,
 * <b>结构上不可能看见对面的借方</b> —— 贷方(confirmBatch 的 addBalance)拆了、
 * 借方({@code RefundServiceImpl} 的退款冲销扣款)原样留着,本测试照绿,
 * 而运营点一次「确认对账」+ 玩家退一次款 = 商家凭空少一笔从没收到过的钱。
 * <br>⇒ 「钱能不能动」这类问题,源码 grep 永远只能覆盖半条链路。
 * 真正守住单边扣款的是**真调 reviewRefund 入口**的行为测试:
 * {@link RefundSingleSidedDebitGuardTest}(活动路)与
 * {@link RefundSingleSidedDebitTopicGuardTest}(主题 coop 路)。
 */
class SettlementPayoutDecommissionedTest {

    private static final String SETTLEMENT_SERVICE =
            "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/SettlementServiceImpl.java";

    private static final String AUTO_SETTLEMENT_TASK =
            "chengyinhub-system/src/main/java/com/chengyinhub/business/task/AutoSettlementTask.java";

    @Test
    void settlementServiceMustNotTouchMemberBalance() throws Exception {
        String src = WiringSource.read(SETTLEMENT_SERVICE).replaceAll("\\s+", "");
        assertFalse(src.contains("addBalance("),
                "SettlementServiceImpl 不得调用 addBalance —— 打款由系统1(settleActivity/settleTopic)独占,"
                        + "系统2 再打即同一笔 gross 双花");
        assertFalse(src.contains("insertUmsMemberBalanceDetail("),
                "SettlementServiceImpl 不得写余额流水 —— 同上,settlement_detail 是纯账本不是打款路径");
    }

    @Test
    void autoSettlementTaskMustStayDecommissioned() {
        assertFalse(WiringSource.exists(AUTO_SETTLEMENT_TASK),
                "AutoSettlementTask 已拆除,不得复活 —— 它 T+1 自动 generateBatch+confirmBatch 打款,"
                        + "会赶在系统1(活动结束+12h)之前先打一次,造成重复打款");
    }
}
