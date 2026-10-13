package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.mapper.ClubMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 MyBatis/MySQL 证明解散闸会命中资金半态、履约和跨方向合作，而非只匹配 SQL 字符串。 */
class ClubDissolutionGateMyBatisTest {

    private static final long CLUB_ID = 73L;

    private SqlSessionFactory factory() throws Exception {
        Configuration cfg = new Configuration();
        cfg.setLocalCacheScope(LocalCacheScope.STATEMENT);
        cfg.getTypeAliasRegistry().registerAlias("Club", Club.class);
        PooledDataSource ds = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                MybatisItDatabase.url("dissolution_gate"),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        cfg.setEnvironment(new Environment("it", new JdbcTransactionFactory(), ds));
        try (InputStream in = Resources.getResourceAsStream("mapper/business/ClubMapper.xml")) {
            new XMLMapperBuilder(in, cfg, "mapper/business/ClubMapper.xml", cfg.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(cfg);
    }

    @Test
    void allDissolutionFactsUseRealStatusSemantics() throws Exception {
        SqlSessionFactory factory = factory();
        try (SqlSession probe = factory.openSession()) {
            assertTrue(probe.getConnection().isValid(2), "真实 MySQL fixture 必须可达");
        }
        try (SqlSession session = factory.openSession(true)) {
            Statement st = session.getConnection().createStatement();
            recreateTables(st);
            ClubMapper mapper = session.getMapper(ClubMapper.class);

            st.execute("INSERT INTO cms_topic(id,club_id,del_flag,status,is_settlement) VALUES(10,73,0,1,0)");
            assertEquals(1, mapper.countUnsettledRevenueByClub(CLUB_ID),
                    "在营且 is_settlement=0 的本团主题必须命中未结算闸");
            st.execute("UPDATE cms_topic SET del_flag=2 WHERE id=10");
            assertEquals(0, mapper.countUnsettledRevenueByClub(CLUB_ID),
                    "未结算主题闸只统计 del_flag=0 的在营主题");
            st.execute("UPDATE cms_topic SET del_flag=0 WHERE id=10");
            st.execute("UPDATE cms_topic SET is_settlement=1 WHERE id=10");
            assertEquals(0, mapper.countUnsettledRevenueByClub(CLUB_ID), "已结算主题不再命中收益闸");

            st.execute("INSERT INTO cms_topic(id,club_id,del_flag,is_settlement) VALUES(12,74,2,1)");
            st.execute("INSERT INTO coop_order(id,topic_id,to_type,to_id,status,del_flag) "
                    + "VALUES(20,12,'club',73,1,2)");
            st.execute("INSERT INTO coop_settlement(id,topic_id,coop_order_id,status,payee_type,line_type,amount,del_flag) "
                    + "VALUES(21,12,20,0,'club','LEAD_FEE',35.00,0)");
            assertEquals("incoming", mapper.selectUnpaidSettlementsByClub(CLUB_ID).get(0).getDirection(),
                    "合作单已归档也不能吞掉已经生成的未打款应付");
            assertEquals("outgoing", mapper.selectUnpaidSettlementsByClub(74L).get(0).getDirection(),
                    "同一 LEAD_FEE 对承接团是待收，对主题所属团必须是待付");
            st.execute("UPDATE coop_settlement SET status=1 WHERE id=21");
            assertEquals(0, mapper.selectUnpaidSettlementsByClub(CLUB_ID).size(), "已打款分项不再阻塞");

            st.execute("INSERT INTO coop_settlement(id,topic_id,coop_order_id,status,payee_type,line_type,amount,del_flag) VALUES"
                    + "(28,10,NULL,0,'initiator','INITIATOR_MARGIN',18.00,0),"
                    + "(29,10,NULL,0,'merchant','MERCHANT_VERIFY',12.00,0),"
                    + "(30,10,NULL,0,NULL,'PLATFORM',NULL,0)");
            st.execute("UPDATE cms_topic SET del_flag=2 WHERE id=10");
            java.util.List<com.chengyinhub.business.domain.ClubPendingSettlement> pending =
                    mapper.selectUnpaidSettlementsByClub(CLUB_ID);
            assertEquals(3, pending.size(), "本团主题的待收、待付与 NULL 金额都必须命中，且父主题软删不能吞账");
            assertEquals("incoming", pending.get(0).getDirection());
            assertEquals("outgoing", pending.get(1).getDirection());
            assertEquals("unknown", pending.get(2).getDirection());
            st.execute("UPDATE coop_settlement SET status=2 WHERE id=28");
            st.execute("UPDATE coop_settlement SET del_flag=2 WHERE id=29");
            st.execute("UPDATE coop_settlement SET status=1 WHERE id=30");
            assertEquals(0, mapper.selectUnpaidSettlementsByClub(CLUB_ID).size(),
                    "结算行自身作废、软删或已打款后不再阻塞");
            st.execute("UPDATE cms_topic SET del_flag=0 WHERE id=10");

            st.execute("INSERT INTO cms_topic(id,club_id,del_flag,is_settlement) VALUES(11,73,0,0)");
            st.execute("INSERT INTO coop_order(id,topic_id,to_type,to_id,status,del_flag) "
                    + "VALUES(22,11,'merchant',900,0,0)");
            assertEquals(1, mapper.countActiveCooperationsByClub(CLUB_ID),
                    "本团未结算主题发给商家的生效合作必须命中");
            st.execute("UPDATE cms_topic SET is_settlement=1 WHERE id=11");
            assertEquals(0, mapper.countActiveCooperationsByClub(CLUB_ID),
                    "父主题已结算后，即使 coop_order 仍为 status=0 也应放行");
            st.execute("UPDATE coop_order SET status=1 WHERE id=22");
            st.execute("INSERT INTO coop_order(id,topic_id,to_type,to_id,status,del_flag) "
                    + "VALUES(23,NULL,'club',73,0,0)");
            assertEquals(1, mapper.countActiveCooperationsByClub(CLUB_ID), "本团作为承接方同样必须命中");
            assertEquals(1, mapper.countActiveCooperationsWithMissingTopicByClub(CLUB_ID),
                    "父主题 NULL 必须 fail-closed 并可被服务记录日志");
            st.execute("UPDATE coop_order SET status=1 WHERE id=23");
            assertEquals(0, mapper.countActiveCooperationsByClub(CLUB_ID), "已作废合作不再阻塞");

            st.execute("INSERT INTO coop_invite(id,to_type,to_id,topic_id,status,deposit_status,del_flag) "
                    + "VALUES(24,'club',73,NULL,3,1,2),(25,'club',73,NULL,3,2,0),(26,'club',73,NULL,0,5,0)");
            assertEquals(java.util.Arrays.asList(24L, 25L, 26L),
                    mapper.selectPendingDepositsByClub(CLUB_ID).stream()
                            .map(com.chengyinhub.business.domain.ClubPendingDeposit::getId)
                            .collect(java.util.stream.Collectors.toList()),
                    "支付中/冻结待退/应缴未缴三种保证金都必须逐笔返回，且不能被软删吞掉");
            assertEquals(false, mapper.selectPendingDepositsByClub(CLUB_ID).get(0).isRetryable(),
                    "软删邀约即使处于取消态也不能显示必失败的退款重试入口");
            assertEquals(true, mapper.selectPendingDepositsByClub(CLUB_ID).get(1).isRetryable(),
                    "未软删、已取消且保证金冻结的邀约应复用真实退款重试入口");
            st.execute("UPDATE coop_invite SET deposit_status=3 WHERE id=24");
            st.execute("UPDATE coop_invite SET deposit_status=4 WHERE id=25");
            st.execute("UPDATE coop_invite SET deposit_status=0 WHERE id=26");
            assertEquals(1, mapper.selectPendingDepositsByClub(CLUB_ID).size(), "历史4无渠道确认不能解散绕过");
            st.execute("UPDATE coop_invite SET deposit_refund_state='SUCCESS',deposit_refund_confirmed_at=NOW(),deposit_out_trade_no='DEP25',deposit_refund_no='RDEP25' WHERE id=25");
            assertEquals(0, mapper.selectPendingDepositsByClub(CLUB_ID).size(),
                    "无需/已扣/可信渠道已退是显式安全终态");
            st.execute("INSERT INTO coop_invite(id,to_type,to_id,topic_id,status,deposit_status,del_flag) "
                    + "VALUES(27,'merchant',900,11,3,2,2)");
            assertEquals(java.util.Collections.singletonList(27L),
                    mapper.selectPendingDepositsByClub(CLUB_ID).stream()
                            .map(com.chengyinhub.business.domain.ClubPendingDeposit::getId)
                            .collect(java.util.stream.Collectors.toList()),
                    "本团主题上的冻结保证金同样不能因邀约或主题软删而消失");
            assertEquals(false, mapper.selectPendingDepositsByClub(CLUB_ID).get(0).isRetryable(),
                    "软删的本团主题邀约必须转人工处理，不能暴露必失败的普通退款接口");

            st.execute("INSERT INTO cms_activity(id,topic_id,club_id,del_flag,status,publish_status,cancel_time,end_date,is_settlement) "
                    + "VALUES(30,NULL,73,0,1,1,NULL,DATE_ADD(NOW(), INTERVAL 1 DAY),0)");
            assertEquals(1, mapper.countOngoingSessionsByClub(CLUB_ID), "未来已发布场次必须命中");
            st.execute("UPDATE cms_activity SET cancel_time=NOW() WHERE id=30");
            assertEquals(0, mapper.countOngoingSessionsByClub(CLUB_ID), "已取消场次不再阻塞");
            st.execute("INSERT INTO cms_activity(id,topic_id,club_id,del_flag,status,publish_status,cancel_time,end_date,is_settlement) "
                    + "VALUES(32,11,NULL,0,1,1,NULL,DATE_ADD(NOW(), INTERVAL 1 DAY),0)");
            st.execute("UPDATE cms_topic SET del_flag=2 WHERE id=11");
            assertEquals(1, mapper.countOngoingSessionsByClub(CLUB_ID),
                    "父主题软删不能抹掉仍在进行的子场次");
            st.execute("UPDATE cms_activity SET cancel_time=NOW() WHERE id=32");

            st.execute("DELETE FROM cms_registration");
            st.execute("INSERT INTO cms_registration(id,owner_type,owner_id,payment_status,registration_status,"
                    + "verification_status,payable_amount,purchase_kind) VALUES(2,1,10,0,1,0,0,1)");
            st.execute("UPDATE cms_topic SET del_flag=2 WHERE id=10");
            assertEquals(1, mapper.countIncompleteRegistrationsByClub(CLUB_ID),
                    "主题软删不能抹掉未完成报名");
            st.execute("UPDATE cms_topic SET del_flag=0 WHERE id=10");
            assertEquals(1, mapper.countIncompleteRegistrationsByClub(CLUB_ID), "待支付报名必须命中");
            st.execute("UPDATE cms_registration SET payment_status=2 WHERE id=2");
            assertEquals(1, mapper.countIncompleteRegistrationsByClub(CLUB_ID),
                    "支付成功但报名状态未翻转的半态必须命中");
            st.execute("UPDATE cms_registration SET registration_status=2,verification_status=1,purchase_kind=1 WHERE id=2");
            assertEquals(0, mapper.countIncompleteRegistrationsByClub(CLUB_ID), "普通票已核销是安全终态");
            st.execute("UPDATE cms_registration SET purchase_kind=3 WHERE id=2");
            st.execute("INSERT INTO registration_entitlement(id,registration_id,status) VALUES(31,2,0)");
            assertEquals(1, mapper.countIncompleteRegistrationsByClub(CLUB_ID),
                    "探索票只核销一章、仍有 ACTIVE 权益时必须继续阻塞");
            st.execute("UPDATE registration_entitlement SET status=1 WHERE id=31");
            assertEquals(0, mapper.countIncompleteRegistrationsByClub(CLUB_ID), "探索票全部权益消费后才安全");
            st.execute("UPDATE cms_registration SET purchase_kind=NULL WHERE id=2");
            assertEquals(0, mapper.countIncompleteRegistrationsByClub(CLUB_ID),
                    "存量 purchase_kind=NULL 且已核销、无未消费权益时应按已完成放行");
            st.execute("UPDATE cms_registration SET registration_status=NULL,payment_status=NULL WHERE id=2");
            assertEquals(1, mapper.countIncompleteRegistrationsByClub(CLUB_ID), "NULL/未知半态必须 fail-closed");
            st.execute("UPDATE cms_registration SET registration_status=3,payment_status=4 WHERE id=2");
            assertEquals(0, mapper.countIncompleteRegistrationsByClub(CLUB_ID), "已退款报名不再阻塞");
            st.execute("INSERT INTO cms_activity(id,topic_id,club_id,del_flag,status,publish_status,cancel_time,end_date,is_settlement) "
                    + "VALUES(31,NULL,73,2,1,1,NULL,NOW(),1)");
            st.execute("INSERT INTO cms_registration(id,owner_type,owner_id,payment_status,registration_status,"
                    + "verification_status,payable_amount,purchase_kind) VALUES(3,2,31,0,1,0,0,1)");
            assertEquals(1, mapper.countIncompleteRegistrationsByClub(CLUB_ID),
                    "活动软删不能抹掉未完成报名");
        }
    }

    private void recreateTables(Statement st) throws Exception {
        st.execute("DROP TABLE IF EXISTS registration_entitlement");
        st.execute("DROP TABLE IF EXISTS coop_settlement");
        st.execute("DROP TABLE IF EXISTS coop_order");
        st.execute("DROP TABLE IF EXISTS coop_invite");
        st.execute("DROP TABLE IF EXISTS cms_registration");
        st.execute("DROP TABLE IF EXISTS cms_activity");
        st.execute("DROP TABLE IF EXISTS cms_topic");
        st.execute("CREATE TABLE cms_topic(id BIGINT PRIMARY KEY,club_id BIGINT,del_flag INT,status INT DEFAULT 1,is_settlement INT)");
        st.execute("CREATE TABLE cms_activity(id BIGINT PRIMARY KEY,topic_id BIGINT,club_id BIGINT,del_flag INT,"
                + "status BIGINT,publish_status BIGINT,cancel_time DATETIME,end_date DATETIME,is_settlement INT)");
        st.execute("CREATE TABLE cms_registration(id BIGINT PRIMARY KEY,owner_type INT,owner_id BIGINT,"
                + "payment_status INT,registration_status INT,verification_status INT,payable_amount DECIMAL(10,2),"
                + "purchase_kind INT)");
        st.execute("CREATE TABLE coop_order(id BIGINT PRIMARY KEY,topic_id BIGINT,to_type VARCHAR(16),to_id BIGINT,"
                + "status INT,del_flag INT)");
        st.execute("CREATE TABLE coop_invite(id BIGINT PRIMARY KEY,to_type VARCHAR(16),to_id BIGINT,topic_id BIGINT,"
                + "status INT,invite_type INT DEFAULT 0,deposit_status INT,deposit_amount DECIMAL(10,2),del_flag INT,"
                + "deposit_refund_state VARCHAR(24),deposit_refund_confirmed_at DATETIME,deposit_out_trade_no VARCHAR(64),deposit_refund_no VARCHAR(64))");
        st.execute("CREATE TABLE coop_settlement(id BIGINT PRIMARY KEY,settlement_route VARCHAR(32),"
                + "source_contract_type VARCHAR(48),source_contract_id BIGINT,commercial_context_type VARCHAR(32),"
                + "commercial_context_id BIGINT,beneficiary_role VARCHAR(32),beneficiary_id BIGINT,service_unit VARCHAR(48),"
                + "topic_id BIGINT,coop_order_id BIGINT,status INT,"
                + "payee_type VARCHAR(16),line_type VARCHAR(32),amount DECIMAL(10,2),del_flag INT)");
        st.execute("CREATE TABLE registration_entitlement(id BIGINT PRIMARY KEY,registration_id BIGINT,status INT)");
    }
}
