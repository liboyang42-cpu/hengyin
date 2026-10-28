package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CoopSettlement;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.mapper.CoopComplaintMapper;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.business.service.INotifyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Coop T+7 打款入口的真实数据库事务回滚与重放门禁。 */
class CoopSettlementPayoutTransactionTest {

    private static final long SETTLEMENT_ID = 700L;
    private static final long TOPIC_ID = 200L;
    private static final long MEMBER_ID = 300L;
    private static final BigDecimal OPENING_BALANCE = new BigDecimal("100.00");
    private static final BigDecimal PAYOUT_AMOUNT = new BigDecimal("30.00");

    private JdbcTemplate jdbc;
    private ICmsTopicService topicService;
    private FailureGate failureGate;
    private boolean balanceWasUpdatedBeforeDetailFailure;
    private boolean balanceAndDetailWereWrittenBeforeStatusFailure;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:coop_payout_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        createSchemaAndFixture();

        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        CmsTopicServiceImpl target = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(target, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(target, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(target, "transactionManager", transactionManager);
        ReflectionTestUtils.setField(target, "coopSettlementOriginalMapper", mock(com.chengyinhub.business.mapper.CoopSettlementOriginalMapper.class));
        ReflectionTestUtils.setField(target, "coopSettlementAdjustmentMapper", mock(com.chengyinhub.business.mapper.CoopSettlementAdjustmentMapper.class));
        com.chengyinhub.business.mapper.CmsTopicMapper topics = mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        when(topics.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenAnswer(call -> jdbc.queryForObject(
                "SELECT id FROM cms_topic WHERE id=? FOR UPDATE", (rs, n) -> {
                    com.chengyinhub.business.domain.CmsTopic topic = new com.chengyinhub.business.domain.CmsTopic();
                    topic.setId(rs.getLong(1)); return topic;
                }, TOPIC_ID));
        ReflectionTestUtils.setField(target, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(target, "coopSettlementMapper", settlementMapper());
        ReflectionTestUtils.setField(target, "coopComplaintMapper", complaintMapper());
        ReflectionTestUtils.setField(target, "settlementDebtLedger", mock(com.chengyinhub.business.service.support.SettlementDebtLedger.class));
        ReflectionTestUtils.setField(target, "umsMemberMapper", memberMapper());
        ReflectionTestUtils.setField(target, "umsMemberBalanceDetailService", balanceDetailService());
        ReflectionTestUtils.setField(target, "notifyService", mock(INotifyService.class));

        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setInterfaces(ICmsTopicService.class);
        proxyFactory.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        topicService = (ICmsTopicService) proxyFactory.getProxy();
    }

    @Test
    void balanceDetailFailureRollsBackThenRetryCreditsExactlyOnce() {
        failureGate = FailureGate.BALANCE_DETAIL;

        // 1-04:失败行必须让整轮抛出(Quartz 记 FAIL),而不是返回 0 装作成功
        IllegalStateException failedRun = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> topicService.payoutDueCoopSettlements());

        assertTrue(failedRun.getMessage().contains("成功 0 笔"), failedRun.getMessage());
        assertTrue(balanceWasUpdatedBeforeDetailFailure,
                "失败注入必须命中‘余额 UPDATE 已执行、流水 INSERT 尚未成功’这道闸");
        assertDatabaseState("100.00", 0, 0,
                "流水写失败必须回滚余额、流水和待打款状态");
        System.out.println("NEGATIVE BALANCE_DETAIL rollback " + databaseState());

        failureGate = FailureGate.NONE;
        int firstRetry = topicService.payoutDueCoopSettlements();
        assertEquals(1, firstRetry, "失败恢复后应成功打款一次");
        assertDatabaseState("130.00", 1, 1, "第一次重跑只应入账一次");
        assertEquals(SETTLEMENT_ID, number(
                "SELECT event_id FROM ums_member_balance_detail WHERE event_type=5").longValue(),
                "Coop 打款流水业务键必须是 settlementId，而不是 topicId");
        assertEquals("Coop结算打款 - 主题" + TOPIC_ID,
                jdbc.queryForObject("SELECT change_reason FROM ums_member_balance_detail", String.class),
                "流水类型前缀必须与唯一索引生成列表达式一致");

        int secondRetry = topicService.payoutDueCoopSettlements();
        assertEquals(0, secondRetry, "已打款结算再次重跑必须跳过");
        assertDatabaseState("130.00", 1, 1, "第二次重跑不得重复入账");
        System.out.println("RETRY BALANCE_DETAIL firstPaid=" + firstRetry + " secondPaid=" + secondRetry
                + " " + databaseState());
    }

    @Test
    void paidStatusFailureRollsBackThenRetryCreditsExactlyOnce() {
        failureGate = FailureGate.PAID_STATUS;

        IllegalStateException failedRun = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> topicService.payoutDueCoopSettlements());

        assertTrue(failedRun.getMessage().contains("成功 0 笔"), failedRun.getMessage());
        assertTrue(balanceAndDetailWereWrittenBeforeStatusFailure,
                "失败注入必须命中‘余额和流水均已写、置已打款 UPDATE 返回 0’这道闸");
        assertDatabaseState("100.00", 0, 0,
                "置已打款失败必须回滚余额、流水和待打款状态");
        System.out.println("NEGATIVE PAID_STATUS rollback " + databaseState());

        failureGate = FailureGate.NONE;
        int firstRetry = topicService.payoutDueCoopSettlements();
        assertEquals(1, firstRetry, "失败恢复后应成功打款一次");
        assertDatabaseState("130.00", 1, 1, "第一次重跑只应入账一次");

        int secondRetry = topicService.payoutDueCoopSettlements();
        assertEquals(0, secondRetry, "已打款结算再次重跑必须跳过");
        assertDatabaseState("130.00", 1, 1, "第二次重跑不得重复入账");
        System.out.println("RETRY PAID_STATUS firstPaid=" + firstRetry + " secondPaid=" + secondRetry
                + " " + databaseState());
    }

    @Test
    void migrationUniqueKeyMatchesRuntimeSettlementBusinessKey() throws Exception {
        Path migration = Paths.get(System.getProperty("basedir"), "sql",
                "migration_coop_payout_unique_20260809.sql");
        String sql = new String(Files.readAllBytes(migration), StandardCharsets.UTF_8);

        assertTrue(sql.contains("event_type = 5 AND change_reason LIKE ''Coop结算打款 - 主题%'' THEN event_id"),
                "生成列必须只投影运行时写出的 settlementId 流水");
        assertTrue(sql.contains("UNIQUE KEY uk_coop_settlement_payout (coop_settlement_payout_id)"),
                "必须由数据库唯一索引兜住 settlementId 重放");
        assertFalse(sql.contains("ADD COLUMN IF NOT EXISTS"), "MySQL 8 不支持该语法");
    }

    private void createSchemaAndFixture() {
        jdbc.execute("CREATE TABLE cms_topic (id BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO cms_topic(id) VALUES (?)", TOPIC_ID);
        jdbc.execute("CREATE TABLE coop_settlement ("
                + "id BIGINT PRIMARY KEY, settlement_route VARCHAR(32), source_contract_type VARCHAR(48),"
                + "source_contract_id BIGINT, commercial_context_type VARCHAR(32), commercial_context_id BIGINT,"
                + "beneficiary_role VARCHAR(32), beneficiary_id BIGINT, service_unit VARCHAR(48),"
                + "topic_id BIGINT, merchant_id BIGINT, amount DECIMAL(12,2),"
                + "verified_heads INT, status INT NOT NULL, payable_time TIMESTAMP, payout_time TIMESTAMP,"
                + "del_flag INT NOT NULL)");
        jdbc.execute("CREATE TABLE ums_member (id BIGINT PRIMARY KEY, balance DECIMAL(12,2) NOT NULL)");
        jdbc.execute("CREATE TABLE ums_member_balance_detail ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, member_id BIGINT, current_balance DECIMAL(12,2),"
                + "after_balance DECIMAL(12,2), change_balance DECIMAL(12,2), change_reason VARCHAR(255),"
                + "event_type BIGINT, event_id BIGINT, change_type BIGINT, del_flag BIGINT, create_time TIMESTAMP)");
        jdbc.update("INSERT INTO ums_member(id,balance) VALUES (?,?)", MEMBER_ID, OPENING_BALANCE);
        jdbc.update("INSERT INTO coop_settlement(id,topic_id,merchant_id,amount,verified_heads,status,payable_time,del_flag) "
                        + "VALUES (?,?,?,?,?,0,?,0)",
                SETTLEMENT_ID, TOPIC_ID, MEMBER_ID, PAYOUT_AMOUNT, 2,
                new Timestamp(System.currentTimeMillis() - 60_000L));
    }

    private CoopComplaintMapper complaintMapper() {
        CoopComplaintMapper mapper = mock(CoopComplaintMapper.class);
        when(mapper.countOpenByTopicId(anyLong())).thenReturn(0);
        return mapper;
    }

    private CoopSettlementMapper settlementMapper() {
        return mock(CoopSettlementMapper.class, invocation -> {
            String method = invocation.getMethod().getName();
            if ("selectDuePayouts".equals(method)) {
                return dueSettlements((Date) invocation.getArgument(0));
            }
            if ("selectDuePayoutForUpdate".equals(method)) {
                return lockedDueSettlement((Long) invocation.getArgument(0), (Date) invocation.getArgument(1));
            }
            if ("markPaidIfPending".equals(method)) {
                return markPaid((Long) invocation.getArgument(0), (Date) invocation.getArgument(1));
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    private UmsMemberMapper memberMapper() {
        return mock(UmsMemberMapper.class, invocation -> {
            String method = invocation.getMethod().getName();
            if ("lockMemberById".equals(method)) {
                Long memberId = invocation.getArgument(0);
                List<Long> ids = jdbc.query("SELECT id FROM ums_member WHERE id=? FOR UPDATE",
                        (rs, rowNum) -> rs.getLong(1), memberId);
                return ids.isEmpty() ? null : ids.get(0);
            }
            if ("selectUmsMemberById".equals(method)) {
                return readMember((Long) invocation.getArgument(0));
            }
            if ("addBalance".equals(method)) {
                Long memberId = invocation.getArgument(0);
                BigDecimal amount = invocation.getArgument(1);
                return jdbc.update("UPDATE ums_member SET balance=balance+? WHERE id=?",
                        amount, memberId);
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    private IUmsMemberBalanceDetailService balanceDetailService() {
        return mock(IUmsMemberBalanceDetailService.class, invocation -> {
            if (!"insertUmsMemberBalanceDetail".equals(invocation.getMethod().getName())) {
                return Answers.RETURNS_DEFAULTS.answer(invocation);
            }
            UmsMemberBalanceDetail detail = invocation.getArgument(0);
            if (failureGate == FailureGate.BALANCE_DETAIL) {
                balanceWasUpdatedBeforeDetailFailure = money("SELECT balance FROM ums_member WHERE id=" + MEMBER_ID)
                        .compareTo(OPENING_BALANCE.add(PAYOUT_AMOUNT)) == 0;
                throw new InjectedBalanceDetailFailure("injected after balance update");
            }
            return jdbc.update("INSERT INTO ums_member_balance_detail("
                            + "member_id,current_balance,after_balance,change_balance,change_reason,event_type,event_id,"
                            + "change_type,del_flag,create_time) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    detail.getMemberId(), detail.getCurrentBalance(), detail.getAfterBalance(), detail.getChangeBalance(),
                    detail.getChangeReason(), detail.getEventType(), detail.getEventId(), detail.getChangeType(),
                    detail.getDelFlag(), detail.getCreateTime());
        });
    }

    private List<CoopSettlement> dueSettlements(Date now) {
        return jdbc.query("SELECT id,topic_id,merchant_id,amount,verified_heads,status,payable_time,payout_time,del_flag "
                        + "FROM coop_settlement WHERE del_flag=0 AND status=0 AND payable_time<=?",
                (rs, rowNum) -> settlement(rs.getLong("id"), rs.getLong("topic_id"), rs.getLong("merchant_id"),
                        rs.getBigDecimal("amount"), rs.getInt("verified_heads"), rs.getInt("status"),
                        rs.getTimestamp("payable_time"), rs.getTimestamp("payout_time"), rs.getInt("del_flag")),
                new Timestamp(now.getTime()));
    }

    private CoopSettlement lockedDueSettlement(Long id, Date now) {
        List<CoopSettlement> rows = jdbc.query("SELECT id,topic_id,merchant_id,amount,verified_heads,status,"
                        + "payable_time,payout_time,del_flag FROM coop_settlement "
                        + "WHERE id=? AND del_flag=0 AND status=0 AND payable_time<=? FOR UPDATE",
                (rs, rowNum) -> settlement(rs.getLong("id"), rs.getLong("topic_id"), rs.getLong("merchant_id"),
                        rs.getBigDecimal("amount"), rs.getInt("verified_heads"), rs.getInt("status"),
                        rs.getTimestamp("payable_time"), rs.getTimestamp("payout_time"), rs.getInt("del_flag")),
                id, new Timestamp(now.getTime()));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int markPaid(Long id, Date payoutTime) {
        if (failureGate == FailureGate.PAID_STATUS) {
            balanceAndDetailWereWrittenBeforeStatusFailure =
                    money("SELECT balance FROM ums_member WHERE id=" + MEMBER_ID)
                            .compareTo(OPENING_BALANCE.add(PAYOUT_AMOUNT)) == 0
                            && number("SELECT COUNT(*) FROM ums_member_balance_detail").intValue() == 1;
            return 0;
        }
        return jdbc.update("UPDATE coop_settlement SET status=1,payout_time=? "
                        + "WHERE id=? AND del_flag=0 AND status=0",
                payoutTime == null ? null : new Timestamp(payoutTime.getTime()), id);
    }

    private UmsMember readMember(Long id) {
        List<UmsMember> rows = jdbc.query("SELECT id,balance FROM ums_member WHERE id=?", (rs, rowNum) -> {
            UmsMember member = new UmsMember();
            member.setId(rs.getLong("id"));
            member.setBalance(rs.getBigDecimal("balance"));
            return member;
        }, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private CoopSettlement settlement(long id, long topicId, long merchantId, BigDecimal amount,
            int verifiedHeads, int status, Date payableTime, Date payoutTime, int delFlag) {
        CoopSettlement settlement = new CoopSettlement();
        settlement.setId(id);
        settlement.setTopicId(topicId);
        settlement.setMerchantId(merchantId);
        settlement.setAmount(amount);
        settlement.setVerifiedHeads(verifiedHeads);
        settlement.setStatus(status);
        settlement.setPayableTime(payableTime);
        settlement.setPayoutTime(payoutTime);
        settlement.setDelFlag(delFlag);
        return settlement;
    }

    private void assertDatabaseState(String balance, int paidStatus, int detailCount, String message) {
        assertEquals(new BigDecimal(balance), money("SELECT balance FROM ums_member WHERE id=" + MEMBER_ID), message);
        assertEquals(paidStatus, number("SELECT status FROM coop_settlement WHERE id=" + SETTLEMENT_ID).intValue(), message);
        assertEquals(detailCount, number("SELECT COUNT(*) FROM ums_member_balance_detail").intValue(), message);
    }

    private String databaseState() {
        return "balance=" + money("SELECT balance FROM ums_member WHERE id=" + MEMBER_ID)
                + " status=" + number("SELECT status FROM coop_settlement WHERE id=" + SETTLEMENT_ID)
                + " detailCount=" + number("SELECT COUNT(*) FROM ums_member_balance_detail");
    }

    private BigDecimal money(String sql) {
        return jdbc.queryForObject(sql, BigDecimal.class);
    }

    private Number number(String sql) {
        return jdbc.queryForObject(sql, Number.class);
    }

    private enum FailureGate {
        NONE,
        BALANCE_DETAIL,
        PAID_STATUS
    }

    private static final class InjectedBalanceDetailFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private InjectedBalanceDetailFailure(String message) {
            super(message);
        }
    }
}
