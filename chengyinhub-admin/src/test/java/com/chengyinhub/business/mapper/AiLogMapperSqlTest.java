package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.AiGenerationLog;
import com.chengyinhub.business.domain.AiMerchantMatchSnapshot;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 审计日志 mapper 的 SQL 守卫:解析真 XML 生成 BoundSql,**不连库、不 mock**。
 *
 * <p>为什么需要它:本批新加的全是 select,而 select 的错法 mock 单测**一个都抓不到** ——
 * mock 的 mapper 返回什么由测试自己摆,SQL 里少一个条件、resultMap 列名写错,
 * service 层测试照样全绿,错到生产才发现「查出来全是 null」或「删掉的记录也在列表里」。
 * 这组表里具体是:
 * <ul>
 *   <li>列表**必须恒带 del_flag = 0** —— 漏了 = 软删的行照样出现在审计台。</li>
 *   <li>traceId **必须 `=` 精确匹配,不许 like** —— item1 刚为 trace_id 加了 idx_ai_gen_trace,
 *       写成 `like concat('%',...,'%')` 索引直接失效退化全表扫;而这一页最主要的用法
 *       就是「拿前端返回的 traceId 反查」。</li>
 *   <li>select 列清单**必须含 token_in / token_out** —— item1 刚补的列,漏选 = 页面永远显示空,
 *       而「这次是 prompt 太长还是模型话太多」正是加这两列的唯一动机。</li>
 *   <li>快照下钻**必须按 trace_id 查** —— 这是 log 表与 ai_merchant_match_snapshot
 *       唯一的连接键(item1 修完 saveLog 才真接通)。</li>
 * </ul>
 *
 * <p>⚠️ 负控已实测(见 PR 的 negative_control 段):
 * 把 traceId 的 `=` 改成 like ⇒ listUsesExactTraceIdMatch 红;
 * 从 selectAiGenerationLogVo 里删掉 token_in ⇒ voSelectsTokenInAndTokenOut 红。
 */
class AiLogMapperSqlTest {

    private static final String LOG_XML = "mapper/business/AiGenerationLogMapper.xml";
    private static final String SNAP_XML = "mapper/business/AiMerchantMatchSnapshotMapper.xml";

    private static final String NS_LOG = "com.chengyinhub.business.mapper.AiGenerationLogMapper.";
    private static final String NS_SNAP = "com.chengyinhub.business.mapper.AiMerchantMatchSnapshotMapper.";

    private Configuration parse(String resource, Class<?> alias) throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias(alias.getSimpleName(), alias);
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
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

    private String logSql(String stmt, Object param) throws Exception {
        return sql(parse(LOG_XML, AiGenerationLog.class), NS_LOG + stmt, param);
    }

    /** 空条件的列表查询(只有恒定条件会留下)。 */
    private String emptyListSql() throws Exception {
        return logSql("selectAiGenerationLogList", new AiGenerationLog());
    }

    /** 带 traceId 的列表查询。 */
    private String traceListSql() throws Exception {
        AiGenerationLog q = new AiGenerationLog();
        q.setTraceId("abc123");
        return logSql("selectAiGenerationLogList", q);
    }

    // ------------------------------------------------- 恒定条件

    /** 软删过滤是恒定的,不是 <if> —— 传空条件时也必须在。 */
    @Test
    void listAlwaysFiltersDelFlag() throws Exception {
        String s = emptyListSql();
        assertTrue(s.contains("del_flag = 0"),
                "列表缺 del_flag = 0,软删记录会出现在审计台。实际: " + s);
    }

    @Test
    void getInfoFiltersDelFlag() throws Exception {
        String s = logSql("selectAiGenerationLogById", 1L);
        assertTrue(s.contains("del_flag = 0"), "详情缺 del_flag = 0。实际: " + s);
    }

    @Test
    void listOrdersByIdDesc() throws Exception {
        String s = emptyListSql();
        assertTrue(s.contains("order by id desc"), "列表缺 order by id desc。实际: " + s);
    }

    // ------------------------------------------------- 列清单(resultMap 列名 typo 的盲区)

