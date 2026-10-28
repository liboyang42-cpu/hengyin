package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * P0-06 退款快照的**接线**测试 —— 补「算得对但没人调用 / 算得对但没人读」这两个空档。
 *
 * <p>{@code RefundPolicySnapshotTest} 证明了 snapshotOnPurchase 算得对、evaluate 认快照。
 * 但那两条都绿,**仍不能证明**:
 * <ol>
 *   <li>下单时真的调了 snapshotOnPurchase 并把四列 set 进去 —— 删掉整段,编译过、测试全绿,
 *       四列恒 NULL,读侧永远回落现取,整个 P0-06 白做且零告警;</li>
 *   <li>读侧真的把 {@code registration.getRefundDeadline()} 传进去了 —— 传 null 编译一样过
 *       (那个位置本来就允许 null = 存量单),于是快照写了也没人看。</li>
 * </ol>
 * 这正是 P0-02 用 mutation 实测过的同一类空档(见 {@link ProductSourceWiringTest}),
 * 所以这里用同样的招:断言源码里的写入点/读取点存在。
 *
 * <p>⚠️ 源码断言只守「代码长这样」。**它守不住「运行时真写进去了」** —— 那只有迁移头里
 * P3 那条落库后自检(查新单四列非空)能回答。别把这里绿当成上线成功。
 *
 * ⚠️ 负控已实测:
 *  - 删 createRegistration 里的 snapshotOnPurchase 整段 ⇒ purchaseWritesSnapshot 红
 *  - 四个 setter 任删其一 ⇒ purchaseWritesSnapshot 红
 *  - 读侧把 getRefundDeadline() 换回 new Date() 之前的 null ⇒ readersPassSnapshotDeadline 红
 */
class RefundSnapshotWiringTest {

