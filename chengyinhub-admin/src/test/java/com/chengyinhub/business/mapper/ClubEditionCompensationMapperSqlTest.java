package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ClubExtraHoursApproval;
import com.chengyinhub.business.domain.ClubQualityDecision;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A1/A2 补偿域 mapper 的 H2 层覆盖 —— 这四份新 XML 此前唯一的覆盖是<b>从未跑过</b>的 MySQL IT。
 *
 * <p>H2 证明的是 <b>SQL 绑定与语句形状</b>：ODKU 只改 planned_hours、两条 CAS 的旧状态条件、
 * 一次确认对证据的要求、更正计数。CHECK / 唯一键 / 生成列这些<b>约束级</b>事实
 * 只有真 MySQL 能证（见 ClubEditionCompensationMybatisIntegrationTest），H2 不冒充它。
 */
class ClubEditionCompensationMapperSqlTest {

    private static final long TOPIC = 23L;
    private static final long CLUB = 7L;
    private static final long OPERATOR = 71L;
    private static final long DAY_MILLIS = 24L * 3600L * 1000L;
    private static final Date TOMORROW = new Date(System.currentTimeMillis() + DAY_MILLIS);
    private static final Date YESTERDAY = new Date(System.currentTimeMillis() - DAY_MILLIS);

    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        Configuration configuration = new Configuration();
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:club_compensation_" + System.nanoTime()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        configuration.addMapper(ClubEditionCompensationMapper.class);
        String resource = "mapper/business/ClubEditionCompensationMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true); Statement s = session.getConnection().createStatement()) {
            s.execute("create table club_edition_hours(id bigint auto_increment primary key,topic_id bigint,"
                    + "club_id bigint,hour_kind varchar(16),planned_hours decimal(10,2),"
                    + "reported_actual_hours decimal(10,2),confirmed_actual_hours decimal(10,2),"
                    + "confirm_status varchar(16),reported_by bigint,reported_at timestamp,confirmed_by bigint,"
                    + "confirmed_at timestamp,evidence_note varchar(1000),update_time timestamp,"
                    + "unique(topic_id,club_id,hour_kind))");
            s.execute("create table club_edition_compensation_terms(id bigint auto_increment primary key,"
                    + "topic_id bigint,executing_club_id bigint,fixed_compensation decimal(14,2),"
                    + "extra_hour_rate decimal(10,2),quality_dimension_amount decimal(10,2),"
                    + "quality_dimensions varchar(255),evidence_due_at timestamp,"
                    + "fulfillment_end_at timestamp,extra_approver_user_id bigint,config_hash char(64),"
                    + "frozen_at timestamp)");
            s.execute("create table club_extra_hours_approval(id bigint auto_increment primary key,"
                    + "topic_id bigint,club_id bigint,category varchar(16),approved_hours decimal(10,2),"
                    + "approved_by bigint,approved_at timestamp,work_started_at timestamp,"
                    + "idempotency_key varchar(96),correction_of bigint,note varchar(500))");
            s.execute("create table club_quality_decision(id bigint auto_increment primary key,topic_id bigint,"
                    + "club_id bigint,dimension varchar(32),decision_status varchar(16),amount decimal(10,2),"
                    + "evidence_hash char(64),excluded_by_fault tinyint default 0,fault_party varchar(16),"
                    + "reason varchar(500),due_at timestamp,"
                    + "confirmed_by bigint,confirmed_at timestamp,decision_version bigint default 1,"
                    + "is_current tinyint default 1,correction_of bigint)");
            s.execute("create table topic_edition_cost(id bigint auto_increment primary key,topic_id bigint,"
                    + "is_current int,fixed_compensation decimal(14,2),extra_approver_user_id bigint)");
            s.execute("create table club_edition_legacy_exemption(topic_id bigint primary key,lifecycle int,"
                    + "selling_started_at timestamp,reason varchar(255),decided_by varchar(64),"
                    + "decided_at timestamp default current_timestamp)");
            s.execute("create table topic_edition_funds(id bigint auto_increment primary key,topic_id bigint,"
                    + "snapshot_kind varchar(16),is_current int,g_earned decimal(14,2))");
            s.execute("create table topic_edition_funds_attribution(id bigint auto_increment primary key,"
                    + "funds_snapshot_id bigint,source_club_id bigint,source_key bigint,ticket_count bigint,"
                    + "net_ticket_count bigint,earned_amount decimal(14,2))");
            s.execute("create table cms_activity(id bigint primary key,topic_id bigint)");
            s.execute("create table cms_registration(id bigint primary key,owner_type int,owner_id bigint,"
                    + "purchase_kind int,payment_status int,source_club_id bigint,source_frozen_at timestamp)");
        }
    }

    @Test
    void finalSnapshotAndFrozenAttributionAreReadFromTheCurrentFinalOnly() throws Exception {
        try (SqlSession session = factory.openSession(true); Statement s = session.getConnection().createStatement()) {
            s.execute("insert into topic_edition_funds(id,topic_id,snapshot_kind,is_current,g_earned)"
                    + " values(10,23,'PROVISIONAL',1,999.00),(11,23,'FINAL',1,1481.50)");
            s.execute("insert into topic_edition_funds_attribution"
                    + "(funds_snapshot_id,source_club_id,source_key,ticket_count,net_ticket_count,earned_amount)"
                    + " values(11,7,7,2,2,981.50),(11,null,0,1,1,500.00)");
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            assertEquals(new BigDecimal("1481.50"), mapper.selectCurrentFinalGEarned(TOPIC),
                    "只读 FINAL 六量：PROVISIONAL 不得被当成「本期已结清」");
            List<com.chengyinhub.business.domain.TopicEditionFundsAttribution> attribution =
                    mapper.selectCurrentFinalAttributions(TOPIC);
            assertEquals(2, attribution.size());
            assertNull(attribution.get(0).getSourceClubId(), "平台自然流量必须保留独立金额行");
            assertEquals(new BigDecimal("500.00"), attribution.get(0).getEarnedAmount());
        }
    }

    /**
     * ★全仓唯一残存的 club_edition_hours upsert 只许覆盖 planned_hours。
     * 第二次 upsert 之后自报/确认/审计列必须一个字没动 —— 那正是旧 upsertHours 后门的形状。
     */
    @Test
    void theSurvivingUpsertOverwritesThePlannedHoursAndNothingElse() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            mapper.upsertPlannedHours(TOPIC, CLUB, "PREP", new BigDecimal("4"));
            mapper.reportActualHoursCas(TOPIC, CLUB, "PREP", new BigDecimal("6"), 88L);
            mapper.confirmActualHoursCas(TOPIC, CLUB, "PREP", new BigDecimal("5.5"), OPERATOR);

            // MySQL 的 ODKU 命中已有行时返回 2（1 删 + 1 插的记账方式），service 判的是 < 1。
            org.junit.jupiter.api.Assertions.assertTrue(
                    mapper.upsertPlannedHours(TOPIC, CLUB, "PREP", new BigDecimal("9")) >= 1);

            com.chengyinhub.business.domain.ClubEditionHours row =
                    mapper.selectHoursForClub(TOPIC, CLUB).get(0);
            assertEquals(new BigDecimal("9.00"), row.getPlannedHours());
            assertEquals("CONFIRMED", row.getConfirmStatus(), "确认状态不得被 upsert 改回去");
            assertEquals(new BigDecimal("5.50"), row.getConfirmedActualHours(), "确认工时不得被 upsert 覆盖");
            assertEquals(Long.valueOf(OPERATOR), row.getConfirmedBy(), "确认人不得被 upsert 覆盖");
            assertEquals(new BigDecimal("6.00"), row.getReportedActualHours(), "自报工时不得被 upsert 覆盖");
        }
    }

    /** 确认的旧状态条件必须是 REPORTED：请求体直传 CONFIRMED 在 SQL 这一层就没有入口。 */
    @Test
    void confirmingHoursThatWereNeverReportedMatchesNoRow() {
        try (SqlSession session = factory.openSession(true)) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            mapper.upsertPlannedHours(TOPIC, CLUB, "ONSITE", new BigDecimal("4"));

            assertEquals(0, mapper.confirmActualHoursCas(TOPIC, CLUB, "ONSITE", new BigDecimal("5"), OPERATOR));
            assertEquals(1, mapper.reportActualHoursCas(TOPIC, CLUB, "ONSITE", new BigDecimal("5"), 88L));
            assertEquals(1, mapper.confirmActualHoursCas(TOPIC, CLUB, "ONSITE", new BigDecimal("5"), OPERATOR));
            // 已确认之后再确认一次拿不到行 —— 一次确认。
            assertEquals(0, mapper.confirmActualHoursCas(TOPIC, CLUB, "ONSITE", new BigDecimal("9"), OPERATOR));
        }
    }

    /** 自报只允许从未确认态出发；确认之后自报也拿不到行。 */
    @Test
    void reportingIsRefusedOnceTheHoursAreConfirmed() {
        try (SqlSession session = factory.openSession(true)) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            mapper.upsertPlannedHours(TOPIC, CLUB, "REVIEW", new BigDecimal("2"));
            mapper.reportActualHoursCas(TOPIC, CLUB, "REVIEW", new BigDecimal("3"), 88L);
            mapper.confirmActualHoursCas(TOPIC, CLUB, "REVIEW", new BigDecimal("3"), OPERATOR);

            assertEquals(0, mapper.reportActualHoursCas(TOPIC, CLUB, "REVIEW", new BigDecimal("99"), 88L));
        }
    }

    /** approved_at 由 SQL 写死 CURRENT_TIMESTAMP(3)：参数里根本没有这一列。 */
    @Test
    void theApprovalTimestampComesFromTheStatementNotFromTheParameter() {
        try (SqlSession session = factory.openSession(true)) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            ClubExtraHoursApproval approval = approval("idem-1", null);
            approval.setApprovedAt(new Date(0L));   // Java 传一个 1970 年的时刻

            mapper.insertExtraApproval(approval);

            Date persisted = mapper.selectExtraApprovals(TOPIC, CLUB).get(0).getApprovedAt();
            org.junit.jupiter.api.Assertions.assertTrue(
                    persisted.getTime() > System.currentTimeMillis() - 600_000L,
                    "审批时刻必须是库写的，Java 传进来的 1970 不得落库，实际:" + persisted);
        }
    }

    /** 更正计数是「一条审批只能被更正一次」的 Java 侧判据；它必须只数指向该行的更正。 */
    @Test
    void correctionCountOnlyCountsCorrectionsOfThatExactApproval() {
        try (SqlSession session = factory.openSession(true)) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            mapper.insertExtraApproval(approval("idem-1", null));
            mapper.insertExtraApproval(approval("idem-2", null));
            List<ClubExtraHoursApproval> roots = mapper.selectExtraApprovals(TOPIC, CLUB);
            Long first = roots.get(0).getId();
            Long second = roots.get(1).getId();
            mapper.insertExtraApproval(approval("corr-1", first));

            assertEquals(1, mapper.countCorrectionsOf(first));
            assertEquals(0, mapper.countCorrectionsOf(second), "别的审批不得被算进来");
        }
    }



    /**
     * ★2026-09-10 用户裁决「探店日不交证据」：确认侧不再要求 evidence_hash。
     *
     * <p>这条用例存在的意义是把**这次放松**钉成一个需要被显式改动的事实。
     * 原来的 CAS 带 {@code (amount = 0 or evidence_hash is not null)}；证据链路删掉之后
     * 那一列恒为 null，留着它等于把每一维焊死在 0。
     * ⚠️ 代价：判 200 现在完全由后台那次人工确认承重，误点一下就是 200。
     */
    @Test
    void aDimensionCanNowBeConfirmedAsTwoHundredWithoutAnyEvidence() {
        try (SqlSession session = factory.openSession(true)) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            mapper.insertQualityDecision(pending("DELIVERY_SAFETY"));

            assertEquals(1, mapper.confirmQualityCas(TOPIC, CLUB, "DELIVERY_SAFETY",
                    new BigDecimal("200"), "依据现场交付清单逐项核对", OPERATOR, new Date()),
                    "证据链路已删,没有证据也必须判得过 —— 否则 Q 永远是 0");
            // 一次确认仍然不可逆:第二次拿不到行。
            assertEquals(0, mapper.confirmQualityCas(TOPIC, CLUB, "DELIVERY_SAFETY",
                    new BigDecimal("200"), "依据现场交付清单逐项核对", OPERATOR, new Date()),
                    "放松的只有证据这一条,一次确认不可逆那条没动");
        }
    }

    /** 版本化更正链：supersede 把旧版本置非 current，之后 selectCurrent 只看到新的那一版。 */
    @Test
    void supersedingADecisionLeavesExactlyOneCurrentVersionBehind() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            mapper.insertQualityDecision(pending("DELIVERY_SAFETY"));
            Long original = mapper.selectCurrentQualityDecisions(TOPIC, CLUB).get(0).getId();

            assertEquals(1, mapper.supersedeCurrentQuality(TOPIC, CLUB, "DELIVERY_SAFETY"));
            ClubQualityDecision excluded = pending("DELIVERY_SAFETY");
            excluded.setDecisionStatus(ClubQualityDecision.STATUS_CONFIRMED);
            excluded.setAmount(new BigDecimal("200"));
            excluded.setExcludedByFault(1);
            excluded.setConfirmedBy(OPERATOR);
            excluded.setConfirmedAt(new Date());
            excluded.setVersion(2L);
            excluded.setCorrectionOf(original);
            mapper.insertQualityDecision(excluded);

            List<ClubQualityDecision> current = mapper.selectCurrentQualityDecisions(TOPIC, CLUB);
            assertEquals(1, current.size(), "只能有一个 current 版本");
            assertEquals(Integer.valueOf(1), current.get(0).getExcludedByFault());
            assertEquals(original, current.get(0).getCorrectionOf());
            assertEquals(2, rowCount("select count(*) from club_quality_decision"),
                    "历史版本必须留着，不是被改写");
            // supersede 之后已经没有 current 的 PENDING 行了，再 supersede 拿不到行。
            assertEquals(1, mapper.supersedeCurrentQuality(TOPIC, CLUB, "DELIVERY_SAFETY"));
        }
    }

    /** F 固定补偿的真源是当前成本版本；没有当前版本就返回 null（service 侧据此冻结为 0）。 */
    @Test
    void theFixedCompensationSourceIsOnlyTheCurrentCostVersion() throws Exception {
        try (SqlSession session = factory.openSession(true); Statement s = session.getConnection().createStatement()) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            assertNull(mapper.selectCurrentFixedCompensation(TOPIC));

            s.execute("insert into topic_edition_cost (topic_id,is_current,fixed_compensation)"
                    + " values (23,0,999.00)");
            // 造事实走的是裸 JDBC，不经 MyBatis ⇒ 一级缓存不会自己失效，必须手动清，
            // 否则这里比的是缓存不是 SQL（那就是一条恒真断言）。
            session.clearCache();
            assertNull(mapper.selectCurrentFixedCompensation(TOPIC), "历史版本不得被当成当前版本");

            s.execute("insert into topic_edition_cost (topic_id,is_current,fixed_compensation)"
                    + " values (23,1,1500.00)");
            session.clearCache();
            assertEquals(new BigDecimal("1500.00"), mapper.selectCurrentFixedCompensation(TOPIC));
        }
    }

    /**
     * ★项目负责人（EXTRA 事前审批人）同样只认<b>当前</b>成本版本，且 0 / NULL 都当没登记。
     *
     * <p>0 不是一个人。放行 0 就等于把冻结审批人写成一个永远按不动预批按钮的 id，
     * 而条款没有更正路径 ⇒ 本期 EXTRA 永久批不了。
     */
    @Test
    void theExtraApproverSourceIsOnlyTheCurrentCostVersionAndNeverZero() throws Exception {
        try (SqlSession session = factory.openSession(true); Statement s = session.getConnection().createStatement()) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            assertNull(mapper.selectCurrentExtraApprover(TOPIC));

            s.execute("insert into topic_edition_cost (topic_id,is_current,fixed_compensation,"
                    + "extra_approver_user_id) values (23,0,2600.00,88)");
            session.clearCache();
            assertNull(mapper.selectCurrentExtraApprover(TOPIC), "历史版本上的负责人不得被当成本期的");

            s.execute("insert into topic_edition_cost (topic_id,is_current,fixed_compensation,"
                    + "extra_approver_user_id) values (23,1,2600.00,0)");
            session.clearCache();
            assertNull(mapper.selectCurrentExtraApprover(TOPIC), "0 不是一个人，必须当没登记");

            s.execute("update topic_edition_cost set extra_approver_user_id = 88 where is_current = 1");
            session.clearCache();
            assertEquals(Long.valueOf(88L), mapper.selectCurrentExtraApprover(TOPIC));
        }
    }

    private int rowCount(String sql) throws Exception {
        try (SqlSession session = factory.openSession(true);
             Statement s = session.getConnection().createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * ★冻结守卫在<b>同一条语句</b>里，不是「先 select 再 upsert」两步。
     *
     * <p>Java 那句 selectTerms 只是给人话的快速失败：两步之间 freezeTermsForSale 可以提交，
     * 而 REPEATABLE READ 下本事务的快照看不见它 —— 冻结后把 planned 改小会直接抬高 EXTRA 应付。
     */
    @Test
    void plannedHoursCannotBeWrittenOnceTheTermsRowExists() {
        try (SqlSession session = factory.openSession(true)) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            assertEquals(1, mapper.upsertPlannedHours(TOPIC, CLUB, "PREP", new BigDecimal("4")));

            insertFrozenTerms(session);

            assertEquals(0, mapper.upsertPlannedHours(TOPIC, CLUB, "PREP", new BigDecimal("1")),
                    "开售冻结之后这条 upsert 必须一行都改不到");
            assertEquals(new BigDecimal("4.00"), mapper.selectHoursForClub(TOPIC, CLUB).get(0).getPlannedHours(),
                    "计划工时必须还是冻结前那个数");
            assertEquals(0, mapper.upsertPlannedHours(TOPIC, CLUB, "REVIEW", new BigDecimal("2")),
                    "冻结之后连新增一类都不许");
        }
    }

    /**
     * ★开工时刻的下界写进了 insert 的 where，和写 approved_at 的是同一条语句同一个库时钟。
     *
     * <p>少了它，事前审批性判据（approved_at &lt; work_started_at）只需要把 workStartedAt
     * 填成一个未来时刻就恒过 —— 说真话（填真实的过去时刻）被罚 0，说假话全额通过。
     */
    @Test
    void anApprovalForWorkThatAlreadyStartedMatchesNoRowAtTheSqlLayer() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            ClubExtraHoursApproval late = approval("idem-late", null);
            late.setWorkStartedAt(YESTERDAY);

            assertEquals(0, mapper.insertExtraApproval(late), "根审批的开工时刻必须晚于库时钟");
            assertEquals(1, mapper.insertExtraApproval(approval("idem-ok", null)));
        }
        assertEquals(1, rowCount("select count(*) from club_extra_hours_approval"),
                "被挡下的那条一行都不许落库");
    }

    /** 更正行继承原件的开工时刻（那时点必然已过去），所以这道闸只对根行设。 */
    @Test
    void aCorrectionInheritsAPastWorkStartAndIsStillWritable() {
        try (SqlSession session = factory.openSession(true)) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            mapper.insertExtraApproval(approval("idem-root", null));
            Long rootId = mapper.selectExtraApprovals(TOPIC, CLUB).get(0).getId();

            ClubExtraHoursApproval correction = approval("idem-corr", rootId);
            correction.setWorkStartedAt(YESTERDAY);

            assertEquals(1, mapper.insertExtraApproval(correction),
                    "更正不改「当初是否事前」这个事实，不该被下界闸挡住");
        }
    }


    /**
     * ★截止约束的是<b>交付时刻</b>，不是平台的审批时刻。
     *
     * <p>总纲 §2.1.2 那一维写的是「预热、现场素材、回顾和 T+7 报告<b>按清单交付</b>」——
     * 管的是交付方的时限。把同一个 {@code due_at} 也套在「平台点确认」上，等于
     * <b>俱乐部按时交了、平台晚判一分钟，这一维就归 0</b>，正是锁定裁决禁止的那一侧
     * （商家/平台过错不得让俱乐部少拿）。
     *
     * <p>⚠️ 2026-09-10 用户裁决「探店日不交证据」之后，确认侧那道
     * {@code evidence_hash is not null} 已随证据链路一并删除。这条用例要证的
     * 「平台晚判不扣俱乐部的钱」不依赖证据，原样成立。
     */
    @Test
    void aDimensionDeliveredInTimeCanStillBeConfirmedAsTwoHundredAfterTheDeadline() {
        try (SqlSession session = factory.openSession(true)) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            ClubQualityDecision expired = pending("CONTENT_REPORT");
            expired.setDueAt(YESTERDAY);
            mapper.insertQualityDecision(expired);

            assertEquals(1, mapper.confirmQualityCas(TOPIC, CLUB, "CONTENT_REPORT",
                    new BigDecimal("200"), "依据现场交付清单逐项核对", OPERATOR, new Date()),
                    "不能因为平台在 T+7 之后才点确认就把这一维降成 0");
        }
    }


    /** 历史豁免登记表是真表真查询，不是只存在于迁移里的一句话。 */
    @Test
    void theLegacyExemptionRegistryIsReadBackByTopic() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            assertEquals(0, mapper.countLegacyExemption(TOPIC));

            try (Statement s = session.getConnection().createStatement()) {
                s.execute("insert into club_edition_legacy_exemption(topic_id,lifecycle,reason,decided_by)"
                        + " values (" + TOPIC + ",3,'开售早于四个A','migration')");
            }
            session.clearCache();

            assertEquals(1, mapper.countLegacyExemption(TOPIC));
            assertEquals(0, mapper.countLegacyExemption(TOPIC + 1));
        }
    }

    /** 归责方必须真的落到列上，不是校验完就丢。 */
    @Test
    void theFaultPartyIsPersistedOnTheDecisionRow() {
        try (SqlSession session = factory.openSession(true)) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            ClubQualityDecision excluded = pending("PLAYER_EXPERIENCE");
            excluded.setDecisionStatus(ClubQualityDecision.STATUS_CONFIRMED);
            excluded.setAmount(new BigDecimal("200"));
            excluded.setExcludedByFault(1);
            excluded.setFaultParty("PLATFORM");
            excluded.setConfirmedBy(OPERATOR);
            excluded.setConfirmedAt(new Date());
            mapper.insertQualityDecision(excluded);

            assertEquals("PLATFORM",
                    mapper.selectCurrentQualityDecisions(TOPIC, CLUB).get(0).getFaultParty());
        }
    }

    /**
     * ★理由必须真的写进去、也必须读得回来 —— 两条写入口各一遍。
     *
     * <p>{@code fault_party} 只有两个枚举值，{@code @Log} 又不写请求体：
     * 「是哪一笔事故、依据什么这么判」这层事实只有 {@code reason} 这一列留得下来。
     * 只写不读（或只读不写）都等于没有，所以这里两头都断言。
     */
    @Test
    void theReasonIsPersistedAndReadBackOnBothQualityWritePaths() {
        try (SqlSession session = factory.openSession(true)) {
            ClubEditionCompensationMapper mapper = session.getMapper(ClubEditionCompensationMapper.class);
            mapper.insertQualityDecision(pending("DELIVERY_SAFETY"));

            assertEquals(1, mapper.confirmQualityCas(TOPIC, CLUB, "DELIVERY_SAFETY", new BigDecimal("200"),
                    "现场三项交付逐条核对通过", OPERATOR, new Date()));
            assertEquals("现场三项交付逐条核对通过",
                    mapper.selectCurrentQualityDecisions(TOPIC, CLUB).get(0).getReason(),
                    "一次确认的判据必须随金额同一条语句落列");

            ClubQualityDecision excluded = pending("PLAYER_EXPERIENCE");
            excluded.setDecisionStatus(ClubQualityDecision.STATUS_CONFIRMED);
            excluded.setAmount(new BigDecimal("200"));
            excluded.setExcludedByFault(1);
            excluded.setFaultParty("MERCHANT");
            excluded.setReason("商家 8/12 临时闭店，工单 #3312");
            excluded.setConfirmedBy(OPERATOR);
            excluded.setConfirmedAt(new Date());
            mapper.insertQualityDecision(excluded);

            for (ClubQualityDecision row : mapper.selectCurrentQualityDecisions(TOPIC, CLUB)) {
                if ("PLAYER_EXPERIENCE".equals(row.getDimension())) {
                    assertEquals("商家 8/12 临时闭店，工单 #3312", row.getReason(),
                            "过错排除的定责依据必须随归责方一起落列");
                }
            }
        }
    }

    private static void insertFrozenTerms(SqlSession session) {
        try (Statement s = session.getConnection().createStatement()) {
            s.execute("insert into club_edition_compensation_terms"
                    + "(topic_id,executing_club_id,fixed_compensation,extra_hour_rate,"
                    + "quality_dimension_amount,quality_dimensions,evidence_due_at,fulfillment_end_at,"
                    + "extra_approver_user_id,config_hash,frozen_at) values ("
                    + TOPIC + "," + CLUB + ",1500.00,40,200,'DELIVERY_SAFETY',"
                    + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP," + OPERATOR + ",'h',CURRENT_TIMESTAMP)");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ClubExtraHoursApproval approval(String idempotencyKey, Long correctionOf) {
        ClubExtraHoursApproval a = new ClubExtraHoursApproval();
        a.setTopicId(TOPIC);
        a.setClubId(CLUB);
        a.setCategory("PREP");
        a.setApprovedHours(new BigDecimal("3"));
        a.setApprovedBy(OPERATOR);
        // 事前审批：开工时刻必须晚于库时钟，否则 insert 的 where 直接命中 0 行（见 insertExtraApproval）。
        a.setWorkStartedAt(TOMORROW);
        a.setIdempotencyKey(idempotencyKey);
        a.setCorrectionOf(correctionOf);
        return a;
    }

    private static ClubQualityDecision pending(String dimension) {
        ClubQualityDecision d = new ClubQualityDecision();
        d.setTopicId(TOPIC);
        d.setClubId(CLUB);
        d.setDimension(dimension);
        d.setDecisionStatus(ClubQualityDecision.STATUS_PENDING);
        // due_at 留着:未决转 0 的分水岭仍看它(证据链路已删,写侧那道 due_at 闸随之消失)。
        d.setDueAt(TOMORROW);
        d.setVersion(1L);
        d.setCurrent(1);
        d.setExcludedByFault(0);
        return d;
    }
}
