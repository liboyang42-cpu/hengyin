package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.service.impl.PricingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-28 批1:费率进库(收敛单真源)+ 锁价快照(冻结费率)。
 *
 * <p><b>病1 费率是编译期常量</b>:PricingServiceImpl 的 PLATFORM_RATE=0.10 与
 * initiatorGuaranteeRate 的 0.05/0.02/5 全是 Java 常量,全仓零 DB 对应物;而库里
 * settlement_rule.platform_commission_rate 是同一概念的另一份、后台可改
 * ⇒ 运营调成 0.15,定价引擎还是 0.10,定出的地板价低 5 个点 → 结算 margin&lt;0 → 拒结悬空。
 *
 * <p><b>病2 没有版本快照 = 发版即资损</b>(比病1 严重,文档没写):coop_order 有 termsVersion
 * 冻商家条款,平台率/保底率却是**结算时现取** —— 定价固化的是价格、结算现取费率 ⇒ 改一个数
 * **追溯改写所有已售未结订单**,平台多抽 → 发起人 margin&lt;0 → 守恒断言拒结 → 款项悬空。
 *
 * <p>两条的修法是一对:费率进库解决「改值要发版」,锁价快照解决「改值追溯改写存量」。
 * **只做前者不做后者,收敛完照样是发版即资损** —— 只是从「改代码触发」变成「改库数据触发」,更容易踩。
 *
 * ⚠️ 负控已实测:
 *  - platformRateOf 改回直接返回 platformRate() ⇒ snapshot* 系列红
 *  - confirmPrice 不写快照 ⇒ (由 FeeSnapshotWiringTest 的源码断言守)
 *  - guaranteeRate 的 min(n, max) 改成不封顶 ⇒ guaranteeRate_cappedByMaxMerchant 红
 */
class FeeSnapshotTest {

    private ISettlementRuleService ruleService;
    private PricingServiceImpl service;

    @BeforeEach
    void setUp() {
        ruleService = mock(ISettlementRuleService.class);
        service = new PricingServiceImpl();
        ReflectionTestUtils.setField(service, "settlementRuleService", ruleService);
    }

    private void givenRule(String platform, String base, String step, Integer max) {
        SettlementRule r = new SettlementRule();
        if (platform != null) r.setPlatformCommissionRate(new BigDecimal(platform));
        if (base != null) r.setGuaranteeBaseRate(new BigDecimal(base));
        if (step != null) r.setGuaranteeStepRate(new BigDecimal(step));
        r.setGuaranteeMaxMerchant(max);
        when(ruleService.getActiveRule(0L, "topic")).thenReturn(r);
    }

    private CmsTopic topicWithSnapshot(String platform, String base, String step, Integer max) {
        CmsTopic t = new CmsTopic();
        t.setId(1L);
        if (platform != null) t.setFeePlatformRate(new BigDecimal(platform));
        if (base != null) t.setFeeGuaranteeBaseRate(new BigDecimal(base));
        if (step != null) t.setFeeGuaranteeStepRate(new BigDecimal(step));
        t.setFeeGuaranteeMaxMerchant(max);
        return t;
    }

    // ---------------------------------------------------------- 病1:费率进库

    /** 平台率读库,不是常量 —— 后台改了值,定价引擎必须跟着变(这就是「调价不用发版」)。 */
    @Test
    void platformRate_readsFromDb() {
        givenRule("0.0300", "0.0500", "0.0200", 5);
        assertEquals(0, new BigDecimal("0.0300").compareTo(service.platformRate()),
                "平台率必须读 settlement_rule —— 读常量就等于「调价要发版」");
    }

    /** 保底率的三个魔数同样读库。 */
    @Test
    void guaranteeRate_readsAllThreeParamsFromDb() {
        givenRule("0.0300", "0.1000", "0.0500", 3);
        // base 0.10 + step 0.05 × min(2,3) = 0.20
        assertEquals(0, new BigDecimal("0.2000").compareTo(service.initiatorGuaranteeRate(2)));
    }

    /** 封顶生效:n 超过 max 也只按 max 算。 */
    @Test
    void guaranteeRate_cappedByMaxMerchant() {
        givenRule("0.0300", "0.0500", "0.0200", 5);
        // 8 家 → 按 5 家封顶 → 0.05 + 0.02×5 = 0.15
        assertEquals(0, new BigDecimal("0.1500").compareTo(service.initiatorGuaranteeRate(8)),
                "保底率必须按 max 封顶,不封顶会让分母被吃穿");
        assertEquals(0, new BigDecimal("0.1500").compareTo(service.initiatorGuaranteeRate(5)),
                "恰好 max 家时与超出时同值");
    }

    /** 负数商家数当 0 处理(别让脏数据把保底率算成负的)。 */
    @Test
    void guaranteeRate_negativeCountTreatedAsZero() {
        givenRule("0.0300", "0.0500", "0.0200", 5);
        assertEquals(0, new BigDecimal("0.0500").compareTo(service.initiatorGuaranteeRate(-3)));
    }

