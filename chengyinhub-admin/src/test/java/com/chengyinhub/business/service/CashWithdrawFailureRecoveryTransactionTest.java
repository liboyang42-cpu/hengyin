package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MemberCashWithdraw;
import com.chengyinhub.business.mapper.MemberCashWithdrawMapper;
import com.chengyinhub.business.service.impl.CashWithdrawFailureRecoveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.math.BigDecimal;
import java.util.Date;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** H2事务故障注入（非真实MySQL证明）；独立回读余额/原单，确认同事务rollback及双worker一次。 */
class CashWithdrawFailureRecoveryTransactionTest {
    private JdbcTemplate jdbc;
    private MemberCashWithdrawMapper mapper;
    private CashWithdrawFailureRecoveryService service;
    @BeforeEach void setup() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:cash_recovery_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table original_withdraw(id bigint primary key, member_id bigint, amount decimal(10,2), status int)");
        jdbc.execute("create table account(id bigint primary key, balance decimal(10,2))");
        jdbc.update("insert into original_withdraw values(1,7,10,0)");
        jdbc.update("insert into account values(7,90)");
        mapper = mock(MemberCashWithdrawMapper.class);
        when(mapper.updateStatusIfProcessing(any())).thenAnswer(i -> jdbc.update(
                "update original_withdraw set status=3 where id=? and status=0", ((MemberCashWithdraw)i.getArgument(0)).getId()));
        when(mapper.selectById(1L)).thenAnswer(i -> jdbc.queryForObject(
                "select id, member_id, amount, status from original_withdraw where id=1", (rs, n) -> {
                    MemberCashWithdraw w = new MemberCashWithdraw(); w.setId(rs.getLong(1));
                    w.setMemberId(rs.getLong(2)); w.setAmount(rs.getBigDecimal(3)); w.setStatus(rs.getInt(4)); return w;
                }));
        when(mapper.updateMemberCashWithdraw(any())).thenAnswer(i -> jdbc.update(
                "update original_withdraw set status=2 where id=? and status=3", ((MemberCashWithdraw)i.getArgument(0)).getId()));
        IUmsMemberService balances = mock(IUmsMemberService.class);
        when(balances.addBalance(any(), any())).thenAnswer(i -> jdbc.update(
                "update account set balance=balance+? where id=?", new Object[] {i.getArgument(1), i.getArgument(0)}) == 1);
        CashWithdrawFailureRecoveryService target = new CashWithdrawFailureRecoveryService();
        ReflectionTestUtils.setField(target, "mapper", mapper); ReflectionTestUtils.setField(target, "umsMemberService", balances);
        ProxyFactory proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds), new AnnotationTransactionAttributeSource()));
        service = (CashWithdrawFailureRecoveryService)proxy.getProxy();
    }
    private boolean recover() { return service.recover(1L,7L,new BigDecimal("10.00"),new Date(),"FAIL",null,null); }
    private void assertLedger(String balance, int status) {
        assertEquals(0, new BigDecimal(balance).compareTo(jdbc.queryForObject("select balance from account where id=7",BigDecimal.class)));
        assertEquals(status, jdbc.queryForObject("select status from original_withdraw where id=1",Integer.class));
    }
    @Test void responseLossRetryCannotRefundTwice() {
        assertTrue(recover());
        assertFalse(recover());
        assertLedger("100",2);
    }
    @Test void lostTerminalWriteRollsBackBothBalanceAndClaim() {
        org.mockito.Mockito.doReturn(0).when(mapper).updateMemberCashWithdraw(any());
        assertThrows(IllegalStateException.class,this::recover);
        assertLedger("90",0);
    }
    @Test void changedOriginalAmountCannotRefundStaleSnapshot() {
        jdbc.update("update original_withdraw set amount=20 where id=1");
        assertThrows(IllegalStateException.class,this::recover);
        assertLedger("90",0);
    }
    @Test void twoWorkersRefundOriginalAmountOnce() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = workers.submit(this::recover); Future<Boolean> second = workers.submit(this::recover);
            assertEquals(1,(first.get(5,TimeUnit.SECONDS)?1:0)+(second.get(5,TimeUnit.SECONDS)?1:0));
            assertLedger("100",2);
        } finally { workers.shutdownNow(); }
    }
    @Test void originalNumberWriteFailureRollsBackNewLedgerBeforeAnyDebit() {
        jdbc.update("delete from original_withdraw");
        when(mapper.insertMemberCashWithdraw(any())).thenAnswer(i -> {
            MemberCashWithdraw original = i.getArgument(0); original.setId(1L);
            return jdbc.update("insert into original_withdraw values(1,7,10,0)");
        });
        org.mockito.Mockito.doReturn(0).when(mapper).updateMemberCashWithdraw(any());
        MemberCashWithdraw original = new MemberCashWithdraw();
        original.setMemberId(7L); original.setAmount(new BigDecimal("10.00"));
        assertThrows(IllegalStateException.class, () -> service.prepare(original));
        assertEquals(0,jdbc.queryForObject("select count(*) from original_withdraw",Integer.class));
        assertEquals(0,new BigDecimal("90").compareTo(jdbc.queryForObject("select balance from account where id=7",BigDecimal.class)));
    }
}
