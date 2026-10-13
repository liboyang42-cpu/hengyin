package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsRegistration;
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
import java.sql.Statement;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 1-43:核销 CAS 与退款 CAS 必须互斥 —— 两边的前置条件都是事务外预读,只有 UPDATE 的 where(当前读)能挡住并发。
 * 真 XML + H2,按「先赢的一方落库后,另一方 CAS 必须返 0」逐向验证。
 */
class VerifyRefundCasMutualExclusionMyBatisTest {

    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("CmsRegistration", CmsRegistration.class);
        PooledDataSource ds = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:verify_refund_cas_" + System.nanoTime()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        cfg.setEnvironment(new Environment("test", new JdbcTransactionFactory(), ds));
        String resource = "mapper/business/CmsRegistrationMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(cfg);
        try (SqlSession s = factory.openSession(true); Statement st = s.getConnection().createStatement()) {
            // 本类只验证原有 payment/refund CAS；人工工单 JSON 语义由 MySQL 8 IT 覆盖。
            st.execute("create alias if not exists JSON_CONTAINS as "
                    + "'boolean jsonContains(String json, String target) { return false; }'");
            st.execute("create table cms_registration (id bigint primary key, payment_status int, registration_status int,"
                    + " verification_status int, purchase_kind int, verifier_id bigint, verification_time timestamp,"
                    + " update_by varchar(64), update_time timestamp, owner_type bigint, owner_id bigint,"
                    + " parent_order_no varchar(64), member_id bigint)");
            st.execute("create table activity_cancel_manual_refund_case (activity_id bigint, topic_id bigint,"
                    + " parent_order_no varchar(64), payer_member_id bigint, status varchar(48),"
                    + " verified_registration_ids_json json, unverified_registration_ids_json json)");
            // 1 = 已付未核销普通票;2 = 已付已核销普通票;3 = 已付已核销 ③ 探索票
            st.execute("insert into cms_registration"
                    + "(id,payment_status,registration_status,verification_status,purchase_kind,"
                    + "verifier_id,verification_time,update_by,update_time)"
                    + " values (1,2,2,0,1,null,null,null,null),"
                    + "(2,2,2,1,1,9,now(),null,null),(3,2,2,1,3,9,now(),null,null)");
        }
    }

    @Test
    void refundClaimedFirst_thenVerifyCasLoses() {
        try (SqlSession s = factory.openSession(true)) {
            CmsRegistrationMapper m = s.getMapper(CmsRegistrationMapper.class);
            assertEquals(1, m.claimRegistrationRefunded(1L, "t", new Date()));
            assertEquals(0, m.verifyIfNotVerified(1L, 9L), "已退款的单不能再被核销");
        }
    }

    @Test
    void verifiedFirst_thenRefundClaimLoses() {
        try (SqlSession s = factory.openSession(true)) {
            CmsRegistrationMapper m = s.getMapper(CmsRegistrationMapper.class);
            assertEquals(1, m.verifyIfNotVerified(1L, 9L));
            assertEquals(0, m.claimRegistrationRefunded(1L, "t", new Date()), "已核销的单不能再领退款权");
            assertEquals(0, m.claimRegistrationRefunded(2L, "t", new Date()), "已核销的单不能再领退款权");
        }
    }

    @Test
    void explorePassVerified_stillRefundableByOperator() {
        try (SqlSession s = factory.openSession(true)) {
            assertEquals(1, s.getMapper(CmsRegistrationMapper.class).claimRegistrationRefunded(3L, "t", new Date()),
                    "③ 首章即置核销位,运营人工退必须仍能领取");
        }
    }

    @Test
    void gatewayRefundNotice_recordsEvenIfVerified() {
        try (SqlSession s = factory.openSession(true)) {
            assertEquals(1, s.getMapper(CmsRegistrationMapper.class).claimRegistrationRefundedByGateway(2L, "system", new Date()),
                    "微信已退款,本地必须如实落账");
        }
    }
}
