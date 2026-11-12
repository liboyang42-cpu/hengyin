package com.chengyinhub.business.payout;

import com.chengyinhub.business.domain.EcomCombineOrder;
import com.chengyinhub.business.domain.EcomCombineSettlement;
import com.chengyinhub.business.domain.EcomLedgerComponent;
import com.chengyinhub.business.domain.EcomOrderSeller;
import com.chengyinhub.business.domain.EcomSupplierPayable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * f-money 资金验收：用一个「微信支付通道模拟器」（按商户号记余额、冻结与每一笔资金移动）驱动**真实**的
 * {@link EcommerceProfitShareService}、{@link EcommerceCombinedSettlementService}，逐类订单跑
 * 付款 → 结算 → 分账 / 完结 → 退款前回退 → 退款 → 对账，记录每一笔钱的移动，并判定有没有「平台代收再分发」（二清）。
 *
 * <p>判定规则：钱从平台账户（平台商户号 {@link #SP} 或平台自营二级商户号 {@link #PLATFORM_SUB}）
 * 流向任何商家 / 俱乐部 / 会员，且不是把平台自己先前分到的佣金原路回退给同一个卖家子商户，即记为
 * {@code REDISTRIBUTION}（二清嫌疑）。原路回退记为 {@code REVERSAL}。</p>
 *
 * <p>付款这一步由模拟器按「回调已验证」的结果直接入账：下单与回调本身（意图冻结、验签、查单逐字段核对、写不可变事实）
 * 由 {@code EcommercePartnerPaymentAdapterTest}、{@code EcommerceCombinedPaymentTest} 覆盖；退款提交按 adapter 的顺序
 * （先 {@code returnForRefund} 全部成功，再电商退款）由本类代调。结果写到 {@code target/f-money-ledger.md}。</p>
 */
class MoneyFlowLedgerAcceptanceTest {
    static final String SP = CombinedPayoutTest.SP;
    static final String SELLER_SUB = CombinedPayoutTest.SELLER_SUB;
    static final String PLATFORM_SUB = CombinedPayoutTest.PLATFORM_SUB;
    static final String OTHER_SUB = "1900000202";
    static final String PAYER = "PAYER";
    static final Set<String> PLATFORM_ACCOUNTS = Set.of(SP, PLATFORM_SUB);
    static final PayoutSwitches ON = CombinedPayoutTest.ON;
    static final PayoutSwitches L2_ONLY = CombinedPayoutTest.L2_ONLY;

    record Move(String step, String from, String to, long fen, String channel, String kind) { }

    static final Map<String, List<Move>> REPORT = new LinkedHashMap<>();

    /** 只做账：把模拟通道的每笔动作记成资金移动，余额守恒。 */
    static final class ChannelSimulator extends FakeEcommerceGateway {
        final Map<String, Long> balance = new LinkedHashMap<>();
        final List<Move> moves = new ArrayList<>();
        final Map<String, String> shareSource = new LinkedHashMap<>(); // outOrderNo -> 出钱子商户

        void move(String step, String from, String to, long fen, String channel) {
            if (fen <= 0) return;
            balance.merge(from, -fen, Long::sum);
            balance.merge(to, fen, Long::sum);
            String kind;
            if (PAYER.equals(from)) kind = "PAYMENT";
            else if (PAYER.equals(to)) kind = "REFUND";
            else if (PLATFORM_ACCOUNTS.contains(from) && "return".equals(step)) kind = "REVERSAL";
            else if (PLATFORM_ACCOUNTS.contains(from)) kind = "REDISTRIBUTION";
            else if (PLATFORM_ACCOUNTS.contains(to)) kind = "COMMISSION";
            else kind = "SUB_TO_SUB";
            moves.add(new Move(step, from, to, fen, channel, kind));
        }

        void pay(String toSub, long fen) { move("pay", PAYER, toSub, fen, "WECHAT"); }

        @Override public ShareResult createProfitSharing(String sub, String tx, String no, List<ProfitSharePlanner.Receiver> r, boolean finish) {
            ShareResult res = super.createProfitSharing(sub, tx, no, r, finish);
            shareSource.put(no, sub);
            for (ProfitSharePlanner.Receiver x : r) move("share", sub, x.mchid(), x.amountFen(), "WECHAT");
            return res;
        }

        @Override public ReturnResult createReturn(String sub, String no, String rno, String mchid, long fen, String d) {
            ReturnResult res = super.createReturn(sub, no, rno, mchid, fen, d);
            if ("SUCCESS".equals(res.result())) move("return", mchid, sub, fen, "WECHAT");
            return res;
        }

        @Override public RefundAccepted createRefund(String sub, String tx, String no, long r, long t, String reason) {
            RefundAccepted res = super.createRefund(sub, tx, no, r, t, reason);
            move("refund", sub, PAYER, r, "WECHAT");
            return res;
        }

        long redistributed() { return moves.stream().filter(m -> "REDISTRIBUTION".equals(m.kind())).mapToLong(Move::fen).sum(); }
        long subToSub() { return moves.stream().filter(m -> "SUB_TO_SUB".equals(m.kind())).mapToLong(Move::fen).sum(); }
        long net() { return balance.values().stream().mapToLong(Long::longValue).sum(); }
    }

    static EcomLedgerComponent comp(long id, String type, String kind, Long member, String yuan) {
        EcomLedgerComponent c = new EcomLedgerComponent();
        c.setSettlementId(id); c.setLineType(type); c.setReceiverKind(kind); c.setReceiverMemberId(member);
        c.setOriginalAmount(new BigDecimal(yuan));
        return c;
    }

    static FakeEcomPayoutMapper sellerTrade(List<EcomLedgerComponent> ledger) {
        FakeEcomPayoutMapper m = new FakeEcomPayoutMapper();
        EcomOrderSeller t = new EcomOrderSeller();
        t.setOutTradeNo("REG1"); t.setSubMchid(SELLER_SUB); t.setSellerType("MERCHANT"); t.setSellerId(5L);
        t.setTotalFen(10000L); t.setTransactionId("4200000001"); t.setRegistrationId(77L);
        m.trades.add(t);
        m.sellerMembers.put("MERCHANT:5", 55L);
        m.components.put(77L, ledger);
        return m;
    }

    static EcommerceProfitShareService shareService(FakeEcomPayoutMapper m, ChannelSimulator ch, FakeEcomCombineMapper cm, PayoutSwitches sw) {
        EcommerceProfitShareService s = new EcommerceProfitShareService(m, ch, sw, TransactionOperations.withoutTransaction());
        s.setCombineMapper(cm);
        return s;
    }

    /** 运营在后台「标记已付」后，供应商应付才真正付出去：平台账户 → 收款会员（通道外：对公 / 灵活用工）。 */
    static void payPayables(ChannelSimulator ch, FakeEcomCombineMapper cm, EcommerceCombinedSettlementService svc, String fromAccount) {
        for (EcomSupplierPayable p : new ArrayList<>(cm.payables)) {
            if (svc.markPaid(p.getId(), "BANK-" + p.getId(), "ops")) {
                ch.move("payable:" + p.getLineType(), fromAccount, "member:" + p.getPayeeMemberId(), p.getAmountFen(), "OFF_CHANNEL");
            }
        }
    }

    // ---------------- 开关全关 ----------------

    @Test
    void allSwitchesOffMovesNothingThroughTheNewChannel() {
        ChannelSimulator ch = new ChannelSimulator();
        FakeEcomPayoutMapper m = sellerTrade(List.of(comp(1, "PLATFORM", "ACCOUNTING", null, "15.00"),
                comp(2, "INITIATOR_MARGIN", "MEMBER", 55L, "85.00")));
        FakeEcomCombineMapper cm = new FakeEcomCombineMapper();
        EcommerceProfitShareService s = shareService(m, ch, cm, PayoutSwitches.allOff());
        assertEquals(0, s.planTopic(1));
        assertEquals(0, s.submitPlanned(10));
        assertEquals(0, s.syncProcessing(10));
        assertEquals(EcommerceProfitShareService.ReturnOutcome.SWITCH_OFF, s.returnForRefund("REG1", "RF1", 4000, 0));
        EcommerceCombinedSettlementService c = new EcommerceCombinedSettlementService(cm, m, ch, PayoutSwitches.allOff(),
                TransactionOperations.withoutTransaction());
        assertEquals(0, c.planTopic(1));
        assertEquals(0, c.submitDue(10));
        assertTrue(ch.calls.isEmpty(), "开关全关时不得调用电商收付通：" + ch.calls);
        assertTrue(cm.payables.isEmpty() && cm.settlements.isEmpty());
        REPORT.put("OFF 新通道（全部开关关闭）", List.copyOf(ch.moves));
    }

    // ---------------- 单商户电商收付通（唯一第三方收款方 = 卖家本人） ----------------

    @Test
    void singleMerchantSellerIsPaidDirectlyAndOnlyCommissionReachesThePlatform() {
        ChannelSimulator ch = new ChannelSimulator();
        FakeEcomPayoutMapper m = sellerTrade(List.of(comp(1, "PLATFORM", "ACCOUNTING", null, "15.00"),
                comp(2, "INITIATOR_MARGIN", "MEMBER", 55L, "85.00")));
        EcommerceProfitShareService s = shareService(m, ch, new FakeEcomCombineMapper(), ON);
        ch.pay(SELLER_SUB, 10000);
        assertEquals(1, s.planTopic(1));
        assertEquals(1, s.submitPlanned(10));
        assertEquals(List.of(new ProfitSharePlanner.Receiver(SP, 1500)), ch.lastReceivers);
        // 部分退款 40 元：先按比例原路回退 6 元佣金，再从卖家子商户退给玩家
        assertEquals(EcommerceProfitShareService.ReturnOutcome.ALL_RETURNED, s.returnForRefund("REG1", "RF1", 4000, 0));
        assertEquals(EcommerceProfitShareService.ReturnOutcome.ALL_RETURNED, s.returnForRefund("REG1", "RF1", 4000, 0)); // 重放
        ch.createRefund(SELLER_SUB, "4200000001", "RF1", 4000, 10000, "部分退款");
        // 对账：卖家 = 85% × 60 元，平台 = 15% × 60 元，玩家净付 60 元
        assertEquals(5100L, ch.balance.get(SELLER_SUB));
        assertEquals(900L, ch.balance.get(SP));
        assertEquals(-6000L, ch.balance.get(PAYER));
        assertEquals(0, ch.net());
        assertEquals(0, ch.redistributed(), "单商户路径不得出现平台向商家付钱");
        assertEquals(0, ch.subToSub());
        REPORT.put("ON 单商户电商收付通（主题门票，卖家唯一收款方）", List.copyOf(ch.moves));
    }

    @Test
    void singleMerchantWithAThirdPartyShareAndCombinedOffFailsClosedAndFundsStayWithTheSeller() {
        ChannelSimulator ch = new ChannelSimulator();
        FakeEcomPayoutMapper m = sellerTrade(List.of(comp(1, "PLATFORM", "ACCOUNTING", null, "15.00"),
                comp(2, "MERCHANT_VERIFY", "MEMBER", 66L, "10.00"), comp(3, "INITIATOR_MARGIN", "MEMBER", 55L, "75.00")));
        m.memberSubMchids.put(66L, List.of(OTHER_SUB));
        EcommerceProfitShareService s = shareService(m, ch, new FakeEcomCombineMapper(), L2_ONLY);
        ch.pay(SELLER_SUB, 10000);
        assertEquals(1, s.planTopic(1));
        assertEquals(0, s.submitPlanned(10));
        assertEquals(0, s.submitPlanned(10)); // T+14 任务重跑仍不提交
        // f-money 待决定 2：业主 11-11 确认微信不支持二级商户之间直接分账。合单关时提交前失败关闭：整单转人工，
        // 不向微信提交任何分账（平台佣金也不分），钱整笔冻结在卖家二级商户，等运营处理。
        assertEquals("MANUAL", m.orders.get(0).getState());
        assertEquals(ProfitSharePlanner.SUB_TO_SUB_UNSUPPORTED, m.orders.get(0).getManualReason());
        assertTrue(ch.calls.isEmpty(), "合单关时不得调用分账：" + ch.calls);
        assertEquals(0, ch.subToSub());
        assertEquals(0, ch.redistributed());
        assertEquals(10000L, ch.balance.get(SELLER_SUB));
        assertEquals(null, ch.balance.get(OTHER_SUB));
        assertEquals(null, ch.balance.get(SP));
        assertEquals(-10000L, ch.balance.get(PAYER));
        assertEquals(0, ch.net());
        REPORT.put("ON(仅 l-payout2，合单关) 单商户 + 第三方份额：转人工，不分账", List.copyOf(ch.moves));
    }

    @Test
    void singleMerchantWithAThirdPartyShareAndCombinedOnRelaysThroughThePlatform() {
        ChannelSimulator ch = new ChannelSimulator();
        FakeEcomPayoutMapper m = sellerTrade(List.of(comp(1, "PLATFORM", "ACCOUNTING", null, "15.00"),
                comp(2, "MERCHANT_VERIFY", "MEMBER", 66L, "10.00"), comp(3, "INITIATOR_MARGIN", "MEMBER", 55L, "75.00")));
        FakeEcomCombineMapper cm = new FakeEcomCombineMapper();
        EcommerceProfitShareService s = shareService(m, ch, cm, ON);
        EcommerceCombinedSettlementService payables = new EcommerceCombinedSettlementService(cm, m, ch, ON,
                TransactionOperations.withoutTransaction());
        ch.pay(SELLER_SUB, 10000);
        s.planTopic(1);
        s.submitPlanned(10);
        assertEquals(List.of(new ProfitSharePlanner.Receiver(SP, 2500)), ch.lastReceivers);
        payPayables(ch, cm, payables, SP);
        assertEquals(1500L, ch.balance.get(SP));
        assertEquals(1000L, ch.balance.get("member:66"));
        assertEquals(7500L, ch.balance.get(SELLER_SUB));
        assertEquals(0, ch.net());
        // 核销商家的 10 元先进平台、再由平台付出：平台代收再分发
        assertEquals(1000, ch.redistributed());
        REPORT.put("ON(目标) 单商户 + 第三方份额（转付 RELAY）", List.copyOf(ch.moves));
    }

    // ---------------- 多商户合单 ----------------

    static EcommerceCombinedSettlementService combined(FakeEcomCombineMapper cm, FakeEcomPayoutMapper pm, ChannelSimulator ch) {
        EcomCombineOrder o = new EcomCombineOrder();
        o.setOutTradeNo("REG1"); o.setSellerSubMchid(SELLER_SUB); o.setSellerType("MERCHANT"); o.setSellerId(5L);
        o.setPlatformSubMchid(PLATFORM_SUB); o.setTotalFen(10000L); o.setSellerFen(5900L); o.setHeldFen(4100L);
        o.setCommissionFen(1500L); o.setReserveFen(2600L); o.setCombineOutTradeNo("REG1"); o.setSellerTransactionId("TX-S");
        cm.orders.put("REG1", o);
        cm.topicOrders.put(1L, List.of("REG1"));
        cm.registrationIds.put("REG1", 77L);
        pm.sellerMembers.put("MERCHANT:5", 55L);
        return new EcommerceCombinedSettlementService(cm, pm, ch, ON, TransactionOperations.withoutTransaction());
    }

    @Test
    void combinedOrderReserveIsHeldByThePlatformAndPaidOutAsSupplierPayables() {
        ChannelSimulator ch = new ChannelSimulator();
        FakeEcomCombineMapper cm = new FakeEcomCombineMapper();
        FakeEcomPayoutMapper pm = new FakeEcomPayoutMapper();
        EcommerceCombinedSettlementService svc = combined(cm, pm, ch);
        pm.components.put(77L, List.of(comp(1, "PLATFORM", "ACCOUNTING", null, "15.00"),
                comp(3, "MERCHANT_VERIFY", "MEMBER", 66L, "10.00"), comp(5, "INITIATOR_MARGIN", "MEMBER", 55L, "75.00")));
        ch.pay(SELLER_SUB, 5900);
        ch.pay(PLATFORM_SUB, 4100);
        assertEquals(1, svc.planTopic(1));
        assertEquals(1, svc.submitDue(10));
        payPayables(ch, cm, svc, PLATFORM_SUB);
        // 对账：平台留 15 元佣金；核销商家 10 元与卖家多出的预留 16 元都由平台自营子商户付出
        assertEquals(1500L, ch.balance.get(PLATFORM_SUB));
        assertEquals(1000L, ch.balance.get("member:66"));
        assertEquals(1600L, ch.balance.get("member:55"));
        assertEquals(5900L, ch.balance.get(SELLER_SUB));
        assertEquals(0, ch.net());
        assertEquals(2600, ch.redistributed());
        REPORT.put("ON(目标) 多商户合单：预留够付", List.copyOf(ch.moves));
    }

    @Test
    void combinedOrderShortfallIsSharedToPlatformThenReturnedBeforeAFullRefund() {
        ChannelSimulator ch = new ChannelSimulator();
        FakeEcomCombineMapper cm = new FakeEcomCombineMapper();
        FakeEcomPayoutMapper pm = new FakeEcomPayoutMapper();
        EcommerceCombinedSettlementService svc = combined(cm, pm, ch);
        pm.components.put(77L, List.of(comp(1, "PLATFORM", "ACCOUNTING", null, "15.00"),
                comp(3, "MERCHANT_VERIFY", "MEMBER", 66L, "30.00"), comp(5, "INITIATOR_MARGIN", "MEMBER", 55L, "55.00")));
        ch.pay(SELLER_SUB, 5900);
        ch.pay(PLATFORM_SUB, 4100);
        svc.planTopic(1);
        svc.submitDue(10);
        assertEquals(List.of(new ProfitSharePlanner.Receiver(SP, 400)), ch.lastReceivers);
        EcomCombineSettlement settled = cm.settlements.get(0);
        assertEquals("SUCCESS", settled.getState());
        // 结算后整单退款：先回退 4 元补差，两笔子单再各自原路退款；未付的供应商应付由退款挂起
        EcomCombineOrder row = cm.selectCombineOrder("REG1");
        assertEquals(EcommerceProfitShareService.ReturnOutcome.ALL_RETURNED, svc.returnForRefund(row, "RF1", 5900, 0));
        ch.createRefund(SELLER_SUB, "TX-S", "RF1-S", 5900, 5900, "退款");
        ch.createRefund(PLATFORM_SUB, "TX-P", "RF1-P", 4100, 4100, "退款");
        assertEquals(0L, ch.balance.get(PAYER));
        assertEquals(0L, ch.balance.get(SELLER_SUB));
        assertEquals(0L, ch.balance.getOrDefault(SP, 0L));
        assertEquals(0L, ch.balance.get(PLATFORM_SUB));
        assertEquals(0, ch.net());
        assertEquals(0, ch.redistributed(), "结算前退款：应付尚未付出");
        REPORT.put("ON(目标) 多商户合单：补差后整单退款", List.copyOf(ch.moves));
    }

    @AfterAll
    static void writeReport() throws IOException {
        StringBuilder sb = new StringBuilder("| 流程 | 步骤 | 从 | 到 | 金额（分） | 通道 | 判定 |\n|---|---|---|---|---|---|---|\n");
        REPORT.forEach((flow, moves) -> {
            if (moves.isEmpty()) sb.append("| ").append(flow).append(" | — | — | — | 0 | — | 无移动 |\n");
            for (Move mv : moves) {
                sb.append("| ").append(flow).append(" | ").append(mv.step()).append(" | ").append(mv.from()).append(" | ")
                        .append(mv.to()).append(" | ").append(mv.fen()).append(" | ").append(mv.channel()).append(" | ")
                        .append(mv.kind()).append(" |\n");
            }
        });
        Path out = Paths.get("target", "f-money-ledger.md");
        Files.createDirectories(out.getParent());
        Files.write(out, sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
