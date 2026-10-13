package com.chengyinhub.business.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A3 冷静期 mapper 的 H2 层覆盖：<b>边界比较</b>与<b>汇总作用域</b>。
 *
 * <p>★ 47:59 拒 / 48:00 放这条不变量的判据全在 {@code cooldown_until &gt; CURRENT_TIMESTAMP(3)} 这一句里，
 * 它在 H2 能真跑，所以不必等真库才有第一层覆盖。
 *
 * <p><b>H2 跑不了的那半边，本类不假装覆盖</b>：{@code insertForPositiveComplaintDetail} 用了
 * MySQL 的 {@code DATE_ADD(x, INTERVAL n HOUR)}，H2 1.4.200 没有这个函数（实测 "Function date_add not found"）。
 * 给 H2 挂一个同名 alias 只会证明那个 shim 好使，不证明生产 SQL 好使 —— 所以不挂。
 * 那条语句的行过滤（event_type=9 / change_type=1 / 同 member）由
 * {@code PlayerWithdrawalCooldownServiceTest} 的静态断言 + {@code PlayerWithdrawalCooldownMybatisIntegrationTest}
 * 的真库回读负责，见报告的 EXTERNAL_GATE。
 */
class PlayerWithdrawalCooldownMapperSqlTest {

    private static final long MEMBER = 100L;
    private static final long OTHER_MEMBER = 200L;

    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        Configuration configuration = new Configuration();
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:player_cooldown_" + System.nanoTime()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        configuration.addMapper(PlayerWithdrawalCooldownMapper.class);
        String resource = "mapper/business/PlayerWithdrawalCooldownMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true); Statement s = session.getConnection().createStatement()) {
            s.execute("create table player_withdrawal_cooldown(id bigint auto_increment primary key,"
                    + "balance_detail_id bigint,member_id bigint,complaint_id bigint,amount decimal(14,2),"
                    + "policy_key varchar(96),cooldown_hours int,created_at timestamp,cooldown_until timestamp,"
                    + "unique(balance_detail_id))");
        }
    }

    /**
     * ★边界方向：未到期的算冻结、已到期的必须放行，比较用的是库时钟。
     * 把 {@code >} 写反成 {@code <}，这条当场红。
     *
     * <p><b>不冒充覆盖的那一格</b>：{@code >} 与 {@code >=} 在「到期时刻正好等于当前毫秒」那一瞬
     * 才有分别，而测试跑到查询时库时钟已经往前走了 —— 没有冻结库时钟就观测不到。
     * 47:59 拒 / 48:00 放的<b>严格性</b>由 {@code PlayerWithdrawalCooldownServiceTest}
     * 对 XML 字面的断言钉住（把 &gt; 改成 &gt;= 那条会红）。
     */
    @Test
    void onlyUnexpiredRowsCountAndTheComparisonUsesTheDatabaseClock() throws Exception {
        // 到期时刻还在 1 分钟之后 ⇒ 仍冻结。
        seed(1L, MEMBER, "30.00", "DATEADD('MINUTE', 1, CURRENT_TIMESTAMP(3))");
        // 到期时刻已经过去 1 秒 ⇒ 必须放行。
        seed(2L, MEMBER, "70.00", "DATEADD('SECOND', -1, CURRENT_TIMESTAMP(3))");

        try (SqlSession session = factory.openSession(true)) {
            PlayerWithdrawalCooldownMapper mapper = session.getMapper(PlayerWithdrawalCooldownMapper.class);
            assertEquals(new BigDecimal("30.00"), mapper.sumUnexpiredFrozenByMemberAtDbNow(MEMBER),
                    "只有未到期那一笔算冻结；已到期那一笔必须放行");
        }
    }

    /** 多笔事件分别计时：已到期与未到期可以并存，只冻结后者。 */
    @Test
    void expiredAndUnexpiredEventsCoexistAndOnlyTheUnexpiredOneIsFrozen() throws Exception {
        seed(1L, MEMBER, "50.00", "DATEADD('HOUR', -1, CURRENT_TIMESTAMP(3))");
        seed(2L, MEMBER, "20.00", "DATEADD('HOUR', 1, CURRENT_TIMESTAMP(3))");
        seed(3L, MEMBER, "5.00", "DATEADD('HOUR', 47, CURRENT_TIMESTAMP(3))");

        try (SqlSession session = factory.openSession(true)) {
            PlayerWithdrawalCooldownMapper mapper = session.getMapper(PlayerWithdrawalCooldownMapper.class);
            assertEquals(new BigDecimal("25.00"), mapper.sumUnexpiredFrozenByMemberAtDbNow(MEMBER));
        }
    }

    /** ★跨 member 不得互相命中：A 提现不能被 B 的冷静额挡住，也不能借 B 的额度提走钱。 */
    @Test
    void oneMembersCooldownNeverShowsUpInAnotherMembersFrozenSum() throws Exception {
        seed(1L, MEMBER, "30.00", "DATEADD('HOUR', 1, CURRENT_TIMESTAMP(3))");
        seed(2L, OTHER_MEMBER, "999.00", "DATEADD('HOUR', 1, CURRENT_TIMESTAMP(3))");

        try (SqlSession session = factory.openSession(true)) {
            PlayerWithdrawalCooldownMapper mapper = session.getMapper(PlayerWithdrawalCooldownMapper.class);
            assertEquals(new BigDecimal("30.00"), mapper.sumUnexpiredFrozenByMemberAtDbNow(MEMBER));
            assertEquals(new BigDecimal("999.00"), mapper.sumUnexpiredFrozenByMemberAtDbNow(OTHER_MEMBER));
        }
    }

    /** 没有任何冷静行时是 0，不是 null ⇒ 不会把「查不到」变成 NPE，也不会误拦。 */
    @Test
    void anEmptyLedgerSumsToZeroRatherThanNull() {
        try (SqlSession session = factory.openSession(true)) {
            PlayerWithdrawalCooldownMapper mapper = session.getMapper(PlayerWithdrawalCooldownMapper.class);
            assertEquals(0, mapper.sumUnexpiredFrozenByMemberAtDbNow(MEMBER).compareTo(BigDecimal.ZERO));
        }
    }

    /** 明细查询也按 member 收口，且按 id 稳定排序。 */
    @Test
    void selectByMemberIsScopedAndOrdered() throws Exception {
        seed(1L, MEMBER, "10.00", "DATEADD('HOUR', 1, CURRENT_TIMESTAMP(3))");
        seed(2L, OTHER_MEMBER, "20.00", "DATEADD('HOUR', 1, CURRENT_TIMESTAMP(3))");
        seed(3L, MEMBER, "30.00", "DATEADD('HOUR', 1, CURRENT_TIMESTAMP(3))");

        try (SqlSession session = factory.openSession(true)) {
            PlayerWithdrawalCooldownMapper mapper = session.getMapper(PlayerWithdrawalCooldownMapper.class);
            assertEquals(2, mapper.selectByMember(MEMBER).size());
            assertEquals(new BigDecimal("10.00"), mapper.selectByMember(MEMBER).get(0).getAmount());
        }
    }

    /** cooldownUntil 用 H2 原生表达式造，不是生产 SQL —— 生产那条 insert 的 DATE_ADD 在 H2 跑不了。 */
    private void seed(long detailId, long memberId, String amount, String cooldownUntilExpr) throws Exception {
        try (SqlSession session = factory.openSession(true);
             Statement s = session.getConnection().createStatement()) {
            s.execute("insert into player_withdrawal_cooldown (balance_detail_id, member_id, complaint_id,"
                    + " amount, policy_key, cooldown_hours, created_at, cooldown_until) values ("
                    + detailId + "," + memberId + "," + detailId + "," + amount
                    + ",'withdrawal.player-complaint-cooldown-hours',48,CURRENT_TIMESTAMP(3),"
                    + cooldownUntilExpr + ")");
        }
    }
}
