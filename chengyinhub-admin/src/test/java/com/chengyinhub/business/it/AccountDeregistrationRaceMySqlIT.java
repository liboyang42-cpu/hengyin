package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.AccountDeregistration;
import com.chengyinhub.business.mapper.AccountDeregistrationMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.AccountDeregistrationServiceImpl;
import com.chengyinhub.business.service.impl.AlarmService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountDeregistrationRaceMySqlIT {
    @Test
    void staleBatchAndLostCancellationPreserveCommittedState() throws Exception {
        String suite = "deregister_race_" + UUID.randomUUID().toString().substring(0, 8);
        DriverManagerDataSource ds = new DriverManagerDataSource(MybatisItDatabase.url(suite),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        try {
            jdbc.execute("create table account_deregistration (id bigint primary key, member_id bigint, "
                    + "status varchar(20), apply_time datetime, execute_after datetime, cancel_time datetime, "
                    + "complete_time datetime, failure_reason varchar(255), create_time datetime, update_time datetime) engine=InnoDB");
            jdbc.execute("create table ums_member (id bigint primary key, del_flag int, phone varchar(30), "
                    + "deregister_status varchar(20), deregister_apply_time datetime, deregister_execute_after datetime, update_time datetime) engine=InnoDB");
            jdbc.update("insert into account_deregistration(id,member_id,status,apply_time,execute_after) "
                    + "values (9,1001,'PENDING',now()-interval 8 day,now()-interval 1 day)");
            jdbc.update("insert into ums_member(id,del_flag,phone,deregister_status) values (1001,0,'fixture-phone','PENDING')");

            Configuration config = new Configuration(new Environment("deregister-race",
                    new SpringManagedTransactionFactory(), ds));
            config.getTypeAliasRegistry().registerAlias("AccountDeregistration", AccountDeregistration.class);
            String xml = "mapper/business/AccountDeregistrationMapper.xml";
            try (InputStream input = Resources.getResourceAsStream(xml)) {
                new XMLMapperBuilder(input, config, xml, config.getSqlFragments()).parse();
            }
            AccountDeregistrationMapper real = new SqlSessionTemplate(
                    new SqlSessionFactoryBuilder().build(config)).getMapper(AccountDeregistrationMapper.class);
            AccountDeregistrationMapper mapper = mock(AccountDeregistrationMapper.class, delegatesTo(real));
            DataSourceTransactionManager tx = new DataSourceTransactionManager(ds);
            AccountDeregistrationServiceImpl service = new AccountDeregistrationServiceImpl();
            UmsMemberMapper members = mock(UmsMemberMapper.class);
            ReflectionTestUtils.setField(service, "deregistrationMapper", mapper);
            ReflectionTestUtils.setField(service, "memberMapper", members);
            ReflectionTestUtils.setField(service, "transactionManager", tx);
            ReflectionTestUtils.setField(service, "alarmService", mock(AlarmService.class));

            doAnswer(call -> {
                assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(ds));
                return real.selectByIdForUpdate(call.getArgument(0));
            }).when(mapper).selectByIdForUpdate(anyLong());

            // Deterministic interleaving: commit cancellation after the batch SELECT, before execution starts.
            doAnswer(call -> {
                List<AccountDeregistration> stale = real.selectDue(call.getArgument(0));
                assertEquals(1, stale.size(), "The due batch must actually contain the pending application");
                assertEquals(1, real.cancelPending(1001L, new Date()));
                return stale;
            }).when(mapper).selectDue(any(Date.class));
            assertEquals(0, service.executeDue(new Date()));
            verifyNoInteractions(members);
            assertEquals("CANCELLED", jdbc.queryForObject("select status from account_deregistration where id=9", String.class));
            assertEquals("fixture-phone", jdbc.queryForObject("select phone from ums_member where id=1001", String.class));

            jdbc.update("update account_deregistration set status='PENDING', execute_after=now()+interval 1 day where id=9");
            // A separate connection wins completion after cancel's initial read.
            doAnswer(call -> {
                AccountDeregistration stale = real.selectLatestByMemberId(1001L);
                try (java.sql.Connection other = ds.getConnection(); java.sql.Statement statement = other.createStatement()) {
                    statement.executeUpdate("update account_deregistration set status='COMPLETED' where id=9");
                }
                return stale;
            }).when(mapper).selectLatestByMemberId(1001L);
            TransactionTemplate transaction = new TransactionTemplate(tx);
            assertThrows(IllegalStateException.class, () -> transaction.execute(status -> service.cancel(1001L)));
            assertEquals("PENDING", jdbc.queryForObject("select deregister_status from ums_member where id=1001", String.class));
            assertEquals("COMPLETED", jdbc.queryForObject("select status from account_deregistration where id=9", String.class));

            // Positive control: unchanged real mapper and transaction must still allow a legitimate cancellation.
            ReflectionTestUtils.setField(service, "deregistrationMapper", real);
            jdbc.update("update account_deregistration set status='PENDING' where id=9");
            assertEquals("CANCELLED", transaction.execute(status -> service.cancel(1001L)).getStatus());
            assertEquals("NORMAL", jdbc.queryForObject("select deregister_status from ums_member where id=1001", String.class));
            assertEquals("CANCELLED", jdbc.queryForObject("select status from account_deregistration where id=9", String.class));
        } finally {
            String database = MybatisItDatabase.databaseName(suite);
            jdbc.execute("drop database `" + database + "`");
            try (java.sql.Connection connection = ds.getConnection()) {
                fail("Dropped database must no longer accept a new connection");
            } catch (java.sql.SQLException expected) {
                assertEquals(1049, expected.getErrorCode());
            }
        }
    }
}
