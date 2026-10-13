package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ClubPayable;
import com.chengyinhub.business.domain.ClubPayableBatch;
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
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * F3 应付台账 mapper 的 H2 层覆盖：<b>只追加</b>、<b>批次作用域</b>、<b>三条 CAS 的旧状态/人员分离条件</b>。
 *
 * <p>H2 证明的是语句形状；DB CHECK（金额方向、PAID 三字段齐备、confirmed≠paid）与唯一幂等键
 * 由 {@code ClubPayableMybatisIntegrationTest} 在真 MySQL 上证，这里不冒充。
 */
class ClubPayableMapperSqlTest {

    private static final long TOPIC = 23L;
    private static final long CLUB = 7L;
    private static final long OTHER_CLUB = 9L;
    private static final long CONFIRMER = 71L;
    private static final long PAYER = 72L;

    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        Configuration configuration = new Configuration();
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:club_payable_" + System.nanoTime()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        configuration.addMapper(ClubPayableMapper.class);
        String resource = "mapper/business/ClubPayableMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true); Statement s = session.getConnection().createStatement()) {
            s.execute("create table topic_edition_finalization(id bigint auto_increment primary key,"
                    + "topic_id bigint,club_id bigint,final_status varchar(16),"
                    + "fixed_compensation decimal(14,2),quality_amount decimal(14,2),extra_amount decimal(14,2),"
                    + "headcount_amount decimal(14,2),"
                    + "payee_profile_id bigint,payee_profile_version bigint,payee_facts_hash char(64),"
                    + "idempotency_key varchar(96),finalized_by bigint,"
                    + "finalized_at timestamp default current_timestamp,unique(topic_id))");
            s.execute("create table club_payable(id bigint auto_increment primary key,topic_id bigint,"
                    + "club_id bigint,component varchar(16),entry_type varchar(16),amount decimal(14,2),"
                    + "batch_id bigint,idempotency_key varchar(96),reversal_of bigint,reason varchar(500),"
                    + "created_by bigint,created_at timestamp default current_timestamp)");
            s.execute("create table club_payable_batch(id bigint auto_increment primary key,"
                    + "batch_no varchar(64),club_id bigint,topic_id bigint,total_amount decimal(14,2),"
                    + "batch_status varchar(16),invoice_status varchar(16),invoice_no varchar(64),"
                    + "red_invoice_no varchar(64),"
                    + "confirmed_by bigint,confirmed_at timestamp,paid_by bigint,paid_at timestamp,"
                    + "payment_reference varchar(128),created_at timestamp default current_timestamp)");
        }
    }

    /** 台账只有 insert：撤销写反向新行，原行一个字不动。 */
    @Test
    void aReversalAppendsANewRowAndLeavesTheOriginalUntouched() {
        try (SqlSession session = factory.openSession(true)) {
            ClubPayableMapper mapper = session.getMapper(ClubPayableMapper.class);
            mapper.insertPayable(accrual("QUALITY", "400"));
            Long originalId = mapper.selectPayables(TOPIC, CLUB).get(0).getId();

            ClubPayable reversal = accrual("QUALITY", "-400");
            reversal.setEntryType(ClubPayable.ENTRY_REVERSAL);
            reversal.setReversalOf(originalId);
            reversal.setIdempotencyKey("REVERSAL:" + originalId);
            mapper.insertPayable(reversal);

            List<ClubPayable> rows = mapper.selectPayables(TOPIC, CLUB);
            assertEquals(2, rows.size());
            assertEquals(new BigDecimal("400.00"), rows.get(0).getAmount(), "原 accrual 不得被改写");
            assertEquals(new BigDecimal("-400.00"), rows.get(1).getAmount());
            assertEquals(originalId, rows.get(1).getReversalOf());
        }
    }

    /**
     * ★红冲路径的锁定读读得到同一行（语句形状可用）。
     *
     * <p>H2 只证「这条 {@code for update} 语句是合法的、取回的是同一行」；<b>行锁真的拦住并发
     * attach</b> 由 {@code ClubPayableMybatisIntegrationTest#aLockedPayableBlocksAConcurrentBatchAttach}
     * 在真 MySQL 上证，这里不冒充。
     */
    @Test
    void theReversalPathCanReadTheOriginalRowWithARowLock() {
        try (SqlSession session = factory.openSession(true)) {
            ClubPayableMapper mapper = session.getMapper(ClubPayableMapper.class);
            mapper.insertPayable(accrual("QUALITY", "400"));
            Long originalId = mapper.selectPayables(TOPIC, CLUB).get(0).getId();

            ClubPayable locked = mapper.selectPayableByIdForUpdate(originalId);

            assertEquals(originalId, locked.getId());
            assertEquals(new BigDecimal("400.00"), locked.getAmount());
            assertNull(locked.getBatchId(), "红冲判「已进批次」看的就是这一列，锁定读必须把它读出来");
        }
    }

    /** ★批次只收本俱乐部、且仍未进批次的行。别的俱乐部的应付行不得被卷进来。 */
    @Test
    void onlyUnbatchedRowsOfThatExactClubAreSweptIntoTheBatch() {
        try (SqlSession session = factory.openSession(true)) {
            ClubPayableMapper mapper = session.getMapper(ClubPayableMapper.class);
            mapper.insertPayable(accrual("FIXED", "1500"));
            ClubPayable foreign = accrual("FIXED", "999");
            foreign.setClubId(OTHER_CLUB);
            mapper.insertPayable(foreign);

            assertEquals(1, mapper.selectUnbatchedPayables(TOPIC, CLUB).size());
            ClubPayableBatch batch = batch("CPB-1");
            mapper.insertBatch(batch);
            assertEquals(1, mapper.attachUnbatchedPayables(batch.getId(), TOPIC, CLUB));

            assertEquals(0, mapper.selectUnbatchedPayables(TOPIC, CLUB).size(), "挂过之后不得再被收一次");
            assertEquals(1, mapper.selectUnbatchedPayables(TOPIC, OTHER_CLUB).size(),
                    "别的俱乐部的行必须原地不动");
        }
    }

    /**
     * ★E4「一期一批次一张发票」：归集与挂靠都按 (topic, club) 收窄。
     *
     * <p>少一个 topic 谓词 ⇒ 给期 B 建批次会把期 A 的行一起挂上 batch_id ⇒
     * 两期两份合同的钱并进同一张发票，且期 A 的红冲能力被连带锁死（CLUB_PAYABLE_ALREADY_BATCHED）。
     */
    @Test
    void anotherEditionsRowsAreNeverSweptIntoThisEditionsBatch() {
        try (SqlSession session = factory.openSession(true)) {
            ClubPayableMapper mapper = session.getMapper(ClubPayableMapper.class);
            mapper.insertPayable(accrual("FIXED", "1500"));
            ClubPayable otherEdition = accrual("FIXED", "999");
            otherEdition.setTopicId(TOPIC + 1);
            mapper.insertPayable(otherEdition);

            assertEquals(1, mapper.selectUnbatchedPayables(TOPIC, CLUB).size(),
                    "同一俱乐部的另一期不得出现在本期的归集里");
            ClubPayableBatch batch = batch("CPB-1");
            mapper.insertBatch(batch);
            assertEquals(1, mapper.attachUnbatchedPayables(batch.getId(), TOPIC, CLUB));

            assertEquals(1, mapper.selectUnbatchedPayables(TOPIC + 1, CLUB).size(),
                    "另一期的行必须仍然可以自己建批次、自己红冲");
        }
    }

    /** 并发建批次：第二个批次挂到 0 行 ⇒ service 判 CONTENT_CHANGED 回滚。 */
    @Test
    void aSecondConcurrentBatchAttachesNothing() {
        try (SqlSession session = factory.openSession(true)) {
            ClubPayableMapper mapper = session.getMapper(ClubPayableMapper.class);
            mapper.insertPayable(accrual("FIXED", "1500"));
            ClubPayableBatch first = batch("CPB-1");
            mapper.insertBatch(first);
            ClubPayableBatch second = batch("CPB-2");
            mapper.insertBatch(second);

            assertEquals(1, mapper.attachUnbatchedPayables(first.getId(), TOPIC, CLUB));
            assertEquals(0, mapper.attachUnbatchedPayables(second.getId(), TOPIC, CLUB));
        }
    }

    /**
     * ★发票轴与付款轴互不引用：开票的 CAS 里没有任何 batch_status 条件。
     * 「已打款但未开票」「已开票但没打款」都是真实状态，混成一条轴就表达不了。
     */
    @Test
    void theInvoiceAxisMovesIndependentlyOfThePaymentAxis() {
        try (SqlSession session = factory.openSession(true)) {
            ClubPayableMapper mapper = session.getMapper(ClubPayableMapper.class);
            ClubPayableBatch batch = batch("CPB-1");
            mapper.insertBatch(batch);
            assertEquals(ClubPayableBatch.INVOICE_NONE, mapper.selectBatch(batch.getId()).getInvoiceStatus());

            // 还是 PENDING（一分钱没付）就能开票
            assertEquals(1, mapper.markBatchInvoiceIssued(batch.getId(), "INV-001"));
            assertEquals(ClubPayableBatch.INVOICE_ISSUED, mapper.selectBatch(batch.getId()).getInvoiceStatus());
            assertEquals("INV-001", mapper.selectBatch(batch.getId()).getInvoiceNo());
            assertEquals(ClubPayableBatch.STATUS_PENDING, mapper.selectBatch(batch.getId()).getBatchStatus(),
                    "开票不得顺手把付款状态推进一格");

            assertEquals(0, mapper.markBatchInvoiceIssued(batch.getId(), "INV-002"), "重复开票拿不到行");

            assertEquals(1, mapper.markBatchInvoiceRed(batch.getId(), "RED-001"));
            assertEquals(ClubPayableBatch.INVOICE_RED, mapper.selectBatch(batch.getId()).getInvoiceStatus());
            assertEquals("RED-001", mapper.selectBatch(batch.getId()).getRedInvoiceNo());
        }
    }

    /** 没开过票就红冲拿不到行（where invoice_status = 'ISSUED'）。 */
    @Test
    void aRedInvoiceRequiresAnIssuedOneFirst() {
        try (SqlSession session = factory.openSession(true)) {
            ClubPayableMapper mapper = session.getMapper(ClubPayableMapper.class);
            ClubPayableBatch batch = batch("CPB-1");
            mapper.insertBatch(batch);

            assertEquals(0, mapper.markBatchInvoiceRed(batch.getId(), "RED-001"));
            assertEquals(ClubPayableBatch.INVOICE_NONE, mapper.selectBatch(batch.getId()).getInvoiceStatus());
        }
    }

    /** 红冲理由必须落进台账行本身：本仓 @Log 不写请求体，日志里查不到。 */
    @Test
    void theReversalReasonIsPersistedOnTheLedgerRowItself() {
        try (SqlSession session = factory.openSession(true)) {
            ClubPayableMapper mapper = session.getMapper(ClubPayableMapper.class);
            ClubPayable reversal = accrual("QUALITY", "-400");
            reversal.setEntryType(ClubPayable.ENTRY_REVERSAL);
            reversal.setIdempotencyKey("REVERSAL:1");
            reversal.setReason("商家过错定责晚到，撤销原判定");
            mapper.insertPayable(reversal);

            assertEquals("商家过错定责晚到，撤销原判定",
                    mapper.selectPayables(TOPIC, CLUB).get(0).getReason());
        }
    }

    /** 按 component 读回全部台账行（含红冲）：重计提要拿它算净额。 */
    @Test
    void payablesCanBeReadBackPerComponentForNetting() {
        try (SqlSession session = factory.openSession(true)) {
            ClubPayableMapper mapper = session.getMapper(ClubPayableMapper.class);
            mapper.insertPayable(accrual("QUALITY", "400"));
            mapper.insertPayable(accrual("FIXED", "1500"));

            assertEquals(1, mapper.selectPayablesByComponent(TOPIC, CLUB, "QUALITY").size());
            assertEquals(new BigDecimal("400.00"),
                    mapper.selectPayablesByComponent(TOPIC, CLUB, "QUALITY").get(0).getAmount());
            assertEquals(0, mapper.selectPayablesByComponent(TOPIC, CLUB, "EXTRA").size());
        }
    }

    /**
     * ★重计提走的是<b>锁定读</b>版本，取回的行必须与非锁定版逐字段一致（差别只在行锁）。
     *
     * <p>H2 只证「这条 {@code for update} 语句合法、选出的是同一批行、{@code batch_id} 读得出来」；
     * <b>行锁真的拦住并发 attach</b> 由 {@code ClubPayableMybatisIntegrationTest#
     * aReaccrualHoldsItsComponentRowsAgainstAConcurrentBatchAttach} 在真 MySQL 上证，这里不冒充。
     */
    @Test
    void theReaccrualPathReadsTheComponentLedgerWithARowLock() {
        try (SqlSession session = factory.openSession(true)) {
            ClubPayableMapper mapper = session.getMapper(ClubPayableMapper.class);
            mapper.insertPayable(accrual("FIXED", "2000"));
            ClubPayable reversal = accrual("FIXED", "-500");
            reversal.setEntryType(ClubPayable.ENTRY_REVERSAL);
            reversal.setIdempotencyKey("REVERSAL:1");
            reversal.setReason("复核后下调");
            mapper.insertPayable(reversal);
            mapper.insertPayable(accrual("QUALITY", "400"));

            List<ClubPayable> locked = mapper.selectPayablesByComponentForUpdate(TOPIC, CLUB, "FIXED");

            assertEquals(2, locked.size(), "锁定读要覆盖该 component 的全部行，含红冲行");
            assertEquals(new BigDecimal("2000.00"), locked.get(0).getAmount());
            assertEquals(new BigDecimal("-500.00"), locked.get(1).getAmount());
            assertNull(locked.get(0).getBatchId(), "「有没有进批次」这个守卫读的就是这一列");
            assertEquals(mapper.selectPayablesByComponent(TOPIC, CLUB, "FIXED").size(), locked.size(),
                    "锁定读与非锁定读必须选出同一批行，差别只在行锁");
            assertEquals(0, mapper.selectPayablesByComponentForUpdate(TOPIC, CLUB, "EXTRA").size());
        }
        // ★行锁子句本身 H2 证不了（它对 for update 宽容），但「这条语句上还挂着 for update」
        //   必须能在本地变红：摘掉它，多付窗口就重新打开，而上面每一条断言照样绿。
        //   行锁真的阻塞并发 attach 由真 MySQL IT 观测。
        String statement = statementOf("selectPayablesByComponentForUpdate");
        assertEquals(true, statement.toLowerCase().contains("for update"),
                "重计提的 component 读必须带 for update，实际是：" + statement);
        assertEquals(false, statementOf("selectPayablesByComponent").toLowerCase().contains("for update"),
                "非锁定版必须保持非锁定，两条各司其职");
    }

    /** 从 mapper XML 里取出某个 statement 的原文（只读文本，不经 MyBatis 解析）。 */
    private static String statementOf(String id) {
        try (InputStream in = Resources.getResourceAsStream("mapper/business/ClubPayableMapper.xml")) {
            byte[] buffer = new byte[in.available()];
            int read = in.read(buffer);
            String xml = new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("<select id=\"" + id + "\"[\\s\\S]*?</select>").matcher(xml);
            if (!matcher.find()) throw new IllegalStateException("找不到 statement " + id);
            return matcher.group();
        } catch (Exception e) {
            throw new IllegalStateException("读不到 ClubPayableMapper.xml", e);
        }
    }

    /** 确认是带旧状态的 CAS：只有 PENDING 能被确认，第二次拿不到行。 */
    @Test
    void onlyADraftBatchCanBeConfirmedAndOnlyOnce() {
        try (SqlSession session = factory.openSession(true)) {
            ClubPayableMapper mapper = session.getMapper(ClubPayableMapper.class);
            ClubPayableBatch batch = batch("CPB-1");
            mapper.insertBatch(batch);

            assertEquals(1, mapper.confirmBatchCas(batch.getId(), CONFIRMER, new Date()));
            assertEquals(0, mapper.confirmBatchCas(batch.getId(), PAYER, new Date()));
            assertEquals(ClubPayableBatch.STATUS_CONFIRMED, mapper.selectBatch(batch.getId()).getBatchStatus());
            assertEquals(Long.valueOf(CONFIRMER), mapper.selectBatch(batch.getId()).getConfirmedBy());
        }
    }

    /**
     * ★人员分离写进了 SQL 的 where：确认人自己来登记付款，这条 UPDATE 命中 0 行。
     * Java 那层的人话判断只是第一道 —— 把它删了，这条依然红。
     */
    @Test
    void theConfirmerCannotRecordThePaymentBecauseTheCasExcludesThem() {
        try (SqlSession session = factory.openSession(true)) {
            ClubPayableMapper mapper = session.getMapper(ClubPayableMapper.class);
            ClubPayableBatch batch = batch("CPB-1");
            mapper.insertBatch(batch);
            mapper.confirmBatchCas(batch.getId(), CONFIRMER, new Date());

            assertEquals(0, mapper.markBatchPaidCas(batch.getId(), CONFIRMER, new Date(), "TX-1"));
            assertNull(mapper.selectBatch(batch.getId()).getPaidBy());

            assertEquals(1, mapper.markBatchPaidCas(batch.getId(), PAYER, new Date(), "TX-1"));
            assertEquals(ClubPayableBatch.STATUS_PAID, mapper.selectBatch(batch.getId()).getBatchStatus());
        }
    }

    /** 未确认的批次不得直接跳到 PAID（旧状态条件写在 where 里）。 */
    @Test
    void aDraftBatchCannotJumpStraightToPaid() {
        try (SqlSession session = factory.openSession(true)) {
            ClubPayableMapper mapper = session.getMapper(ClubPayableMapper.class);
            ClubPayableBatch batch = batch("CPB-1");
            mapper.insertBatch(batch);

            assertEquals(0, mapper.markBatchPaidCas(batch.getId(), PAYER, new Date(), "TX-1"));
        }
    }

    /** FINAL 头按 topic 唯一读回；锁定读与普通读读到的是同一行。 */
    @Test
    void theFinalizationHeadIsReadBackByTopic() {
        try (SqlSession session = factory.openSession(true)) {
            ClubPayableMapper mapper = session.getMapper(ClubPayableMapper.class);
            com.chengyinhub.business.domain.TopicEditionFinalization head =
                    new com.chengyinhub.business.domain.TopicEditionFinalization();
            head.setTopicId(TOPIC);
            head.setClubId(CLUB);
            head.setFinalStatus(com.chengyinhub.business.domain.TopicEditionFinalization.STATUS_FINAL);
            head.setFixedCompensation(new BigDecimal("1500.00"));
            head.setQualityAmount(new BigDecimal("400.00"));
            head.setExtraAmount(new BigDecimal("80.00"));
            head.setPayeeProfileId(9L);
            head.setPayeeProfileVersion(1L);
            head.setPayeeFactsHash("FACTS_HASH");
            head.setIdempotencyKey("FINAL:" + TOPIC);
            head.setFinalizedBy(CONFIRMER);
            assertEquals(1, mapper.insertFinalization(head));

            assertEquals(CLUB, mapper.selectFinalization(TOPIC).getClubId());
            assertEquals("FACTS_HASH", mapper.selectFinalizationForUpdate(TOPIC).getPayeeFactsHash());
            assertNull(mapper.selectFinalization(999L));
        }
    }

    private static ClubPayable accrual(String component, String amount) {
        ClubPayable payable = new ClubPayable();
        payable.setTopicId(TOPIC);
        payable.setClubId(CLUB);
        payable.setComponent(component);
        payable.setEntryType(ClubPayable.ENTRY_ACCRUAL);
        payable.setAmount(new BigDecimal(amount));
        payable.setIdempotencyKey("FINAL:" + TOPIC);
        payable.setCreatedBy(CONFIRMER);
        return payable;
    }

    private static ClubPayableBatch batch(String batchNo) {
        ClubPayableBatch batch = new ClubPayableBatch();
        batch.setBatchNo(batchNo);
        batch.setClubId(CLUB);
        batch.setTopicId(TOPIC);
        batch.setTotalAmount(new BigDecimal("1500.00"));
        batch.setBatchStatus(ClubPayableBatch.STATUS_PENDING);
        batch.setInvoiceStatus(ClubPayableBatch.INVOICE_NONE);
        return batch;
    }
}
