package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsRegistrationScan;
import com.chengyinhub.business.domain.OmsTicket;
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
 * Phase 0 止血批的 mapper 侧守卫:用 MyBatis 真解析 XML 并生成 BoundSql,**不连库**。
 *
 * 为什么需要这个类:mapper XML 的动态 SQL 此前是零覆盖 —— 三个 *IT 想守它,但 pom 无 failsafe
 * 配置、surefire 默认 include 不匹配 `*IT`,故它们在 CI 与本地都从不执行。而 mock 单测又骗不过
 * 自己:controller 测试里 mock 的 selectCmsRegistrationScanList 是**我假设**它按 registrationType
 * 过滤,真 XML 少了 &lt;if&gt; 也照样绿。只有解析真 XML 才能证明条件真的进了 SQL。
 *
 * 覆盖:
 * ① V3 —— selectCmsRegistrationScanList 带 registrationType 时 SQL 必须出现 registration_type 条件
 * ② M3 —— selectOmsTicketList 必须有 order by(否则 resolveMode 取「第一张票」随扫描路径变)
 * ③ V2 —— verifyIfNotVerified 必须是条件置位(where 带未核销判定),不能是无条件 update
 *
 * ⚠️ 负控已实测,见各用例注释。
 */
class Phase0MapperSqlTest {

    private static final String SCAN_MAPPER = "mapper/business/CmsRegistrationScanMapper.xml";
    private static final String TICKET_MAPPER = "mapper/business/OmsTicketMapper.xml";
    private static final String REG_MAPPER = "mapper/business/CmsRegistrationMapper.xml";

    /** 解析指定 mapper XML,返回其 Configuration(无 Environment/DataSource —— getBoundSql 不需要连库)。 */
    private Configuration parse(String resource, Object... aliases) throws Exception {
        Configuration cfg = new Configuration();
        for (Object a : aliases) {
            Class<?> c = (Class<?>) a;
            cfg.getTypeAliasRegistry().registerAlias(c.getSimpleName(), c);
        }
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
        }
        return cfg;
    }

    /** 生成 SQL 并归一空白 + 转小写,便于断言。 */
    private String sql(Configuration cfg, String statementId, Object param) {
        return cfg.getMappedStatement(statementId)
                .getBoundSql(param)
                .getSql()
                .replaceAll("\\s+", " ")
                .toLowerCase();
    }

    // ---------------------------------------------------------------- V3

    /**
     * 带 registrationType 查询 ⇒ SQL 必须真的按 registration_type 过滤。
     * 这是 V3「核销成功却报查询失败」的另一半:controller 补了 setRegistrationType(1),
     * XML 没有对应 &lt;if&gt; 的话 MyBatis 静默忽略该字段,等于没改。
     *
     * ⚠️ 负控:删掉 CmsRegistrationScanMapper.xml 里的 registrationType &lt;if&gt; ⇒ 本用例红。
     */
    @Test
    void scanList_withRegistrationType_filtersByColumn() throws Exception {
        Configuration cfg = parse(SCAN_MAPPER, CmsRegistrationScan.class);

        CmsRegistrationScan probe = new CmsRegistrationScan();
        probe.setRegistrationId(1L);
        probe.setMemberId(2L);
        probe.setRegistrationType(1);

        String s = sql(cfg, "com.chengyinhub.business.mapper.CmsRegistrationScanMapper.selectCmsRegistrationScanList", probe);
        assertTrue(s.contains("registration_type ="),
                "探针带 registrationType 时必须按 registration_type 过滤,否则会命中报名时插的 type=0 模板行。实际 SQL: " + s);
    }

    /**
     * 不带 registrationType ⇒ 不得平白加条件(保持既有调用方语义,别把别人查全量的地方改成只查 type=null)。
     */
    @Test
    void scanList_withoutRegistrationType_noFilter() throws Exception {
        Configuration cfg = parse(SCAN_MAPPER, CmsRegistrationScan.class);

        CmsRegistrationScan probe = new CmsRegistrationScan();
        probe.setRegistrationId(1L);

        String s = sql(cfg, "com.chengyinhub.business.mapper.CmsRegistrationScanMapper.selectCmsRegistrationScanList", probe);
        assertFalse(s.contains("registration_type ="),
                "未传 registrationType 时不应出现该条件。实际 SQL: " + s);
    }

    // ---------------------------------------------------------------- M3

    /**
     * 票列表必须有确定顺序 —— resolveMode 取「第一张有 mode 的票」,无 order by 时
     * 玩法模式由 InnoDB 扫描路径决定。
     *
     * ⚠️ 负控:删掉 OmsTicketMapper.xml 的 order by ⇒ 本用例红。
     */
    @Test
    void ticketList_hasDeterministicOrder() throws Exception {
        Configuration cfg = parse(TICKET_MAPPER, OmsTicket.class);

        OmsTicket q = new OmsTicket();
        q.setOwnerId(1L);

        String s = sql(cfg, "com.chengyinhub.business.mapper.OmsTicketMapper.selectOmsTicketList", q);
        assertTrue(s.contains("order by id asc"),
                "票列表必须 order by id asc,否则混票主题 resolveMode 结果不稳定。实际 SQL: " + s);
    }

    // ---------------------------------------------------------------- V2

    /**
     * 核销置位必须是**条件**置位:where 带未核销判定,0 行即已被他人核销。
     * 无条件 update 等于回到读判,并发双扫照样双写。
     *
     * ⚠️ 负控:把 where 改成只剩 `where id = #{id}` ⇒ 本用例红。
     */
    @Test
    void verifyIfNotVerified_isConditionalUpdate() throws Exception {
        Configuration cfg = parse(REG_MAPPER, CmsRegistration.class);

        Map<String, Object> param = new HashMap<String, Object>();
        param.put("id", 1L);
        param.put("verifierId", 2L);

        String s = sql(cfg, "com.chengyinhub.business.mapper.CmsRegistrationMapper.verifyIfNotVerified", param);
        assertTrue(s.contains("verification_status = 1"), "必须置核销态。实际 SQL: " + s);
        assertTrue(s.contains("where") && s.contains("verification_status is null")
                        && s.contains("verification_status <> 1"),
                "必须是条件置位(where 带未核销判定),否则挡不住并发双扫。实际 SQL: " + s);
    }
}
