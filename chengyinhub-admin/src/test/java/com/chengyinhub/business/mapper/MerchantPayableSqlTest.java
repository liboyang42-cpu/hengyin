package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantPayable;
import com.chengyinhub.business.domain.MerchantPayableBatch;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ③ 应付台账的两条 SQL 层守卫。
 *
 * <p><b>为什么这两条必须在 SQL 层守</b>:服务测里 mapper 全是 mock —— SQL 写错那边照样全绿。
 * 而这两处写错的后果分别是<b>账翻倍多付</b>和<b>历史被改写</b>,都不会报错。
 *
 * <p>⚠️ <b>这类断言的天花板</b>(照抄 {@link ExploreRecallAndIdemSqlTest} 那句实在话):
 * 它证明的是<b>这句 SQL 长这样</b>,不是 <b>MySQL 认这句 SQL</b>、更不是<b>它跑起来是对的</b>。
 * 语法与行为由迁移文件 §P4 的落库自检探针验(那是在真库上)。两层缺一层都能骗过自己。
 *
 * <p>⚠️ 负控已实测:
 * <ul>
 *   <li>把 sumNetByMerchantAndPeriod 改成 {@code sum(amount)} ⇒
 *       {@link #net_usesSignedSumNotRawSum} 红</li>
 *   <li>往 MerchantPayableMapper.xml 里加任意一句 update/delete ⇒
 *       {@link #payableMapper_hasNoUpdateOrDelete} 红</li>
 * </ul>
 */
class MerchantPayableSqlTest {

    private static final String PAYABLE = "mapper/business/MerchantPayableMapper.xml";
    private static final String NS = "com.chengyinhub.business.mapper.MerchantPayableMapper.";

    private static final String BATCH = "mapper/business/MerchantPayableBatchMapper.xml";
    private static final String NS_BATCH = "com.chengyinhub.business.mapper.MerchantPayableBatchMapper.";

    private Configuration parse() throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("MerchantPayable", MerchantPayable.class);
        try (InputStream in = Resources.getResourceAsStream(PAYABLE)) {
            new XMLMapperBuilder(in, cfg, PAYABLE, cfg.getSqlFragments()).parse();
        }
        return cfg;
    }

    private Configuration parseBatch() throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("MerchantPayableBatch", MerchantPayableBatch.class);
        try (InputStream in = Resources.getResourceAsStream(BATCH)) {
            new XMLMapperBuilder(in, cfg, BATCH, cfg.getSqlFragments()).parse();
        }
        return cfg;
    }

    private String sql(Configuration cfg, String statementId, Object param) {
        return cfg.getMappedStatement(statementId)
                .getBoundSql(param)
                .getSql()
                .replaceAll("\\s+", " ")
                .toLowerCase();
    }

    /**
     * ★ 净额必须是 <b>SUM(CASE entry_type WHEN 'ACCRUAL' THEN amount ELSE -amount END)</b>。
     *
     * <p>amount 是**绝对值**,方向由 entry_type 单独承载(§4.1 竞品实证的可移植范式)。
     * 写成 {@code sum(amount)} 会把冲正**当成又欠一笔** ⇒ 一笔被撤销的核销反而让应付翻倍
     * ⇒ 平台多付。这条错误不会报任何错,只会在月结时多打一笔钱出去。
     */
    @Test
    void net_usesSignedSumNotRawSum() throws Exception {
        Map<String, Object> p = new HashMap<String, Object>();
        p.put("merchantMemberId", 1L);
        p.put("periodYm", "2026-07");
        String s = sql(parse(), NS + "sumNetByMerchantAndPeriod", p);

        assertTrue(s.contains("case entry_type when 'accrual' then amount else -amount end"),
                "净额必须按 entry_type 定方向;实际 SQL = " + s);
        assertFalse(s.matches(".*sum\\(\\s*amount\\s*\\).*"),
                "sum(amount) 会把冲正算成又欠一笔 = 翻倍多付;实际 SQL = " + s);
    }

    /**
     * ★★ <b>append-only 的机器可验形式</b>:本 mapper 不得有任何 update / delete。
     *
     * <p>台账行记的是「这笔核销当时发生过、费率是 X」——那是历史事实。
     * 冲正靠追加 {@code entry_type='REVERSAL'} 新行,状态机在 {@code merchant_payable_batch}。
     * 一旦这里出现 update,就意味着有人在改历史:已开票批次的金额会与发票对不上,
     * 且再也查不出发生过什么。
     *
     * <p>⚠️ 这条比注释里写「请勿 update」有用得多 —— 注释拦不住人,这条会红。
     */
    @Test
    void payableMapper_hasNoUpdateOrDelete() throws Exception {
        Configuration cfg = parse();
        for (Object o : cfg.getMappedStatements()) {
            if (!(o instanceof MappedStatement)) {
                continue;   // 同名短 id 会注册成 Ambiguity 占位,跳过
            }
            MappedStatement ms = (MappedStatement) o;
            SqlCommandType t = ms.getSqlCommandType();
            assertFalse(t == SqlCommandType.UPDATE || t == SqlCommandType.DELETE,
                    "merchant_payable 是 append-only 事件流水,不该有 " + t + ":" + ms.getId()
                            + "。冲正请追加 REVERSAL 行;状态流转请改 merchant_payable_batch。");
        }
    }

    /**
     * ★ 冲正定位批次的 SQL 必须用 {@code date_format}(与 create_time 分桶同口径)算月,
     * 而不是按外部传进来的字符串月匹配。
     *
     * <p>P2-b:服务层原先用 Java {@code periodOf}(SimpleDateFormat + JVM 时区)算月再按字符串查批次,
     * 与 {@code selectMerchantIdsByPeriod} / {@code sumNet} 的 SQL {@code date_format}(MySQL 会话时区)
     * 口径可能不一致 ⇒ 月边界那笔查不到批次、漏「已开票需红字发票」提醒。服务测(mock batchMapper)
     * 看不到这句 SQL 长相 —— 与本文件守的另两条同理,SQL 必须单独在这层守。
     *
     * <p>🔴 负控:把 {@code selectByMerchantAndAccrualTime} 的 period_ym 条件从
     * {@code date_format(#{createTime},'%Y-%m')} 改回按字符串 {@code = #{periodYm}} ⇒ 本条红。
     */
    @Test
    void accrualTimeLookup_bucketsMonthInSqlNotJava() throws Exception {
        Map<String, Object> p = new HashMap<String, Object>();
        p.put("merchantMemberId", 1L);
        p.put("createTime", new java.util.Date());
        String s = sql(parseBatch(), NS_BATCH + "selectByMerchantAndAccrualTime", p);

        assertTrue(s.contains("date_format(") && s.contains("period_ym"),
                "定位批次必须用 SQL date_format 算被冲那笔的月,与成员分桶同源;实际 SQL = " + s);
    }


    /** 🔴 负控:markPaid 去掉 paid_at 或 pay_voucher_no 任一列,本条必须红。 */
    @Test
    void markPaid_setsPaidAtAndVoucherTogether() throws Exception {
        Map<String, Object> p = new HashMap<String, Object>();
        p.put("id", 1L);
        p.put("paidBy", "finance2");
        p.put("payVoucherNo", "V1");
        String s = sql(parseBatch(), NS_BATCH + "markPaid", p);
        int paidStatus = s.indexOf("set status = 'paid'");
        int where = s.indexOf(" where ", paidStatus);

        assertTrue(paidStatus >= 0
                        && s.indexOf("paid_at = now()", paidStatus) > paidStatus
                        && s.indexOf("pay_voucher_no = ?", paidStatus) > paidStatus
                        && where > s.indexOf("pay_voucher_no = ?", paidStatus),
                "PAID 必须同一条 SQL 同时落 paid_at 与 pay_voucher_no;实际 SQL = " + s);
    }
}