    /** 查不到规则行 ⇒ 回落硬编码(= 落库前的行为),不崩。 */
    @Test
    void missingRule_fallsBackToLegacyConstants() {
        when(ruleService.getActiveRule(anyLong(), anyString())).thenReturn(null);
        assertEquals(0, new BigDecimal("0.10").compareTo(service.platformRate()),
                "查不到规则要回落到原硬编码值,保证 fallback 时行为不变");
        assertEquals(0, new BigDecimal("0.15").compareTo(service.initiatorGuaranteeRate(9)));
    }

    /** 读库抛异常 ⇒ 同样回落,不把定价页面炸掉。 */
    @Test
    void ruleLookupThrows_fallsBack() {
        when(ruleService.getActiveRule(anyLong(), anyString())).thenThrow(new RuntimeException("库炸了"));
        assertEquals(0, new BigDecimal("0.10").compareTo(service.platformRate()));
    }

    /** 规则行存在但字段为空(老行没回填)⇒ 逐字段回落,不 NPE。 */
    @Test
    void ruleWithNullFields_fallsBackPerField() {
        givenRule("0.0300", null, null, null);
        assertEquals(0, new BigDecimal("0.0300").compareTo(service.platformRate()), "有值的用库里的");
        assertEquals(0, new BigDecimal("0.15").compareTo(service.initiatorGuaranteeRate(9)), "空的逐个回落");
    }

    // ---------------------------------------------------------- 病2:锁价快照(核心)

    /**
     * **这条是本类的心脏**:结算读快照,不读 live —— 即使库里的费率已经被改过。
     *
     * <p>场景:主题锁价时平台率 10%,之后运营在后台调成 3%。这个主题的票是按 10% 的成本结构定的价,
     * 结算时必须仍按 10% 算,否则平台少抽 7 个点(反方向则是从发起人毛利里挖 → margin&lt;0 → 拒结)。
     */
    @Test
    void snapshot_winsOverLiveRate() {
        givenRule("0.0300", "0.0500", "0.0200", 5);                    // 库里已经改成 3%
        CmsTopic locked = topicWithSnapshot("0.1000", "0.0500", "0.0200", 5);   // 锁价时是 10%

        assertEquals(0, new BigDecimal("0.1000").compareTo(service.platformRateOf(locked)),
                "结算必须按锁价那一刻的 10% 算 —— 读 live 的 3% 就是追溯改写已售订单");
    }

    /** 快照命中时不该再去查库(证明分支真的短路了,也省一次 DB)。 */
    @Test
    void snapshot_present_doesNotQueryDb() {
        CmsTopic locked = topicWithSnapshot("0.1000", "0.0500", "0.0200", 5);

        service.platformRateOf(locked);
        service.initiatorGuaranteeRateOf(locked, 3);

        verify(ruleService, never()).getActiveRule(anyLong(), anyString());
    }

    /** 保底率同样按快照的公式参数算。 */
    @Test
    void snapshot_guaranteeUsesFrozenFormulaParams() {
        givenRule("0.0300", "0.9000", "0.9000", 9);                    // 库里被改成离谱的值
        CmsTopic locked = topicWithSnapshot("0.1000", "0.0500", "0.0200", 5);

        // 按快照:0.05 + 0.02×min(3,5) = 0.11
        assertEquals(0, new BigDecimal("0.1100").compareTo(service.initiatorGuaranteeRateOf(locked, 3)));
    }

    /** 未锁价 / 存量主题(快照为空)⇒ 回落 live —— 与快照落地前行为一致。 */
    @Test
    void noSnapshot_fallsBackToLive() {
        givenRule("0.0300", "0.0500", "0.0200", 5);
        CmsTopic legacy = new CmsTopic();
        legacy.setId(1L);

        assertEquals(0, new BigDecimal("0.0300").compareTo(service.platformRateOf(legacy)),
                "存量主题没有快照,读 live 是对的(这正是可空列的意义)");
    }

    /** topic 为 null(调用方没拿到主题)⇒ 回落 live,不 NPE。 */
    @Test
    void nullTopic_fallsBackToLive() {
        givenRule("0.0300", "0.0500", "0.0200", 5);
        assertEquals(0, new BigDecimal("0.0300").compareTo(service.platformRateOf(null)));
    }

    /** 保底快照三个参数缺一 ⇒ 整体回落 live(半截快照比没快照更危险,别拿它算)。 */
    @Test
    void partialGuaranteeSnapshot_fallsBackToLive() {
        givenRule("0.0300", "0.0500", "0.0200", 5);
        CmsTopic half = topicWithSnapshot("0.1000", "0.0500", null, 5);   // step 缺失

        // 回落 live:0.05 + 0.02×2 = 0.09
        assertEquals(0, new BigDecimal("0.0900").compareTo(service.initiatorGuaranteeRateOf(half, 2)),
                "半截快照不可信,整体回落 —— 拿缺参数的快照算会得出一个谁也没批准过的率");
    }
}