    /** item1 刚补的两列必须被 select 出来,否则页面永远空。 */
    @Test
    void voSelectsTokenInAndTokenOut() throws Exception {
        String s = emptyListSql();
        assertTrue(s.contains("token_in"), "select 列清单漏 token_in —— 页面 token 入永远空。实际: " + s);
        assertTrue(s.contains("token_out"), "select 列清单漏 token_out。实际: " + s);
        assertTrue(s.contains("trace_id"), "select 列清单漏 trace_id。实际: " + s);
        assertTrue(s.contains("input_brief") && s.contains("output_brief"),
                "select 列清单漏 input_brief/output_brief —— 详情弹窗会是空的。实际: " + s);
    }

    // ------------------------------------------------- traceId 必须精确匹配

    /**
     * ★ traceId 用 `=` 不用 like。
     * like '%x%' 会让 item1 刚建的 idx_ai_gen_trace 完全失效(前导通配符无法走索引)。
     */
    @Test
    void listUsesExactTraceIdMatch() throws Exception {
        String s = traceListSql();
        assertTrue(s.contains("trace_id = ?"),
                "traceId 不是精确匹配,idx_ai_gen_trace 用不上。实际: " + s);
        assertFalse(s.contains("trace_id like"),
                "traceId 用了 like,索引失效退化全表扫。实际: " + s);
    }

    /** traceId 是 <if>,不传时不应出现在 where 里(否则永远查不到东西)。 */
    @Test
    void listOmitsTraceIdWhenAbsent() throws Exception {
        assertFalse(emptyListSql().contains("trace_id = ?"),
                "没传 traceId 时不该拼 trace_id 条件。实际: " + emptyListSql());
    }

    // ------------------------------------------------- 其余 <if> 逐个在

    @Test
    void listFiltersByMemberId() throws Exception {
        AiGenerationLog q = new AiGenerationLog();
        q.setMemberId(7L);
        assertTrue(logSql("selectAiGenerationLogList", q).contains("member_id = ?"),
                "memberId 条件没拼上");
    }

    @Test
    void listFiltersByBizType() throws Exception {
        AiGenerationLog q = new AiGenerationLog();
        q.setBizType("merchant_plan");
        assertTrue(logSql("selectAiGenerationLogList", q).contains("biz_type = ?"),
                "bizType 条件没拼上");
    }

    @Test
    void listFiltersByProvider() throws Exception {
        AiGenerationLog q = new AiGenerationLog();
        q.setProvider("qwen");
        assertTrue(logSql("selectAiGenerationLogList", q).contains("provider = ?"),
                "provider 条件没拼上");
    }

    /** safetyFlag=0(正常)是最常用的筛选值,必须不被 null 判误伤。 */
    @Test
    void listFiltersBySafetyFlagZero() throws Exception {
        AiGenerationLog q = new AiGenerationLog();
        q.setSafetyFlag(AiGenerationLog.SAFETY_OK);
        assertTrue(logSql("selectAiGenerationLogList", q).contains("safety_flag = ?"),
                "safetyFlag=0 被吃掉了 —— 「只看正常调用」筛不出来");
    }

    /** BaseEntity.params 的 beginTime/endTime 区间(不改 domain,免费拿)。 */
    @Test
    void listFiltersByCreateTimeRange() throws Exception {
        AiGenerationLog q = new AiGenerationLog();
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("beginTime", "2026-07-01");
        params.put("endTime", "2026-07-16");
        q.setParams(params);
        String s = logSql("selectAiGenerationLogList", q);
        assertTrue(s.contains("date_format(create_time,'%y%m%d') >="), "beginTime 区间没拼上。实际: " + s);
        assertTrue(s.contains("date_format(create_time,'%y%m%d') <="), "endTime 区间没拼上。实际: " + s);
    }

    // ------------------------------------------------- 快照下钻

    /** 快照与 log 表唯一的连接键是 trace_id(item1 修完 saveLog 才真接通)。 */
    @Test
    void snapshotIsLookedUpByTraceId() throws Exception {
        String s = sql(parse(SNAP_XML, AiMerchantMatchSnapshot.class), NS_SNAP + "selectSnapshotByTraceId", "abc123");
        assertTrue(s.contains("trace_id = ?"), "快照没按 trace_id 查,和 log 表 join 不起来。实际: " + s);
        assertTrue(s.contains("del_flag = 0"), "快照缺 del_flag = 0。实际: " + s);
        assertTrue(s.contains("candidates_json") && s.contains("choice_json"),
                "快照 select 漏 candidates_json/choice_json —— 下钻等于没下。实际: " + s);
    }
}
