package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.sql.Statement;
import java.util.Date;
import java.util.List;
import java.util.stream.LongStream;
import static org.junit.jupiter.api.Assertions.*;

/** Execute the real mapper queries, proving sample predicates agree with counts and stop at twenty IDs. */
class MonitorAlarmQueryContractTest {
    @Test void auditSamplesKeepStatusCutoffAndDeadlineFactsAndHaveAStableBound() throws Exception {
        try (SqlSession session = fixture("AuditTask").openSession(true); Statement sql = session.getConnection().createStatement()) {
            sql.execute("create table audit_task(id bigint primary key,status int,submitted_at timestamp,deadline_at timestamp)");
            for (int i = 1; i <= 28; i++) sql.execute("insert into audit_task values(" + i + ",0,'2000-01-01','2000-01-01')");
            sql.execute("insert into audit_task values(30,1,'2000-01-01','2000-01-01'),(31,0,'2040-01-01',null),(32,0,'2040-01-01','2040-01-01')");
            AuditTaskMapper mapper = session.getMapper(AuditTaskMapper.class);
            Date boundary = new Date(1700000000000L);
            assertEquals(28, mapper.countPendingBacklog(boundary)); assertEquals(28, mapper.countPendingOverdue(boundary));
            List<Long> expected = LongStream.rangeClosed(1, 20).boxed().toList();
            assertEquals(expected, mapper.samplePendingBacklogIds(boundary));
            assertEquals(expected, mapper.samplePendingOverdueIds(boundary));
        }
    }

    @Test void withdrawalSamplesUseTheOriginalDeletedStatusAndNullSubmitTimeFallback() throws Exception {
        try (SqlSession session = fixture("MemberCashWithdraw").openSession(true); Statement sql = session.getConnection().createStatement()) {
            sql.execute("create table member_cash_withdraw(id bigint primary key,del_flag int,status int,submit_time timestamp,create_time timestamp,transfer_batch_id varchar(50))");
            for (int i = 1; i <= 27; i++) sql.execute("insert into member_cash_withdraw values(" + i + ",0,0,null,'2000-01-01',null)");
            sql.execute("insert into member_cash_withdraw values(30,0,3,null,'2000-01-01',null),(31,0,4,null,'2000-01-01',null),"
                    + "(32,0,1,null,'2000-01-01',null),(33,0,1,null,'2000-01-01',''),(34,1,0,null,'2000-01-01',null),"
                    + "(35,1,3,null,'2000-01-01',null),(36,1,1,null,'2000-01-01',null),(37,0,0,null,'2040-01-01',null),"
                    + "(38,0,1,null,'2000-01-01','actual-batch')");
            MemberCashWithdrawMapper mapper = session.getMapper(MemberCashWithdrawMapper.class);
            Date boundary = new Date(1700000000000L);
            assertEquals(27, mapper.countStuckProcessing(boundary));
            assertEquals(LongStream.rangeClosed(1, 20).boxed().toList(), mapper.sampleStuckProcessingIds(boundary));
            assertEquals(2, mapper.countManualPending()); assertEquals(List.of(30L,31L), mapper.sampleManualPendingIds());
            assertEquals(2, mapper.countSuccessWithoutBatch()); assertEquals(List.of(32L,33L), mapper.sampleSuccessWithoutBatchIds());
        }
    }

    @Test void settlementSampleIsBoundedButTheMismatchCountAndLegacyQueryKeepAllActualBatches() throws Exception {
        try (SqlSession session = fixture("SettlementBatch").openSession(true); Statement sql = session.getConnection().createStatement()) {
            sql.execute("create table settlement_batch(id bigint primary key,merchant_id bigint,total_amount decimal(12,2))");
            sql.execute("create table settlement_detail(batch_id bigint,settlement_amount decimal(12,2))");
            for (int i = 1; i <= 27; i++) sql.execute("insert into settlement_batch values(" + i + ",7,100)");
            sql.execute("insert into settlement_batch values(30,7,100)"); sql.execute("insert into settlement_detail values(30,100)");
            SettlementBatchMapper mapper = session.getMapper(SettlementBatchMapper.class);
            assertEquals(28L, mapper.countBatches()); assertEquals(27L, mapper.countAmountMismatchBatches());
            assertEquals(27, mapper.selectAmountMismatchBatches().size());
            assertEquals(LongStream.rangeClosed(1, 20).boxed().toList(), mapper.sampleAmountMismatchBatches().stream().map(row -> row.getBatchId()).toList());
        }
    }

    private SqlSessionFactory fixture(String alias) throws Exception {
        UnpooledDataSource source = new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:monitor_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        Configuration config = new Configuration();
        config.setEnvironment(new Environment("fixture", new JdbcTransactionFactory(), source));
        config.getTypeAliasRegistry().registerAlias("AuditTask", AuditTask.class);
        config.getTypeAliasRegistry().registerAlias("MemberCashWithdraw", MemberCashWithdraw.class);
        config.getTypeAliasRegistry().registerAlias("SettlementBatch", SettlementBatch.class);
        String resource = "mapper/business/" + alias + "Mapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, config, resource, config.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(config);
    }
}
