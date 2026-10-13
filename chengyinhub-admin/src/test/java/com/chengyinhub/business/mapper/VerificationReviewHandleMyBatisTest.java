package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.VerificationReview;
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
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批 5 · T3 下半:处置端点的 SQL 真跑,不是断言字符串。
 *
 * <p>N13 第三条要的正是这条:「入队后调新的处置端点置 status=1 ⇒ 队列查询里消失」。
 * 只写 mapper 不验这一条,处置端点可能写了却没接上查询 —— 运营点了「已处置」,
 * 工单照旧躺在待复核里,零报错。
 *
 * <p>两条 WHERE 守卫各自单独钉:
 * ① {@code topic_id} —— 换成别的主题 id 必须 0 行(端点判过范围的那一行才写得进去);
 * ② {@code status = 0} —— 重复处置必须 0 行(不覆写别人刚下的结论)。
 */
class VerificationReviewHandleMyBatisTest {

    private static final long TOPIC = 100L;
    private static final long OTHER_TOPIC = 200L;

    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("VerificationReview", VerificationReview.class);
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:verification_review_handle;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/business/VerificationReviewMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true);
             Statement st = session.getConnection().createStatement()) {
            st.execute("drop table if exists verification_review");
            // 列形状照抄 migration_commerce_order_fields_and_verification_review_20260805.sql
            st.execute("create table verification_review ("
                    + "id bigint primary key auto_increment, verifier_member_id bigint not null, "
                    + "topic_id bigint not null, risk_type varchar(32) not null, "
                    + "verification_rate decimal(10,4) not null, paid_count int not null, "
                    + "verified_count int not null, one_minute_count int not null, "
                    + "status tinyint not null default 0, idempotency_key varchar(128) not null, "
                    + "create_time timestamp not null default current_timestamp, update_time timestamp null)");
            st.execute("insert into verification_review(id, verifier_member_id, topic_id, risk_type, "
                    + "verification_rate, paid_count, verified_count, one_minute_count, status, idempotency_key) "
                    + "values (1, 8801, 100, 'BURST', 0.9583, 120, 115, 9, 0, 'k1')");
            st.execute("insert into verification_review(id, verifier_member_id, topic_id, risk_type, "
                    + "verification_rate, paid_count, verified_count, one_minute_count, status, idempotency_key) "
                    + "values (2, 8802, 200, 'HIGH_RATE', 0.9600, 100, 96, 1, 0, 'k2')");
        }
    }

    private VerificationReviewMapper mapper(SqlSession session) {
        return session.getMapper(VerificationReviewMapper.class);
    }

    private List<Long> pendingIds() {
        try (SqlSession session = factory.openSession(true)) {
            VerificationReview query = new VerificationReview();
            query.setStatus(0);
            return mapper(session).selectQueue(query).stream()
                    .map(VerificationReview::getId).sorted().collect(Collectors.toList());
        }
    }

    /** N13 第三条:处置后从待复核队列里消失。 */
    @Test
    @Timeout(10)
    void handledRowDisappearsFromPendingQueue() {
        assertEquals(java.util.Arrays.asList(1L, 2L), pendingIds());
        try (SqlSession session = factory.openSession(true)) {
            assertEquals(1, mapper(session).updateStatus(1L, TOPIC, 1));
        }
        assertEquals(java.util.Collections.singletonList(2L), pendingIds(),
                "处置过的工单必须从 status=0 的队列里消失,否则运营点了也白点");
        try (SqlSession session = factory.openSession(true)) {
            VerificationReview handled = mapper(session).selectById(1L);
            assertNotNull(handled);
            assertEquals(1, handled.getStatus().intValue());
            assertNotNull(handled.getUpdateTime(), "处置必须留下时间痕迹");
        }
    }

    /** 守卫 ①:主题 id 对不上必须 0 行 —— 消掉端点「读-判范围-写」之间的 TOCTOU。 */
    @Test
    @Timeout(10)
    void wrongTopicScopeWritesNothing() {
        try (SqlSession session = factory.openSession(true)) {
            assertEquals(0, mapper(session).updateStatus(1L, OTHER_TOPIC, 2),
                    "topic_id 不在 WHERE 里 = 任何拿到 id 的人都能处置别的主题的工单");
            assertEquals(0, mapper(session).selectById(1L).getStatus().intValue());
        }
    }

    /** 守卫 ②:重复处置必须 0 行,不得把别人刚下的结论覆写成另一个结论。 */
    @Test
    @Timeout(10)
    void repeatedHandleIsRejectedByCas() {
        try (SqlSession session = factory.openSession(true)) {
            assertEquals(1, mapper(session).updateStatus(2L, OTHER_TOPIC, 2));
            assertEquals(0, mapper(session).updateStatus(2L, OTHER_TOPIC, 1),
                    "status=0 不在 WHERE 里 = 后到的人能把「确认异常」改回「确认正常」");
            assertEquals(2, mapper(session).selectById(2L).getStatus().intValue());
        }
    }

    /** 队列按主题过滤(审计 3 · B7):运营只该看到自己范围内的工单。 */
    @Test
    @Timeout(10)
    void queueFiltersByTopic() {
        try (SqlSession session = factory.openSession(true)) {
            VerificationReview query = new VerificationReview();
            query.setTopicId(OTHER_TOPIC);
            List<VerificationReview> rows = mapper(session).selectQueue(query);
            assertEquals(1, rows.size());
            assertEquals(2L, rows.get(0).getId().longValue());
        }
    }

    @Test
    @Timeout(10)
    void missingIdReturnsNull() {
        try (SqlSession session = factory.openSession(true)) {
            assertNull(mapper(session).selectById(999L));
            assertTrue(mapper(session).updateStatus(999L, TOPIC, 1) == 0);
        }
    }

    /**
     * T3 的 product_type 判据真的出现在 selectVerifierStats 生成的 SQL 里,
     * 而写在 SELECT 列表中间的那段中文注释**没有**漏进 SQL。
     *
     * <p>为什么单独钉这条:这个查询要 join cms_registration / cms_activity /
     * cms_registration_scan / cms_topic 四张表,在 H2 上真跑成本高,于是很容易一行都不验就发出去 ——
     * 而它一旦漏了那一列,{@code inspectAndQueue} 拿到的 productType 恒 NULL,
     * 探店日就退回「每场必炸」,**且没有任何一层会报错**。读回 MyBatis 编译后的 SQL
     * 比断言 XML 字符串强:它证明的是「MyBatis 真的这么发」,不是「文件里这么写着」。
     */
    @Test
    @Timeout(10)
    void verifierStatsSqlCarriesProductTypeAndNoLeakedComment() {
        try (SqlSession session = factory.openSession(true)) {
            String sql = session.getConfiguration()
                    .getMappedStatement("com.chengyinhub.business.mapper.VerificationReviewMapper.selectVerifierStats")
                    .getBoundSql(new java.util.HashMap<String, Object>())
                    .getSql();
            assertTrue(sql.contains("as productType"),
                    "缺 productType ⇒ 探店日退回每场必炸且零告警。实际 SQL:" + sql);
            assertTrue(sql.contains("from cms_topic where id = current_topic.topic_id"),
                    "判据必须取主题级 product_type(活动壳已由 current_topic 归一)");
            assertTrue(!sql.contains("<!--") && !sql.contains("批 5"),
                    "XML 注释不得漏进 SQL。实际 SQL:" + sql);
            assertTrue(!sql.toLowerCase().contains("coalesce(product_type"),
                    "product_type 为 NULL 是「存量未回填」,不许在 SQL 里兜成 0");
        }
    }
}
