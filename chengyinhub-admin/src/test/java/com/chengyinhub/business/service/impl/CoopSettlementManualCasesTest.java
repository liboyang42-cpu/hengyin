package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.CoopSettlementManualCaseMapper;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Spring resource/rollback ordering only; durable SQL receipt requires the MySQL candidate. */
class CoopSettlementManualCasesTest {
    @Test void negativeMarginIsRetainedOnlyAfterBusinessRollbackInANewTransaction() {
        var mapper=mock(CoopSettlementManualCaseMapper.class);var transactions=transactions();
        var service=new CoopSettlementManualCases(mapper,transactions);AtomicReference<Map<String,Object>> original=new AtomicReference<>();
        when(mapper.insertIgnore(anyMap())).thenAnswer(call->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());original.set(call.getArgument(0));return 1;});
        when(mapper.selectByKeyForUpdate(anyString())).thenAnswer(call->{var row=original.get();return Map.of("topic_id",row.get("topicId"),"state","MANUAL_REQUIRED","source_snapshot",row.get("sourceSnapshot"),"negative_amount",row.get("negativeAmount"));});
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
            service.afterRollback(11L,"{\"original\":true}",new BigDecimal("-1.00"));verifyNoInteractions(mapper);throw new IllegalStateException("rollback original economics");
        }));
        verify(mapper).insertIgnore(anyMap());assertEquals("NEGATIVE_ORGANIZER_MARGIN",original.get().get("reason"));assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
    @Test void committedTransactionNeverCreatesANegativeMarginManualReceipt() {
        var mapper=mock(CoopSettlementManualCaseMapper.class);var transactions=transactions();var service=new CoopSettlementManualCases(mapper,transactions);
        new TransactionTemplate(transactions).executeWithoutResult(tx->service.afterRollback(11L,"{}",new BigDecimal("-1.00")));
        verifyNoInteractions(mapper);
    }
    @Test void openManualCaseBlocksAutomaticSettlementWithoutResolvingOrWritingIt() {
        var mapper=mock(CoopSettlementManualCaseMapper.class);var transactions=transactions();var service=new CoopSettlementManualCases(mapper,transactions);
        when(mapper.selectOpenTopicForUpdate(11L)).thenReturn(11L);
        new TransactionTemplate(transactions).executeWithoutResult(tx->assertThrows(IllegalStateException.class,()->service.requireNoOpenCase(11L)));
        verify(mapper,never()).insertIgnore(anyMap());
    }
    private static PlatformTransactionManager transactions(){return new AbstractPlatformTransactionManager(){
        @Override protected Object doGetTransaction(){return new Object();}
        @Override protected void doBegin(Object tx,TransactionDefinition definition){}
        @Override protected void doCommit(DefaultTransactionStatus status){}
        @Override protected void doRollback(DefaultTransactionStatus status){}
    };}
}