    private String sourceOf(String relPath) throws Exception {
        return WiringSource.read(relPath);
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    private static final String[] PRODUCTION_CALLERS = {
            "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TeamGroupServiceImpl.java",
            "chengyinhub-admin/src/main/java/com/chengyinhub/web/service/registration/RegistrationQueryApplication.java",
    };

    private static final String REG_SERVICE =
            "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsRegistrationServiceImpl.java";

    /**
     * 下单主链路必须打快照,且**四列一个都不能少**。
     *
     * <p>少写一列的后果不对称,别以为「写了两列总比零列强」:只有 refund_deadline 是读侧真正用的,
     * 少了它 = 快照等于没打;而少了 code/version = 将来政策一改,这些单再也说不清当初按哪套卖的。
     */
    @Test
    void purchaseWritesSnapshot() throws Exception {
        String src = sourceOf(REG_SERVICE);

        assertTrue(src.contains("RefundPolicy.snapshotOnPurchase("),
                "下单必须调 snapshotOnPurchase —— 删掉它编译过、测试全绿、四列恒 NULL,P0-06 白做");
        assertTrue(src.contains("setRefundPolicyCode("), "少了 code:政策一改就说不清这单当初按哪套卖的");
        assertTrue(src.contains("setRefundPolicyVersion("), "少了 version:同 code 的参数调整无从区分");
        assertTrue(src.contains("setRefundDeadline("), "少了 deadline:**读侧唯一真正用的那列**,少它=快照等于没打");
    }

    /**
     * 快照必须喂**票的 end_time**(③ 的锚点源),不是别的时间。
     * 喂错了照样编译、照样非空 —— 冻下来的却是个谁也没答应过玩家的截止点。
     */
    @Test
    void snapshotFedFromTicketEndTime() throws Exception {
        // 抹掉所有空白再比 —— 断言的是「喂了哪个字段」,不是「怎么换行的」。
        // 绑死排版会让一次 IDE 格式化把测试弄红,而错误信息还指着业务逻辑,把排查者带偏。
        String src = sourceOf(REG_SERVICE).replaceAll("\\s+", "");
        assertTrue(src.contains("snapshotOnPurchase(registration.getPurchaseKind(),ticket.getEndTime())"),
                "③ 的锚点源 = ticket.getEndTime();喂 startTime/主题 endDate 都会冻出一个错的截止点");
    }

    /**
     * 快照必须与 purchase_kind **同处** —— 产品真源定了,政策就定了。
     * 分两处写迟早分叉出「购买类型是 ③、政策快照按 ① 冻」的单。
     */
    @Test
    void snapshotSitsWithPurchaseKind() throws Exception {
        String src = sourceOf(REG_SERVICE);
        int kindAt = src.indexOf("registration.setPurchaseKind(");
        int snapAt = src.indexOf("RefundPolicy.snapshotOnPurchase(");
        assertTrue(kindAt > 0 && snapAt > kindAt && snapAt - kindAt < 1200,
                "快照必须紧跟 setPurchaseKind —— 隔远了迟早有人只改一边");
    }

    /**
     * <b>读侧必须传快照</b>。四个 evaluate 调用点都在手上握着 registration,
     * 传 null 编译一样过(null = 存量单是合法值),于是快照写了也没人看 —— 静默失效。
     */
    @Test
    void readersPassSnapshotDeadline() throws Exception {
        String team = sourceOf(
                "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TeamGroupServiceImpl.java");
        String api = sourceOf(
                "chengyinhub-admin/src/main/java/com/chengyinhub/web/service/registration/RegistrationQueryApplication.java");

        assertEquals(1, countOccurrences(team, "r.getRefundDeadline()"),
                "活动仍读取快照；主题退款改由 FulfillmentWindowResolver 统一履约窗");
        assertEquals(countOccurrences(api, "RefundPolicy.evaluate("),
                countOccurrences(api, "registration.getRefundDeadline()"),
                "每个退款展示/列表调用点都要传快照 —— 展示与执行不同口径 = 页面说可退、点了退不了");
    }

    /**
     * 旧的 8 参 evaluate 已 @Deprecated,**生产代码必须零调用** —— 用回它 = 悄悄绕过快照,
     * 退款窗口又变回现取。
     *
     * <p>⚠️ 这条原本只断言源码里有 "@Deprecated" 字样 —— 名字宣称守着「零调用」,实际啥也没守:
     * 谁在生产代码里写一句 8 参调用它照样绿。改成真的去数调用点的实参个数。
     */
    @Test
    void noProductionCallerUsesLegacySignature() throws Exception {
        String policy = sourceOf(
                "chengyinhub-system/src/main/java/com/chengyinhub/business/util/RefundPolicy.java");
        assertTrue(policy.contains("@Deprecated"),
                "8 参签名必须标 @Deprecated,否则新代码会不知不觉绕过快照");

        for (String f : PRODUCTION_CALLERS) {
            String flat = sourceOf(f).replaceAll("\\s+", "");
            int at = 0;
            while ((at = flat.indexOf("RefundPolicy.evaluate(", at)) >= 0) {
                at += "RefundPolicy.evaluate(".length();
                assertSnapshotAndFreeCancellationArguments(arguments(flat, at), f);
            }
        }
    }

    @Test
    void legacyOverloadsAndNullSnapshotOrFreeCancellationFactsFailTheContract() {
        List<String> valid = arguments("2L,r.getPaymentStatus(),r.getVerificationStatus(),null,null,null,"
                + "activity.getStartDate(),r.getPurchaseKind(),r.getRefundDeadline(),"
                + "r.getPayableAmount(),r.getPointUsed(),new Date())", 0);
        assertSnapshotAndFreeCancellationArguments(valid, "fixture");
        for (int count : new int[]{8, 10}) {
            assertThrows(AssertionError.class, () -> assertSnapshotAndFreeCancellationArguments(
                    valid.subList(0, count), "legacy"));
        }
        for (int position : new int[]{8, 9, 10}) {
            List<String> missing = new ArrayList<>(valid);
            missing.set(position, "null");
            assertThrows(AssertionError.class, () -> assertSnapshotAndFreeCancellationArguments(missing, "null"));
        }
    }

    private static void assertSnapshotAndFreeCancellationArguments(List<String> args, String file) {
        assertEquals(12, args.size(), file + " 必须同时传已售快照和免费取消的现金/积分事实");
        assertTrue(args.get(8).matches("(?:r|registration)\\.getRefundDeadline\\(\\)"), file + " 未读已售快照");
        assertTrue(args.get(9).matches("(?:r|registration)\\.getPayableAmount\\(\\)"), file + " 未读实际应付额");
        assertTrue(args.get(10).matches("(?:r|registration)\\.getPointUsed\\(\\)"), file + " 未读实际积分使用额");
    }

    /** 读取调用的顶层实参，嵌套getter/构造调用内的逗号不算分隔符。 */
    private static List<String> arguments(String flat, int argStart) {
        List<String> args = new ArrayList<>();
        int depth = 0, start = argStart;
        for (int i = argStart; i < flat.length(); i++) {
            char c = flat.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                if (depth == 0) {
                    args.add(flat.substring(start, i).replaceAll("\\s+", ""));
                    return args;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                args.add(flat.substring(start, i).replaceAll("\\s+", ""));
                start = i + 1;
            }
        }
        throw new AssertionError("退款策略调用缺少闭括号");
    }
}
